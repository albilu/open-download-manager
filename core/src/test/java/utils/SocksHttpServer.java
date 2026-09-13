package utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loopback SOCKS5 endpoint serving HTTP itself or forwarding to a local HTTP fixture. */
public final class SocksHttpServer implements AutoCloseable {
    private final ServerSocket server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    public final List<String> hosts = new CopyOnWriteArrayList<>();
    public final List<String> credentials = new CopyOnWriteArrayList<>();
    public final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final boolean authenticate;
    private final byte[] body;
    private final InetSocketAddress upstream;
    private final CountDownLatch responseReady;

    public SocksHttpServer(boolean authenticate, String body) throws Exception {
        this(authenticate, body.getBytes(StandardCharsets.UTF_8));
    }

    public SocksHttpServer(boolean authenticate, byte[] body) throws Exception {
        this(authenticate, body, null, null);
    }

    /** Keeps transfers active until the test releases the response or closes the fixture. */
    public SocksHttpServer(boolean authenticate, String body, CountDownLatch responseReady) throws Exception {
        this(authenticate, body.getBytes(StandardCharsets.UTF_8), null, responseReady);
    }

    /** Routes every requested host to the given loopback HTTP server without resolving it. */
    public SocksHttpServer(InetSocketAddress upstream) throws Exception {
        this(false, null, upstream, null);
    }

    private SocksHttpServer(boolean authenticate, byte[] body, InetSocketAddress upstream,
            CountDownLatch responseReady) throws Exception {
        if (upstream != null && (upstream.isUnresolved() || !upstream.getAddress().isLoopbackAddress())) {
            throw new IllegalArgumentException("The HTTP fixture must be on loopback");
        }
        this.authenticate = authenticate;
        this.body = body;
        this.upstream = upstream;
        this.responseReady = responseReady;
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        workers.submit(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    workers.submit(() -> serve(socket));
                } catch (Exception e) {
                    if (!server.isClosed()) failures.add(e);
                }
            }
        });
    }

    public int port() { return server.getLocalPort(); }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(10_000);
            var in = new java.io.DataInputStream(socket.getInputStream());
            var out = socket.getOutputStream();
            if (in.readUnsignedByte() != 5) throw new IllegalStateException("Expected SOCKS5 greeting");
            in.readNBytes(in.readUnsignedByte());
            out.write(new byte[] {5, (byte) (authenticate ? 2 : 0)});
            out.flush();
            if (authenticate) {
                if (in.readUnsignedByte() != 1) throw new IllegalStateException("Expected password authentication");
                String user = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
                String pass = new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
                credentials.add(user + ":" + pass);
                out.write(new byte[] {1, 0});
                out.flush();
            }
            if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 1) {
                throw new IllegalStateException("Expected SOCKS5 CONNECT");
            }
            in.readUnsignedByte();
            String host = switch (in.readUnsignedByte()) {
                case 1 -> InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
                case 3 -> new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.UTF_8);
                case 4 -> InetAddress.getByAddress(in.readNBytes(16)).getHostAddress();
                default -> throw new IllegalStateException("Invalid SOCKS address");
            };
            in.readUnsignedShort();
            hosts.add(host);
            out.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 80});
            out.flush();
            BufferedReader request = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            StringBuilder headers = new StringBuilder();
            String line;
            while ((line = request.readLine()) != null && !line.isEmpty()) {
                if (!line.regionMatches(true, 0, "Connection:", 0, "Connection:".length())) {
                    headers.append(line).append("\r\n");
                }
            }
            if (responseReady != null) {
                responseReady.await();
            }
            if (upstream != null) {
                // These fixtures receive GET/HEAD requests. Closing the upstream
                // connection bounds the response copy even with HTTP/1.1 servers.
                headers.append("Connection: close\r\n\r\n");
                try (Socket target = new Socket()) {
                    target.connect(upstream, 5_000);
                    target.setSoTimeout(10_000);
                    target.getOutputStream().write(headers.toString().getBytes(StandardCharsets.US_ASCII));
                    target.getOutputStream().flush();
                    target.getInputStream().transferTo(out);
                    out.flush();
                }
                return;
            }
            byte[] payload = body;
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "
                    + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
        } catch (Exception e) {
            if (!server.isClosed()) failures.add(e);
        }
    }

    @Override
    public void close() throws Exception {
        server.close();
        workers.shutdownNow();
        workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}

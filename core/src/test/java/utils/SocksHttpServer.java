package utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loopback SOCKS5 endpoint that serves HTTP itself; no public DNS or upstream connection. */
public final class SocksHttpServer implements AutoCloseable {
    private final ServerSocket server;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    public final List<String> hosts = new CopyOnWriteArrayList<>();
    public final List<String> credentials = new CopyOnWriteArrayList<>();
    public final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final boolean authenticate;
    private final String body;

    public SocksHttpServer(boolean authenticate, String body) throws Exception {
        this.authenticate = authenticate;
        this.body = body;
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
            String line;
            while ((line = request.readLine()) != null && !line.isEmpty()) { }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
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

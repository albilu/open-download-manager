package org.proxychains;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.manager.tools.ToolPaths;

class ProxychainsSelectedRouteTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"socks5://audit:p%40ss%3Aword@127.0.0.1:1080",
            "socks5h://[::1]:1080", "socks4a://127.0.0.1:1080", "http://127.0.0.1:8080"})
    void nativeProxychainsAcceptsTheExplicitConfiguration(String address) throws Exception {
        Path config = ProxychainsConfig.forProxyAddress(address).createTempConfig();
        try {
            String text = Files.readString(config);
            assertTrue(text.startsWith("strict_chain\nproxy_dns\n"));
            if (address.contains("audit")) assertTrue(text.contains("audit p@ss:word"));
            if (address.contains("[::1]")) assertTrue(text.contains("socks5 ::1 1080"));
            Process process = new ProcessBuilder(ToolPaths.proxychains(), "-f", config.toString(), "/bin/true")
                    .redirectErrorStream(true).start();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"socks5://audit:secret@127.0.0.1:1080", "socks5://[::1]:1080", "http://127.0.0.1:8080"})
    void clientPassesTheSelectedConfigurationAndRemovesItAfterExit(String address) throws Exception {
        Path capture = captureLauncher();
        ProxychainsClient client = new ProxychainsClient(capture.toString(), "/unused/system.conf");
        Download download = new Download(java.net.URI.create("http://download.odm.invalid/payload.bin"));
        download.setDestination(directory);
        download.setUseProxy(true).setProxyAddress(address);
        try {
            client.startDownload(download, null, Map.of()).get(5, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> download.getStatus() == Download.Status.COMPLETED);
            String config = Files.readString(Path.of(capture + ".config"));
            if (address.contains("audit")) assertTrue(config.contains("socks5 127.0.0.1 1080 audit secret"));
            if (address.contains("[::1]")) assertTrue(config.contains("socks5 ::1 1080"));
            if (address.startsWith("http")) assertTrue(config.contains("http 127.0.0.1 8080"));
            Path generated = Path.of(Files.readString(Path.of(capture + ".path")));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> !Files.exists(generated));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void invalidSelectionFailsBeforeLaunchingRatherThanUsingSystemConfiguration() throws Exception {
        Path capture = captureLauncher();
        ProxychainsClient client = new ProxychainsClient(capture.toString(), "/unused/system.conf");
        Download download = new Download(java.net.URI.create("http://download.odm.invalid/payload.bin"));
        download.setDestination(directory);
        download.setUseProxy(true).setProxyAddress("socks5://user:bad%0Apassword@127.0.0.1:1080");
        try {
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> client.startDownload(download, null, Map.of()).get(5, TimeUnit.SECONDS));
            assertEquals(Download.Status.ERROR, download.getStatus());
            assertFalse(Files.exists(Path.of(capture + ".path")));
        } finally {
            client.shutdown();
        }
    }

    private Path captureLauncher() throws Exception {
        Path executable = directory.resolve("capture-proxychains");
        Files.writeString(executable, "#!/bin/sh\n[ \"$1\" = -h ] && exit 0\n"
                + "[ \"$1\" = -f ] || exit 23\ncp -- \"$2\" \"$0.config\"\n"
                + "printf '%s' \"$2\" > \"$0.path\"\n");
        executable.toFile().setExecutable(true);
        return executable;
    }

    @ParameterizedTest
    @ValueSource(strings = {"socks5://host", "socks5://host:0", "socks5://host:65536",
            "socks5://u:p%0Ahttp@host:1080", "socks5://u:p%20word@host:1080",
            "socks5://user@host:1080", "https://host:1080", "socks5://host:1080/path"})
    void unrepresentableSelectionsAreRejected(String address) {
        assertThrows(IllegalArgumentException.class, () -> ProxychainsConfig.forProxyAddress(address));
    }

    @ParameterizedTest
    @ValueSource(strings = {"torrent", "meta4"})
    void realAria2AcceptsLocalDescriptorsThroughProxychains(String extension) throws Exception {
        Path source = directory.resolve("descriptor with spaces." + extension);
        String payload = "selected-payload.bin";
        if (extension.equals("torrent")) {
            var bytes = new java.io.ByteArrayOutputStream();
            bytes.write(("d4:infod6:lengthi10e4:name" + payload.length() + ":" + payload
                    + "12:piece lengthi16384e6:pieces20:").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bytes.write(new byte[20]);
            bytes.write("ee".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(source, bytes.toByteArray());
        } else {
            Files.writeString(source, "<metalink xmlns=\"urn:ietf:params:xml:ns:metalink\"><file name=\""
                    + payload + "\"><size>10</size><url>http://payload.odm.invalid/file.bin</url></file></metalink>");
        }
        Download download = extension.equals("torrent") ? Download.fromTorrent(source, directory)
                : Download.fromMetaLink(source, directory);
        ProxychainsClient client = new ProxychainsClient();
        Path config = ProxychainsConfig.forProxyAddress("socks5://127.0.0.1:1").createTempConfig();
        try {
            var command = new ArrayList<>(client.buildProxychainsCommand(download,
                    directory.resolve(payload), config, Map.of()));
            assertFalse(command.contains(source.toUri().toString()));
            command.add("--show-files=true"); // Parses descriptors without starting a network transfer.
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains(payload), output);
        } finally {
            client.shutdown();
            Files.deleteIfExists(config);
        }
    }
}

package org.curl;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.manager.download.Download;
import static org.junit.jupiter.api.Assertions.*;

class CurlSftpFallbackTest {
    @Test
    void preservesSocksRouteCredentialsAndMd5HostPin() {
        CurlClient client = new CurlClient("curl");
        try {
            Download download = sftp("md5=0123456789abcdef0123456789abcdef");
            var command = client.buildCurlCommand(download, Path.of("/tmp/file.bin"));
            assertEquals(download.getProxyAddress(), command.get(command.indexOf("-x") + 1));
            assertEquals("0123456789abcdef0123456789abcdef",
                    command.get(command.indexOf("--hostpubmd5") + 1));
            assertEquals(download.getUri().toString(), command.getLast());
            assertFalse(command.contains("--insecure"));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void rejectsSha1HostPinRatherThanDroppingVerification() {
        CurlClient client = new CurlClient("curl");
        try {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> client.buildCurlCommand(sftp("sha-1=" + "a".repeat(40)), Path.of("/tmp/file.bin")));
            assertTrue(error.getMessage().contains("SHA-1 host-key pin"));
        } finally {
            client.shutdown();
        }
    }

    private Download sftp(String pin) {
        Download download = new Download(URI.create("sftp://user:secret@host.invalid/file.bin"));
        download.setType(Download.Type.CURL);
        CurlSettings settings = new CurlSettings();
        settings.setUseProxy(true);
        settings.setProxyAddress("socks5h://proxy-user:proxy-secret@127.0.0.1:9050");
        settings.setOption("ssh-host-key-md", pin);
        download.setSettings(settings);
        return download;
    }
}

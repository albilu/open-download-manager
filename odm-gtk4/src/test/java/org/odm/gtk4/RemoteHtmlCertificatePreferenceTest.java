package org.odm.gtk4;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class RemoteHtmlCertificatePreferenceTest {
    @TempDir Path directory;

    @Test void remoteImportHonorsTheSelectedVerificationPolicy() throws Exception {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(certificate()));
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/page", exchange -> {
            requests.incrementAndGet();
            byte[] body = "<a href='/files/archive.zip'>archive</a>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            URI source = URI.create("https://localhost:" + server.getAddress().getPort() + "/page");
            GlobalSettings settings = new GlobalSettings();
            Exception rejected = assertThrows(IllegalArgumentException.class, () ->
                    HtmlImportExport.fetchRemoteHtmlLinks(source, null, ImportLimits.defaults(),
                            settings.isVerifyHttpsCertificates()));
            assertTrue(UiErrors.message(rejected).contains("TLS certificate verification failed"));
            assertEquals(0, requests.get());
            settings.setVerifyHttpsCertificates(false);
            assertEquals(List.of(source.resolve("/files/archive.zip").toString()),
                    HtmlImportExport.fetchRemoteHtmlLinks(source, null, ImportLimits.defaults(),
                            settings.isVerifyHttpsCertificates()));
            assertEquals(1, requests.get());
            assertThrows(IllegalArgumentException.class, () ->
                    HtmlImportExport.fetchRemoteHtmlLinks(source, null, ImportLimits.defaults()));
            assertEquals(1, requests.get(), "the compatibility path must still verify certificates");
        } finally {
            server.stop(0);
        }
    }

    private SSLContext certificate() throws Exception {
        Path store = directory.resolve("server.p12");
        Path log = directory.resolve("keytool.log");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "fixture-password",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1", "-noprompt")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(keytool.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, keytool.exitValue(), Files.readString(log));
        } finally {
            keytool.destroyForcibly();
        }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "fixture-password".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "fixture-password".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }
}

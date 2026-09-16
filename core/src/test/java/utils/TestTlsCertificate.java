package utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.*;

/** A real, locally generated HTTPS identity that no system trust store accepts. */
public record TestTlsCertificate(SSLContext context, Path pem) {
    public static TestTlsCertificate create(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path keyStore = directory.resolve("server.p12");
        Path output = directory.resolve("keytool.log");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                "-storepass", "fixture-password", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-validity", "1", "-noprompt")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(keytool.waitFor(15, TimeUnit.SECONDS), "keytool timed out");
            assertEquals(0, keytool.exitValue(), Files.readString(output));
        } finally {
            keytool.destroyForcibly();
        }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStore)) {
            keys.load(input, "fixture-password".toCharArray());
        }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "fixture-password".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        Path pem = directory.resolve("trusted.pem");
        Files.writeString(pem, "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keys.getCertificate("server").getEncoded())
                + "\n-----END CERTIFICATE-----\n", StandardCharsets.US_ASCII);
        return new TestTlsCertificate(context, pem);
    }
}

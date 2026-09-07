package org.jackett;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.manager.tools.BoundedHttpFetcher;

/** Installs official Linux releases without invoking an archive's scripts. */
public final class JackettInstaller {
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 1024L * 1024 * 1024;

    private final String proxyAddress;

    public JackettInstaller() { this(null); }
    public JackettInstaller(String proxyAddress) {
        this.proxyAddress = org.manager.tools.NetworkProcessPolicy.proxyAddress(proxyAddress);
    }

    public record Release(String version, URI url, String sha256) { }

    public Release latestRelease() throws IOException {
        byte[] bytes = BoundedHttpFetcher.fetch(URI.create(
                "https://api.github.com/repos/Jackett/Jackett/releases/latest"),
                2 * 1024 * 1024, Duration.ofSeconds(10), Duration.ofSeconds(30), proxyAddress);
        JsonNode release = JackettClient.JSON.readTree(bytes);
        String name = assetName(System.getProperty("os.name"), System.getProperty("os.arch"),
                Files.isRegularFile(Path.of("/etc/alpine-release")));
        for (JsonNode asset : release.path("assets")) {
            if (name.equals(asset.path("name").asText())) {
                String digest = asset.path("digest").asText("");
                URI url = URI.create(asset.path("browser_download_url").asText());
                if (!digest.matches("sha256:[a-fA-F0-9]{64}")
                        || !"https".equals(url.getScheme()) || !"github.com".equals(url.getHost())
                        || !url.getPath().startsWith("/Jackett/Jackett/releases/download/")) {
                    throw new IOException("Jackett release is missing a trusted URL or SHA-256 digest");
                }
                return new Release(release.path("tag_name").asText(), url, digest.substring(7));
            }
        }
        throw new IOException("No Jackett release is available for this Linux architecture");
    }

    static String assetName(String os, String arch, boolean musl) throws IOException {
        if (!os.toLowerCase(Locale.ROOT).contains("linux")) {
            throw new IOException("Automatic Jackett installation currently supports Linux");
        }
        String cpu = switch (arch.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "AMDx64";
            case "aarch64", "arm64" -> "ARM64";
            case "arm", "armv7", "armv7l" -> "ARM32";
            default -> throw new IOException("Unsupported Jackett architecture: " + arch);
        };
        return "Jackett.Binaries.Linux" + (musl ? "Musl" : "") + cpu + ".tar.gz";
    }

    public String installLatest(Path directory) throws IOException {
        Release release = latestRelease();
        Path archive = Files.createTempFile("odm-jackett-", ".tar.gz");
        try {
            download(release.url(), archive);
            verifyDigest(archive, release.sha256());
            installArchive(archive, directory, release.version());
            return release.version();
        } finally { Files.deleteIfExists(archive); }
    }

    private void download(URI url, Path file) throws IOException {
        BoundedHttpFetcher.fetchTo(url, file, MAX_ARCHIVE_BYTES,
                Duration.ofSeconds(10), Duration.ofMinutes(10), proxyAddress);
    }

    static void verifyDigest(Path archive, String expected) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(archive)) {
                byte[] buffer = new byte[65536];
                int length;
                while ((length = in.read(buffer)) != -1) { digest.update(buffer, 0, length); }
            }
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected)) {
                throw new IOException("Jackett release SHA-256 verification failed");
            }
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Extraction is bounded and staged. A malformed archive leaves the prior installation intact. */
    public void installArchive(Path archive, Path directory, String version) throws IOException {
        if (Files.size(archive) > MAX_ARCHIVE_BYTES) { throw new IOException("Jackett archive is too large"); }
        Files.createDirectories(directory);
        Path staging = Files.createTempDirectory(directory, ".install-");
        Path backup = null;
        Path installed = directory.resolve("Jackett");
        try {
            long total = 0;
            int entries = 0;
            try (var input = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
                TarArchiveEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    if (++entries > 20000 || entry.getSize() < 0
                            || entry.getSize() > MAX_EXTRACTED_BYTES - total) {
                        throw new IOException("Jackett archive exceeds extraction limits");
                    }
                    Path relative = Path.of(entry.getName());
                    Path target = staging.resolve(relative).normalize();
                    if (relative.isAbsolute() || !target.startsWith(staging.resolve("Jackett"))
                            || (!entry.isDirectory() && !entry.isFile())
                            || entry.isSymbolicLink() || entry.isLink() || entry.isSparse()) {
                        throw new IOException("Unsafe entry in Jackett archive");
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                            long written = copy(input, out, entry.getSize());
                            if (written != entry.getSize()) { throw new IOException("Truncated Jackett archive"); }
                            total += written;
                        }
                        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(
                                (entry.getMode() & 0111) != 0 ? "rwx------" : "rw-------"));
                    }
                }
            }
            Path executable = staging.resolve("Jackett/jackett");
            if (!Files.isRegularFile(executable) || Files.size(executable) == 0) {
                throw new IOException("Archive does not contain Jackett/jackett");
            }
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
            Files.writeString(staging.resolve("Jackett/odm-version"), version);
            if (Files.exists(installed)) {
                backup = directory.resolve(".previous-" + java.util.UUID.randomUUID());
                Files.move(installed, backup, StandardCopyOption.ATOMIC_MOVE);
            }
            try { Files.move(staging.resolve("Jackett"), installed, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException failure) {
                if (backup != null) { Files.move(backup, installed, StandardCopyOption.ATOMIC_MOVE); }
                throw failure;
            }
        } finally {
            deleteTree(staging);
            if (backup != null && Files.exists(installed)) { deleteTree(backup); }
        }
    }

    private static long copy(InputStream in, java.io.OutputStream out, long limit) throws IOException {
        long written = 0;
        byte[] buffer = new byte[65536];
        int length;
        while ((length = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) { throw new java.io.InterruptedIOException("Installation cancelled"); }
            if (length > limit - written) { throw new IOException("Jackett archive exceeds the size limit"); }
            out.write(buffer, 0, length);
            written += length;
        }
        return written;
    }

    static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) { return; }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(item); }
        }
    }
}

package org.ytdlp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.manager.tools.NetworkProcessPolicy;
import org.manager.tools.ProxiedCommand;

/** Gives FFmpeg network inputs the same route as yt-dlp, including live HLS. */
final class RoutedMediaTools implements AutoCloseable {
    private final Path directory;
    private final ProxiedCommand route;

    private RoutedMediaTools(Path directory, ProxiedCommand route) {
        this.directory = directory;
        this.route = route;
    }

    static RoutedMediaTools prepare(String proxy) throws IOException {
        if (NetworkProcessPolicy.proxyAddress(proxy).isEmpty()) {
            return new RoutedMediaTools(null, null);
        }
        ProxiedCommand route = ProxiedCommand.prepare(proxy);
        Path directory = null;
        try {
            directory = Files.createTempDirectory("odm-media-tools-");
            for (String tool : List.of("ffmpeg", "ffprobe")) {
                Path executable = findExecutable(tool);
                Path wrapper = directory.resolve(tool);
                String command = route.wrap(List.of(executable.toString())).stream()
                        .map(RoutedMediaTools::quote).collect(java.util.stream.Collectors.joining(" "));
                // yt-dlp may reintroduce HTTP_PROXY for FFmpeg. Route only once, in proxychains.
                // UDP/RTP discovery and transports cannot cross this TCP proxy.
                String script = "#!/bin/bash\n"
                        + "unset http_proxy https_proxy ftp_proxy all_proxy no_proxy HTTP_PROXY HTTPS_PROXY FTP_PROXY ALL_PROXY NO_PROXY\n"
                        + "args=()\nwhile (( $# )); do\n  case \"$1\" in\n"
                        + "    -http_proxy|-protocol_whitelist|-protocol_blacklist) shift; (( $# )) && shift ;;\n"
                        + "    -i) args+=(-protocol_whitelist file,pipe,http,https,tcp,tls,crypto,data -http_proxy '' -i); shift ;;\n"
                        + "    *) args+=(\"$1\"); shift ;;\n  esac\ndone\n";
                if (tool.equals("ffprobe")) {
                    script += "args=(-protocol_whitelist file,pipe,http,https,tcp,tls,crypto,data -http_proxy '' \"${args[@]}\")\n";
                }
                script += "exec " + command + " \"${args[@]}\"\n";
                Files.writeString(wrapper, script);
                Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
            }
            return new RoutedMediaTools(directory, route);
        } catch (IOException | RuntimeException failure) {
            new RoutedMediaTools(directory, route).close();
            throw failure;
        }
    }

    void applyTo(List<String> command) {
        if (directory != null) {
            // Native configuration must not select an unwrapped downloader or FFmpeg binary.
            command.addAll(1, List.of("--ignore-config", "--ffmpeg-location", directory.toString(),
                    "--external-downloader", "native", "--external-downloader", "http,ftp,m3u8,dash:native"));
        }
    }

    private static Path findExecutable(String executable) throws IOException {
        for (String entry : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (entry.isBlank()) { continue; }
            Path path = Path.of(entry).resolve(executable);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) { return path.toAbsolutePath(); }
        }
        throw new IOException(executable + " is required for proxied media operations");
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    @Override public void close() throws IOException {
        try {
            if (directory != null) {
                Files.deleteIfExists(directory.resolve("ffmpeg"));
                Files.deleteIfExists(directory.resolve("ffprobe"));
                Files.deleteIfExists(directory);
            }
        } finally {
            if (route != null) { route.close(); }
        }
    }
}

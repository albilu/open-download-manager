package org.ytdlp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Runs the same byte-bounded naming rule inside yt-dlp's preview and transfer. */
final class MediaOutputNames implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path directory;
    private final Path configuration;
    private final boolean preserveExisting;

    private MediaOutputNames(Path directory, boolean preserveExisting) {
        this.directory = directory;
        this.preserveExisting = preserveExisting;
        configuration = directory.resolve("names.json");
    }

    static MediaOutputNames prepare(YtDlpSettings settings, Path destination, boolean preserveExisting) throws IOException {
        var names = new MediaOutputNames(Files.createTempDirectory("odm-media-names-"), preserveExisting);
        try {
            Path plugin = names.directory.resolve("odm/yt_dlp_plugins/postprocessor/odm_output_name.py");
            Files.createDirectories(plugin.getParent());
            try (var input = MediaOutputNames.class.getResourceAsStream("/ytdlp/odm_output_name.py")) {
                if (input == null) { throw new IOException("Missing media filename support"); }
                Files.copy(input, plugin);
            }
            names.update(settings, destination);
            return names;
        } catch (IOException | RuntimeException failure) {
            names.close();
            throw failure;
        }
    }

    void update(YtDlpSettings settings, Path destination) throws IOException {
        MAPPER.writeValue(configuration.toFile(), Map.of(
                "literal", settings.getOutputTemplate() == null ? "" : settings.getOutputTemplate(),
                "counter", settings.getOutputNameCounter(),
                "reserved", settings.getReservedOutputNames(),
                "preserve_existing", preserveExisting,
                "destination", destination == null ? "" : destination.toAbsolutePath().toString()));
    }

    void applyTo(List<String> command) {
        command.addAll(1, List.of("--plugin-dirs", directory.toString(), "--use-postprocessor",
                "OdmOutputName:when=pre_process;configuration=" + configuration));
    }

    @Override public void close() throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

package org.manager.download;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Descriptor file inspection")
class DescriptorFileInspectorTest {

    @Test
    @DisplayName("multi-file torrents retain their root hierarchy and aria indexes")
    void multiFileTorrentHierarchy() throws Exception {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "Series");
        info.put("files", List.of(
                Map.of("length", 1_024L, "path", List.of("Season 1", "episode.mkv")),
                Map.of("length", 42L, "path", List.of("readme.txt"))));

        List<DownloadFileInfo> files = DescriptorFileInspector.inspect(
                bencode(Map.of("info", info)), Download.Protocol.TORRENT);

        assertEquals(List.of(
                new DownloadFileInfo(1, "Series/Season 1/episode.mkv", 1_024),
                new DownloadFileInfo(2, "Series/readme.txt", 42)), files);
    }

    @Test
    @DisplayName("single-file torrents expose their content rather than descriptor filename")
    void singleFileTorrent() throws Exception {
        byte[] torrent = bencode(Map.of("info", Map.of(
                "length", 123L,
                "name.utf-8", "release.iso")));

        assertEquals(List.of(new DownloadFileInfo(1, "release.iso", 123)),
                DescriptorFileInspector.inspect(torrent, Download.Protocol.TORRENT));
    }

    @Test
    @DisplayName("Metalink file names keep nested folders and descriptor order")
    void metalinkHierarchy() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                  <file name="release/bin/app"><size>4096</size></file>
                  <file name="release/README.txt"><size>99</size></file>
                </metalink>
                """;

        assertEquals(List.of(
                new DownloadFileInfo(1, "release/bin/app", 4_096),
                new DownloadFileInfo(2, "release/README.txt", 99)),
                DescriptorFileInspector.inspect(xml.getBytes(StandardCharsets.UTF_8),
                        Download.Protocol.METALINK));
    }

    @Test
    @DisplayName("Metalink parsing rejects document type declarations")
    void metalinkRejectsDoctype() {
        String xml = """
                <!DOCTYPE metalink [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                  <file name="&xxe;"><size>1</size></file>
                </metalink>
                """;

        assertThrows(IOException.class, () -> DescriptorFileInspector.inspect(
                xml.getBytes(StandardCharsets.UTF_8), Download.Protocol.METALINK));
    }

    private static byte[] bencode(Object value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeBencode(output, value);
        return output.toByteArray();
    }

    private static void writeBencode(ByteArrayOutputStream output, Object value)
            throws IOException {
        switch (value) {
            case Map<?, ?> map -> {
                output.write('d');
                TreeMap<String, Object> sorted = new TreeMap<>();
                map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    writeBencode(output, entry.getKey());
                    writeBencode(output, entry.getValue());
                }
                output.write('e');
            }
            case List<?> list -> {
                output.write('l');
                for (Object item : list) {
                    writeBencode(output, item);
                }
                output.write('e');
            }
            case Number number -> output.write(
                    ("i" + number.longValue() + "e").getBytes(StandardCharsets.US_ASCII));
            case byte[] bytes -> writeBytes(output, bytes);
            case String text -> writeBytes(output, text.getBytes(StandardCharsets.UTF_8));
            case null, default -> throw new IOException("Unsupported test bencode value " + value);
        }
    }

    private static void writeBytes(ByteArrayOutputStream output, byte[] bytes) throws IOException {
        output.write(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        output.write(':');
        output.write(bytes);
    }
}

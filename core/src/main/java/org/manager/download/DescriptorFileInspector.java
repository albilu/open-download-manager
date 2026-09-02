package org.manager.download;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Parses file metadata from torrent and Metalink descriptors without adding a download. */
public final class DescriptorFileInspector {

    /** Descriptors are metadata, not payloads; reject unexpectedly large input. */
    public static final int MAX_DESCRIPTOR_BYTES = 16 * 1024 * 1024;

    private DescriptorFileInspector() {
    }

    /**
     * Inspects a descriptor in memory.
     *
     * @param bytes descriptor bytes
     * @param protocol {@link Download.Protocol#TORRENT} or
     *            {@link Download.Protocol#METALINK}
     * @return files in the descriptor's stable, one-based engine order
     * @throws IOException if the descriptor is malformed or unsafe
     */
    public static List<DownloadFileInfo> inspect(byte[] bytes, Download.Protocol protocol)
            throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Descriptor is empty");
        }
        if (bytes.length > MAX_DESCRIPTOR_BYTES) {
            throw new IOException("Descriptor exceeds the 16 MiB limit");
        }
        return switch (protocol) {
            case TORRENT -> inspectTorrent(bytes);
            case METALINK -> inspectMetalink(bytes);
            default -> throw new IOException("Unsupported descriptor protocol: " + protocol);
        };
    }

    private static List<DownloadFileInfo> inspectTorrent(byte[] bytes) throws IOException {
        Object decoded = new BencodeReader(bytes).readDocument();
        Map<String, Object> root = dictionary(decoded, "torrent root");
        Map<String, Object> info = dictionary(root.get("info"), "torrent info");
        String rootName = text(firstPresent(info, "name.utf-8", "name"));
        if (rootName.isBlank()) {
            throw new IOException("Torrent info has no name");
        }

        Object filesValue = info.get("files");
        if (filesValue instanceof List<?> files) {
            List<DownloadFileInfo> result = new ArrayList<>(files.size());
            int index = 1;
            for (Object value : files) {
                Map<String, Object> file = dictionary(value, "torrent file");
                long length = nonNegativeLong(file.get("length"), "torrent file length");
                Object pathValue = firstPresent(file, "path.utf-8", "path");
                if (!(pathValue instanceof List<?> pathParts) || pathParts.isEmpty()) {
                    throw new IOException("Torrent file has no path");
                }
                List<String> components = new ArrayList<>(pathParts.size() + 1);
                components.add(rootName);
                for (Object part : pathParts) {
                    String component = text(part);
                    if (component.isBlank()) {
                        throw new IOException("Torrent file contains a blank path component");
                    }
                    components.add(component);
                }
                result.add(new DownloadFileInfo(index++, String.join("/", components), length));
            }
            if (result.isEmpty()) {
                throw new IOException("Torrent file list is empty");
            }
            return List.copyOf(result);
        }

        long length = nonNegativeLong(info.get("length"), "torrent file length");
        return List.of(new DownloadFileInfo(1, rootName, length));
    }

    private static List<DownloadFileInfo> inspectMetalink(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            NodeList files = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(bytes))
                    .getElementsByTagNameNS("*", "file");
            List<DownloadFileInfo> result = new ArrayList<>(files.getLength());
            for (int i = 0; i < files.getLength(); i++) {
                Element file = (Element) files.item(i);
                String name = file.getAttribute("name").strip();
                if (name.isEmpty()) {
                    throw new IOException("Metalink file has no name");
                }
                long length = 0;
                for (Node child = file.getFirstChild(); child != null; child = child.getNextSibling()) {
                    String localName = child.getLocalName();
                    if ("size".equals(localName != null ? localName : child.getNodeName())) {
                        String sizeText = child.getTextContent().strip();
                        if (!sizeText.isEmpty()) {
                            length = nonNegativeLong(sizeText, "Metalink file size");
                        }
                        break;
                    }
                }
                result.add(new DownloadFileInfo(i + 1, name, length));
            }
            if (result.isEmpty()) {
                throw new IOException("Metalink contains no files");
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid Metalink descriptor: " + e.getMessage(), e);
        }
    }

    private static Object firstPresent(Map<String, Object> values, String preferred,
            String fallback) {
        Object value = values.get(preferred);
        return value != null ? value : values.get(fallback);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dictionary(Object value, String description)
            throws IOException {
        if (!(value instanceof Map<?, ?>)) {
            throw new IOException("Invalid " + description + " dictionary");
        }
        return (Map<String, Object>) value;
    }

    private static String text(Object value) throws IOException {
        if (!(value instanceof byte[] bytes)) {
            throw new IOException("Expected a descriptor string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long nonNegativeLong(Object value, String description) throws IOException {
        long parsed;
        try {
            parsed = value instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new IOException("Invalid " + description, e);
        }
        if (parsed < 0) {
            throw new IOException(description + " cannot be negative");
        }
        return parsed;
    }

    /** Small bounded decoder for the subset of bencode used by torrent metadata. */
    private static final class BencodeReader {

        private static final int MAX_DEPTH = 64;
        private static final int MAX_VALUES = 200_000;
        private final byte[] data;
        private int position;
        private int values;

        private BencodeReader(byte[] data) {
            this.data = data;
        }

        private Object readDocument() throws IOException {
            Object result = readValue(0);
            if (position != data.length) {
                throw new IOException("Trailing bytes after torrent metadata");
            }
            return result;
        }

        private Object readValue(int depth) throws IOException {
            if (depth > MAX_DEPTH || ++values > MAX_VALUES || position >= data.length) {
                throw new IOException("Torrent metadata exceeds parser limits");
            }
            return switch (data[position]) {
                case 'd' -> readDictionary(depth + 1);
                case 'l' -> readList(depth + 1);
                case 'i' -> readInteger();
                default -> {
                    if (data[position] < '0' || data[position] > '9') {
                        throw new IOException("Invalid bencode token at byte " + position);
                    }
                    yield readBytes();
                }
            };
        }

        private Map<String, Object> readDictionary(int depth) throws IOException {
            position++;
            Map<String, Object> result = new LinkedHashMap<>();
            while (!atEndMarker()) {
                String key = new String(readBytes(), StandardCharsets.UTF_8);
                result.put(key, readValue(depth));
            }
            position++;
            return result;
        }

        private List<Object> readList(int depth) throws IOException {
            position++;
            List<Object> result = new ArrayList<>();
            while (!atEndMarker()) {
                result.add(readValue(depth));
            }
            position++;
            return result;
        }

        private long readInteger() throws IOException {
            position++;
            int start = position;
            while (position < data.length && data[position] != 'e') {
                position++;
            }
            if (position >= data.length || start == position) {
                throw new IOException("Unterminated bencode integer");
            }
            String encoded = new String(data, start, position - start, StandardCharsets.US_ASCII);
            position++;
            try {
                return Long.parseLong(encoded);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid bencode integer", e);
            }
        }

        private byte[] readBytes() throws IOException {
            int lengthStart = position;
            while (position < data.length && data[position] >= '0' && data[position] <= '9') {
                position++;
            }
            if (position >= data.length || data[position] != ':' || lengthStart == position) {
                throw new IOException("Invalid bencode byte string length");
            }
            String encodedLength = new String(data, lengthStart, position - lengthStart,
                    StandardCharsets.US_ASCII);
            position++;
            int length;
            try {
                length = Integer.parseInt(encodedLength);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid bencode byte string length", e);
            }
            if (length < 0 || length > data.length - position) {
                throw new IOException("Bencode byte string exceeds descriptor bounds");
            }
            byte[] result = java.util.Arrays.copyOfRange(data, position, position + length);
            position += length;
            return result;
        }

        private boolean atEndMarker() throws IOException {
            if (position >= data.length) {
                throw new IOException("Unterminated bencode collection");
            }
            return data[position] == 'e';
        }
    }
}

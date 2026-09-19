package org.odm.gtk4;

import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TranslationCatalogTest {
    @Test
    void everyMarkedJavaAndGtkMessageHasACompleteFrenchTranslation() throws Exception {
        Map<String, String> catalog = catalog();
        assertTrue(catalog.size() > 900, "The full UI catalog must be bundled");
        for (var entry : catalog.entrySet()) {
            for (String form : entry.getValue().split("\u0000", -1)) {
                assertFalse(form.isBlank(), "Missing French form: " + entry.getKey());
            }
        }
        try (var files = Files.list(Path.of("src/main/java/org/odm/gtk4"));
                var fileManager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            var sources = files.filter(path -> path.toString().endsWith(".java")).toList();
            var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, fileManager, null,
                    java.util.List.of("-proc:none"), null, fileManager.getJavaFileObjectsFromPaths(sources));
            for (var unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethodInvocation(MethodInvocationTree call, Void ignored) {
                        String method = call.getMethodSelect().toString();
                        if (method.equals("I18n.context") && call.getArguments().size() == 2
                                && call.getArguments().get(0) instanceof LiteralTree context
                                && call.getArguments().get(1) instanceof LiteralTree source) {
                            assertTrue(catalog.containsKey(context.getValue() + "\u0004" + source.getValue()),
                                    "Missing contextual translation: " + call);
                        }
                        if (java.util.Set.of("I18n.tr", "I18n.mark", "I18n.format", "I18n.plural").contains(method)
                                && !call.getArguments().isEmpty()
                                && call.getArguments().getFirst() instanceof LiteralTree literal
                                && literal.getValue() instanceof String source && !source.isEmpty()) {
                            assertTrue(catalog.containsKey(source), "Missing catalog entry in "
                                    + unit.getSourceFile().getName() + ": " + source);
                        }
                        return super.visitMethodInvocation(call, ignored);
                    }
                }.scan(unit, null);
            }
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var files = Files.list(Path.of("src/main/resources/ui"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".ui")).toList()) {
                var document = factory.newDocumentBuilder().parse(file.toFile());
                assertEquals(I18n.DOMAIN, document.getDocumentElement().getAttribute("domain"),
                        "GTK must use the application translation catalog: " + file);
                var elements = document.getElementsByTagName("*");
                for (int i = 0; i < elements.getLength(); i++) {
                    var element = (org.w3c.dom.Element) elements.item(i);
                    if (element.getAttribute("translatable").equals("yes")) {
                        String source = element.getTextContent();
                        if (element.hasAttribute("context")) {
                            source = element.getAttribute("context") + "\u0004" + source;
                        }
                        assertTrue(catalog.containsKey(source), "Missing GTK translation in " + file + ": " + source);
                    }
                }
            }
        }
    }

    private static Map<String, String> catalog() throws Exception {
        byte[] data;
        try (var input = I18n.class.getResourceAsStream("/locale/fr/LC_MESSAGES/odm.mo")) {
            assertNotNull(input, "Compiled catalog must be on the classpath");
            data = input.readAllBytes();
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x950412de, buffer.getInt(0), "GNU MO magic");
        int count = buffer.getInt(8), originals = buffer.getInt(12), translations = buffer.getInt(16);
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String original = string(buffer, data, originals + i * 8);
            if (!original.isEmpty()) {
                result.put(original.split("\u0000", 2)[0], string(buffer, data, translations + i * 8));
            }
        }
        return result;
    }

    private static String string(ByteBuffer buffer, byte[] data, int offset) {
        return new String(data, buffer.getInt(offset + 4), buffer.getInt(offset), StandardCharsets.UTF_8);
    }
}

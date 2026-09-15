package org.manager.tools;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/** Bounded, redacted native-tool output retained for a failed process. */
public final class ProcessDiagnostics {
    private static final int MAX_CHARS = 4096;
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>\"']+|\\bmagnet:\\?[^\\s<>\"']+");
    private static final Pattern HEADER = Pattern.compile(
            "(?im)\\b(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key)[\"']?\\s*[:=][^\\r\\n]*");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|token|access_token|refresh_token|api[-_]key|secret)"
            + "([\"']?\\s*[:=]\\s*)(?:\"[^\"]*(?:\"|$)|'[^']*(?:'|$)|[^\\s,;}\\]]+)");
    private static final Pattern FAILURE = Pattern.compile(
            "(?i)\\[(?:error|warn(?:ing)?)\\]|\\b(?:error|warning|exception|curl):|\\berrorCode=");
    private record Line(String text, boolean failure) { }
    private final Deque<Line> lines = new ArrayDeque<>();
    private int chars;

    public static String sanitize(String text) {
        if (text == null) { return ""; }
        // Bound regex work as well as the retained result. Redact before
        // truncating the result so a URL/token at the boundary stays hidden.
        String safe = text.substring(0, Math.min(text.length(), MAX_CHARS * 2));
        safe = ANSI.matcher(safe).replaceAll("");
        safe = URL.matcher(safe).replaceAll("<URL>");
        safe = HEADER.matcher(safe).replaceAll("<redacted header>");
        safe = SECRET.matcher(safe).replaceAll("$1$2<redacted>");
        safe = safe.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        return safe.substring(0, Math.min(safe.length(), MAX_CHARS));
    }

    public synchronized void addLine(String line) {
        if (line == null || line.isBlank()) { return; }
        String trimmed = line.stripLeading();
        // Machine metadata contains URLs, request headers and cookies. It
        // is not a diagnostic, even when it precedes a failed extraction.
        if (trimmed.startsWith("{") || trimmed.startsWith("[{") || trimmed.startsWith("[\"")
                || trimmed.startsWith("[debug]") || trimmed.startsWith("|odm")
                || trimmed.startsWith("[download]") || trimmed.startsWith("[#")) { return; }
        String safe = sanitize(line);
        safe = safe.substring(0, Math.min(1024, safe.length()));
        if (safe.isBlank() || !lines.isEmpty() && safe.equals(lines.peekLast().text())) { return; }
        boolean failure = FAILURE.matcher(safe).find();
        while (!lines.isEmpty() && (lines.size() >= 8 || chars + safe.length() + 1 > MAX_CHARS)) {
            // Keep actionable errors when a tool ends with a long results
            // table or boilerplate. Among errors, retain the newest ones.
            Line evicted = lines.stream().filter(item -> !item.failure()).findFirst().orElse(null);
            if (evicted == null) {
                if (!failure) { return; }
                evicted = lines.getFirst();
            }
            lines.remove(evicted);
            chars -= evicted.text().length() + 1;
        }
        lines.addLast(new Line(safe, failure));
        chars += safe.length() + 1;
    }

    public synchronized String message(String summary) {
        String safeSummary = sanitize(summary);
        return lines.isEmpty() ? safeSummary : safeSummary + "\n"
                + String.join("\n", lines.stream().map(Line::text).toList());
    }

    public static String failureMessage(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException)) {
            failure = failure.getCause();
        }
        return sanitize(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    }
}

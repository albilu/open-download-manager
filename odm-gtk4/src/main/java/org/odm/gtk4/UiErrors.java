package org.odm.gtk4;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/** User-facing failure text. Callers log the original failure, not this summary. */
final class UiErrors {
    private static final String UNEXPECTED =
            "An unexpected error occurred. See the application log for details.";
    private static final Pattern EXCEPTION = Pattern.compile(
            "\\b(?:[\\w$]+\\.)*(?:[A-Z][\\w$]*(?:Exception|Error)|Exception|Throwable)\\b:?[ \\t]*");
    private static final Pattern STACK_FRAME = Pattern.compile(
            "^(?:at\\s+[^\\s]+\\([^)]*\\)|\\.\\.\\. \\d+ (?:more|common frames omitted)|"
                    + "--- End of (?:inner exception|stack trace).*)$");
    private static final Pattern INLINE_STACK = Pattern.compile(
            "\\s+(?:at\\s+(?:[\\w$]+[./])+[\\w$<>]+\\(|--->|Caused by:|Suppressed:)");

    private UiErrors() { }

    static String message(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cause = failure;
        while (cause != null && seen.add(cause)) {
            if (cause.getCause() == null || seen.contains(cause.getCause())) {
                break;
            }
            cause = cause.getCause();
        }
        if (cause == null) { return UNEXPECTED; }
        String known = knownFailure(cause.getClass().getSimpleName());
        return known != null ? known : message(cause.getMessage());
    }

    /** Also handles failures persisted by engines or received from external services. */
    static String message(String failure) {
        String text = details(failure).lines().filter(line -> !line.isBlank())
                .findFirst().orElse(UNEXPECTED);
        return text.length() > 240 ? text.substring(0, 237).stripTrailing() + "…" : text;
    }

    /** Retains tool output in the explicit details window, without Java stack traces. */
    static String details(String output) {
        if (output == null || output.isBlank()) { return UNEXPECTED; }
        return output.lines().map(UiErrors::cleanLine).filter(line -> !line.isBlank())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.joining("\n"),
                        text -> text.isBlank() ? UNEXPECTED : text));
    }

    private static String cleanLine(String line) {
        String text = line.strip().replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "");
        if (STACK_FRAME.matcher(text).matches()) { return ""; }
        text = text.replaceFirst("^(?:Caused by:|Suppressed:|Exception in thread \\\"[^\\\"]*\\\")\\s*", "");
        text = INLINE_STACK.split(text, 2)[0];
        var match = EXCEPTION.matcher(text);
        while (match.find()) {
            String name = match.group().replaceFirst(":.*$", "").strip();
            name = name.substring(name.lastIndexOf('.') + 1);
            String known = knownFailure(name);
            if (known != null) {
                return (text.substring(0, match.start()) + known).strip();
            }
            text = match.replaceFirst("").strip();
            match = EXCEPTION.matcher(text);
        }
        return text.isBlank() ? UNEXPECTED : text;
    }

    private static String knownFailure(String name) {
        return switch (name) {
            case "UnknownHostException", "UnresolvedAddressException" ->
                    "The server address could not be found. Check the address and network settings.";
            case "ConnectException" ->
                    "Could not connect to the server. Check your network and proxy settings.";
            case "SocketTimeoutException", "HttpTimeoutException", "HttpConnectTimeoutException", "TimeoutException" ->
                    "The request timed out. Please try again.";
            case "SSLException", "SSLHandshakeException", "SSLPeerUnverifiedException" ->
                    "Could not establish a secure connection. Check your network settings.";
            case "AccessDeniedException" -> "Permission denied. Check access to the selected file or folder.";
            case "NoSuchFileException", "FileNotFoundException" -> "The file or folder could not be found.";
            case "FileAlreadyExistsException" -> "A file already exists at the destination.";
            case "CancellationException", "InterruptedException" -> "The operation was canceled.";
            case "NullPointerException", "ClassCastException", "IndexOutOfBoundsException",
                    "ArrayIndexOutOfBoundsException", "StringIndexOutOfBoundsException",
                    "NoSuchMethodError", "NoClassDefFoundError", "ExceptionInInitializerError",
                    "StackOverflowError", "AssertionError" -> UNEXPECTED;
            default -> null;
        };
    }
}

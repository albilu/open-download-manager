package org.manager.url;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.manager.download.Download;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared admission policy for download sources. {@link #parse(String)} validates
 * an entire input; {@link #extract(String)} discovers candidates in free-form
 * text. HTML resolution and sequence generation happen before admission.
 * Validation is structural and does not perform DNS or network requests.
 */
public final class DownloadUrlPolicy {

    private DownloadUrlPolicy() { }

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadUrlPolicy.class);

    /** Generous ceiling that also bounds parser/regex work on hostile clipboard data. */
    private static final int MAX_URL_LENGTH = 65_536;

    /*
     * Find only a supported scheme marker here, then scan the surrounding token
     * iteratively. Keeping host/path grammar out of this regex is intentional:
     * repeated DNS-label regex groups can overflow the Java regex stack on a
     * long dotted clipboard value.
     *
     * The left boundary prevents a URL-looking suffix from being salvaged out
     * of values such as javascript:https://..., git+https://..., an email, or a
     * local filesystem path.
     */
    private static final Pattern SUPPORTED_SCHEME_START = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_+.:@/\\\\-])"
            + "(?:https?://|ftps?://|sftp://|file:/+|magnet:\\?)");

    private static final Pattern SUPPORTED_SCHEME_PREFIX = Pattern.compile(
            "(?iu)^(?:https?://|ftps?://|sftp://|file:/+|magnet:\\?)");

    private static final Pattern EXPLICIT_SCHEME_PREFIX = Pattern.compile(
            "(?iu)^[a-z][a-z0-9+.-]*:");

    private static final Pattern DNS_LABEL_PATTERN = Pattern.compile(
            "(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private static final Pattern HEX_BT_INFO_HASH = Pattern.compile("(?i)[0-9a-f]{40}");
    private static final Pattern BASE32_BT_INFO_HASH = Pattern.compile("(?i)[a-z2-7]{32}");
    private static final Pattern BTMH_INFO_HASH = Pattern.compile("(?i)1220[0-9a-f]{64}");

    /*
     * A bare two-label token is inherently ambiguous (example.com is a URL,
     * README.md is normally a filename). Common web suffixes are accepted;
     * uncommon suffixes need another URL signal such as www, a port, or a
     * path/query. Explicitly schemed URLs are never subject to this heuristic.
     */
    private static final Set<String> COMMON_BARE_TLDS = Set.of(
            "aero", "ai", "app", "asia", "biz", "blog", "cat", "cloud",
            "club", "co", "com", "coop", "dev", "digital", "edu", "email",
            "gov", "info", "int", "io", "jobs", "live", "me", "mil", "mobi",
            "museum", "net", "news", "online", "onion", "org", "pro", "shop", "site",
            "software", "space", "store", "systems", "tech", "tel", "top",
            "travel", "tv", "website", "world", "xyz");

    private static final Set<String> AMBIGUOUS_FILE_SUFFIXES = Set.of(
            "7z", "a", "aac", "apk", "avi", "bak", "bash", "bat", "bin",
            "bz", "bz2", "c", "cc", "cfg", "class", "cmd", "conf", "cpp",
            "cs", "css", "csv", "dart", "db", "deb", "dll", "dmg", "doc",
            "docx", "dylib", "exe", "fish", "flac", "flv", "fs", "gif", "go",
            "gradle", "groovy", "gz", "h", "hpp", "htm", "html", "ico", "img",
            "ini", "ipa", "iso", "jar", "java", "jpeg", "jpg", "js", "json",
            "jsx", "kt", "kts", "less", "lock", "log", "lua", "map", "md",
            "mkv", "mov", "mp3", "mp4", "msi", "name", "o", "obj", "ogg",
            "old", "pdf", "php", "pkg", "pl", "png", "ppt", "pptx",
            "properties", "ps1", "py", "rar", "rb", "rpm", "rs", "sass",
            "scala", "scss", "sh", "so", "sql", "sqlite", "svelte", "svg",
            "swift", "tar", "tmp", "toml", "torrent", "ts", "tsx", "txt", "vb",
            "vue", "war", "wasm", "wav", "webm", "webp", "wmv", "xls", "xlsx",
            "xml", "xz", "yaml", "yml", "zip", "zsh");

    /**
     * Extracts all valid URLs from the given text.
     *
     * @param text The text to search for URLs
     * @return Validated sources found in the text, in encounter order without duplicates
     */
    public static List<ValidatedSource> extract(String text) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        List<ValidatedSource> urls = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        int cursor = 0;
        while (cursor < text.length()) {
            while (cursor < text.length() && isTokenBoundary(text.charAt(cursor))) {
                cursor++;
            }
            if (cursor >= text.length()) {
                break;
            }

            int tokenStart = cursor;
            while (cursor < text.length() && !isTokenBoundary(text.charAt(cursor))) {
                cursor++;
            }
            inspectToken(text.substring(tokenStart, cursor), urls, seen);
        }

        return urls;
    }

    private static void inspectToken(String token, List<ValidatedSource> urls, Set<String> seen) {
        String wholeCandidate = unwrapBareCandidate(token);
        if (addIfValid(wholeCandidate, urls, seen)) {
            return;
        }
        // Once a token explicitly starts as a URL, failure applies to the whole
        // token. Do not rescue an embedded URL from its malformed path/query.
        if (EXPLICIT_SCHEME_PREFIX.matcher(wholeCandidate).find()) {
            return;
        }

        Matcher schemeMatcher = SUPPORTED_SCHEME_START.matcher(token);
        boolean containedSupportedScheme = false;
        while (schemeMatcher.find()) {
            containedSupportedScheme = true;
            String candidate = trimTrailingDelimiters(token.substring(schemeMatcher.start()));
            addIfValid(candidate, urls, seen);
        }

        if (containedSupportedScheme) {
            return;
        }

        addIfValid(wholeCandidate, urls, seen);
    }

    private static boolean addIfValid(String candidate, List<ValidatedSource> urls, Set<String> seen) {
        Optional<ValidatedSource> normalized = parse(candidate);
        if (normalized.isEmpty()) {
            return false;
        }
        ValidatedSource source = normalized.orElseThrow();
        if (seen.add(source.uri().toString())) {
            urls.add(source);
            LOGGER.debug("Detected a valid URL");
        }
        return true;
    }

    private static String unwrapBareCandidate(String token) {
        int markdownTarget = token.lastIndexOf("](");
        String candidate = markdownTarget >= 0
                ? token.substring(markdownTarget + 2)
                : token;
        int start = 0;
        while (start < candidate.length() && isOpeningWrapper(candidate.charAt(start))) {
            start++;
        }
        return trimTrailingDelimiters(candidate.substring(start));
    }

    private static boolean isOpeningWrapper(char value) {
        return value == '(' || value == '[' || value == '{' || value == '\'';
    }

    private static boolean isTokenBoundary(char value) {
        return Character.isWhitespace(value)
                || Character.isISOControl(value)
                || value == '<' || value == '>' || value == '"' || value == '`'
                || value == '\u2018' || value == '\u2019'
                || value == '\u201c' || value == '\u201d';
    }

    private static String trimTrailingDelimiters(String candidate) {
        int end = candidate.length();
        int parentheses = delimiterBalance(candidate, '(', ')');
        int brackets = delimiterBalance(candidate, '[', ']');
        int braces = delimiterBalance(candidate, '{', '}');
        while (end > 0) {
            char last = candidate.charAt(end - 1);
            if (last == '.' || last == ',' || last == ';' || last == ':'
                    || last == '!' || last == '?' || last == '\'' || last == '\u2026') {
                end--;
                continue;
            }
            if (last == ')' && parentheses < 0) {
                end--;
                parentheses++;
                continue;
            }
            if (last == ']' && brackets < 0) {
                end--;
                brackets++;
                continue;
            }
            if (last == '}' && braces < 0) {
                end--;
                braces++;
                continue;
            }
            break;
        }
        return candidate.substring(0, end);
    }

    private static int delimiterBalance(String value, char opening, char closing) {
        int balance = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == opening) {
                balance++;
            } else if (current == closing) {
                balance--;
            }
        }
        return balance;
    }

    /**
     * Checks if the given text contains any valid URLs.
     *
     * @param text The text to check
     * @return true if valid URLs are found, false otherwise
     */
    public static boolean containsUrls(String text) {
        return !extract(text).isEmpty();
    }

    /**
     * Normalizes a URL string to a proper URI.
     *
     * @param urlString The URL string to normalize
     * @return A normalized URI, or null if invalid
     */
    private static URI normalizeUrl(String urlString) {
        if (urlString == null || urlString.isEmpty() || urlString.length() > MAX_URL_LENGTH) {
            return null;
        }
        try {
            if (!SUPPORTED_SCHEME_PREFIX.matcher(urlString).find()) {
                if (!looksLikeBareWebUrl(urlString)) {
                    return null;
                }
                urlString = "https://" + urlString;
            }

            int schemeEnd = urlString.indexOf(':');
            if (schemeEnd <= 0) {
                return null;
            }
            String normalized = urlString.substring(0, schemeEnd).toLowerCase(Locale.ROOT)
                    + urlString.substring(schemeEnd);
            if (normalized.regionMatches(true, 0, "http://", 0, 7)
                    || normalized.regionMatches(true, 0, "https://", 0, 8)
                    || normalized.regionMatches(true, 0, "ftp://", 0, 6)
                    || normalized.regionMatches(true, 0, "ftps://", 0, 7)
                    || normalized.regionMatches(true, 0, "sftp://", 0, 7)) {
                normalized = normalizeNetworkAuthority(normalized);
            }
            return new URI(normalized);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeNetworkAuthority(String url) {
        int authorityStart = url.indexOf("://") + 3;
        int authorityEnd = url.length();
        for (int i = authorityStart; i < url.length(); i++) {
            char value = url.charAt(i);
            if (value == '/' || value == '?' || value == '#') {
                authorityEnd = i;
                break;
            }
        }

        String authority = url.substring(authorityStart, authorityEnd);
        int at = authority.lastIndexOf('@');
        String userInfo = at >= 0 ? authority.substring(0, at + 1) : "";
        String hostAndPort = at >= 0 ? authority.substring(at + 1) : authority;
        if (hostAndPort.isEmpty()) {
            throw new IllegalArgumentException("missing host");
        }

        String host;
        String port = "";
        if (hostAndPort.charAt(0) == '[') {
            int closing = hostAndPort.indexOf(']');
            if (closing < 0) {
                throw new IllegalArgumentException("invalid IP literal");
            }
            host = hostAndPort.substring(0, closing + 1).toLowerCase(Locale.ROOT);
            port = hostAndPort.substring(closing + 1);
        } else {
            int colon = hostAndPort.lastIndexOf(':');
            if (colon >= 0) {
                host = hostAndPort.substring(0, colon);
                port = hostAndPort.substring(colon);
            } else {
                host = hostAndPort;
            }
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        }

        return url.substring(0, authorityStart) + userInfo + host + port
                + url.substring(authorityEnd);
    }

    private static boolean looksLikeBareWebUrl(String candidate) {
        if (candidate.isEmpty() || candidate.length() > MAX_URL_LENGTH
                || candidate.indexOf('@') >= 0 || candidate.indexOf('\\') >= 0
                || candidate.charAt(0) == '/' || candidate.charAt(0) == '.'
                || candidate.charAt(0) == '-') {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (Character.isWhitespace(candidate.charAt(i))
                    || Character.isISOControl(candidate.charAt(i))) {
                return false;
            }
        }

        int authorityEnd = candidate.length();
        for (int i = 0; i < candidate.length(); i++) {
            char value = candidate.charAt(i);
            if (value == '/' || value == '?' || value == '#') {
                authorityEnd = i;
                break;
            }
        }
        String authority = candidate.substring(0, authorityEnd);
        if (authority.isEmpty() || authority.charAt(0) == '[') {
            return false; // IP literals require an explicit scheme.
        }

        boolean explicitPort = false;
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
            if (authority.indexOf(':') != colon) {
                return false;
            }
            String rawPort = authority.substring(colon + 1);
            if (rawPort.isEmpty() || !rawPort.chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                int port = Integer.parseInt(rawPort);
                if (port < 1 || port > 65_535) {
                    return false;
                }
            } catch (NumberFormatException invalidPort) {
                return false;
            }
            authority = authority.substring(0, colon);
            explicitPort = true;
        }

        final String asciiHost;
        try {
            asciiHost = IDN.toASCII(authority, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalidHost) {
            return false;
        }
        if (!isValidDnsHost(asciiHost) || asciiHost.matches("[0-9.]+")) {
            return false; // bare numeric text is too ambiguous for clipboard admission.
        }

        String hostWithoutDot = asciiHost.endsWith(".")
                ? asciiHost.substring(0, asciiHost.length() - 1)
                : asciiHost;
        int lastDot = hostWithoutDot.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == hostWithoutDot.length() - 1) {
            return false;
        }
        String tld = hostWithoutDot.substring(lastDot + 1);
        if (AMBIGUOUS_FILE_SUFFIXES.contains(tld)) {
            return false;
        }

        boolean strongerUrlSignal = candidate.regionMatches(true, 0, "www.", 0, 4)
                || authorityEnd < candidate.length()
                || explicitPort
                || tld.startsWith("xn--");
        return tld.length() == 2 || COMMON_BARE_TLDS.contains(tld) || strongerUrlSignal;
    }

    /** Normalizes user input and returns it only when it is a supported URL. */
    public static Optional<ValidatedSource> parse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        URI normalized = normalizeUrl(input.strip());
        return isValidDownloadUri(normalized)
                ? Optional.of(new ValidatedSource(normalized)) : Optional.empty();
    }

    /**
     * Normalizes and validates URL text, throwing a user-facing argument
     * error instead of allowing each caller to accept a different scheme.
     */
    public static ValidatedSource require(String input) {
        return parse(input)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or unsupported download URL"));
    }

    /** Validates and normalizes an already-parsed URI. */
    public static ValidatedSource require(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("Download URI cannot be null");
        }
        return require(uri.toString());
    }

    /**
     * Checks if a URI represents a valid download URL.
     *
     * @param uri The URI to check
     * @return true if it's a valid download URL, false otherwise
     */
    public static boolean isValidDownloadUri(URI uri) {
        if (uri == null || uri.toString().length() > MAX_URL_LENGTH) {
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }

        scheme = scheme.toLowerCase(Locale.ROOT);

        // Accept common download protocols
        if (scheme.equals("http") || scheme.equals("https")
                || scheme.equals("ftp") || scheme.equals("ftps")
                || scheme.equals("sftp")
                || scheme.equals("magnet")
                || scheme.equals("file")) {

            // Magnet links require an exact-topic parameter.
            if (scheme.equals("magnet")) {
                return uri.isOpaque() && uri.getRawSchemeSpecificPart().startsWith("?")
                        && hasValidMagnetExactTopic(uri);
            }

            // Local URLs are valid only for aria2 descriptor files.
            if (scheme.equals("file")) {
                return isValidLocalDescriptor(uri);
            }

            if (!isValidNetworkUri(uri)) {
                return false;
            }

            // Any structurally valid URL using a supported network scheme is a
            // candidate; the remote response determines its eventual content.
            return true;
        }

        return false;
    }

    private static boolean hasValidMagnetExactTopic(URI uri) {
        String query = uri.getRawQuery();
        if (query == null) {
            query = uri.getRawSchemeSpecificPart();
            if (query != null && query.startsWith("?")) {
                query = query.substring(1);
            }
        }
        if (query == null || query.isEmpty()) {
            return false;
        }

        for (String parameter : query.split("&")) {
            int equals = parameter.indexOf('=');
            if (equals <= 0 || !parameter.substring(0, equals).equalsIgnoreCase("xt")) {
                continue;
            }
            try {
                String topic = URLDecoder.decode(parameter.substring(equals + 1),
                        StandardCharsets.UTF_8);
                if (isSupportedMagnetTopic(topic)) {
                    return true;
                }
            } catch (IllegalArgumentException invalidEscape) {
                return false;
            }
        }
        return false;
    }

    private static boolean isSupportedMagnetTopic(String topic) {
        if (topic.regionMatches(true, 0, "urn:btih:", 0, 9)) {
            String hash = topic.substring(9);
            return HEX_BT_INFO_HASH.matcher(hash).matches()
                    || BASE32_BT_INFO_HASH.matcher(hash).matches();
        }
        if (topic.regionMatches(true, 0, "urn:btmh:", 0, 9)) {
            return BTMH_INFO_HASH.matcher(topic.substring(9)).matches();
        }
        return false;
    }

    private static boolean isValidNetworkUri(URI uri) {
        if (uri.isOpaque() || uri.getRawAuthority() == null
                || uri.getHost() == null || uri.getHost().isBlank()) {
            return false;
        }
        return hasValidPort(uri) && isValidHost(uri.getHost());
    }

    private static boolean isValidLocalDescriptor(URI uri) {
        Download.Protocol protocol = Download.Protocol.fromUri(uri);
        if (uri.isOpaque() || uri.getPath() == null || !uri.getPath().startsWith("/")
                || uri.getRawAuthority() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (protocol != Download.Protocol.TORRENT
                && protocol != Download.Protocol.METALINK)) {
            return false;
        }
        try {
            Path.of(uri);
            return true;
        } catch (IllegalArgumentException invalidPath) {
            return false;
        }
    }

    private static boolean hasValidPort(URI uri) {
        String authority = uri.getRawAuthority();
        int at = authority.lastIndexOf('@');
        String hostAndPort = at >= 0 ? authority.substring(at + 1) : authority;
        String rawPort = null;

        if (hostAndPort.startsWith("[")) {
            int closing = hostAndPort.indexOf(']');
            if (closing < 0) {
                return false;
            }
            String suffix = hostAndPort.substring(closing + 1);
            if (!suffix.isEmpty()) {
                if (suffix.charAt(0) != ':') {
                    return false;
                }
                rawPort = suffix.substring(1);
            }
        } else {
            int colon = hostAndPort.lastIndexOf(':');
            if (colon >= 0) {
                if (hostAndPort.indexOf(':') != colon) {
                    return false;
                }
                rawPort = hostAndPort.substring(colon + 1);
            }
        }

        if (rawPort == null) {
            return uri.getPort() == -1;
        }
        if (rawPort.isEmpty() || !rawPort.chars().allMatch(Character::isDigit)) {
            return false;
        }
        try {
            int port = Integer.parseInt(rawPort);
            return port >= 1 && port <= 65_535 && uri.getPort() == port;
        } catch (NumberFormatException invalidPort) {
            return false;
        }
    }

    private static boolean isValidHost(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.length() > 2; // URI has already validated the IP literal grammar.
        }
        final String asciiHost;
        try {
            asciiHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalidHost) {
            return false;
        }
        if (asciiHost.matches("[0-9.]+")) {
            return isIpv4Address(asciiHost);
        }
        return isValidDnsHost(asciiHost);
    }

    private static boolean isValidDnsHost(String host) {
        String withoutTrailingDot = host.endsWith(".")
                ? host.substring(0, host.length() - 1)
                : host;
        if (withoutTrailingDot.isEmpty() || withoutTrailingDot.length() > 253) {
            return false;
        }
        String[] labels = withoutTrailingDot.split("\\.", -1);
        for (String label : labels) {
            if (!DNS_LABEL_PATTERN.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4Address(String host) {
        if (!host.matches("[0-9.]+")) {
            return false;
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            try {
                if (Integer.parseInt(octet) > 255) {
                    return false;
                }
            } catch (NumberFormatException invalidOctet) {
                return false;
            }
        }
        return true;
    }

    /**
     * A normalized, supported source. Only this policy can construct one;
     * classification and input adapters consume the admitted URI.
     */
    public static final class ValidatedSource {
        private final URI uri;

        private ValidatedSource(URI uri) {
            this.uri = uri;
        }

        public URI uri() {
            return uri;
        }

        public Download.Protocol protocol() {
            return Download.Protocol.fromUri(uri);
        }

        public boolean isWeb() {
            return "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        }

        /** A workflow restriction applied after shared URL validation. */
        public ValidatedSource requireWeb() {
            if (!isWeb()) {
                throw new IllegalArgumentException("An HTTP(S) URL is required");
            }
            return this;
        }

        /** Mirrors and direct-transfer engines cannot accept descriptors or magnets. */
        public ValidatedSource requireDirectTransfer() {
            if (protocol() == null || !protocol().isDirectTransfer()) {
                throw new IllegalArgumentException("A direct HTTP, HTTPS, FTP or SFTP URL is required");
            }
            return this;
        }
    }

}

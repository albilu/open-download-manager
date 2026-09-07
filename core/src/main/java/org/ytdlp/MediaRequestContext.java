package org.ytdlp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Request context captured during discovery, retained with the new download. */
public record MediaRequestContext(String pageUrl, String referer, String userAgent,
        String origin, String cookies) {

    public MediaRequestContext {
        referer = header(referer);
        userAgent = header(userAgent);
        origin = header(origin);
        cookies = cookies == null ? "" : cookies;
        if (cookies.length() > 1024 * 1024) {
            throw new IllegalArgumentException("Media cookies exceed the size limit");
        }
    }

    private static String header(String value) {
        if (value == null) { return ""; }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid media request header");
        }
        return value;
    }

    public void applyTo(YtDlpSettings settings) {
        settings.setMediaRequestContext(this);
        settings.setReferer(referer);
        settings.setUserAgent(userAgent);
        // Cookies use a domain/path-scoped jar, never a global Cookie header.
        settings.setCookieHeader(null);
        settings.setCookieFile(null);
        settings.setBrowserCookieSource(YtDlpSettings.BrowserCookieSource.NONE);
        settings.setBrowserCookieProfile(null);
    }

    /** Each engine invocation gets its own cookie file; no dialog-owned paths escape. */
    static Prepared prepare(YtDlpSettings original) throws IOException {
        MediaRequestContext context = original.getMediaRequestContext();
        if (context == null || original.getCookieFile() != null || original.getBrowserCookieArgument() != null) {
            return new Prepared(original, null);
        }
        YtDlpSettings settings = (YtDlpSettings) original.copy();
        Path file = Files.createTempFile("odm-media-cookies-", ".txt");
        try {
            Files.writeString(file, context.cookies().isBlank()
                    ? "# Netscape HTTP Cookie File\n" : context.cookies());
            settings.setCookieFile(file.toString());
            settings.setBrowserCookieSource(YtDlpSettings.BrowserCookieSource.NONE);
            return new Prepared(settings, file);
        } catch (Exception failure) {
            Files.deleteIfExists(file);
            throw failure;
        }
    }

    record Prepared(YtDlpSettings settings, Path cookieFile) implements AutoCloseable {
        @Override public void close() throws IOException {
            if (cookieFile != null) { Files.deleteIfExists(cookieFile); }
        }
    }
}

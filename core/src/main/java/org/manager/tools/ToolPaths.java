package org.manager.tools;

import org.manager.ApplicationContext;

/**
 * Single seam for external-tool executable resolution. Each tool client
 * used to carry its own copy of the try-factory-get-manager-get-path-with-
 * fallback preamble (~25 lines x 6 clients, drifting apart over time); all
 * of them now delegate here. Blank manager paths fall back to the bare
 * executable name so PATH resolution still works without the managers.
 */
public final class ToolPaths {

    private ToolPaths() {
    }

    /** Resolves a tool path via its ToolManager, with a bare-name fallback. */
    public static String resolve(String toolId, String fallback) {
        try {
            ToolManagerFactory factory = ApplicationContext.getToolManagerFactory();
            if (factory != null) {
                String path = factory.getToolPath(toolId);
                if (path != null && !path.isBlank()) {
                    return path;
                }
            }
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** aria2c executable path. */
    public static String aria2c() {
        return resolve("aria2", "aria2c");
    }

    /** yt-dlp executable path. */
    public static String ytDlp() {
        return resolve("yt-dlp", "yt-dlp");
    }

    /** httrack executable path. */
    public static String httrack() {
        return resolve("httrack", "httrack");
    }

    /** curl executable path. */
    public static String curl() {
        return resolve("curl", "curl");
    }

    /** proxychains executable path (proxychains4 is the modern binary name). */
    public static String proxychains() {
        return resolve("proxychains", "proxychains4");
    }
}

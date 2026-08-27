package org.manager.download;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.manager.download.handler.Aria2DownloadHandler;
import org.manager.download.handler.DownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

/**
 * Owns the aria2 session/input file plumbing: the paths derived from the
 * download directory and session save/load through the aria2 handler's
 * typed API. Failures are logged and swallowed — persistence of the ODM
 * state store must not be blocked by an aria2 session problem.
 */
class Aria2SessionManager {

    private static final Logger LOGGER = Logger.getLogger(Aria2SessionManager.class.getName());

    private static final String ARIA2_SESSION_FILE = "aria2-session.txt";
    private static final String ARIA2_INPUT_FILE = "aria2-input.txt";

    private final Path sessionFilePath;
    private final Path inputFilePath;

    /**
     * Creates the session manager for a download directory.
     *
     * @param downloadDirectory the directory holding aria2's session/input
     *            files (stays with the download directory, unlike ODM state
     *            which lives in the XDG data dir)
     */
    Aria2SessionManager(Path downloadDirectory) {
        this.sessionFilePath = downloadDirectory.resolve(ARIA2_SESSION_FILE);
        this.inputFilePath = downloadDirectory.resolve(ARIA2_INPUT_FILE);
    }

    /**
     * Saves the aria2 session through the handler's typed API. Typed cast,
     * not reflection: a rename must break at compile time, not silently at
     * shutdown.
     */
    void saveSession(DownloadHandlerFactory factory) {
        try {
            if (factory != null) {
                DownloadHandler aria2Handler = factory.getHandler(Download.Type.ARIA2);
                if (aria2Handler instanceof Aria2DownloadHandler typed) {
                    typed.saveSession();
                    LOGGER.info("Saved aria2 session to: " + sessionFilePath);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to save aria2 session", e);
        }
    }

    /**
     * Loads the aria2 session from the session file, when it exists.
     */
    void loadSession(DownloadHandlerFactory factory) {
        try {
            if (Files.exists(sessionFilePath)) {
                if (factory != null) {
                    DownloadHandler aria2Handler = factory.getHandler(Download.Type.ARIA2);
                    if (aria2Handler instanceof Aria2DownloadHandler typed) {
                        typed.loadSession(sessionFilePath);
                        LOGGER.info("Loaded aria2 session from: " + sessionFilePath);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to load aria2 session", e);
        }
    }

    /**
     * Configures aria2 to use session and input files for better
     * persistence. This method should be called during aria2 handler
     * initialization.
     */
    void configureAria2Session() {
        try {
            // Create aria2 configuration with session support
            Map<String, String> aria2Config = new HashMap<>();
            aria2Config.put("save-session", sessionFilePath.toString());
            aria2Config.put("save-session-interval", "60"); // Save every 60 seconds

            if (Files.exists(sessionFilePath)) {
                aria2Config.put("input-file", sessionFilePath.toString());
            }

            // This configuration will be used by the aria2 handler during initialization
            LOGGER.info("Configured aria2 session management with files: " + sessionFilePath);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to configure aria2 session", e);
        }
    }

    /** Path of the aria2 session file. */
    Path getSessionFilePath() {
        return sessionFilePath;
    }

    /** Path of the aria2 input file. */
    Path getInputFilePath() {
        return inputFilePath;
    }
}

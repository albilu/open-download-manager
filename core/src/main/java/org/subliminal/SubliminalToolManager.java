package org.subliminal;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.manager.GlobalSettings;
import org.manager.tools.AbstractToolManager;

/** Discovers and validates the Subliminal subtitle-download CLI. */
public final class SubliminalToolManager extends AbstractToolManager {

    public static final String TOOL_ID = "subliminal";
    public static final String EXECUTABLE_NAME = "subliminal";

    private static final List<String> COMMON_LOCATIONS = List.of(
            "/usr/bin/subliminal",
            "/usr/local/bin/subliminal",
            Path.of(System.getProperty("user.home", ""), ".local", "bin", "subliminal")
                    .toString());

    public SubliminalToolManager(GlobalSettings settings, ExecutorService executor) {
        super(settings, executor);
    }

    @Override
    public String getToolId() {
        return TOOL_ID;
    }

    @Override
    public String getExecutableName() {
        return EXECUTABLE_NAME;
    }

    @Override
    protected String getConfiguredPath() {
        return settings.getSubliminalPath();
    }

    @Override
    protected void updateSettingsPath(String path) {
        settings.setSubliminalPath(path);
    }

    @Override
    protected List<String> getCommonLocations() {
        return COMMON_LOCATIONS;
    }

    @Override
    protected String[] getVersionCommand(String toolPath) {
        return new String[]{toolPath, "--version"};
    }

    @Override
    protected String parseVersion(String output) {
        if (output == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)subliminal(?:,?\\s+version)?\\s+([0-9][^\\s]*)")
                .matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Override
    protected Map<String, Boolean> detectFeatures() {
        return getToolPath() == null ? Map.of() : Map.of(
                "subtitle-download", true,
                "multiple-languages", true,
                "skip-existing", true);
    }

    @Override
    protected boolean supportsEmbeddedBinary() {
        return false;
    }

    @Override
    protected String getEmbeddedBinaryResourcePath() {
        return null;
    }

    @Override
    protected boolean executeBasicCheck(String toolPath) {
        Process process = null;
        try {
            process = new ProcessBuilder(toolPath, "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            LOGGER.log(Level.FINE, "Subliminal basic check failed", e);
            return false;
        }
    }
}

package org.antivirus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.manager.GlobalSettings;
import org.manager.tools.AbstractToolManager;

/** Discovers and validates one supported system antivirus scanner. */
public final class AntivirusToolManager extends AbstractToolManager {

    /** Supported scanners and their stable settings/tool identifiers. */
    public enum Scanner {
        CLAMAV("clamav", "ClamAV", "clamscan", "--version"),
        CHKROOTKIT("chkrootkit", "chkrootkit", "chkrootkit", "-V"),
        RKHUNTER("rkhunter", "rkhunter", "rkhunter", "--version");

        private final String key;
        private final String label;
        private final String executableName;
        private final String versionArgument;

        Scanner(String key, String label, String executableName,
                String versionArgument) {
            this.key = key;
            this.label = label;
            this.executableName = executableName;
            this.versionArgument = versionArgument;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public String executableName() {
            return executableName;
        }

        public String toolId() {
            return "antivirus-" + key;
        }
    }

    private static final Pattern VERSION = Pattern.compile(
            "(?i)(?:version[^0-9]*)?([0-9]+(?:\\.[0-9A-Za-z_-]+)+)");

    private final Scanner scanner;

    public AntivirusToolManager(Scanner scanner, GlobalSettings settings,
            ExecutorService executor) {
        super(settings, executor);
        this.scanner = java.util.Objects.requireNonNull(scanner, "scanner");
    }

    public Scanner getScanner() {
        return scanner;
    }

    @Override
    public String getToolId() {
        return scanner.toolId();
    }

    @Override
    public String getExecutableName() {
        return scanner.executableName();
    }

    @Override
    protected String getConfiguredPath() {
        return settings.getProperty("antivirus." + scanner.key() + "Path", "");
    }

    @Override
    protected void updateSettingsPath(String path) {
        settings.setProperty("antivirus." + scanner.key() + "Path",
                path == null ? "" : path);
    }

    @Override
    protected List<String> getCommonLocations() {
        String executable = scanner.executableName();
        return List.of(
                executable,
                "/usr/bin/" + executable,
                "/usr/local/bin/" + executable,
                "/usr/sbin/" + executable,
                "/usr/local/sbin/" + executable);
    }

    @Override
    protected String[] getVersionCommand(String toolPath) {
        return new String[]{toolPath, scanner.versionArgument};
    }

    @Override
    protected String parseVersion(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        Matcher matcher = VERSION.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Override
    protected Map<String, Boolean> detectFeatures() {
        return getToolPath() == null ? Map.of() : Map.of(
                "malware-scan", scanner == Scanner.CLAMAV,
                "rootkit-scan", scanner != Scanner.CLAMAV);
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
            process = new ProcessBuilder(getVersionCommand(toolPath))
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
            LOGGER.debug(scanner.label() + " validation failed", e);
            return false;
        }
    }
}

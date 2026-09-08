package org.odm.gtk4;

import java.net.URI;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.Switch;
import org.gnome.gtk.Widget;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadNetworkCapabilities;
import org.manager.download.DownloadSettings;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.ExternalToolSettings;
import org.manager.url.DownloadUrlPolicy;

/**
 * Applies the engine/protocol capability contract to a dialog's Network
 * Options controls. It also keeps proxy detail fields disabled until a
 * supported proxy type is selected.
 */
final class NetworkOptionControls {

    record Capabilities(Set<ExternalToolSettings.Capability> supported, int maxConnections) {
        static final Capabilities NONE = new Capabilities(Set.of(), Integer.MAX_VALUE);

        Capabilities {
            supported = Set.copyOf(supported);
        }
    }

    private static final String UNSUPPORTED =
            "Not supported by the selected download engine or protocol.";

    private final SpinButton connections;
    private final SpinButton downloadLimit;
    private final SpinButton uploadLimit;
    private final SpinButton retries;
    private final SpinButton retryDelay;
    private final Entry referer;
    private final Entry userAgent;
    private final Entry cookie;
    private final DropDown proxyType;
    private final Entry proxyHost;
    private final SpinButton proxyPort;
    private final Entry proxyUsername;
    private final Entry proxyPassword;
    private final Switch tor;
    private final Map<Widget, String> originalTooltips = new IdentityHashMap<>();

    private boolean plainProxySupported;
    private boolean socksProxySupported;
    private boolean torAvailable;
    private boolean adjustingProxyType;
    private boolean adjustingConnections;
    private int requestedConnections;

    NetworkOptionControls(SpinButton connections, SpinButton downloadLimit,
            SpinButton uploadLimit, SpinButton retries, SpinButton retryDelay,
            Entry referer, Entry userAgent, Entry cookie, DropDown proxyType,
            Entry proxyHost, SpinButton proxyPort, Entry proxyUsername,
            Entry proxyPassword, Switch tor) {
        this.connections = connections;
        requestedConnections = (int) connections.getValue();
        connections.onValueChanged(() -> {
            if (!adjustingConnections) {
                requestedConnections = (int) connections.getValue();
            }
        });
        this.downloadLimit = downloadLimit;
        this.uploadLimit = uploadLimit;
        this.retries = retries;
        this.retryDelay = retryDelay;
        this.referer = referer;
        this.userAgent = userAgent;
        this.cookie = cookie;
        this.proxyType = proxyType;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.proxyUsername = proxyUsername;
        this.proxyPassword = proxyPassword;
        this.tor = tor;

        for (Widget widget : List.of(connections, downloadLimit, uploadLimit,
                retries, retryDelay, referer, userAgent, cookie, proxyType,
                proxyHost, proxyPort, proxyUsername, proxyPassword, tor)) {
            originalTooltips.put(widget, widget.getTooltipText());
        }
        proxyType.onNotify("selected", ignored -> refreshProxySensitivity());
        tor.onStateSet(active -> {
            refreshProxySensitivity(active);
            return false;
        });
    }

    void applyCapabilities(Capabilities capabilities) {
        Set<ExternalToolSettings.Capability> supported = capabilities.supported();
        adjustingConnections = true;
        try {
            connections.setRange(1, Math.min(64, capabilities.maxConnections()));
            connections.setValue(requestedConnections);
        } finally {
            adjustingConnections = false;
        }
        setSupported(connections, supported.contains(
                ExternalToolSettings.Capability.CONNECTIONS));
        if (connections.getSensitive()) {
            connections.setTooltipText(capabilities.maxConnections() == Integer.MAX_VALUE
                    ? "Maximum simultaneous media fragments (1–64)."
                    : "Maximum simultaneous connections supported by this engine: "
                            + capabilities.maxConnections() + ".");
        }
        setSupported(downloadLimit, supported.contains(
                ExternalToolSettings.Capability.DOWNLOAD_LIMIT));
        setSupported(uploadLimit, supported.contains(
                ExternalToolSettings.Capability.UPLOAD_LIMIT));
        setSupported(retries, supported.contains(
                ExternalToolSettings.Capability.MAX_RETRIES));
        setSupported(retryDelay, supported.contains(
                ExternalToolSettings.Capability.RETRY_DELAY));
        setSupported(referer, supported.contains(
                ExternalToolSettings.Capability.REFERER));
        setSupported(userAgent, supported.contains(
                ExternalToolSettings.Capability.USER_AGENT));
        setSupported(cookie, supported.contains(
                ExternalToolSettings.Capability.COOKIE));

        plainProxySupported = supported.contains(ExternalToolSettings.Capability.PROXY);
        socksProxySupported = supported.contains(
                ExternalToolSettings.Capability.SOCKS_PROXY);
        boolean anyProxy = plainProxySupported || socksProxySupported;
        proxyType.setSensitive(anyProxy);
        proxyType.setTooltipText(proxyTooltip());
        refreshTorSensitivity();
        refreshProxySensitivity();
    }

    /** Keep the requested value when switching to an engine with a lower limit. */
    void setConnectionsValue(int value) {
        requestedConnections = Math.max(1, value);
        adjustingConnections = true;
        try {
            connections.setValue(requestedConnections);
        } finally {
            adjustingConnections = false;
        }
    }

    void bindTorService(Widget owner, org.tor.TorService service) {
        TorControlBinding.bind(owner, service, available -> {
            torAvailable = available;
            refreshTorSensitivity();
        });
    }

    private void refreshTorSensitivity() {
        tor.setSensitive(socksProxySupported && torAvailable);
        tor.setTooltipText(!socksProxySupported ? UNSUPPORTED
                : !torAvailable ? "Start Tor from Edit → Tor to change this option."
                : originalTooltips.get(tor));
    }

    boolean isSensitive(ExternalToolSettings.Capability capability) {
        return switch (capability) {
            case CONNECTIONS -> connections.getSensitive();
            case DOWNLOAD_LIMIT -> downloadLimit.getSensitive();
            case UPLOAD_LIMIT -> uploadLimit.getSensitive();
            case MAX_RETRIES -> retries.getSensitive();
            case RETRY_DELAY -> retryDelay.getSensitive();
            case REFERER -> referer.getSensitive();
            case USER_AGENT -> userAgent.getSensitive();
            case COOKIE -> cookie.getSensitive();
            case PROXY -> plainProxySupported;
            case SOCKS_PROXY -> socksProxySupported;
        };
    }

    private void setSupported(Widget widget, boolean supported) {
        widget.setSensitive(supported);
        widget.setTooltipText(supported ? originalTooltips.get(widget) : UNSUPPORTED);
    }

    private String proxyTooltip() {
        if (plainProxySupported && socksProxySupported) {
            return originalTooltips.get(proxyType);
        }
        if (plainProxySupported) {
            return "This engine supports HTTP/HTTPS proxies; SOCKS and Tor are unavailable.";
        }
        if (socksProxySupported) {
            return "This protocol supports SOCKS proxies; HTTP/HTTPS proxying is unavailable.";
        }
        return UNSUPPORTED;
    }

    private void refreshProxySensitivity() {
        refreshProxySensitivity(tor.getActive());
    }

    private void refreshProxySensitivity(boolean torActive) {
        long selected = proxyType.getSelected();
        boolean selectedSupported = selected == 0
                || selected <= 2 && plainProxySupported
                || selected >= 3 && selected <= 4 && socksProxySupported;
        if (!selectedSupported && !adjustingProxyType) {
            adjustingProxyType = true;
            try {
                proxyType.setSelected(0);
                selected = 0;
            } finally {
                adjustingProxyType = false;
            }
        }
        boolean fieldsEnabled = proxyType.getSensitive() && !torActive
                && selected > 0 && selectedSupported;
        for (Widget field : List.of(proxyHost, proxyPort, proxyUsername, proxyPassword)) {
            field.setSensitive(fieldsEnabled);
            field.setTooltipText(fieldsEnabled
                    ? originalTooltips.get(field)
                    : proxyType.getSensitive() ? "Select a supported proxy type to edit this field."
                            : UNSUPPORTED);
        }
    }

    static Capabilities capabilitiesFor(
            GlobalSettings globalSettings, Download.Type type, Download.Protocol protocol) {
        GlobalSettings global = globalSettings != null ? globalSettings : new GlobalSettings();
        DownloadSettings settings = new DownloadSettingsFactory(global)
                .createSettings(type, protocol);
        return new Capabilities(DownloadNetworkCapabilities.forSettings(settings, type, protocol),
                settings.maxConnectionsLimit());
    }

    /**
     * Returns the capabilities shared by every valid URL in a batch. A mixed
     * import therefore never offers a value that some selected record would
     * silently ignore.
     */
    static Capabilities commonCapabilities(
            GlobalSettings globalSettings, List<String> sources) {
        EnumSet<ExternalToolSettings.Capability> common =
                EnumSet.allOf(ExternalToolSettings.Capability.class);
        GlobalSettings global = globalSettings != null ? globalSettings : new GlobalSettings();
        DownloadSettingsFactory factory = new DownloadSettingsFactory(global);
        record Route(Download.Type type, Download.Protocol protocol) {
        }
        Map<Route, Capabilities> routeCapabilities = new HashMap<>();
        int connectionLimit = Integer.MAX_VALUE;
        boolean found = false;
        if (sources != null) {
            for (String source : sources) {
                try {
                    URI uri = DownloadUrlPolicy.require(source).uri();
                    Download.Type type = org.manager.download.MediaUrlDetector.isMediaUrl(uri)
                            ? Download.Type.YOUTUBE : Download.Type.ARIA2;
                    Route route = new Route(type, Download.Protocol.fromUri(uri));
                    Capabilities capabilities = routeCapabilities.computeIfAbsent(route, key -> {
                        DownloadSettings settings = factory.createSettings(
                                key.type(), key.protocol());
                        return new Capabilities(DownloadNetworkCapabilities.forSettings(settings,
                                key.type(), key.protocol()), settings.maxConnectionsLimit());
                    });
                    common.retainAll(capabilities.supported());
                    connectionLimit = Math.min(connectionLimit, capabilities.maxConnections());
                    found = true;
                    if (common.isEmpty()) {
                        break;
                    }
                } catch (RuntimeException invalidSource) {
                    // Invalid rows are skipped by import admission as well.
                }
            }
        }
        return found ? new Capabilities(common, connectionLimit) : Capabilities.NONE;
    }
}

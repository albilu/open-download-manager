package org.odm.gtk4;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.gnome.gtk.Box;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Switch;
import org.gnome.gtk.Widget;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettings;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.ExternalToolSettings;
import org.tor.TorService;

/** Reusable, GtkBuilder-backed editor for the per-record Network Options contract. */
final class NetworkOptionsPane {

    private final Box root;
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
    private final StringList plainProxyTypes;
    private final StringList allProxyTypes;
    private final NetworkOptionControls controls;
    private TorService torService;
    private final EnumSet<ExternalToolSettings.Capability> changedCapabilities =
            EnumSet.noneOf(ExternalToolSettings.Capability.class);

    private boolean proxyChanged;
    private boolean updating;
    private boolean mixedValues;
    private Runnable proxyChangeListener = () -> { };

    /** Creates a new-record editor initialized from the global Network defaults. */
    NetworkOptionsPane(GlobalSettings globalSettings, Download.Type type,
            Download.Protocol protocol) {
        this();
        GlobalSettings global = globalSettings != null ? globalSettings : new GlobalSettings();
        updateCapabilities(global, type, protocol);
        loadDefaults(global);
        clearChanges();
    }

    /** Creates an existing-record editor initialized from the first selected record. */
    NetworkOptionsPane(List<Download> downloads) {
        this(downloads, null);
    }

    /** Creates an existing-record editor using the managed service's current Tor port. */
    NetworkOptionsPane(List<Download> downloads, TorService torService) {
        this();
        if (downloads == null || downloads.isEmpty()) {
            throw new IllegalArgumentException("At least one download is required");
        }
        this.torService = torService;
        List<Download> records = List.copyOf(downloads);
        setCapabilities(PropertySettingsBatch.commonCapabilities(records));
        loadDownload(records.getFirst());
        mixedValues = describeMixedValues(records);
        clearChanges();
    }

    private NetworkOptionsPane() {
        GtkBuilder builder = UiLoader.load("/ui/network-options.ui");
        root = Widgets.require(builder, "network_options_root", Box.class);
        connections = Widgets.require(builder, "network_connections_spin",
                SpinButton.class);
        retries = Widgets.require(builder, "network_retry_limit_spin",
                SpinButton.class);
        retryDelay = Widgets.require(builder, "network_retry_delay_spin",
                SpinButton.class);
        downloadLimit = Widgets.require(builder, "network_download_limit_spin",
                SpinButton.class);
        uploadLimit = Widgets.require(builder, "network_upload_limit_spin",
                SpinButton.class);
        referer = Widgets.require(builder, "network_referer_entry", Entry.class);
        cookie = Widgets.require(builder, "network_cookie_entry", Entry.class);
        userAgent = Widgets.require(builder, "network_user_agent_entry", Entry.class);
        proxyType = Widgets.require(builder, "network_proxy_type_combo", DropDown.class);
        plainProxyTypes = Widgets.require(builder, "network_plain_proxy_types",
                StringList.class);
        allProxyTypes = Widgets.require(builder, "network_all_proxy_types",
                StringList.class);
        proxyType.setModel(allProxyTypes);
        proxyHost = Widgets.require(builder, "network_proxy_host_entry", Entry.class);
        proxyPort = Widgets.require(builder, "network_proxy_port_spin", SpinButton.class);
        proxyUsername = Widgets.require(builder, "network_proxy_username_entry", Entry.class);
        proxyPassword = Widgets.require(builder, "network_proxy_password_entry", Entry.class);
        tor = Widgets.require(builder, "network_tor_switch", Switch.class);

        AccessibilitySupport.label(connections, "Maximum connections");
        AccessibilitySupport.label(retries, "Retry limit");
        AccessibilitySupport.label(retryDelay, "Seconds before retry");
        AccessibilitySupport.label(downloadLimit,
                "Maximum download speed in KiB per second");
        AccessibilitySupport.label(uploadLimit,
                "Maximum upload speed in KiB per second");
        AccessibilitySupport.label(referer, "HTTP referer");
        AccessibilitySupport.label(cookie, "HTTP cookie header");
        AccessibilitySupport.label(userAgent, "HTTP user agent");
        AccessibilitySupport.label(proxyType, "Proxy type");
        AccessibilitySupport.label(proxyHost, "Proxy host");
        AccessibilitySupport.label(proxyPort, "Proxy port");
        AccessibilitySupport.label(proxyUsername, "Proxy username");
        AccessibilitySupport.label(proxyPassword, "Proxy password");
        AccessibilitySupport.label(tor, "Route this download through Tor");

        controls = new NetworkOptionControls(connections, downloadLimit, uploadLimit,
                retries, retryDelay, referer, userAgent, cookie, proxyType,
                proxyHost, proxyPort, proxyUsername, proxyPassword, tor);
        registerChangeTracking();
    }

    Box widget() {
        return root;
    }

    void grabFocus() {
        connections.grabFocus();
    }

    boolean isTorSelected() {
        // Service availability disables editing, not the saved route.
        return controls.isSensitive(ExternalToolSettings.Capability.SOCKS_PROXY)
                && tor.getActive();
    }

    void bindTorService(org.tor.TorService service) {
        torService = service;
        controls.bindTorService(root, service);
    }

    String selectedProxyAddress() {
        String address = DialogOptions.selectedProxyAddress(isTorSelected(),
                (int) proxyType.getSelected(), proxyHost.getText(),
                (int) proxyPort.getValue(), proxyUsername.getText(),
                proxyPassword.getText(), DialogOptions.torSocksPort(torService));
        if (!isTorSelected() && proxyType.getSelected() > 0 && address == null) {
            throw new IllegalArgumentException("Complete the selected proxy address");
        }
        return address;
    }

    void onProxyChanged(Runnable listener) {
        proxyChangeListener = Objects.requireNonNull(listener);
    }

    DialogOptions.NetworkValues values() {
        return new DialogOptions.NetworkValues(
                (int) connections.getValue(), (int) downloadLimit.getValue(),
                (int) uploadLimit.getValue(), (int) retries.getValue(),
                (int) retryDelay.getValue(), referer.getText(), userAgent.getText(),
                cookie.getText(), isTorSelected(), DialogOptions.torSocksPort(torService),
                (int) proxyType.getSelected(),
                proxyHost.getText(), (int) proxyPort.getValue(),
                proxyUsername.getText(), proxyPassword.getText());
    }

    void applyTo(Download download) {
        selectedProxyAddress(); // Reject incomplete selected routes before applying any settings.
        boolean inherited = !proxyChanged && download.getSettings().isProxyInherited();
        String inheritedAddress = download.getProxyAddress();
        values().applyTo(download);
        if (inherited && Objects.equals(inheritedAddress, download.getProxyAddress())) {
            download.getSettings().setProxyInherited(true);
        }
    }

    /** Re-evaluates enabled controls without replacing values already entered by the user. */
    void updateCapabilities(GlobalSettings globalSettings, Download.Type type,
            Download.Protocol protocol) {
        setCapabilities(NetworkOptionControls.capabilitiesFor(
                globalSettings, type, protocol));
    }

    boolean hasChanges() {
        return !changedCapabilities.isEmpty() || proxyChanged;
    }

    boolean hasMixedValues() {
        return mixedValues;
    }

    boolean isSensitive(ExternalToolSettings.Capability capability) {
        return controls.isSensitive(capability);
    }

    EnumSet<ExternalToolSettings.Capability> changedCapabilities() {
        return changedCapabilities.clone();
    }

    boolean isProxyChanged() {
        return proxyChanged;
    }

    void clearChanges() {
        changedCapabilities.clear();
        proxyChanged = false;
    }

    private void setCapabilities(Set<ExternalToolSettings.Capability> capabilities) {
        Set<ExternalToolSettings.Capability> supported = capabilities != null
                ? capabilities : Set.of();
        withoutTracking(() -> {
            int selected = (int) proxyType.getSelected();
            boolean socksSupported = supported.contains(
                    ExternalToolSettings.Capability.SOCKS_PROXY);
            proxyType.setModel(socksSupported ? allProxyTypes : plainProxyTypes);
            proxyType.setSelected(!socksSupported && selected > 2 ? 0 : selected);
            controls.applyCapabilities(supported);
        });
    }

    private void loadDefaults(GlobalSettings global) {
        DownloadSettingsFactory.NetworkDefaults defaults =
                DownloadSettingsFactory.NetworkDefaults.from(global);
        withoutTracking(() -> {
            connections.setValue(defaults.maxConnections());
            retries.setValue(defaults.maxRetries());
            retryDelay.setValue(defaults.retryDelaySeconds());
            downloadLimit.setValue(defaults.downloadLimitKb());
            uploadLimit.setValue(defaults.uploadLimitKb());
            referer.setText(defaults.referer());
            userAgent.setText(defaults.userAgent());
            cookie.setText(defaults.cookie());

            DialogOptions.ProxyFields manual = DialogOptions.parseProxy(
                    DialogOptions.manualProxyAddress(global));
            if (!controls.isSensitive(ExternalToolSettings.Capability.SOCKS_PROXY)
                    && manual.typeIndex() > 2) {
                manual = DialogOptions.ProxyFields.none();
            }
            setProxyFields(manual, false);
            tor.setActive(controls.isSensitive(ExternalToolSettings.Capability.SOCKS_PROXY)
                    && global.getBooleanProperty("tor.enabled", false));
        });
    }

    private void loadDownload(Download download) {
        withoutTracking(() -> {
            DownloadSettings settings = download.getSettings();
            connections.setValue(settings.getMaxConnections());
            downloadLimit.setValue(settings.getDownloadLimitKB());
            uploadLimit.setValue(settings.getUploadLimitKB());
            retries.setValue(settings.getMaxRetries());
            retryDelay.setValue(settings.getRetryDelaySeconds());
            referer.setText(normalized(settings.getReferer()));
            userAgent.setText(normalized(settings.getUserAgent()));
            cookie.setText(normalized(settings.getCookieHeader())
                    .replaceFirst("(?i)^Cookie:\\s*", ""));

            DialogOptions.ProxyFields proxy = download.isUseProxy()
                    ? DialogOptions.parseProxy(download.getProxyAddress())
                    : DialogOptions.ProxyFields.none();
            boolean torProxy = DialogOptions.isManagedTorProxy(
                    download.getProxyAddress(), DialogOptions.torSocksPort(torService));
            if (!controls.isSensitive(ExternalToolSettings.Capability.SOCKS_PROXY)
                    && proxy.typeIndex() > 2) {
                proxy = DialogOptions.ProxyFields.none();
                torProxy = false;
            }
            setProxyFields(proxy, torProxy);
            tor.setActive(torProxy);
        });
    }

    private void setProxyFields(DialogOptions.ProxyFields proxy, boolean torProxy) {
        proxyType.setSelected(torProxy ? 0 : proxy.typeIndex());
        proxyHost.setText(torProxy ? "" : proxy.host());
        proxyPort.setValue(torProxy ? 0 : proxy.port());
        proxyUsername.setText(torProxy ? "" : proxy.username());
        proxyPassword.setText(torProxy ? "" : proxy.password());
    }

    /** Adds mixed-value hints and reports whether the selected records differ. */
    boolean describeMixedValues(List<Download> downloads) {
        if (downloads.size() < 2) {
            return false;
        }
        boolean anyMixed = false;
        anyMixed |= markMixed(downloads, connections, "connection counts",
                d -> d.getSettings().getMaxConnections());
        anyMixed |= markMixed(downloads, downloadLimit, "download limits",
                d -> d.getSettings().getDownloadLimitKB());
        anyMixed |= markMixed(downloads, uploadLimit, "upload limits",
                d -> d.getSettings().getUploadLimitKB());
        anyMixed |= markMixed(downloads, retries, "retry limits",
                d -> d.getSettings().getMaxRetries());
        anyMixed |= markMixed(downloads, retryDelay, "retry delays",
                d -> d.getSettings().getRetryDelaySeconds());
        anyMixed |= markMixed(downloads, referer, "Referer values",
                d -> normalized(d.getSettings().getReferer()));
        anyMixed |= markMixed(downloads, userAgent, "User-Agent values",
                d -> normalized(d.getSettings().getUserAgent()));
        anyMixed |= markMixed(downloads, cookie, "cookie values",
                d -> normalized(d.getSettings().getCookieHeader()));
        anyMixed |= markMixed(downloads, proxyType, "proxy routes",
                d -> d.isUseProxy() ? normalized(d.getProxyAddress()) : "");
        return anyMixed;
    }

    private static boolean markMixed(List<Download> downloads, Widget widget,
            String description, Function<Download, Object> value) {
        Object first = value.apply(downloads.getFirst());
        boolean mixed = downloads.stream().skip(1)
                .map(value).anyMatch(other -> !Objects.equals(first, other));
        if (mixed && widget.getSensitive()) {
            widget.setTooltipText("Selected downloads have different " + description
                    + "; leave this unchanged to preserve each value.");
        }
        return mixed;
    }

    private void registerChangeTracking() {
        connections.onValueChanged(() -> mark(
                ExternalToolSettings.Capability.CONNECTIONS));
        downloadLimit.onValueChanged(() -> mark(
                ExternalToolSettings.Capability.DOWNLOAD_LIMIT));
        uploadLimit.onValueChanged(() -> mark(
                ExternalToolSettings.Capability.UPLOAD_LIMIT));
        retries.onValueChanged(() -> mark(
                ExternalToolSettings.Capability.MAX_RETRIES));
        retryDelay.onValueChanged(() -> mark(
                ExternalToolSettings.Capability.RETRY_DELAY));
        referer.onChanged(() -> mark(ExternalToolSettings.Capability.REFERER));
        userAgent.onChanged(() -> mark(ExternalToolSettings.Capability.USER_AGENT));
        cookie.onChanged(() -> mark(ExternalToolSettings.Capability.COOKIE));
        proxyType.onNotify("selected", ignored -> markProxyChanged());
        proxyHost.onChanged(this::markProxyChanged);
        proxyPort.onValueChanged(this::markProxyChanged);
        proxyUsername.onChanged(this::markProxyChanged);
        proxyPassword.onChanged(this::markProxyChanged);
        tor.onNotify("active", ignored -> markProxyChanged());
    }

    private void mark(ExternalToolSettings.Capability capability) {
        if (!updating) {
            changedCapabilities.add(capability);
        }
    }

    private void markProxyChanged() {
        if (!updating) {
            proxyChanged = true;
            proxyChangeListener.run();
        }
    }

    private void withoutTracking(Runnable update) {
        boolean previous = updating;
        updating = true;
        try {
            update.run();
        } finally {
            updating = previous;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value;
    }
}

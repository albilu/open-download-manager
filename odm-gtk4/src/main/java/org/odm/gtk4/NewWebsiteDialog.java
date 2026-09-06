package org.odm.gtk4;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Window;
import org.httrack.HttrackSettings;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadSettingsFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.url.DownloadUrlPolicy;

/** New Website Scrape dialog backed by the declarative {@code new-website.ui}. */
public final class NewWebsiteDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewWebsiteDialog.class);
    private static final String[] SCOPE_LABELS = {
        "Same directory", "Same host", "Same domain",
        "Include nearby external assets", "Custom external depth"
    };

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onDownloadQueued;
    private final org.tor.TorService torService;
    private final NetworkOptionsPane networkOptions;
    private final SpinnerActivity activity;
    private final AtomicBoolean closed = new AtomicBoolean();

    private final Entry urlEntry;
    private final SpinButton depthSpin;
    private final DropDown scopeDrop;
    private final SpinButton externalDepthSpin;
    private final Entry includeEntry;
    private final Entry excludeEntry;
    private final CheckButton includeArchivesCheck;
    private final Entry additionalHeadersEntry;
    private final PathChooserButton cookieFileChooser;
    private final Label statusLabel;
    private final Button startButton;

    private boolean submissionInFlight;

    public NewWebsiteDialog(Window parent, DownloadManager downloadManager,
            Runnable onDownloadQueued) {
        this(parent, downloadManager, onDownloadQueued, null);
    }

    public NewWebsiteDialog(Window parent, DownloadManager downloadManager,
            Runnable onDownloadQueued, org.tor.TorService torService) {
        this.downloadManager = downloadManager;
        this.onDownloadQueued = onDownloadQueued;
        this.torService = torService;

        GtkBuilder builder = UiLoader.load("/ui/new-website.ui");
        this.dialog = Widgets.require(builder, "new_website_dialog", Window.class);
        this.urlEntry = Widgets.require(builder, "website_url_entry", Entry.class);
        this.depthSpin = Widgets.require(builder, "website_depth_spin", SpinButton.class);
        this.scopeDrop = Widgets.require(builder, "website_scope_combo", DropDown.class);
        this.externalDepthSpin = Widgets.require(builder,
                "website_external_depth_spin", SpinButton.class);
        this.includeEntry = Widgets.require(builder, "website_include_entry", Entry.class);
        this.excludeEntry = Widgets.require(builder, "website_exclude_entry", Entry.class);
        this.includeArchivesCheck = Widgets.require(builder,
                "website_include_archives_check", CheckButton.class);
        this.additionalHeadersEntry = Widgets.require(builder,
                "website_headers_entry", Entry.class);
        this.statusLabel = Widgets.require(builder, "website_status_label", Label.class);
        this.startButton = Widgets.require(builder, "website_start_button", Button.class);
        this.activity = new SpinnerActivity(
                Widgets.require(builder, "new_website_spinner", Spinner.class));

        DialogSupport.configureIndependent(dialog, parent);

        HttrackSettings defaults = new DownloadSettingsFactory(
                downloadManager.getGlobalSettings()).createHttrackSettings();
        depthSpin.setValue(defaults.getDepth());
        externalDepthSpin.setValue(defaults.getExternalDepth());
        scopeDrop.setModel(stringModel(SCOPE_LABELS));
        scopeDrop.setSelected(defaults.getCrawlScope().ordinal());
        includeArchivesCheck.setActive(defaults.isIncludeArchives());

        Button cookieFileButton = Widgets.require(builder,
                "website_cookie_file_button", Button.class);
        Button clearCookieFileButton = Widgets.require(builder,
                "website_clear_cookie_button", Button.class);
        this.cookieFileChooser = PathChooserButton.forFile(cookieFileButton, dialog,
                "Select Netscape cookie file", null,
                ignored -> clearCookieFileButton.setSensitive(true));
        clearCookieFileButton.onClicked(() -> {
            cookieFileChooser.clear();
            clearCookieFileButton.setSensitive(false);
        });

        this.networkOptions = new NetworkOptionsPane(downloadManager.getGlobalSettings(),
                Download.Type.WEBSITE_SCRAPING, Download.Protocol.HTTPS);
        networkOptions.bindTorService(torService);
        Widgets.require(builder, "website_network_options_host", Box.class)
                .append(networkOptions.widget());

        AccessibilitySupport.label(urlEntry, "Website URL to mirror");
        AccessibilitySupport.label(depthSpin, "Website crawl depth");
        AccessibilitySupport.label(scopeDrop, "Website crawl scope");
        AccessibilitySupport.label(externalDepthSpin, "External website crawl depth");
        AccessibilitySupport.label(includeEntry, "Included website URL patterns");
        AccessibilitySupport.label(excludeEntry, "Excluded website URL patterns");
        AccessibilitySupport.label(includeArchivesCheck,
                "Include archive files in this website mirror");
        AccessibilitySupport.label(additionalHeadersEntry,
                "Additional HTTP headers for this website mirror");
        AccessibilitySupport.label(cookieFileButton,
                "Netscape cookie file for this website mirror");

        Runnable updateExternalDepth = () -> externalDepthSpin.setSensitive(
                scopeDrop.getSelected()
                        == HttrackSettings.CrawlScope.CUSTOM_EXTERNAL_DEPTH.ordinal());
        scopeDrop.onNotify("selected", ignored -> updateExternalDepth.run());
        updateExternalDepth.run();

        Widgets.require(builder, "website_cancel_button", Button.class)
                .onClicked(dialog::close);
        startButton.onClicked(this::onStart);
        urlEntry.onActivate(this::onStart);
        dialog.onCloseRequest(() -> {
            synchronized (closed) { closed.set(true); }
            activity.dispose();
            return false;
        });
    }

    public void present() {
        dialog.present();
        ClipboardUrlPrefill.populate(dialog, urlEntry, ClipboardUrlPrefill::isWebPage);
        urlEntry.grabFocus();
    }

    private void onStart() {
        if (closed.get() || submissionInFlight) { return; }
        String url = urlEntry.getText().trim();
        if (url.isEmpty()) {
            AccessibilitySupport.status(statusLabel, "Enter a URL");
            return;
        }
        try {
            java.net.URI source = DownloadUrlPolicy.require(url).requireWeb().uri();
            Download download = DownloadSubmission.draft(downloadManager, source,
                    defaultDestination(), Download.Type.WEBSITE_SCRAPING);
            if (!(download.getSettings() instanceof HttrackSettings settings)) {
                throw new IllegalStateException(
                        "Website download does not have HTTrack settings");
            }
            applyWebsiteScrapeOptions(settings, (int) depthSpin.getValue(),
                    selectedScope(), (int) externalDepthSpin.getValue(),
                    includeEntry.getText(), excludeEntry.getText(),
                    includeArchivesCheck.getActive(), additionalHeadersEntry.getText(),
                    cookieFileChooser.getPath());
            networkOptions.applyTo(download);

            Download submitted = download;
            submissionInFlight = true;
            startButton.setSensitive(false);
            AccessibilitySupport.status(statusLabel, "Adding website scrape to queue…");
            activity.track(DownloadSubmission.submit(downloadManager, submitted,
                    DialogOptions.ensureTorAvailable(networkOptions.isTorSelected(), torService), closed, null))
                    .whenComplete((ignored, error) -> UiThread.marshal(() -> {
                        if (closed.get()) {
                            return;
                        }
                        if (error == null) {
                            if (onDownloadQueued != null) {
                                onDownloadQueued.run();
                            }
                            dialog.close();
                        } else {
                            submissionInFlight = downloadManager.getDownload(submitted.getId()) != null;
                            startButton.setSensitive(!submissionInFlight);
                            AccessibilitySupport.status(statusLabel,
                                    "Could not add to queue: " + rootMessage(error)
                                            + (submissionInFlight ? ". This download remains in Downloads; manage it there."
                                                    : ". Press Start Scrape to retry."),
                                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                            LOGGER.warn("Queue rejected website scrape", error);
                        }
                    }));
        } catch (Exception e) {
            submissionInFlight = false;
            startButton.setSensitive(true);
            AccessibilitySupport.status(statusLabel,
                    "Invalid request: " + rootMessage(e),
                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
        }
    }

    private HttrackSettings.CrawlScope selectedScope() {
        int selected = scopeDrop.getSelected();
        HttrackSettings.CrawlScope[] scopes = HttrackSettings.CrawlScope.values();
        return selected >= 0 && selected < scopes.length
                ? scopes[selected] : HttrackSettings.CrawlScope.SAME_HOST;
    }

    private Path defaultDestination() {
        Path configured = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();
        return configured != null ? configured
                : org.manager.util.OdmPaths.downloadDirectory();
    }

    /** Applies the website-specific controls to this record's HTTrack settings. */
    static void applyWebsiteScrapeOptions(HttrackSettings settings,
            int depth, HttrackSettings.CrawlScope scope,
            int externalDepth, String includePatterns, String excludePatterns,
            boolean includeArchives, String additionalHeaders,
            Path cookieFile) {
        settings.setDepth(Math.max(1, depth));
        settings.setCrawlScope(scope);
        settings.setExternalDepth(externalDepth);
        settings.setIncludePatterns(splitHttrackPatterns(includePatterns));
        settings.setExcludePatterns(splitHttrackPatterns(excludePatterns));
        settings.setIncludeArchives(includeArchives);
        settings.setAdditionalHttpHeaders(splitHttrackHeaders(additionalHeaders));
        settings.setCookieFile(cookieFile);
    }

    private static List<String> splitHttrackPatterns(String patterns) {
        if (patterns == null || patterns.isBlank()) {
            return List.of();
        }
        return List.of(patterns.trim().split("\\s+"));
    }

    private static List<String> splitHttrackHeaders(String headers) {
        if (headers == null || headers.isBlank()) {
            return List.of();
        }
        return Arrays.stream(headers.split("[|\\r\\n]+"))
                .map(String::strip)
                .filter(header -> !header.isEmpty())
                .toList();
    }

    private static StringList stringModel(String[] labels) {
        StringList model = new StringList(new String[0]);
        for (String label : labels) {
            model.append(label);
        }
        return model;
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null ? message.split("\\R", 2)[0]
                : cause.getClass().getSimpleName();
    }
}

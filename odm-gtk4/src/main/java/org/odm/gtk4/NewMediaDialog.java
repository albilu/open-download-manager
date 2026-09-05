package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gnome.gtk.Button;
import org.gnome.gtk.Box;
import org.gnome.gtk.CellRendererToggle;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.StringList;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeView;
import org.gnome.gtk.Window;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadSettingsFactory;
import org.ytdlp.YtDlpClient;
import org.ytdlp.YtDlpSettings;

/**
 * New Media dialog: yt-dlp workflow with metadata discovery. Fetching info
 * runs a lightweight yt-dlp preview asynchronously and populates the format
 * or playlist list; the chosen format, audio-only, playlist, subtitle, cookie,
 * container, and SponsorBlock options are applied to the per-download
 * {@link YtDlpSettings}. All async results are marshalled back through
 * {@link UiThread}.
 */
public class NewMediaDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewMediaDialog.class);

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onDownloadQueued;
    private final org.tor.TorService torService;
    private final YtDlpClient ytDlpClient;
    private final YtDlpSettings defaultSettings;
    private final NetworkOptionsPane networkOptions;
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile java.util.concurrent.CompletableFuture<YtDlpClient.VideoInfo> metadataFuture;

    private final Entry urlEntry;
    private final Button fetchInfoButton;
    private final Label statusLabel;
    private final Label infoLabel;
    private final DropDown formatDrop;
    private final DropDown containerProfileDrop;
    private final CheckButton audioOnlyCheck;
    private final CheckButton playlistCheck;
    private final Entry playlistItemsEntry;
    private final CheckButton playlistSelectAllCheck;
    private final ListStore playlistStore;
    private final TreeView playlistTreeview;
    private final ScrolledWindow playlistPreviewScroller;
    private final CheckButton subtitlesCheck;
    private final Entry subtitleLangEntry;
    private final DropDown browserCookieDrop;
    private final Entry browserProfileEntry;
    private final DropDown sponsorBlockDrop;
    private final Entry sponsorBlockCategoriesEntry;
    private final PathChooserButton cookieFileChooser;
    private final PathChooserButton folderChooser;
    private final Label diskSpaceLabel;
    private final Button startButton;

    /** Formats shown in the dropdown, parallel to the StringList model. */
    private final List<YtDlpClient.VideoFormat> formats = new ArrayList<>();
    private final List<YtDlpClient.PlaylistEntry> playlistEntries = new ArrayList<>();
    private boolean updatingPlaylistControls;
    private String previewUrl;
    private Path destinationFolder;
    private Path cookieFile;
    private boolean submissionInFlight;
    private long metadataGeneration;

    public NewMediaDialog(Window parent, DownloadManager downloadManager, Runnable onDownloadQueued) {
        this(parent, downloadManager, onDownloadQueued, null);
    }

    public NewMediaDialog(Window parent, DownloadManager downloadManager,
            Runnable onDownloadQueued, org.tor.TorService torService) {
        this.downloadManager = downloadManager;
        this.onDownloadQueued = onDownloadQueued;
        this.torService = torService;
        org.manager.GlobalSettings globalSettings = downloadManager.getGlobalSettings();
        this.defaultSettings = (YtDlpSettings) new DownloadSettingsFactory(globalSettings)
                .createSettings(Download.Type.YOUTUBE);
        String ytDlpPath = globalSettings.getYtDlpPath();
        this.ytDlpClient = new YtDlpClient(
                ytDlpPath != null ? ytDlpPath : "yt-dlp",
                globalSettings.isHonorExternalYtDlpConfiguration(),
                globalSettings.isHonorExternalAria2Configuration());

        GtkBuilder builder = UiLoader.load("/ui/new-media.ui");
        this.dialog = Widgets.require(builder, "new_media_dialog", Window.class);
        this.urlEntry = Widgets.require(builder, "media_url_entry", Entry.class);
        this.fetchInfoButton = Widgets.require(builder, "fetch_info_button", Button.class);
        this.statusLabel = Widgets.require(builder, "media_status_label", Label.class);
        this.infoLabel = Widgets.require(builder, "media_info_label", Label.class);
        this.formatDrop = Widgets.require(builder, "format_drop", DropDown.class);
        this.containerProfileDrop = Widgets.require(builder,
                "media_container_profile_combo", DropDown.class);
        this.audioOnlyCheck = Widgets.require(builder, "audio_only_check", CheckButton.class);
        this.playlistCheck = Widgets.require(builder, "playlist_check", CheckButton.class);
        this.playlistItemsEntry = Widgets.require(builder, "playlist_items_entry", Entry.class);
        this.playlistSelectAllCheck = Widgets.require(builder,
                "playlist_select_all_check", CheckButton.class);
        this.playlistStore = Widgets.require(builder, "playlist_store", ListStore.class);
        this.playlistTreeview = Widgets.require(builder, "playlist_treeview", TreeView.class);
        this.playlistPreviewScroller = Widgets.require(builder,
                "playlist_preview_scroller", ScrolledWindow.class);
        this.subtitlesCheck = Widgets.require(builder, "subtitles_check", CheckButton.class);
        this.subtitleLangEntry = Widgets.require(builder, "subtitle_lang_entry", Entry.class);
        this.browserCookieDrop = Widgets.require(builder,
                "media_cookie_browser_combo", DropDown.class);
        this.browserProfileEntry = Widgets.require(builder,
                "media_cookie_browser_profile_entry", Entry.class);
        this.sponsorBlockDrop = Widgets.require(builder,
                "sponsorblock_mode_combo", DropDown.class);
        this.sponsorBlockCategoriesEntry = Widgets.require(builder,
                "sponsorblock_categories_entry", Entry.class);
        Button cookieFileButton = Widgets.require(builder, "cookie_file_chooser", Button.class);
        MenuButton folderButton = Widgets.require(builder, "media_folder_chooser", MenuButton.class);
        this.diskSpaceLabel = Widgets.require(builder, "media_disk_space_label", Label.class);
        this.startButton = Widgets.require(builder, "media_start_button", Button.class);
        this.networkOptions = new NetworkOptionsPane(globalSettings,
                Download.Type.YOUTUBE, Download.Protocol.HTTPS);
        Widgets.require(builder, "media_network_options_host", Box.class)
                .append(networkOptions.widget());

        AccessibilitySupport.label(urlEntry, "Media URL");
        AccessibilitySupport.label(formatDrop, "Media format");
        AccessibilitySupport.label(containerProfileDrop, "Media container profile");
        AccessibilitySupport.label(playlistItemsEntry,
                "Playlist item numbers or ranges");
        AccessibilitySupport.label(playlistTreeview, "Playlist preview items");
        AccessibilitySupport.label(subtitleLangEntry, "Subtitle languages");
        AccessibilitySupport.label(browserCookieDrop, "Browser cookie source");
        AccessibilitySupport.label(browserProfileEntry, "Optional browser profile");
        AccessibilitySupport.label(sponsorBlockDrop, "SponsorBlock action");
        AccessibilitySupport.label(sponsorBlockCategoriesEntry,
                "SponsorBlock segment categories");
        AccessibilitySupport.label(cookieFileButton, "Browser cookie file");
        AccessibilitySupport.label(folderButton, "Media destination folder");

        dialog.setTransientFor(parent);

        StringList placeholder = new StringList(new String[0]);
        placeholder.append("Automatic (fetch info to choose a format)");
        formatDrop.setModel(placeholder);
        containerProfileDrop.setModel(enumModel(
                java.util.Arrays.stream(YtDlpSettings.ContainerProfile.values())
                        .map(YtDlpSettings.ContainerProfile::displayName).toList()));
        containerProfileDrop.setSelected(YtDlpSettings.ContainerProfile.AUTOMATIC.ordinal());
        browserCookieDrop.setModel(enumModel(
                java.util.Arrays.stream(YtDlpSettings.BrowserCookieSource.values())
                        .map(YtDlpSettings.BrowserCookieSource::displayName).toList()));
        browserCookieDrop.setSelected(YtDlpSettings.BrowserCookieSource.NONE.ordinal());
        browserProfileEntry.setText("");
        subtitleLangEntry.setText("en");
        sponsorBlockDrop.setModel(enumModel(
                java.util.Arrays.stream(YtDlpSettings.SponsorBlockMode.values())
                        .map(YtDlpSettings.SponsorBlockMode::displayName).toList()));
        sponsorBlockDrop.setSelected(YtDlpSettings.SponsorBlockMode.OFF.ordinal());

        destinationFolder = Path.of(currentDefaultDirectory());
        this.cookieFileChooser = PathChooserButton.forFile(cookieFileButton, dialog,
                "Select cookies file", null, path -> { cookieFile = path; invalidateMetadata(); });
        this.folderChooser = PathChooserButton.forFolder(folderButton, dialog,
                "Select destination folder", destinationFolder,
                path -> {
                    destinationFolder = path;
                    updateDiskSpace(path);
                });
        updateDiskSpace(destinationFolder);

        // Enable Download once a URL is present; info fetch is optional
        urlEntry.onChanged(() -> {
            invalidateMetadata();
            if (previewUrl != null && !previewUrl.equals(urlEntry.getText().trim())) {
                clearPlaylistPreview();
            }
            refreshButtons();
        });
        networkOptions.onProxyChanged(this::invalidateMetadata);
        browserProfileEntry.onChanged(this::invalidateMetadata);
        browserCookieDrop.onNotify("selected", ignored -> invalidateMetadata());
        urlEntry.onActivate(this::onFetchInfo);
        fetchInfoButton.onClicked(this::onFetchInfo);
        subtitlesCheck.onToggled(() ->
                subtitleLangEntry.setSensitive(subtitlesCheck.getActive()));
        playlistCheck.onToggled(this::updatePlaylistSensitivity);
        playlistItemsEntry.onChanged(this::onPlaylistExpressionChanged);
        playlistSelectAllCheck.onToggled(this::onPlaylistSelectAllToggled);
        Widgets.require(builder, "playlist_selected_renderer", CellRendererToggle.class)
                .onToggled(this::onPlaylistItemToggled);
        browserCookieDrop.onNotify("selected", ignored -> updateBrowserProfileSensitivity());
        sponsorBlockDrop.onNotify("selected", ignored -> updateSponsorBlockSensitivity());
        updatePlaylistSensitivity();
        updateBrowserProfileSensitivity();
        updateSponsorBlockSensitivity();
        Widgets.require(builder, "media_cancel_button", Button.class).onClicked(dialog::close);
        startButton.onClicked(this::onStart);
        dialog.onCloseRequest(() -> {
            closeMetadataClient();
            return false;
        });
    }

    public void present() {
        dialog.present();
        ClipboardUrlPrefill.populate(dialog, urlEntry,
                org.manager.download.MediaUrlDetector::isMediaUrl);
        urlEntry.grabFocus();
    }

    /** Triggers asynchronous metadata/format discovery for the entered URL. */
    private void refreshButtons() {
        boolean available = !closed.get() && !submissionInFlight
                && (metadataFuture == null || metadataFuture.isDone());
        fetchInfoButton.setSensitive(available);
        startButton.setSensitive(available && !urlEntry.getText().isBlank());
    }

    private void invalidateMetadata() {
        metadataGeneration++;
        var previous = metadataFuture;
        metadataFuture = null;
        if (previous != null) { previous.cancel(true); }
        refreshButtons();
    }

    private void onFetchInfo() {
        if (closed.get() || submissionInFlight) { return; }
        String url = urlEntry.getText().trim();
        if (url.isBlank()) { return; }
        invalidateMetadata();
        final YtDlpSettings previewSettings;
        try {
            URI uri = org.manager.clipboard.UrlDetector.requireValidDownloadUrl(url);
            previewSettings = (YtDlpSettings) defaultSettings.copy();
            applyAuthenticationOptions(previewSettings);
            Download previewDownload = new Download(uri);
            previewDownload.setType(Download.Type.YOUTUBE);
            previewDownload.setSettings(previewSettings);
            networkOptions.applyTo(previewDownload);
        } catch (Exception failure) {
            AccessibilitySupport.status(statusLabel, "Could not fetch info: " + rootMessage(failure));
            refreshButtons();
            return;
        }
        long generation = metadataGeneration;
        AccessibilitySupport.status(statusLabel, "Fetching media info…");
        metadataFuture = DialogOptions.ensureTorAvailable(networkOptions.isTorSelected(), torService)
                .thenCompose(ignored -> ytDlpClient.previewMedia(url, previewSettings));
        refreshButtons();
        metadataFuture.whenComplete((info, failure) -> UiThread.marshal(() -> {
            if (closed.get() || generation != metadataGeneration) { return; }
            metadataFuture = null;
            refreshButtons();
            if (failure == null) {
                onInfoFetched(url, info);
            } else {
                AccessibilitySupport.status(statusLabel, "Could not fetch info: " + rootMessage(failure)
                        + " — you can still download with the default best format.",
                        org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
            }
        }));
    }

    private void onInfoFetched(String url, YtDlpClient.VideoInfo info) {
        previewUrl = url;
        refreshButtons();
        int playlistSize = info.getEntries() == null ? 0 : info.getEntries().size();
        AccessibilitySupport.status(statusLabel, playlistSize > 0
                ? "Playlist preview fetched — " + playlistSize + " item(s)"
                : "Formats fetched");

        infoLabel.setVisible(true);
        infoLabel.setLabel(String.format("%s — %s, %s",
                info.getTitle() != null ? info.getTitle() : "(untitled)",
                info.getUploader() != null ? info.getUploader() : "unknown uploader",
                formatDuration(info.getDuration())));

        formats.clear();
        StringList list = new StringList(new String[0]);
        list.append("Automatic (yt-dlp default)");
        if (info.getFormats() != null) {
            for (YtDlpClient.VideoFormat format : info.getFormats()) {
                formats.add(format);
                list.append(describeFormat(format));
            }
        }
        formatDrop.setModel(list);
        formatDrop.setSensitive(!formats.isEmpty());
        populatePlaylistPreview(info.getEntries());
    }

    private void onStart() {
        if (closed.get() || submissionInFlight) { return; }
        try {
            URI uri = org.manager.clipboard.UrlDetector.requireValidDownloadUrl(urlEntry.getText());
            Download download = DownloadSubmission.draft(downloadManager, uri, destinationFolder,
                    Download.Type.YOUTUBE);
            applyMediaOptions(download);
            networkOptions.applyTo(download);
            submissionInFlight = true;
            invalidateMetadata();
            AccessibilitySupport.status(statusLabel, "Adding media download to queue…");
            Download submitted = download;
            DownloadSubmission.submit(downloadManager, submitted,
                    DialogOptions.ensureTorAvailable(networkOptions.isTorSelected(), torService), closed, null)
                    .whenComplete((ignored, error) ->
                    UiThread.marshal(() -> {
                        if (closed.get()) {
                            return;
                        }
                        if (error == null) {
                            LOGGER.info("Queued media download: " + submitted.getName());
                            if (onDownloadQueued != null) {
                                onDownloadQueued.run();
                            }
                            dialog.close();
                        } else {
                            submissionInFlight = downloadManager.getDownload(submitted.getId()) != null;
                            refreshButtons();
                            AccessibilitySupport.status(statusLabel,
                                    "Could not add to queue: " + rootMessage(error)
                                            + (submissionInFlight ? ". This download remains in Downloads; manage it there."
                                                    : ". Press Download to retry."),
                                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                            LOGGER.warn("Queue rejected media download", error);
                        }
                    }));
        } catch (Exception e) {
            LOGGER.warn("Media download rejected: " + e.getMessage(), e);
            submissionInFlight = false;
            refreshButtons();
            AccessibilitySupport.status(statusLabel, "Cannot start: " + e.getMessage(),
                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
        }
    }

    /** Applies the dialog choices onto the per-download yt-dlp settings. */
    private void applyMediaOptions(Download download) {
        if (!(download.getSettings() instanceof YtDlpSettings settings)) {
            return;
        }
        int selected = (int) formatDrop.getSelected();
        settings.setFormat(formatDrop.getSensitive()
                ? selectedFormatId(selected, formats) : "");
        boolean audioOnly = audioOnlyCheck.getActive();
        settings.setExtractAudio(audioOnly);
        if (audioOnly) {
            settings.setAudioFormat("best");
        }
        settings.setContainerProfile(selectedEnum(containerProfileDrop,
                YtDlpSettings.ContainerProfile.values(),
                YtDlpSettings.ContainerProfile.AUTOMATIC));
        // Playlist unchecked (default) asks yt-dlp to download only the
        // single video even when the URL points at a playlist
        settings.setNoPlaylist(!playlistCheck.getActive());
        settings.setPlaylistItemSpec(playlistCheck.getActive()
                ? selectedPlaylistExpression() : null);
        boolean downloadSubtitles = subtitlesCheck.getActive();
        List<String> langs = new ArrayList<>();
        for (String lang : subtitleLangEntry.getText().split("[,\\s]+")) {
            if (!lang.isBlank()) {
                langs.add(lang.trim());
            }
        }
        if (downloadSubtitles && langs.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one subtitle language");
        }
        settings.setWriteSubtitles(downloadSubtitles);
        settings.setEmbedSubs(downloadSubtitles);
        settings.setSubtitleLanguages(langs.isEmpty() ? List.of("en") : langs);
        applyAuthenticationOptions(settings);
        YtDlpSettings.SponsorBlockMode sponsorBlockMode = selectedEnum(sponsorBlockDrop,
                YtDlpSettings.SponsorBlockMode.values(),
                YtDlpSettings.SponsorBlockMode.OFF);
        settings.setSponsorBlockMode(sponsorBlockMode);
        if (sponsorBlockMode != YtDlpSettings.SponsorBlockMode.OFF) {
            settings.setSponsorBlockCategories(sponsorBlockCategoriesEntry.getText());
        }
    }

    private void applyAuthenticationOptions(YtDlpSettings settings) {
        settings.setBrowserCookieSource(selectedEnum(browserCookieDrop,
                YtDlpSettings.BrowserCookieSource.values(),
                YtDlpSettings.BrowserCookieSource.NONE));
        settings.setBrowserCookieProfile(browserProfileEntry.getText());
        settings.setCookieFile(cookieFile == null ? null : cookieFile.toString());
    }

    private void populatePlaylistPreview(List<YtDlpClient.PlaylistEntry> entries) {
        playlistStore.clear();
        playlistEntries.clear();
        if (entries != null) {
            for (YtDlpClient.PlaylistEntry entry : entries) {
                playlistEntries.add(entry);
                TreeIter iter = new TreeIter();
                playlistStore.append(iter);
                ListStoreCells.setBoolean(playlistStore, iter, 0, true);
                ListStoreCells.setInt(playlistStore, iter, 1, entry.getIndex());
                ListStoreCells.setString(playlistStore, iter, 2,
                        entry.getTitle() == null || entry.getTitle().isBlank()
                                ? "(unavailable item)" : entry.getTitle());
                ListStoreCells.setString(playlistStore, iter, 3,
                        entry.getDuration() > 0 ? formatDuration(entry.getDuration()) : "—");
            }
        }
        boolean available = !playlistEntries.isEmpty();
        playlistPreviewScroller.setVisible(available);
        playlistSelectAllCheck.setVisible(available);
        updatingPlaylistControls = true;
        try {
            playlistCheck.setActive(available);
            playlistItemsEntry.setText("");
            playlistSelectAllCheck.setActive(true);
            playlistSelectAllCheck.setInconsistent(false);
        } finally {
            updatingPlaylistControls = false;
        }
        updatePlaylistSensitivity();
    }

    private void clearPlaylistPreview() {
        previewUrl = null;
        formats.clear();
        StringList placeholder = new StringList(new String[0]);
        placeholder.append("Automatic (fetch info to choose a format)");
        formatDrop.setModel(placeholder);
        formatDrop.setSensitive(false);
        infoLabel.setVisible(false);
        playlistStore.clear();
        playlistEntries.clear();
        playlistPreviewScroller.setVisible(false);
        playlistSelectAllCheck.setVisible(false);
        updatingPlaylistControls = true;
        try {
            playlistCheck.setActive(false);
            playlistItemsEntry.setText("");
        } finally {
            updatingPlaylistControls = false;
        }
        updatePlaylistSensitivity();
    }

    private void updatePlaylistSensitivity() {
        boolean enabled = playlistCheck.getActive();
        playlistItemsEntry.setSensitive(enabled);
        playlistTreeview.setSensitive(enabled);
        playlistSelectAllCheck.setSensitive(enabled && !playlistEntries.isEmpty());
    }

    private void onPlaylistItemToggled(String path) {
        if (!playlistCheck.getActive()) {
            return;
        }
        TreeIter iter = new TreeIter();
        if (playlistStore.getIter(iter, TreePath.fromString(path))) {
            ListStoreCells.setBoolean(playlistStore, iter, 0,
                    !ListStoreCells.getBoolean(playlistStore, iter, 0));
            updatePlaylistExpressionFromRows();
        }
    }

    private void onPlaylistSelectAllToggled() {
        if (updatingPlaylistControls || playlistEntries.isEmpty()) {
            return;
        }
        boolean selected = playlistSelectAllCheck.getActive();
        TreeIter iter = new TreeIter();
        if (playlistStore.getIterFirst(iter)) {
            do {
                ListStoreCells.setBoolean(playlistStore, iter, 0, selected);
            } while (playlistStore.iterNext(iter));
        }
        updatePlaylistExpressionFromRows();
    }

    private void onPlaylistExpressionChanged() {
        if (updatingPlaylistControls || playlistEntries.isEmpty()) {
            return;
        }
        final String expression;
        try {
            expression = YtDlpSettings.normalizePlaylistItemSpec(
                    playlistItemsEntry.getText());
        } catch (IllegalArgumentException incompleteExpression) {
            // Users pass through incomplete forms such as "1," while typing.
            // Validation is reported when Download is pressed.
            return;
        }
        TreeIter iter = new TreeIter();
        if (playlistStore.getIterFirst(iter)) {
            int total = playlistEntries.size();
            do {
                int index = ListStoreCells.getInt(playlistStore, iter, 1);
                ListStoreCells.setBoolean(playlistStore, iter, 0,
                        expression.isEmpty()
                                || playlistExpressionContains(expression, index, total));
            } while (playlistStore.iterNext(iter));
        }
        updatePlaylistSelectAllState();
    }

    private void updatePlaylistExpressionFromRows() {
        List<Integer> selected = selectedPlaylistIndexes();
        updatingPlaylistControls = true;
        try {
            playlistItemsEntry.setText(selected.size() == playlistEntries.size()
                    ? "" : selected.stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(",")));
        } finally {
            updatingPlaylistControls = false;
        }
        updatePlaylistSelectAllState();
    }

    private void updatePlaylistSelectAllState() {
        int selected = selectedPlaylistIndexes().size();
        int total = playlistEntries.size();
        updatingPlaylistControls = true;
        try {
            playlistSelectAllCheck.setActive(total > 0 && selected == total);
            playlistSelectAllCheck.setInconsistent(selected > 0 && selected < total);
        } finally {
            updatingPlaylistControls = false;
        }
    }

    private List<Integer> selectedPlaylistIndexes() {
        List<Integer> selected = new ArrayList<>();
        TreeIter iter = new TreeIter();
        if (playlistStore.getIterFirst(iter)) {
            do {
                if (ListStoreCells.getBoolean(playlistStore, iter, 0)) {
                    selected.add(ListStoreCells.getInt(playlistStore, iter, 1));
                }
            } while (playlistStore.iterNext(iter));
        }
        return selected;
    }

    private String selectedPlaylistExpression() {
        String expression = YtDlpSettings.normalizePlaylistItemSpec(
                playlistItemsEntry.getText());
        if (playlistEntries.isEmpty()) {
            return expression.isEmpty() ? null : expression;
        }
        List<Integer> selected = selectedPlaylistIndexes();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("Select at least one playlist item");
        }
        if (selected.size() == playlistEntries.size()) {
            return null;
        }
        return selected.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    static boolean playlistExpressionContains(String expression, int index, int total) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        for (String part : expression.split(",")) {
            if (!part.contains(":")) {
                if (resolvePlaylistIndex(Integer.parseInt(part), total) == index) {
                    return true;
                }
                continue;
            }
            String[] range = part.split(":", -1);
            int step = range.length == 3 ? Integer.parseInt(range[2]) : 1;
            int start = range[0].isEmpty()
                    ? (step > 0 ? 1 : total)
                    : resolvePlaylistIndex(Integer.parseInt(range[0]), total);
            int end = range[1].isEmpty()
                    ? (step > 0 ? total : 1)
                    : resolvePlaylistIndex(Integer.parseInt(range[1]), total);
            boolean inRange = step > 0
                    ? index >= start && index <= end
                    : index <= start && index >= end;
            if (inRange && Math.floorMod(index - start, Math.abs(step)) == 0) {
                return true;
            }
        }
        return false;
    }

    private static int resolvePlaylistIndex(int index, int total) {
        return index < 0 ? total + index + 1 : index;
    }

    private void updateBrowserProfileSensitivity() {
        browserProfileEntry.setSensitive(selectedEnum(browserCookieDrop,
                YtDlpSettings.BrowserCookieSource.values(),
                YtDlpSettings.BrowserCookieSource.NONE)
                != YtDlpSettings.BrowserCookieSource.NONE);
    }

    private void updateSponsorBlockSensitivity() {
        sponsorBlockCategoriesEntry.setSensitive(selectedEnum(sponsorBlockDrop,
                YtDlpSettings.SponsorBlockMode.values(),
                YtDlpSettings.SponsorBlockMode.OFF) != YtDlpSettings.SponsorBlockMode.OFF);
    }

    private static StringList enumModel(List<String> labels) {
        StringList model = new StringList(new String[0]);
        labels.forEach(model::append);
        return model;
    }

    private static <E extends Enum<E>> E selectedEnum(DropDown dropDown,
            E[] values, E fallback) {
        long selected = dropDown.getSelected();
        return selected >= 0 && selected < values.length
                ? values[(int) selected] : fallback;
    }

    private void closeMetadataClient() {
        synchronized (closed) {
            if (!closed.compareAndSet(false, true)) { return; }
        }
        java.util.concurrent.CompletableFuture<YtDlpClient.VideoInfo> pending = metadataFuture;
        if (pending != null) {
            pending.cancel(true);
        }
        java.util.concurrent.CompletableFuture.runAsync(ytDlpClient::shutdown);
    }

    private static String describeFormat(YtDlpClient.VideoFormat format) {
        String resolution = format.getResolution() != null && !format.getResolution().isBlank()
                ? format.getResolution()
                : (format.getVcodec() != null && !format.getVcodec().equals("none") ? "video" : "audio");
        String size = format.getFilesize() > 0
                ? " — " + format.getFilesize() / (1024 * 1024) + " MB"
                : "";
        return format.getFormatId() + " · " + format.getExt() + " · " + resolution
                + (format.getFps() > 0 ? " " + format.getFps() + "fps" : "") + size;
    }

    static String selectedFormatId(int selected,
            List<YtDlpClient.VideoFormat> availableFormats) {
        return selected > 0 && selected <= availableFormats.size()
                ? availableFormats.get(selected - 1).getFormatId() : "";
    }

    private static String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "unknown length";
        }
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? String.format("%dh %02dm %02ds", h, m, s)
                : String.format("%dm %02ds", m, s);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null ? message.split("\n")[0] : cause.getClass().getSimpleName();
    }

    private String currentDefaultDirectory() {
        Path dir = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();
        return dir != null ? dir.toString()
                : org.manager.util.OdmPaths.downloadDirectory().toString();
    }

    private void updateDiskSpace(Path directory) {
        try {
            long free = directory.toFile().getUsableSpace();
            diskSpaceLabel.setLabel(String.format(java.util.Locale.ROOT,
                    "%.2f GB free", free / (1024.0 * 1024 * 1024)));
        } catch (Exception e) {
            diskSpaceLabel.setLabel("");
        }
    }
}

package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gnome.gio.File;
import org.gnome.gtk.Button;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.Entry;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Window;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.ytdlp.YtDlpClient;
import org.ytdlp.YtDlpSettings;

/**
 * New Media dialog: yt-dlp workflow with metadata discovery. Fetching info
 * runs {@code yt-dlp --dump-json} asynchronously and populates the format
 * list; the chosen format, audio-only, playlist, subtitle, and cookie
 * options are applied to the per-download {@link YtDlpSettings}. All async
 * results are marshalled back through {@link UiThread}.
 */
public class NewMediaDialog {

    private static final Logger LOGGER = Logger.getLogger(NewMediaDialog.class.getName());

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onDownloadQueued;
    private final YtDlpClient ytDlpClient;
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile java.util.concurrent.CompletableFuture<YtDlpClient.VideoInfo> metadataFuture;

    private final Entry urlEntry;
    private final Button fetchInfoButton;
    private final Label statusLabel;
    private final Label infoLabel;
    private final DropDown formatDrop;
    private final CheckButton audioOnlyCheck;
    private final CheckButton playlistCheck;
    private final CheckButton subtitlesCheck;
    private final Entry subtitleLangEntry;
    private final Button cookieFileChooser;
    private final Button folderChooser;
    private final Button startButton;

    /** Formats shown in the dropdown, parallel to the StringList model. */
    private final List<YtDlpClient.VideoFormat> formats = new ArrayList<>();
    private Path destinationFolder;
    private Path cookieFile;
    /** Reused after queue rejection so Retry cannot create duplicate rows. */
    private Download pendingDownload;

    public NewMediaDialog(Window parent, DownloadManager downloadManager, Runnable onDownloadQueued) {
        this.downloadManager = downloadManager;
        this.onDownloadQueued = onDownloadQueued;
        String ytDlpPath = downloadManager.getGlobalSettings().getYtDlpPath();
        this.ytDlpClient = new YtDlpClient(ytDlpPath != null ? ytDlpPath : "yt-dlp");

        GtkBuilder builder = UiLoader.load("/ui/new-media.ui");
        this.dialog = Widgets.require(builder, "new_media_dialog", Window.class);
        this.urlEntry = Widgets.require(builder, "media_url_entry", Entry.class);
        this.fetchInfoButton = Widgets.require(builder, "fetch_info_button", Button.class);
        this.statusLabel = Widgets.require(builder, "media_status_label", Label.class);
        this.infoLabel = Widgets.require(builder, "media_info_label", Label.class);
        this.formatDrop = Widgets.require(builder, "format_drop", DropDown.class);
        this.audioOnlyCheck = Widgets.require(builder, "audio_only_check", CheckButton.class);
        this.playlistCheck = Widgets.require(builder, "playlist_check", CheckButton.class);
        this.subtitlesCheck = Widgets.require(builder, "subtitles_check", CheckButton.class);
        this.subtitleLangEntry = Widgets.require(builder, "subtitle_lang_entry", Entry.class);
        this.cookieFileChooser = Widgets.require(builder, "cookie_file_chooser", Button.class);
        this.folderChooser = Widgets.require(builder, "media_folder_chooser", Button.class);
        this.startButton = Widgets.require(builder, "media_start_button", Button.class);

        AccessibilitySupport.label(urlEntry, "Media URL");
        AccessibilitySupport.label(formatDrop, "Media format");
        AccessibilitySupport.label(subtitleLangEntry, "Subtitle languages");
        AccessibilitySupport.label(cookieFileChooser, "Browser cookie file");
        AccessibilitySupport.label(folderChooser, "Media destination folder");

        dialog.setTransientFor(parent);

        StringList placeholder = new StringList(new String[0]);
        placeholder.append("Fetch info to list formats");
        formatDrop.setModel(placeholder);

        String defaultDir = currentDefaultDirectory();
        folderChooser.setLabel(defaultDir);
        destinationFolder = Path.of(defaultDir);

        // Enable Download once a URL is present; info fetch is optional
        urlEntry.onChanged(() -> startButton.setSensitive(!urlEntry.getText().isBlank()));
        urlEntry.onActivate(this::onFetchInfo);
        fetchInfoButton.onClicked(this::onFetchInfo);
        subtitlesCheck.onToggled(() ->
                subtitleLangEntry.setSensitive(subtitlesCheck.getActive()));
        cookieFileChooser.onClicked(this::onChooseCookieFile);
        folderChooser.onClicked(this::onChooseFolder);
        Widgets.require(builder, "media_cancel_button", Button.class).onClicked(dialog::close);
        startButton.onClicked(this::onStart);
        dialog.onCloseRequest(() -> {
            closeMetadataClient();
            return false;
        });
    }

    public void present() {
        dialog.present();
        urlEntry.grabFocus();
    }

    /** Triggers asynchronous metadata/format discovery for the entered URL. */
    private void onFetchInfo() {
        String url = urlEntry.getText().trim();
        if (url.isBlank()) {
            return;
        }
        fetchInfoButton.setSensitive(false);
        startButton.setSensitive(false);
        AccessibilitySupport.status(statusLabel, "Fetching media info…");

        java.util.concurrent.CompletableFuture<YtDlpClient.VideoInfo> previous = metadataFuture;
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }
        String proxy = downloadManager.getGlobalSettings().isGlobalProxyEnabled()
                ? downloadManager.getGlobalSettings().getGlobalProxyAddress() : null;
        metadataFuture = ytDlpClient.extractInfo(url, proxy);
        metadataFuture.thenAccept(info -> UiThread.marshal(() -> {
                    if (!closed.get() && url.equals(urlEntry.getText().trim())) {
                        onInfoFetched(url, info);
                    }
                }))
                .exceptionally(e -> {
                    UiThread.marshal(() -> {
                        if (closed.get()) {
                            return;
                        }
                        AccessibilitySupport.status(statusLabel, "Could not fetch info: "
                                + rootMessage(e)
                                + " — you can still download with the default best format.",
                                org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                        fetchInfoButton.setSensitive(true);
                        startButton.setSensitive(true);
                    });
                    return null;
                });
    }

    private void onInfoFetched(String url, YtDlpClient.VideoInfo info) {
        fetchInfoButton.setSensitive(true);
        startButton.setSensitive(true);
        AccessibilitySupport.status(statusLabel, "Formats fetched");

        infoLabel.setVisible(true);
        infoLabel.setLabel(String.format("%s — %s, %s",
                info.getTitle() != null ? info.getTitle() : "(untitled)",
                info.getUploader() != null ? info.getUploader() : "unknown uploader",
                formatDuration(info.getDuration())));

        formats.clear();
        StringList list = new StringList(new String[0]);
        if (info.getFormats() != null) {
            for (YtDlpClient.VideoFormat format : info.getFormats()) {
                formats.add(format);
                list.append(describeFormat(format));
            }
        }
        if (formats.isEmpty()) {
            list.append("best (default)");
        }
        formatDrop.setModel(list);
        formatDrop.setSensitive(!formats.isEmpty());
    }

    private void onChooseCookieFile() {
        FileDialog fileDialog = new FileDialog();
        fileDialog.setTitle("Select cookies file");
        fileDialog.open(dialog, null, result -> {
            try {
                File file = fileDialog.openFinish(result);
                if (file != null && file.getPath() != null) {
                    cookieFile = Path.of(file.getPath().toString());
                    cookieFileChooser.setLabel(cookieFile.getFileName().toString());
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Cookie file selection cancelled or failed", e);
            }
        });
    }

    private void onChooseFolder() {
        FileDialog fileDialog = new FileDialog();
        fileDialog.setTitle("Select destination folder");
        fileDialog.selectFolder(dialog, null, result -> {
            try {
                File folder = fileDialog.selectFolderFinish(result);
                if (folder != null && folder.getPath() != null) {
                    destinationFolder = Path.of(folder.getPath().toString());
                    folderChooser.setLabel(destinationFolder.toString());
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Folder selection cancelled or failed", e);
            }
        });
    }

    private void onStart() {
        try {
            Download download = pendingDownload;
            if (download == null) {
                URI uri = org.manager.clipboard.UrlDetector.requireValidDownloadUrl(
                        urlEntry.getText());
                download = downloadManager.createYoutubeDownload(uri, destinationFolder, null);
                applyMediaOptions(download);
            }
            pendingDownload = download;
            startButton.setSensitive(false);
            AccessibilitySupport.status(statusLabel, "Adding media download to queue…");
            Download submitted = download;
            downloadManager.queueDownload(download).whenComplete((ignored, error) ->
                    UiThread.marshal(() -> {
                        if (closed.get()) {
                            return;
                        }
                        if (error == null) {
                            pendingDownload = null;
                            LOGGER.info("Queued media download: " + submitted.getName());
                            if (onDownloadQueued != null) {
                                onDownloadQueued.run();
                            }
                            dialog.close();
                        } else {
                            startButton.setSensitive(true);
                            AccessibilitySupport.status(statusLabel,
                                    "Could not add to queue: " + rootMessage(error)
                                            + ". Press Download to retry.",
                                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                            LOGGER.log(Level.WARNING, "Queue rejected media download", error);
                        }
                    }));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Media download rejected: " + e.getMessage(), e);
            startButton.setSensitive(true);
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
        if (formatDrop.getSensitive() && selected >= 0 && selected < formats.size()) {
            settings.setFormat(formats.get(selected).getFormatId());
        }
        if (audioOnlyCheck.getActive()) {
            settings.setExtractAudio(true);
            settings.setAudioFormat("best");
        }
        // Playlist unchecked (default) asks yt-dlp to download only the
        // single video even when the URL points at a playlist
        settings.setNoPlaylist(!playlistCheck.getActive());
        if (subtitlesCheck.getActive() && !subtitleLangEntry.getText().isBlank()) {
            settings.setWriteSubtitles(true);
            settings.setEmbedSubs(true);
            List<String> langs = new ArrayList<>();
            for (String lang : subtitleLangEntry.getText().split("[,\\s]+")) {
                if (!lang.isBlank()) {
                    langs.add(lang.trim());
                }
            }
            settings.setSubtitleLanguages(langs);
        }
        if (cookieFile != null) {
            settings.setCookieFile(cookieFile.toString());
        }
    }

    private void closeMetadataClient() {
        if (!closed.compareAndSet(false, true)) {
            return;
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
        return dir != null ? dir.toString() : System.getProperty("user.home") + "/Downloads";
    }
}

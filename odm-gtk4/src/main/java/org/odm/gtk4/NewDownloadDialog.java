package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CellRendererCombo;
import org.gnome.gtk.CellRendererToggle;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.Entry;
import org.gnome.gtk.Grid;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Notebook;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreeRowReference;
import org.gnome.gtk.TreeStore;
import org.gnome.gtk.TreeView;
import org.gnome.gtk.Window;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.url.DownloadUrlPolicy;

/**
 * New Download dialog with Download, Files, and per-download engine/network
 * option tabs. This explicit user action queues immediately; background
 * automatic-start and descriptor-trash policies stay in Settings.
 */
public class NewDownloadDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewDownloadDialog.class);
    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onDownloadQueued;
    private final org.tor.TorService torService;

    private final Entry urlEntry;
    private final PathChooserButton torrentFileChooser;
    private final PathChooserButton saveFolderChooser;
    private final Label diskSpaceLabel;
    private final Entry filenameEntry;
    private final TreeStore filesListstore;
    private final TreeView filesTreeview;
    private final Label filesStatusLabel;
    private final CheckButton selectAllFilesCheck;
    private final Notebook optionsNotebook;
    private final java.util.Map<String, TreeRowReference> fileRows = new java.util.HashMap<>();
    private final Grid sftpHostKeyGrid;
    private final Entry sftpHostKeyEntry;
    private final NetworkOptionsPane networkOptions;
    private final Label checksumLabel;
    private final CheckButton verifyChecksumCheck;
    private final Button startButton;
    private final SpinnerActivity activity;
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    private Path selectedTorrentFile;
    private Path destinationFolder;
    private boolean updatingFilenameSuggestion;
    private boolean filenameEditedByUser;
    private boolean updatingSelectAll;
    private boolean previewLoading;
    private boolean submissionInFlight;
    private long previewEpoch;
    private URI loadedPreviewSource;

    /** Checksum detected for the current URL (null while unknown/not probed). */
    private volatile org.manager.download.ChecksumProbe.DetectedChecksum detectedChecksum;
    /** URL whose sibling checksum files were probed (negative results cached too). */
    private volatile java.net.URI lastProbedUrl;
    private volatile long checksumProbeGeneration;

    public NewDownloadDialog(Window parent, DownloadManager downloadManager, Runnable onDownloadQueued) {
        this(parent, downloadManager, onDownloadQueued, null);
    }

    public NewDownloadDialog(Window parent, DownloadManager downloadManager,
            Runnable onDownloadQueued, org.tor.TorService torService) {
        this.downloadManager = downloadManager;
        this.onDownloadQueued = onDownloadQueued;
        this.torService = torService;

        GtkBuilder builder = UiLoader.load("/ui/new-download.ui");
        this.dialog = Widgets.require(builder, "new_download_dialog", Window.class);
        this.urlEntry = Widgets.require(builder, "url_entry", Entry.class);
        Button torrentFileButton = Widgets.require(builder, "torrent_file_chooser", Button.class);
        MenuButton saveFolderButton = Widgets.require(builder, "save_folder_chooser", MenuButton.class);
        this.diskSpaceLabel = Widgets.require(builder, "disk_space_label", Label.class);
        this.filenameEntry = Widgets.require(builder, "filename_entry", Entry.class);
        this.filesListstore = Widgets.require(builder, "files_liststore", TreeStore.class);
        this.filesTreeview = Widgets.require(builder, "files_treeview", TreeView.class);
        this.filesStatusLabel = Widgets.require(builder, "files_status_label", Label.class);
        this.selectAllFilesCheck = Widgets.require(builder,
                "select_all_files_check", CheckButton.class);
        this.optionsNotebook = Widgets.require(builder, "options_notebook", Notebook.class);
        this.sftpHostKeyGrid = Widgets.require(builder, "sftp_host_key_grid", Grid.class);
        this.sftpHostKeyEntry = Widgets.require(builder, "sftp_host_key_entry", Entry.class);
        this.networkOptions = new NetworkOptionsPane(downloadManager.getGlobalSettings(),
                Download.Type.ARIA2, Download.Protocol.HTTPS);
        networkOptions.bindTorService(torService);
        Widgets.require(builder, "new_download_network_options_host", Box.class)
                .append(networkOptions.widget());
        this.checksumLabel = Widgets.require(builder, "checksum_label", Label.class);
        this.verifyChecksumCheck = Widgets.require(builder, "verify_checksum_check", CheckButton.class);
        this.startButton = Widgets.require(builder, "new_download_start_button", Button.class);
        this.activity = new SpinnerActivity(
                Widgets.require(builder, "new_download_spinner", Spinner.class));
        networkOptions.onProxyChanged(() -> {
            lastProbedUrl = null;
            resetChecksumUi();
            java.net.URI uri = safeCurrentUri();
            if (uri != null) {
                probeChecksumAsynchronously(uri);
            }
        });

        AccessibilitySupport.label(urlEntry, "Download URL");
        AccessibilitySupport.label(torrentFileButton, "Choose torrent or Metalink descriptor");
        AccessibilitySupport.label(saveFolderButton, "Download destination folder");
        AccessibilitySupport.label(filenameEntry, "Output filename");
        AccessibilitySupport.label(filesTreeview, "Files in the torrent or Metalink");
        AccessibilitySupport.label(selectAllFilesCheck, "Select every descriptor file");
        AccessibilitySupport.label(sftpHostKeyEntry, "Expected SFTP host key digest");
        sftpHostKeyEntry.setTooltipText(
                "Expected server public-key digest: sha-1=<40 hex digits> or md5=<32 hex digits>. "
                + "Leaving it blank disables aria2 host-key verification.");
        AccessibilitySupport.label(verifyChecksumCheck, "Verify checksum at completion");

        DialogSupport.configureIndependent(dialog, parent);

        ListStore priorityStore = Widgets.require(builder, "file_priority_store", ListStore.class);
        for (String priority : new String[]{FileTreeSupport.PRIORITY_HIGH,
                FileTreeSupport.PRIORITY_NORMAL, FileTreeSupport.PRIORITY_LOW}) {
            TreeIter iter = new TreeIter();
            priorityStore.append(iter);
            ListStoreCells.setString(priorityStore, iter, 0, priority);
        }
        filesTreeview.setExpanderColumn(Widgets.require(builder,
                "new_files_name_column", org.gnome.gtk.TreeViewColumn.class));
        Widgets.require(builder, "selected_renderer", CellRendererToggle.class)
                .onToggled(this::onFileSelectionToggled);
        Widgets.require(builder, "file_priority", CellRendererCombo.class)
                .onChanged((path, priorityIter) -> {
                    String priority = ListStoreCells.getString(priorityStore, priorityIter, 0);
                    FileTreeSupport.setPriority(filesListstore, path, priority);
                });
        selectAllFilesCheck.onToggled(this::onSelectAllFilesToggled);
        optionsNotebook.onSwitchPage((page, pageNumber) -> {
            if (pageNumber == 1) {
                requestCurrentFilePreview();
            }
        });

        Path defaultDestination = Path.of(currentDefaultDirectory());
        this.torrentFileChooser = PathChooserButton.forFile(torrentFileButton, dialog,
                "Select torrent or metalink file", null, path -> {
                    selectedTorrentFile = path;
                    if (!urlEntry.getText().isBlank()) {
                        urlEntry.setText("");
                    }
                    updateNetworkCapabilities(Download.Type.ARIA2,
                            Download.Protocol.fromPath(path));
                    analyzeTorrentFile();
                });
        this.saveFolderChooser = PathChooserButton.forFolder(saveFolderButton, dialog,
                "Select destination folder", defaultDestination, path -> {
                    destinationFolder = path;
                    updateDiskSpace(path.toString());
                });
        this.destinationFolder = defaultDestination;
        updateDiskSpace(defaultDestination.toString());

        // Live URL analysis (mirrors the approved old UI): filename auto-fill
        // + magnet metadata + multi-file listing in the Files tab
        filenameEntry.onChanged(() -> {
            if (!updatingFilenameSuggestion) {
                filenameEditedByUser = true;
            }
        });
        sftpHostKeyEntry.onChanged(() ->
                sftpHostKeyEntry.getStyleContext().removeClass("error"));
        urlEntry.onChanged(() -> {
            urlEntry.getStyleContext().removeClass("error");
            analyzeUrl();
        });

        Widgets.require(builder, "new_download_cancel_button", Button.class)
                .onClicked(this::closeDialog);
        dialog.onCloseRequest(() -> {
            synchronized (closed) { closed.set(true); }
            checksumProbeGeneration++;
            releaseFilePreviewRows();
            activity.dispose();
            return false;
        });
        startButton.onClicked(this::onStart);
    }

    public void present() {
        dialog.present();
        ClipboardUrlPrefill.populate(dialog, urlEntry, uri -> true);
        urlEntry.grabFocus();
    }

    /**
     * Prefills the URL field and runs the live analysis, then presents the
     * dialog. Used by the clipboard confirmation flow.
     *
     * @param url the detected URL to offer for download
     */
    public void prefillUrl(String url) {
        if (url != null && !url.isBlank()) {
            urlEntry.setText(url);
        }
    }

    /** Updates filename/checksum hints and defers descriptor work until the Files tab is used. */
    private void analyzeUrl() {
        String url = urlEntry.getText().trim();
        invalidateFilePreview();
        if (!url.isEmpty() && selectedTorrentFile != null) {
            selectedTorrentFile = null;
            torrentFileChooser.clear();
        }
        resetChecksumUi();
        if (url.isEmpty()) {
            updateNetworkCapabilities(Download.Type.ARIA2, Download.Protocol.HTTPS);
            setSftpHostKeyVisible(false);
            filesStatusLabel.setLabel(
                    "Enter a torrent, magnet, or Metalink source to inspect its files.");
            return;
        }
        try {
            DownloadUrlPolicy.ValidatedSource source = DownloadUrlPolicy.require(url);
            java.net.URI uri = source.uri();
            Download.Protocol protocol = source.protocol();
            Download.Type type = org.manager.download.MediaUrlDetector.isMediaSource(source)
                    ? Download.Type.YOUTUBE : Download.Type.ARIA2;
            updateNetworkCapabilities(type, protocol);
            setSftpHostKeyVisible(protocol == Download.Protocol.SFTP);
            if (protocol == Download.Protocol.MAGNET) {
                analyzeMagnet(url);
            }
            String path = uri.getPath();
            if (path != null && !path.isEmpty()) {
                String filename = path.substring(path.lastIndexOf('/') + 1);
                if (!filename.isEmpty() && filenameEntry.getText().isBlank()) {
                    setFilenameSuggestion(java.net.URLDecoder.decode(filename,
                            java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            if (protocol == Download.Protocol.TORRENT
                    || protocol == Download.Protocol.MAGNET
                    || protocol == Download.Protocol.METALINK) {
                filesStatusLabel.setLabel(optionsNotebook.getCurrentPage() == 1
                        ? "Loading file metadata…"
                        : "Open the Files tab to load selectable file metadata.");
                if (optionsNotebook.getCurrentPage() == 1) {
                    requestCurrentFilePreview();
                }
            } else {
                showSingleUrlFile(uri);
                probeChecksumAsynchronously(uri);
            }
        } catch (Exception e) {
            updateNetworkCapabilities(Download.Type.ARIA2, Download.Protocol.HTTPS);
            setSftpHostKeyVisible(false);
            filesStatusLabel.setLabel("Enter a valid download URL or magnet link.");
        }
    }

    private void updateNetworkCapabilities(Download.Type type, Download.Protocol protocol) {
        networkOptions.updateCapabilities(downloadManager.getGlobalSettings(), type, protocol);
    }

    private void setSftpHostKeyVisible(boolean visible) {
        sftpHostKeyGrid.setVisible(visible);
    }

    /**
     * Probes sibling checksum files (.sha256/.sha512/.sha1/.md5) for the
     * entered URL on a worker thread; results update the dialog on the GTK
     * thread. Negative results are cached per URL so re-typing does not
     * re-probe. Only URLs that look like direct file downloads are probed.
     */
    private void probeChecksumAsynchronously(java.net.URI uri) {
        String path = uri.getPath();
        boolean looksLikeFile = path != null && path.lastIndexOf('.') > path.lastIndexOf('/');
        if (!looksLikeFile || java.util.Objects.equals(uri, lastProbedUrl)) {
            return;
        }
        final String proxy;
        try {
            proxy = networkOptions.selectedProxyAddress();
        } catch (IllegalArgumentException incompleteRoute) {
            return; // Wait for a complete route; never substitute direct networking.
        }
        lastProbedUrl = uri;
        detectedChecksum = null;
        long generation = ++checksumProbeGeneration;
        boolean torSelected = networkOptions.isTorSelected();
        java.util.concurrent.CompletableFuture<Void> probe = java.util.concurrent.CompletableFuture
                .runAsync(() -> { }, CompletableFuture.delayedExecutor(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> generation == checksumProbeGeneration
                        ? DialogOptions.ensureTorAvailable(torSelected, torService)
                        : CompletableFuture.completedFuture(null))
                .thenCompose(ignored -> java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        generation == checksumProbeGeneration
                                ? org.manager.download.ChecksumProbe.probe(uri, proxy).orElse(null) : null))
                .thenAccept(found -> UiThread.marshal(() -> {
                    if (generation != checksumProbeGeneration
                            || !java.util.Objects.equals(uri, safeCurrentUri())) {
                        return;
                    }
                    detectedChecksum = found;
                    if (found != null) {
                        checksumLabel.setLabel(found.algorithm() + ": " + found.checksum());
                        verifyChecksumCheck.setVisible(true);
                        verifyChecksumCheck.setActive(true);
                    }
                }))
                .exceptionally(e -> {
                    LOGGER.debug("Checksum probe failed", e);
                    return null;
                });
        activity.track(probe);
    }

    private java.net.URI safeCurrentUri() {
        return DownloadUrlPolicy.parse(urlEntry.getText()).map(DownloadUrlPolicy.ValidatedSource::uri)
                .orElse(null);
    }

    /** Clears the checksum widgets for a new URL being typed. */
    private void resetChecksumUi() {
        checksumProbeGeneration++;
        lastProbedUrl = null;
        checksumLabel.setLabel("—");
        verifyChecksumCheck.setVisible(false);
        verifyChecksumCheck.setActive(false);
        detectedChecksum = null;
    }

    /** Uses magnet display metadata for the filename while peers resolve the real file list. */
    private void analyzeMagnet(String magnet) {
        String displayName = magnetParam(magnet, "dn");
        if (filenameEntry.getText().isBlank() && displayName != null) {
            setFilenameSuggestion(displayName);
        }
    }

    private void setFilenameSuggestion(String suggestion) {
        if (filenameEditedByUser || suggestion == null || suggestion.isBlank()) {
            return;
        }
        updatingFilenameSuggestion = true;
        try {
            filenameEntry.setText(suggestion);
        } finally {
            updatingFilenameSuggestion = false;
        }
    }

    /** Starts a real descriptor-content preview after a local file is selected. */
    private void analyzeTorrentFile() {
        invalidateFilePreview();
        if (selectedTorrentFile == null) {
            return;
        }
        filesStatusLabel.setLabel("Loading file metadata…");
        requestCurrentFilePreview();
    }

    /** Extracts a parameter value from a magnet URI query string. */
    private static String magnetParam(String magnet, String key) {
        for (String part : magnet.substring(Math.min(7, magnet.length())).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equalsIgnoreCase(key)) {
                try {
                    return java.net.URLDecoder.decode(part.substring(eq + 1),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return part.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private void requestCurrentFilePreview() {
        URI source = currentFilePreviewSource();
        if (source == null) {
            return;
        }
        Download.Protocol protocol = Download.Protocol.fromUri(source);
        if (protocol != Download.Protocol.TORRENT
                && protocol != Download.Protocol.MAGNET
                && protocol != Download.Protocol.METALINK) {
            return;
        }
        if (source.equals(loadedPreviewSource) && !FileTreeSupport.allIndexes(filesListstore).isEmpty()) {
            return;
        }

        long epoch = ++previewEpoch;
        FileTreeSupport.clear(filesListstore, fileRows);
        updateSelectAll(false, false, false);
        filesStatusLabel.setLabel(protocol == Download.Protocol.MAGNET
                ? "Retrieving magnet metadata from peers…"
                : "Reading descriptor files…");
        previewLoading = true;
        refreshStartSensitivity();
        CompletableFuture<java.util.List<org.manager.download.DownloadFileInfo>> preview =
                downloadManager.previewDownloadFiles(source, selectedPreviewProxy());
        if (preview == null) {
            preview = CompletableFuture.completedFuture(java.util.List.of());
        }
        activity.track(preview).whenComplete((files, error) -> UiThread.marshal(() -> {
            if (epoch != previewEpoch) {
                return;
            }
            previewLoading = false;
            if (error != null) {
                filesStatusLabel.setLabel("Could not load file metadata: " + rootMessage(error)
                        + ". Starting the download will include all files.");
                LOGGER.warn("File metadata preview failed for " + source, error);
                refreshStartSensitivity();
                return;
            }
            showPreviewFiles(source, files);
        }));
    }

    private String selectedPreviewProxy() {
        return networkOptions.selectedProxyAddress();
    }

    private URI currentFilePreviewSource() {
        if (selectedTorrentFile != null) {
            return selectedTorrentFile.toUri();
        }
        return safeCurrentUri();
    }

    private void showPreviewFiles(URI source,
            java.util.List<org.manager.download.DownloadFileInfo> files) {
        java.util.List<FileTreeSupport.Entry> rows = new java.util.ArrayList<>();
        long totalSize = 0;
        for (org.manager.download.DownloadFileInfo file : files) {
            rows.add(new FileTreeSupport.Entry(true, file.path(), file.length(), 0,
                    file.index(), FileTreeSupport.PRIORITY_NORMAL));
            totalSize += file.length();
        }
        boolean changed = FileTreeSupport.reconcile(filesListstore, fileRows, rows, null);
        if (changed) {
            FileTreeSupport.expandTopLevel(filesTreeview, filesListstore);
        }
        loadedPreviewSource = source;
        boolean available = !rows.isEmpty();
        updateSelectAll(available, available, false);
        filesStatusLabel.setLabel(available
                ? rows.size() + " file(s), " + DownloadFormats.size(totalSize)
                        + " — uncheck files you do not want."
                : "No selectable files were found in this source.");
        refreshStartSensitivity();
    }

    private void showSingleUrlFile(URI uri) {
        String path = uri.getPath();
        String name = path == null || path.isBlank()
                ? uri.getHost() : DetailTabsPresenter.fileName(path);
        showPreviewFiles(uri, java.util.List.of(
                new org.manager.download.DownloadFileInfo(1, name, 0)));
        selectAllFilesCheck.setSensitive(false);
        filesStatusLabel.setLabel("Single-file download");
    }

    private void onFileSelectionToggled(String path) {
        if (!FileTreeSupport.toggleSelection(filesListstore, path)) {
            return;
        }
        updateSelectionSummary();
    }

    private void onSelectAllFilesToggled() {
        if (updatingSelectAll) {
            return;
        }
        FileTreeSupport.selectAll(filesListstore, selectAllFilesCheck.getActive());
        updateSelectionSummary();
    }

    private void updateSelectionSummary() {
        int selected = FileTreeSupport.selectedIndexes(filesListstore).size();
        int total = FileTreeSupport.allIndexes(filesListstore).size();
        updateSelectAll(total > 0 && selected == total, total > 0,
                selected > 0 && selected < total);
        filesStatusLabel.setLabel(selected == 0 && total > 0
                ? "Select at least one file to start the download."
                : selected + " of " + total + " file(s) selected");
        refreshStartSensitivity();
    }

    private void updateSelectAll(boolean active, boolean sensitive, boolean inconsistent) {
        updatingSelectAll = true;
        try {
            selectAllFilesCheck.setActive(active);
            selectAllFilesCheck.setInconsistent(inconsistent);
            selectAllFilesCheck.setSensitive(sensitive);
        } finally {
            updatingSelectAll = false;
        }
    }

    private void invalidateFilePreview() {
        previewEpoch++;
        previewLoading = false;
        loadedPreviewSource = null;
        FileTreeSupport.clear(filesListstore, fileRows);
        updateSelectAll(false, false, false);
        // A superseded asynchronous preview will deliberately ignore its
        // completion callback. Restore the button here so editing the source
        // cannot leave Start disabled forever.
        refreshStartSensitivity();
    }

    /** Keeps Start unavailable while work is active or a descriptor selects no files. */
    private void refreshStartSensitivity() {
        boolean hasPreviewFiles = loadedPreviewSource != null
                && !FileTreeSupport.allIndexes(filesListstore).isEmpty();
        boolean hasValidSelection = !hasPreviewFiles
                || !FileTreeSupport.selectedIndexes(filesListstore).isEmpty();
        startButton.setSensitive(!previewLoading && !submissionInFlight && hasValidSelection);
    }

    private void releaseFilePreviewRows() {
        previewEpoch++;
        FileTreeSupport.freeReferences(fileRows);
    }

    private void closeDialog() {
        releaseFilePreviewRows();
        activity.dispose();
        dialog.close();
    }

    private void updateDiskSpace(String dir) {
        try {
            long free = new java.io.File(dir).getUsableSpace();
            diskSpaceLabel.setLabel(String.format("%.2f GB free", free / (1024.0 * 1024 * 1024)));
        } catch (Exception e) {
            diskSpaceLabel.setLabel("");
        }
    }

    private void onStart() {
        if (submissionInFlight || closed.get()) {
            return;
        }
        try {
            Download download = createDownload();
            applyRequestedFilename(download);
            applyOptions(download);
            registerChecksumVerification(download);
            submissionInFlight = true;
            refreshStartSensitivity();
            AccessibilitySupport.status(diskSpaceLabel, "Adding download to queue…");
            Path originalToTrash = selectedTorrentFile != null && downloadManager.getGlobalSettings()
                    .getBooleanProperty("ui.moveTorrent", false) ? selectedTorrentFile : null;
            activity.track(DownloadSubmission.submit(downloadManager, download,
                    DialogOptions.ensureTorAvailable(networkOptions.isTorSelected(), torService),
                    closed, originalToTrash)).whenComplete((warning, error) -> UiThread.marshal(() -> {
                if (closed.get()) { return; }
                if (error == null) {
                    if (warning == null) {
                        finishSubmission(download);
                    } else {
                        if (onDownloadQueued != null) { onDownloadQueued.run(); }
                        AccessibilitySupport.status(diskSpaceLabel, warning);
                    }
                } else {
                    submissionInFlight = downloadManager.getDownload(download.getId()) != null;
                    refreshStartSensitivity();
                    AccessibilitySupport.status(diskSpaceLabel,
                            "Could not add to queue: " + rootMessage(error)
                                    + (submissionInFlight ? ". This download remains in Downloads; manage it there."
                                            : ". Press Start to retry."),
                            org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                    LOGGER.warn("Queue rejected new download", error);
                }
            }));
        } catch (Exception e) {
            submissionInFlight = false;
            refreshStartSensitivity();
            LOGGER.warn("New download rejected: " + e.getMessage());
            if (!sftpHostKeyEntry.hasCssClass("error")) {
                urlEntry.getStyleContext().addClass("error");
            }
            AccessibilitySupport.status(diskSpaceLabel, "Cannot add download: " + rootMessage(e),
                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
        }
    }

    private void finishSubmission(Download download) {
        LOGGER.info("Queued new download: " + download.getName());
        if (onDownloadQueued != null) {
            onDownloadQueued.run();
        }
        dialog.close();
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private void applyRequestedFilename(Download download) {
        if (!filenameEditedByUser) {
            return;
        }
        String requested = filenameEntry.getText().strip();
        download.setRequestedFileName(requested);
        if (!requested.isEmpty()) {
            download.setName(requested);
        }
    }

    /**
     * When a checksum was detected and the user kept "Verify at completion"
     * checked, stores it on the download and schedules the
     * {@link org.manager.download.action.ChecksumValidationAction} for
     * completion time.
     */
    private void registerChecksumVerification(Download download) {
        org.manager.download.ChecksumProbe.DetectedChecksum found = detectedChecksum;
        if (found == null || !verifyChecksumCheck.getVisible()
                || !verifyChecksumCheck.getActive()) {
            return;
        }
        download.setChecksumAlgorithm(found.algorithm());
        download.setExpectedChecksum(found.checksum());
        downloadManager.addAfterCompletionAction(download,
                org.manager.download.action.ChecksumValidationAction.fromString(
                        found.algorithm() + ":" + found.checksum()));
        LOGGER.info("Checksum verification scheduled for " + download.getName()
                + " (" + found.algorithm() + ")");
    }

    private Download createDownload() {
        Path destination = destinationFolder != null ? destinationFolder
                : Path.of(currentDefaultDirectory());

        if (selectedTorrentFile != null) {
            return DownloadSubmission.draft(downloadManager, selectedTorrentFile.toUri(),
                    destination, Download.Type.ARIA2);
        }

        String url = urlEntry.getText().trim();
        if (url.isEmpty()) {
            throw new IllegalArgumentException("Enter a URL or choose a torrent/metalink file.");
        }
        return DownloadSubmission.draft(downloadManager,
                DownloadUrlPolicy.require(url).uri(), destination, null);
    }

    private void applyOptions(Download download) {
        networkOptions.applyTo(download);

        if (download.getProtocol() == Download.Protocol.SFTP
                && download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings) {
            try {
                aria2Settings.setSshHostKeyDigest(sftpHostKeyEntry.getText());
            } catch (IllegalArgumentException invalidDigest) {
                sftpHostKeyEntry.getStyleContext().addClass("error");
                throw invalidDigest;
            }
        }

        applyFileChoices(download);
    }

    /** Applies the checked descriptor indexes and persists the displayed priorities. */
    private void applyFileChoices(Download download) {
        if (!(download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings)
                || !java.util.Objects.equals(currentFilePreviewSource(), loadedPreviewSource)) {
            return;
        }
        Download.Protocol protocol = download.getProtocol();
        if (protocol != Download.Protocol.TORRENT
                && protocol != Download.Protocol.MAGNET
                && protocol != Download.Protocol.METALINK) {
            return;
        }
        java.util.List<Integer> all = FileTreeSupport.allIndexes(filesListstore);
        java.util.List<Integer> selected = FileTreeSupport.selectedIndexes(filesListstore);
        if (all.isEmpty()) {
            return;
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("Select at least one file to download.");
        }
        if (selected.size() == all.size()) {
            aria2Settings.setSelectedFiles(null);
        } else {
            aria2Settings.setSelectedFiles(encodeFileSelection(selected));
        }
        aria2Settings.setFilePriorities(FileTreeSupport.priorities(filesListstore));
    }

    static String encodeFileSelection(java.util.List<Integer> indexes) {
        return indexes.stream().filter(index -> index != null && index > 0)
                .distinct().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private String currentDefaultDirectory() {
        Path dir = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();
        return dir != null ? dir.toString()
                : org.manager.util.OdmPaths.downloadDirectory().toString();
    }
}

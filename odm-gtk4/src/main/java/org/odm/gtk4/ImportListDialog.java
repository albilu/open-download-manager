package org.odm.gtk4;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gnome.gio.File;
import org.gnome.gtk.Button;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.TextBuffer;
import org.gnome.gtk.TextView;
import org.gnome.gtk.Window;
import org.manager.download.Download;
import org.manager.download.DownloadManager;

/**
 * Import-list dialog (GTK4 port). The old dialog was a shell whose
 * processDownloads()/handleValidate() were TODOs — imported URLs never became
 * downloads. This one executes: validate parses and reports, import queues.
 */
public class ImportListDialog {

    private static final Logger LOGGER = Logger.getLogger(ImportListDialog.class.getName());

    private final Window dialog;
    private final DownloadManager downloadManager;
    private final Runnable onImportDone;

    private final TextView urlsTextView;
    private final Label statusLabel;

    public ImportListDialog(Window parent, DownloadManager downloadManager, Runnable onImportDone) {
        this.downloadManager = downloadManager;
        this.onImportDone = onImportDone;

        GtkBuilder builder = UiLoader.load("/ui/import-list.ui");
        this.dialog = Widgets.require(builder, "import_list_dialog", Window.class);
        this.urlsTextView = Widgets.require(builder, "urls_textview", TextView.class);
        this.statusLabel = Widgets.require(builder, "status_label", Label.class);

        dialog.setTransientFor(parent);

        Widgets.require(builder, "from_file_button", Button.class).onClicked(this::onFromFile);
        Widgets.require(builder, "validate_button", Button.class).onClicked(this::onValidate);
        Widgets.require(builder, "cancel_button", Button.class).onClicked(dialog::close);
        Widgets.require(builder, "import_button", Button.class).onClicked(this::onImport);
    }

    public void present() {
        dialog.present();
    }

    private void onFromFile() {
        FileDialog fileDialog = new FileDialog();
        fileDialog.setTitle("Select URL list file");
        fileDialog.open(dialog, null, result -> {
            try {
                File file = fileDialog.openFinish(result);
                if (file != null && file.getPath() != null) {
                    String content = Files.readString(Path.of(file.getPath().toString()));
                    urlsTextView.getBuffer().setText(content, -1);
                    statusLabel.setLabel("Loaded " + file.getBasename());
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "List file selection cancelled or failed", e);
            }
        });
    }

    private void onValidate() {
        ParseResult parsed = parseUrls();
        statusLabel.setLabel(parsed.valid().size() + " valid URL(s), "
                + parsed.invalid().size() + " invalid line(s)");
        if (!parsed.invalid().isEmpty()) {
            LOGGER.info("Invalid lines: " + parsed.invalid());
        }
    }

    private void onImport() {
        ParseResult parsed = parseUrls();
        if (parsed.valid().isEmpty()) {
            statusLabel.setLabel("Nothing to import.");
            return;
        }
        int queued = 0;
        for (URI uri : parsed.valid()) {
            try {
                Download download = downloadManager.createDownload(uri, null);
                downloadManager.queueDownload(download);
                queued++;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to queue " + uri, e);
            }
        }
        statusLabel.setLabel("Queued " + queued + " download(s).");
        LOGGER.info("Imported " + queued + " downloads from list");
        if (onImportDone != null) {
            onImportDone.run();
        }
        dialog.close();
    }

    private ParseResult parseUrls() {
        TextBuffer buffer = urlsTextView.getBuffer();
        org.gnome.gtk.TextIter start = new org.gnome.gtk.TextIter();
        org.gnome.gtk.TextIter end = new org.gnome.gtk.TextIter();
        buffer.getStartIter(start);
        buffer.getEndIter(end);
        String text = buffer.getText(start, end, false);
        List<URI> valid = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                URI uri = new URI(line);
                if (uri.getScheme() == null) {
                    invalid.add(line);
                } else {
                    valid.add(uri);
                }
            } catch (Exception e) {
                invalid.add(line);
            }
        }
        return new ParseResult(valid, invalid);
    }

    private record ParseResult(List<URI> valid, List<String> invalid) {
    }
}

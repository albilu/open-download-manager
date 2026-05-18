package org.odm.ui.handler;

import java.util.logging.Logger;

import org.jgtk.GladeUI;
import org.jgtk.core.GtkNativeLibraries;
import org.odm.ui.service.NewDownloadService;

import com.sun.jna.Pointer;

/**
 * Handles all GTK signal events for the New Download dialog.
 * Delegates business logic to NewDownloadService.
 */
public class NewDownloadSignalHandler {

    private static final Logger LOGGER = Logger.getLogger(NewDownloadSignalHandler.class.getName());

    private final NewDownloadService service;
    private final GladeUI ui;

    public NewDownloadSignalHandler(NewDownloadService service, GladeUI ui) {
        this.service = service;
        this.ui = ui;
    }

    // ====== Dialog Signals ======

    /**
     * Handles dialog destroy signal.
     */
    public void onDialogDestroy(Pointer widget, Pointer data) {
        LOGGER.info("New download dialog destroyed");
        service.handleDialogDestroy();
    }

    /**
     * Handles dialog delete event.
     */
    public void onDialogDeleteEvent(Pointer widget, Pointer data) {
        LOGGER.info("New download dialog delete event");
        service.handleDialogDeleteEvent();
    }

    // ====== Button Signals ======

    /**
     * Handles OK button click.
     */
    public void onOkButtonClicked(Pointer widget, Pointer data) {
        LOGGER.info("OK button clicked");
        service.handleOkButton();
    }

    /**
     * Handles Cancel button click.
     */
    public void onCancelButtonClicked(Pointer widget, Pointer data) {
        LOGGER.info("Cancel button clicked");
        service.handleCancelButton();
    }

    /**
     * Handles Browse button click for destination.
     */
    public void onBrowseButtonClicked(Pointer widget, Pointer data) {
        LOGGER.info("Browse button clicked");
        service.handleBrowseButton();
    }

    /**
     * Handles Analyze button click.
     */
    public void onAnalyzeButtonClicked(Pointer widget, Pointer data) {
        LOGGER.info("Analyze button clicked");
        service.handleAnalyzeButton();
    }

    /**
     * Handles new download cancel button click.
     */
    public void on_new_download_cancel_button_clicked(Pointer widget, Pointer data) {
        LOGGER.info("New download cancel button clicked");
        service.handleCancelButton();
    }

    /**
     * Handles new download start button click.
     */
    public void on_new_download_start_button_clicked(Pointer widget, Pointer data) {
        LOGGER.info("New download start button clicked");
        service.handleOkButton();
    }

    // ====== Notebook Signals ======

    /**
     * Handles notebook page switch (legacy approach).
     * Gets the new page number by querying the notebook widget directly.
     */
    public void on_options_notebook_switch_page(Pointer widget, Pointer data) {
        // Get the current (new) page index from the notebook widget
        int newPageIndex = GtkNativeLibraries.Gtk.INSTANCE.gtk_notebook_get_current_page(widget);
        LOGGER.fine("Options notebook page switched to: " + newPageIndex + " (legacy approach)");
        service.handleNotebookPageSwitch(newPageIndex);
    }

    /**
     * Enhanced notebook page switch handler.
     * Receives the page number directly from GTK signal parameters.
     */
    public void onNotebookPageSwitchEnhanced(Pointer notebook, Pointer page, int pageNum, Pointer userData) {
        LOGGER.fine("----Options notebook page switched to: " + pageNum + " (enhanced approach - direct parameter)");
        service.handleNotebookPageSwitch(pageNum);
    }

    // ====== URL Entry Signals ======

    /**
     * Handles URL entry text change.
     */
    public void onUrlEntryChanged(Pointer widget, Pointer data) {
        LOGGER.fine("URL entry changed");
        service.handleUrlEntryChanged();
    }

    /**
     * Handles URL entry activation (Enter key).
     */
    public void onUrlEntryActivated(Pointer widget, Pointer data) {
        LOGGER.info("URL entry activated");
        service.handleUrlEntryActivated();
    }

    /**
     * Handles URL entry focus in event.
     */
    public void on_url_entry_focus_in_event(Pointer widget, Pointer data) {
        LOGGER.fine("URL entry focus in");
        service.handleUrlEntryFocusIn();
    }

    // ====== File Chooser Signals ======

    /**
     * Handles torrent file chooser file set.
     */
    public void on_torrent_file_chooser_file_set(Pointer widget, Pointer data) {
        LOGGER.info("Torrent file chooser file set");
        service.handleTorrentFileChooserFileSet();
    }

    /**
     * Handles save folder chooser file set.
     */
    public void on_save_folder_chooser_file_set(Pointer widget, Pointer data) {
        LOGGER.info("Save folder chooser file set");
        service.handleSaveFolderChooserFileSet();
    }

    /**
     * Handles torrent file chooser button press event.
     */
    public void on_torrent_file_chooser_button_press_event(Pointer widget, Pointer data) {
        LOGGER.fine("Torrent file chooser button press");
        service.handleTorrentFileChooserButtonPress();
    }

    // ====== Entry Change Signals ======

    /**
     * Handles filename entry changed.
     */
    public void on_filename_entry_changed(Pointer widget, Pointer data) {
        LOGGER.fine("Filename entry changed");
        service.handleFilenameEntryChanged();
    }

    // ====== TreeView Signals ======

    /**
     * Handles files treeview row activated.
     */
    public void on_files_treeview_row_activated(Pointer widget, Pointer data) {
        LOGGER.fine("Files treeview row activated");
        service.handleFilesTreeviewRowActivated();
    }

    /**
     * Handles files treeview selection changed.
     */
    public void on_files_treeview_selection_changed(Pointer widget, Pointer data) {
        LOGGER.fine("Files treeview selection changed");
        service.handleFilesTreeviewSelectionChanged();
    }

    /**
     * Handles selected renderer toggled.
     */
    public void on_selected_renderer_toggled(Pointer widget, Pointer data) {
        LOGGER.fine("Selected renderer toggled");
        service.handleSelectedRendererToggled();
    }

    // ====== Spin Button Signals ======

    /**
     * Handles max connections spin value changed.
     */
    public void on_max_connections_spin_value_changed(Pointer widget, Pointer data) {
        LOGGER.fine("Max connections spin value changed");
        service.handleMaxConnectionsSpinValueChanged();
    }

    /**
     * Handles retry limit spin value changed.
     */
    public void on_retry_limit_spin_value_changed(Pointer widget, Pointer data) {
        LOGGER.fine("Retry limit spin value changed");
        service.handleRetryLimitSpinValueChanged();
    }

    /**
     * Handles max download speed spin value changed.
     */
    public void on_max_download_speed_spin_value_changed(Pointer widget, Pointer data) {
        LOGGER.fine("Max download speed spin value changed");
        service.handleMaxDownloadSpeedSpinValueChanged();
    }

    /**
     * Handles max upload speed spin value changed.
     */
    public void on_max_upload_speed_spin_value_changed(Pointer widget, Pointer data) {
        LOGGER.fine("Max upload speed spin value changed");
        service.handleMaxUploadSpeedSpinValueChanged();
    }

    /**
     * Handles retry after value changed.
     */
    public void on_retry_after_value_changed(Pointer widget, Pointer data) {
        LOGGER.fine("Retry after value changed");
        service.handleRetryAfterValueChanged();
    }

    // ====== Proxy Settings Signals ======

    /**
     * Handles proxy type combo changed.
     */
    public void on_proxy_type_combo_changed(Pointer widget, Pointer data) {
        LOGGER.fine("Proxy type combo changed");
        service.handleProxyTypeComboChanged();
    }

    /**
     * Handles proxy host entry changed.
     */
    public void on_proxy_host_entry_changed(Pointer widget, Pointer data) {
        LOGGER.fine("Proxy host entry changed");
        service.handleProxyHostEntryChanged();
    }

    /**
     * Handles proxy port spin value changed.
     */
    public void on_proxy_port_spin_value_changed(Pointer widget, Pointer data) {
        LOGGER.fine("Proxy port spin value changed");
        service.handleProxyPortSpinValueChanged();
    }

    /**
     * Handles proxy username entry changed.
     */
    public void on_proxy_username_entry_changed(Pointer widget, Pointer data) {
        LOGGER.fine("Proxy username entry changed");
        service.handleProxyUsernameEntryChanged();
    }

    /**
     * Handles proxy password entry changed.
     */
    public void on_proxy_password_entry_changed(Pointer widget, Pointer data) {
        LOGGER.fine("Proxy password entry changed");
        service.handleProxyPasswordEntryChanged();
    }

    // ====== Checkbox Signals ======

    /**
     * Handles authentication checkbox toggle.
     */
    public void onUseAuthToggled(Pointer widget, Pointer data) {
        LOGGER.info("Authentication checkbox toggled");
        service.handleUseAuthToggled();
    }

    /**
     * Handles proxy checkbox toggle.
     */
    public void onUseProxyToggled(Pointer widget, Pointer data) {
        LOGGER.info("Proxy checkbox toggled");
        service.handleUseProxyToggled();
    }

    // ====== Clear Button Signals ======

    /**
     * Handles clear URL button clicked.
     */
    public void on_clear_url_button_clicked(Pointer widget, Pointer data) {
        LOGGER.info("Clear URL button clicked");
        service.handleClearUrlButton();
    }

    /**
     * Handles clear torrent button clicked.
     */
    public void on_clear_torrent_button_clicked(Pointer widget, Pointer data) {
        LOGGER.info("Clear torrent button clicked");
        service.handleClearTorrentButton();
    }

    // ====== Options Tab Signals ======

    /**
     * Handles start automatically checkbox toggle.
     */
    public void on_start_automatically_check_toggled(Pointer widget, Pointer data) {
        LOGGER.fine("Start automatically checkbox toggled");
        service.handleStartAutomaticallyToggled();
    }

    /**
     * Handles move torrent checkbox toggle.
     */
    public void on_move_torrent_check_toggled(Pointer widget, Pointer data) {
        LOGGER.fine("Move torrent checkbox toggled");
        service.handleMoveTorrentToggled();
    }

    /**
     * Handles Tor switch state change.
     */
    public void on_tor_switch_state_set(Pointer widget, Pointer data) {
        LOGGER.fine("Tor switch state changed");
        service.handleTorSwitchStateSet();
    }
}

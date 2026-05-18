package org.jgtk.service;

import com.sun.jna.Pointer;
import org.jgtk.widget.BasicWidgetService;
import org.jgtk.widget.TextWidgetService;
import org.jgtk.widget.InteractiveWidgetService;
import org.jgtk.widget.DialogWidgetService;

import java.util.logging.Logger;

/**
 * Unified widget management service that delegates to specialized widget
 * services.
 * Provides a single interface for all widget operations while maintaining
 * modularity.
 */
public class WidgetManagementService {

    private static final Logger LOGGER = Logger.getLogger(WidgetManagementService.class.getName());

    private final ResourceLoadingService resourceService;

    /**
     * Creates a new widget management service.
     *
     * @param resourceService the resource loading service to use for widget lookup
     */
    public WidgetManagementService(ResourceLoadingService resourceService) {
        this.resourceService = resourceService;
    }

    // === Widget Lookup ===

    /**
     * Gets a widget by its ID.
     *
     * @param widgetId the ID of the widget as specified in the Glade file
     * @return the widget pointer, or null if not found
     */
    public Pointer getWidget(String widgetId) {
        return resourceService.getWidget(widgetId);
    }

    // === Basic Widget Operations ===

    /**
     * Shows all child widgets of the specified widget.
     *
     * @param widgetId the ID of the widget to show
     */
    public void showAll(String widgetId) {
        Pointer widget = getWidget(widgetId);
        BasicWidgetService.showAll(widget);
    }

    /**
     * Shows the specified widget.
     *
     * @param widgetId the ID of the widget to show
     */
    public void show(String widgetId) {
        Pointer widget = getWidget(widgetId);
        BasicWidgetService.show(widget);
    }

    /**
     * Hides the specified widget.
     *
     * @param widgetId the ID of the widget to hide
     */
    public void hide(String widgetId) {
        Pointer widget = getWidget(widgetId);
        BasicWidgetService.hide(widget);
    }

    /**
     * Sets the visibility of a widget.
     *
     * @param widgetId the ID of the widget
     * @param visible  true to show, false to hide
     */
    public void setWidgetVisible(String widgetId, boolean visible) {
        Pointer widget = getWidget(widgetId);
        BasicWidgetService.setVisible(widget, visible);
    }

    /**
     * Gets the visibility state of a widget.
     *
     * @param widgetId the ID of the widget
     * @return true if visible, false if hidden
     */
    public boolean getWidgetVisible(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return BasicWidgetService.isVisible(widget);
    }

    /**
     * Sets the sensitivity (enabled/disabled state) of a widget.
     *
     * @param widgetId  the ID of the widget
     * @param sensitive true to enable, false to disable
     */
    public void setWidgetSensitive(String widgetId, boolean sensitive) {
        Pointer widget = getWidget(widgetId);
        BasicWidgetService.setSensitive(widget, sensitive);
    }

    /**
     * Gets the sensitivity state of a widget.
     *
     * @param widgetId the ID of the widget
     * @return true if enabled, false if disabled
     */
    public boolean getWidgetSensitive(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return BasicWidgetService.isSensitive(widget);
    }

    /**
     * Sets a tooltip for a widget.
     *
     * @param widgetId the ID of the widget
     * @param tooltip  the tooltip text
     */
    public void setWidgetTooltip(String widgetId, String tooltip) {
        Pointer widget = getWidget(widgetId);
        BasicWidgetService.setTooltip(widget, tooltip);
    }

    // === Text Widget Operations ===

    /**
     * Sets the text of an entry widget.
     *
     * @param widgetId the ID of the entry widget
     * @param text     the text to set
     */
    public void setEntryText(String widgetId, String text) {
        Pointer widget = getWidget(widgetId);
        TextWidgetService.setEntryText(widget, text);
    }

    /**
     * Gets the text from an entry widget.
     *
     * @param widgetId the ID of the entry widget
     * @return the text in the entry, or null if widget not found
     */
    public String getEntryText(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return TextWidgetService.getEntryText(widget);
    }

    /**
     * Sets the text of a label widget.
     *
     * @param widgetId the ID of the label widget
     * @param text     the text to set
     */
    public void setLabelText(String widgetId, String text) {
        Pointer widget = getWidget(widgetId);
        TextWidgetService.setLabelText(widget, text);
    }

    /**
     * Gets the text of a label widget.
     *
     * @param widgetId the ID of the label widget
     * @return the text of the label, or null if not found
     */
    public String getLabelText(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return TextWidgetService.getLabelText(widget);
    }

    /**
     * Sets the value of a spin button widget.
     *
     * @param widgetId the ID of the spin button widget
     * @param value    the value to set
     */
    public void setSpinButtonValue(String widgetId, double value) {
        Pointer widget = getWidget(widgetId);
        TextWidgetService.setSpinButtonValue(widget, value);
    }

    /**
     * Gets the value from a spin button widget as an integer.
     *
     * @param widgetId the ID of the spin button widget
     * @return the value, or 0 if widget not found
     */
    public int getSpinButtonValueAsInt(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return TextWidgetService.getSpinButtonValueAsInt(widget);
    }

    /**
     * Gets the value from a spin button widget as a double.
     *
     * @param widgetId the ID of the spin button widget
     * @return the value, or 0.0 if widget not found
     */
    public double getSpinButtonValue(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return TextWidgetService.getSpinButtonValue(widget);
    }

    // === Interactive Widget Operations ===

    /**
     * Sets the active state of a toggle button or checkbox.
     *
     * @param widgetId the ID of the toggle button
     * @param active   true to check/activate, false to uncheck/deactivate
     */
    public void setToggleButtonActive(String widgetId, boolean active) {
        Pointer widget = getWidget(widgetId);
        InteractiveWidgetService.setToggleButtonActive(widget, active);
    }

    /**
     * Gets the active state of a toggle button or checkbox.
     *
     * @param widgetId the ID of the toggle button
     * @return true if checked/active, false otherwise
     */
    public boolean getToggleButtonActive(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return InteractiveWidgetService.getToggleButtonActive(widget);
    }

    /**
     * Sets the active state of a check menu item.
     *
     * @param widgetId the ID of the check menu item
     * @param active   true to check, false to uncheck
     */
    public void setCheckMenuItemActive(String widgetId, boolean active) {
        Pointer widget = getWidget(widgetId);
        InteractiveWidgetService.setCheckMenuItemActive(widget, active);
    }

    /**
     * Gets the active state of a check menu item.
     *
     * @param widgetId the ID of the check menu item
     * @return true if checked/active, false otherwise
     */
    public boolean getCheckMenuItemActive(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return InteractiveWidgetService.getCheckMenuItemActive(widget);
    }

    /**
     * Sets the active state of a switch widget.
     *
     * @param widgetId the ID of the switch widget
     * @param active   true to activate, false to deactivate
     */
    public void setSwitchActive(String widgetId, boolean active) {
        Pointer widget = getWidget(widgetId);
        InteractiveWidgetService.setSwitchActive(widget, active);
    }

    /**
     * Gets the active state of a switch widget.
     *
     * @param widgetId the ID of the switch widget
     * @return true if active, false otherwise
     */
    public boolean getSwitchActive(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return InteractiveWidgetService.getSwitchActive(widget);
    }

    /**
     * Polymorphic method to get the active state of various widget types.
     *
     * @param widgetId the ID of the widget
     * @return true if checked/active, false otherwise
     */
    public boolean getWidgetActive(String widgetId) {
        Pointer widget = getWidget(widgetId);

        if (BasicWidgetService.isToggleButton(widget)) {
            return InteractiveWidgetService.getToggleButtonActive(widget);
        } else if (BasicWidgetService.isCheckMenuItem(widget)) {
            return InteractiveWidgetService.getCheckMenuItemActive(widget);
        } else if (BasicWidgetService.isSwitch(widget)) {
            return InteractiveWidgetService.getSwitchActive(widget);
        }

        LOGGER.warning("Widget " + widgetId + " is not an active widget type");
        return false;
    }

    /**
     * Polymorphic method to set the active state of various widget types.
     *
     * @param widgetId the ID of the widget
     * @param active   true to check/activate, false to uncheck/deactivate
     */
    public void setWidgetActive(String widgetId, boolean active) {
        Pointer widget = getWidget(widgetId);

        if (BasicWidgetService.isToggleButton(widget)) {
            InteractiveWidgetService.setToggleButtonActive(widget, active);
        } else if (BasicWidgetService.isCheckMenuItem(widget)) {
            InteractiveWidgetService.setCheckMenuItemActive(widget, active);
        } else if (BasicWidgetService.isSwitch(widget)) {
            InteractiveWidgetService.setSwitchActive(widget, active);
        } else {
            LOGGER.warning("Widget " + widgetId + " is not an active widget type");
        }
    }

    // === Combo Box Operations ===

    /**
     * Clears all items from a combo box.
     *
     * @param widgetId the ID of the combo box
     */
    public void clearComboBox(String widgetId) {
        Pointer widget = getWidget(widgetId);
        InteractiveWidgetService.clearComboBox(widget);
    }

    /**
     * Adds an item to a combo box.
     *
     * @param widgetId the ID of the combo box
     * @param text     the text to add
     */
    public void addComboBoxItem(String widgetId, String text) {
        Pointer widget = getWidget(widgetId);
        InteractiveWidgetService.addComboBoxItem(widget, text);
    }

    /**
     * Sets the active item in a combo box by index.
     *
     * @param widgetId the ID of the combo box
     * @param index    the index to select (0-based)
     */
    public void setComboBoxActive(String widgetId, int index) {
        Pointer widget = getWidget(widgetId);
        InteractiveWidgetService.setComboBoxActive(widget, index);
    }

    /**
     * Gets the active item index from a combo box.
     *
     * @param widgetId the ID of the combo box
     * @return the active index, or -1 if no selection or widget not found
     */
    public int getComboBoxActive(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return InteractiveWidgetService.getComboBoxActive(widget);
    }

    /**
     * Gets the active text from a combo box.
     *
     * @param widgetId the ID of the combo box
     * @return the active text, or null if no selection or widget not found
     */
    public String getComboBoxActiveText(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return InteractiveWidgetService.getComboBoxActiveText(widget);
    }

    // === File Chooser Operations ===

    /**
     * Sets the current folder for a file chooser.
     *
     * @param widgetId the ID of the file chooser
     * @param folder   the folder path to set
     */
    public void setFileChooserCurrentFolder(String widgetId, String folder) {
        Pointer widget = getWidget(widgetId);
        DialogWidgetService.setFileChooserCurrentFolder(widget, folder);
    }

    /**
     * Gets the current folder from a file chooser.
     *
     * @param widgetId the ID of the file chooser
     * @return the current folder path, or null if widget not found
     */
    public String getFileChooserCurrentFolder(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return DialogWidgetService.getFileChooserCurrentFolder(widget);
    }

    /**
     * Sets the filename for a file chooser.
     *
     * @param widgetId the ID of the file chooser
     * @param filename the filename to set
     */
    public void setFileChooserFilename(String widgetId, String filename) {
        Pointer widget = getWidget(widgetId);
        DialogWidgetService.setFileChooserFilename(widget, filename);
    }

    /**
     * Gets the filename from a file chooser.
     *
     * @param widgetId the ID of the file chooser
     * @return the selected filename, or null if no selection or widget not found
     */
    public String getFileChooserFilename(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return DialogWidgetService.getFileChooserFilename(widget);
    }

    // === Dialog Operations ===

    /**
     * Shows an error message dialog.
     *
     * @param parentWidgetId the ID of the parent window, or null for no parent
     * @param message        the error message to display
     */
    public void showErrorDialog(String parentWidgetId, String message) {
        Pointer parent = parentWidgetId != null ? getWidget(parentWidgetId) : null;
        DialogWidgetService.showErrorDialog(parent, message);
    }

    /**
     * Shows an information message dialog.
     *
     * @param parentWidgetId the ID of the parent window, or null for no parent
     * @param message        the information message to display
     */
    public void showInfoDialog(String parentWidgetId, String message) {
        Pointer parent = parentWidgetId != null ? getWidget(parentWidgetId) : null;
        DialogWidgetService.showInfoDialog(parent, message);
    }

    /**
     * Shows a warning message dialog.
     *
     * @param parentWidgetId the ID of the parent window, or null for no parent
     * @param message        the warning message to display
     */
    public void showWarningDialog(String parentWidgetId, String message) {
        Pointer parent = parentWidgetId != null ? getWidget(parentWidgetId) : null;
        DialogWidgetService.showWarningDialog(parent, message);
    }

    /**
     * Shows a question dialog with Yes/No buttons.
     *
     * @param parentWidgetId the ID of the parent window, or null for no parent
     * @param message        the question to display
     * @return true if Yes was clicked, false if No was clicked
     */
    public boolean showQuestionDialog(String parentWidgetId, String message) {
        Pointer parent = parentWidgetId != null ? getWidget(parentWidgetId) : null;
        return DialogWidgetService.showQuestionDialog(parent, message);
    }

    /**
     * Shows a file chooser dialog for selecting a file.
     *
     * @param parentWidgetId the ID of the parent window, or null for no parent
     * @param title          the title of the dialog
     * @param currentFolder  the initial folder to show
     * @return the selected file path, or null if cancelled
     */
    public String showFileChooserDialog(String parentWidgetId, String title, String currentFolder) {
        Pointer parent = parentWidgetId != null ? getWidget(parentWidgetId) : null;
        return DialogWidgetService.showFileOpenDialog(parent, title, currentFolder);
    }

    /**
     * Shows a file chooser dialog for selecting a directory.
     *
     * @param parentWidgetId the ID of the parent window, or null for no parent
     * @param title          the title of the dialog
     * @param currentFolder  the initial folder to show
     * @return the selected directory path, or null if cancelled
     */
    public String showDirectoryChooserDialog(String parentWidgetId, String title, String currentFolder) {
        Pointer parent = parentWidgetId != null ? getWidget(parentWidgetId) : null;
        return DialogWidgetService.showDirectoryChooserDialog(parent, title, currentFolder);
    }

    // === Window Operations ===

    /**
     * Sets the title of a window.
     *
     * @param widgetId the ID of the window
     * @param title    the title to set
     */
    public void setWindowTitle(String widgetId, String title) {
        Pointer widget = getWidget(widgetId);
        DialogWidgetService.setWindowTitle(widget, title);
    }

    /**
     * Gets the title of a window.
     *
     * @param widgetId the ID of the window
     * @return the window title, or null if widget not found
     */
    public String getWindowTitle(String widgetId) {
        Pointer widget = getWidget(widgetId);
        return DialogWidgetService.getWindowTitle(widget);
    }
}

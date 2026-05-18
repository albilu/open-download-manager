package org.jgtk.core;

/**
 * GTK constants for dialogs, responses, and other GTK operations.
 * Extracted from the monolithic GladeUI class to improve organization.
 */
public final class GtkConstants {

    private GtkConstants() {
        // Utility class - prevent instantiation
    }

    // GTK Dialog flags
    public static final int GTK_DIALOG_MODAL = 1;
    public static final int GTK_DIALOG_DESTROY_WITH_PARENT = 2;

    // GTK Message Types
    public static final int GTK_MESSAGE_INFO = 0;
    public static final int GTK_MESSAGE_WARNING = 1;
    public static final int GTK_MESSAGE_QUESTION = 2;
    public static final int GTK_MESSAGE_ERROR = 3;

    // GTK Button Types
    public static final int GTK_BUTTONS_NONE = 0;
    public static final int GTK_BUTTONS_OK = 1;
    public static final int GTK_BUTTONS_CLOSE = 2;
    public static final int GTK_BUTTONS_CANCEL = 3;
    public static final int GTK_BUTTONS_YES_NO = 4;
    public static final int GTK_BUTTONS_OK_CANCEL = 5;

    // GTK Response Types
    public static final int GTK_RESPONSE_NONE = -1;
    public static final int GTK_RESPONSE_REJECT = -2;
    public static final int GTK_RESPONSE_ACCEPT = -3;
    public static final int GTK_RESPONSE_DELETE_EVENT = -4;
    public static final int GTK_RESPONSE_OK = -5;
    public static final int GTK_RESPONSE_CANCEL = -6;
    public static final int GTK_RESPONSE_CLOSE = -7;
    public static final int GTK_RESPONSE_YES = -8;
    public static final int GTK_RESPONSE_NO = -9;
    public static final int GTK_RESPONSE_APPLY = -10;
    public static final int GTK_RESPONSE_HELP = -11;

    // GTK File Chooser Actions
    public static final int GTK_FILE_CHOOSER_ACTION_OPEN = 0;
    public static final int GTK_FILE_CHOOSER_ACTION_SAVE = 1;
    public static final int GTK_FILE_CHOOSER_ACTION_SELECT_FOLDER = 2;
    public static final int GTK_FILE_CHOOSER_ACTION_CREATE_FOLDER = 3;
}

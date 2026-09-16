package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.gnome.glib.MainContext;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.ListBoxRow;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Popover;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Viewport;
import org.gnome.gtk.Window;
import org.javagi.base.Out;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathChooserButtonTest {
    @TempDir Path directory;

    @BeforeAll
    static void initGtk() throws Exception {
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        Gtk.init();
    }

    @Test
    void folderChooserProvidesGtk3StylePlacesAndSelectsThemFromAPopover() {
        MenuButton button = new MenuButton();
        button.setLabel("Select folder…");
        AtomicReference<Path> selected = new AtomicReference<>();
        List<FolderPlaces.Place> places = List.of(
                new FolderPlaces.Place("Home", "user-home-symbolic", Path.of("/home/test")),
                new FolderPlaces.Place("Downloads", "folder-download-symbolic",
                        Path.of("/home/test/Downloads")));
        PathChooserButton chooser = PathChooserButton.forFolder(
                button, null, "Select destination folder", null, selected::set,
                () -> places, history());

        assertTrue(button.hasCssClass("path-chooser"));
        Box content = assertInstanceOf(Box.class, button.getChild());
        Image leadingIcon = assertInstanceOf(Image.class, content.getFirstChild());
        Label label = assertInstanceOf(Label.class, leadingIcon.getNextSibling());
        assertEquals("folder-symbolic", leadingIcon.getIconName());
        assertEquals("Select folder…", label.getLabel());
        assertTrue(button.getAlwaysShowArrow());
        assertNull(chooser.getPath());

        Popover popover = button.getPopover();
        assertNotNull(popover);
        assertTrue(popover.hasCssClass("menu"));
        ListBox list = popoverList(button);
        assertEquals("Home", rowLabel(list.getRowAtIndex(0)));
        assertEquals("Downloads", rowLabel(list.getRowAtIndex(1)));
        assertEquals("Other…", rowLabel(list.getRowAtIndex(2)));
        assertNull(list.getRowAtIndex(3));

        list.emitRowActivated(list.getRowAtIndex(1));

        assertEquals(Path.of("/home/test/Downloads"), selected.get());
        assertEquals(Path.of("/home/test/Downloads"), chooser.getPath());
        assertEquals("Downloads", label.getLabel());
        assertEquals("/home/test/Downloads", button.getTooltipText());
        assertEquals(1.0, rowSelectionIcon(popoverList(button).getRowAtIndex(1)).getOpacity());

        chooser.clear();
        assertNull(chooser.getPath());
        assertEquals("Select folder…", label.getLabel());
    }

    @Test
    void folderPlacesKeepDisplayOrderWhileFilteringMissingAndDuplicatePaths() {
        List<FolderPlaces.Place> places = FolderPlaces.filterAndDeduplicate(List.of(
                new FolderPlaces.Place("Home", "user-home-symbolic", Path.of("/home/test")),
                new FolderPlaces.Place("Downloads", "folder-download-symbolic",
                        Path.of("/home/test/Downloads")),
                new FolderPlaces.Place("Same downloads", "folder-symbolic",
                        Path.of("/home/test/Downloads/../Downloads")),
                new FolderPlaces.Place("Missing", "folder-symbolic", Path.of("/missing"))),
                path -> !path.equals(Path.of("/missing")));

        assertEquals(List.of("Home", "Downloads"),
                places.stream().map(FolderPlaces.Place::label).toList());
    }

    @Test
    void folderPopoverMatchesTheAllocatedButtonWidthWhenOpened() throws InterruptedException {
        MenuButton button = new MenuButton();
        button.setLabel("Select folder…");
        button.setHexpand(true);
        PathChooserButton.forFolder(button, null, "Select destination folder", null,
                ignored -> { }, List::of, history());

        Window window = new Window();
        window.setDefaultSize(520, 80);
        window.setChild(button);
        try {
            window.present();
            awaitGtk(() -> button.getWidth() > 0, "GTK did not allocate the menu button");
            int buttonWidth = button.getWidth();

            button.popup();

            Popover popover = button.getPopover();
            assertNotNull(popover);
            awaitGtk(() -> popover.getWidth() == buttonWidth,
                    () -> "the popover width was " + popover.getWidth()
                            + "; expected button width " + buttonWidth);
            Out<Integer> width = new Out<>();
            popover.getSizeRequest(width, new Out<>());
            assertTrue(width.get() > 0, "the popup factory must set a live width request");
            assertEquals(buttonWidth, popover.getWidth());
        } finally {
            window.close();
            drainGtkEvents();
        }
    }

    @Test
    void fileChooserUsesAFileIconAndFilenameDisplay() {
        Button button = Button.withLabel("Torrent/Metalink file…");
        PathChooserButton chooser = PathChooserButton.forFile(
                button, null, "Select descriptor", Path.of("/tmp/example.torrent"), ignored -> { });

        Box content = assertInstanceOf(Box.class, button.getChild());
        Image leadingIcon = assertInstanceOf(Image.class, content.getFirstChild());
        Label label = assertInstanceOf(Label.class, leadingIcon.getNextSibling());
        assertEquals("document-open-symbolic", leadingIcon.getIconName());
        assertEquals("example.torrent", label.getLabel());
        assertEquals(Path.of("/tmp/example.torrent"), chooser.getPath());
    }

    @Test
    void remembersExplicitSelectionsAcrossChoosersWithoutReplacingDefaults() throws Exception {
        Path defaultFolder = Files.createDirectory(directory.resolve("default"));
        Path chosenFolder = Files.createDirectory(directory.resolve("chosen"));
        MenuButton firstButton = new MenuButton();
        AtomicReference<Path> selected = new AtomicReference<>();
        PathChooserButton first = PathChooserButton.forFolder(firstButton, null, "Select folder",
                defaultFolder, selected::set, () -> List.of(
                        new FolderPlaces.Place("Chosen", "folder-symbolic", chosenFolder)), history());
        assertNull(history().load(), "loading a default is not a user choice");
        first.setPath(chosenFolder);
        first.clear();
        assertNull(history().load(), "programmatic updates do not overwrite the last choice");
        listActivate(firstButton, 0);
        assertEquals(chosenFolder, selected.get());
        assertEquals(chosenFolder, history().load());

        MenuButton secondButton = new MenuButton();
        AtomicReference<Path> secondSelection = new AtomicReference<>();
        PathChooserButton second = PathChooserButton.forFolder(secondButton, null, "Select folder",
                defaultFolder, secondSelection::set, List::of, history());
        assertEquals(defaultFolder, second.getPath());
        assertNull(secondSelection.get());
        assertEquals("default", rowLabel(popoverList(secondButton).getRowAtIndex(0)));
        assertEquals("Last used: chosen", rowLabel(popoverList(secondButton).getRowAtIndex(1)));
        listActivate(secondButton, 1);
        assertEquals(chosenFolder, secondSelection.get());
        assertEquals(chosenFolder.toString(), secondButton.getTooltipText());

        ListBox list = popoverList(secondButton);
        assertEquals("Last used: chosen", rowLabel(list.getRowAtIndex(0)));
        assertEquals("Other…", rowLabel(list.getRowAtIndex(1)));
        assertNull(list.getRowAtIndex(2), "current and last used folder appear only once");
    }

    @Test
    void nativeDialogSelectionIsRememberedAndMissingShortcutsAreIgnored() throws Exception {
        Path chosenFolder = Files.createDirectory(directory.resolve("browse selection"));
        MenuButton button = new MenuButton();
        AtomicReference<Path> selected = new AtomicReference<>();
        PathChooserButton chooser = PathChooserButton.forFolder(button, null, "Select folder", null,
                selected::set, List::of, history());
        var accept = PathChooserButton.class.getDeclaredMethod("accept", org.gnome.gio.File.class);
        accept.setAccessible(true);
        accept.invoke(chooser, org.gnome.gio.File.forPath(chosenFolder.toString()));
        assertEquals(chosenFolder, selected.get());
        assertEquals(chosenFolder, history().load());
        accept.invoke(chooser, new Object[]{null});
        assertEquals(chosenFolder, history().load(), "cancellation leaves the last choice intact");

        Files.delete(chosenFolder);
        chooser.clear();
        assertEquals("Other…", rowLabel(popoverList(button).getRowAtIndex(0)));
        assertNull(popoverList(button).getRowAtIndex(1));
    }

    @Test
    void lastUsedFolderRefreshesWhenAnExistingChooserIsOpened() throws Exception {
        MenuButton button = new MenuButton();
        PathChooserButton.forFolder(button, null, "Select folder", null,
                ignored -> { }, List::of, history());
        Path first = Files.createDirectory(directory.resolve("first"));
        Path latest = Files.createDirectory(directory.resolve("latest"));
        history().remember(first);
        history().remember(latest);
        Window window = new Window();
        window.setChild(button);
        try {
            window.present();
            drainGtkEvents();
            button.popup();
            assertEquals("Last used: latest", rowLabel(popoverList(button).getRowAtIndex(0)));
            assertEquals("Other…", rowLabel(popoverList(button).getRowAtIndex(1)));
            assertNull(popoverList(button).getRowAtIndex(2));
        } finally {
            window.destroy();
            drainGtkEvents();
        }
    }

    @Test
    void stillNotifiesTheCallerWhenHistoryCannotBeWritten() throws Exception {
        Path blocked = Files.createFile(directory.resolve("blocked"));
        MenuButton button = new MenuButton();
        AtomicReference<Path> selected = new AtomicReference<>();
        PathChooserButton chooser = PathChooserButton.forFolder(button, null, "Select folder", null,
                selected::set, () -> List.of(new FolderPlaces.Place("Folder", "folder-symbolic", directory)),
                new LastChosenFolder(blocked.resolve("last-chosen-folder")));
        listActivate(button, 0);
        assertEquals(directory, selected.get());
        assertEquals(directory, chooser.getPath());
        assertFalse(Files.exists(blocked.resolve("last-chosen-folder")));
    }

    private LastChosenFolder history() {
        return new LastChosenFolder(directory.resolve("state/last-chosen-folder"));
    }

    private static void listActivate(MenuButton button, int index) {
        ListBox list = popoverList(button);
        list.emitRowActivated(list.getRowAtIndex(index));
    }

    private static String rowLabel(ListBoxRow row) {
        assertNotNull(row);
        Box content = assertInstanceOf(Box.class, row.getChild());
        Image icon = assertInstanceOf(Image.class, content.getFirstChild());
        Label label = assertInstanceOf(Label.class, icon.getNextSibling());
        return label.getLabel();
    }

    private static Image rowSelectionIcon(ListBoxRow row) {
        Box content = assertInstanceOf(Box.class, row.getChild());
        Image icon = assertInstanceOf(Image.class, content.getFirstChild());
        Label label = assertInstanceOf(Label.class, icon.getNextSibling());
        return assertInstanceOf(Image.class, label.getNextSibling());
    }

    private static ListBox popoverList(MenuButton button) {
        Popover popover = button.getPopover();
        assertNotNull(popover);
        ScrolledWindow scroller = assertInstanceOf(ScrolledWindow.class, popover.getChild());
        Viewport viewport = assertInstanceOf(Viewport.class, scroller.getChild());
        return assertInstanceOf(ListBox.class, viewport.getChild());
    }

    private static void drainGtkEvents() {
        MainContext context = MainContext.default_();
        while (context.pending()) {
            context.iteration(false);
        }
    }

    private static void awaitGtk(BooleanSupplier condition, String failureMessage)
            throws InterruptedException {
        awaitGtk(condition, () -> failureMessage);
    }

    private static void awaitGtk(BooleanSupplier condition, java.util.function.Supplier<String> failureMessage)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            drainGtkEvents();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(failureMessage.get());
    }
}

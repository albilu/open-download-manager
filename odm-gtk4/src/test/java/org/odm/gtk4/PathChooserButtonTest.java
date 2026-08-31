package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class PathChooserButtonTest {

    @BeforeAll
    static void initGtk() {
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
                () -> places);

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
                ignored -> { }, List::of);

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
                    "the popover did not settle at the button width");
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
        for (int attempt = 0; attempt < 100; attempt++) {
            drainGtkEvents();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(failureMessage);
    }
}

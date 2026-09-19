package org.odm.gtk4;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.net.URI;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.gnome.gdk.Display;
import org.gnome.glib.MainContext;
import org.gnome.gobject.GObjects;
import org.gnome.graphene.Rect;
import org.gnome.gtk.*;
import org.javagi.base.Out;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.schedule.ScheduleManager;
import org.tor.TorService;

/** Real pointer gestures under Xvfb, rather than direct moveColumnAfter calls. */
@Timeout(30)
class TreeViewColumnDragGtkTest {
    private static XPointer pointer;

    @BeforeAll
    static void initGtk() throws Exception {
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        // Keep Java-GI cleanup on the GTK thread for this whole test JVM.
        assertTrue(MainContext.default_().acquire());
        Gtk.init();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                GObjects.typeNameFromInstance(Display.getDefault()).contains("X11"),
                "Pointer tests require X11/Xvfb");
        pointer = new XPointer();
    }

    @AfterAll
    static void closePointer() {
        if (pointer != null) { pointer.close(); }
    }

    @Test
    void downloadHeaderDragPreservesSortingSelectionAndVisibilityActions() throws Exception {
        var first = new Download(URI.create("https://example.test/first.bin"));
        var second = new Download(URI.create("https://example.test/second.bin"));
        first.setDownloaded(100);
        second.setDownloaded(200);
        DownloadManager manager = mock(DownloadManager.class);
        when(manager.getGlobalSettings()).thenReturn(new GlobalSettings());
        when(manager.getDownloads(anyInt(), anyInt())).thenReturn(List.of(first, second));
        when(manager.getDownloadCount()).thenReturn(2);
        var main = new MainWindow(null, manager, mock(TorService.class), mock(ScheduleManager.class));
        try {
            var field = MainWindow.class.getDeclaredField("uiBuilder");
            field.setAccessible(true);
            var builder = (GtkBuilder) field.get(main);
            Window window = Widgets.require(builder, "main_window", Window.class);
            TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
            ListStore store = Widgets.require(builder, "download_store", ListStore.class);
            TreeViewColumn completed = Widgets.require(builder, "complete_column", TreeViewColumn.class);
            TreeViewColumn name = Widgets.require(builder, "name_column", TreeViewColumn.class);
            TreeViewColumn ratio = Widgets.require(builder, "ratio_column", TreeViewColumn.class);
            show(window);
            await(() -> store.iterNChildren(null) == 2);
            tree.getSelection().selectAll();
            pointer.click(at(window, completed.getButton(), 0.5, 0.5));
            assertEquals(completed.getSortColumnId(), sortColumn(store));
            var sortOrder = completed.getSortOrder();
            var original = List.copyOf(tree.getColumns());

            drag(window, completed, name, 0.25);
            await(() -> tree.getColumns().indexOf(completed) < tree.getColumns().indexOf(name));
            assertEquals(completed.getSortColumnId(), sortColumn(store));
            assertEquals(sortOrder, completed.getSortOrder(), "dragging must not toggle sorting");
            assertEquals(2, tree.getSelection().countSelectedRows());
            assertSame(store, tree.getModel());
            assertFalse(ratio.getVisible());

            int actionIndex = MainWindow.downloadColumnLabels().indexOf("Completed");
            assertTrue(window.activateActionVariant("win.col-" + actionIndex, null));
            assertFalse(completed.getVisible(), "visibility must follow the moved column");
            assertTrue(name.getVisible());
            window.activateActionVariant("win.col-" + actionIndex, null);
            pump(100);
            drag(window, completed, name, 0.75);
            await(() -> original.equals(List.copyOf(tree.getColumns())));

            // An invalid drop in the body must not move a column or alter rows.
            pointer.drag(at(window, completed.getButton(), 0.5, 0.5), at(window, tree, 0.4, 0.8));
            assertEquals(original, List.copyOf(tree.getColumns()));
            assertEquals(2, tree.getSelection().countSelectedRows());
            pointer.click(at(window, completed.getButton(), 0.5, 0.5));
            assertNotEquals(sortOrder, completed.getSortOrder(), "ordinary header clicks must still sort");
        } finally {
            main.dispose();
            pump(100);
        }
    }

    @Test
    void fileHeadersDragToFirstAndLastAndStillResize() throws Exception {
        GtkBuilder builder = UiLoader.load("/ui/new-download.ui");
        Window window = Widgets.require(builder, "new_download_dialog", Window.class);
        try {
            Widgets.require(builder, "options_notebook", Notebook.class).setCurrentPage(1);
            TreeView tree = Widgets.require(builder, "files_treeview", TreeView.class);
            TreeViewColumn priority = Widgets.require(builder, "new_files_priority_column", TreeViewColumn.class);
            TreeViewColumn name = Widgets.require(builder, "new_files_name_column", TreeViewColumn.class);
            TreeViewColumn size = Widgets.require(builder, "new_files_size_column", TreeViewColumn.class);
            show(window);
            var original = List.copyOf(tree.getColumns());
            drag(window, priority, original.getFirst(), 0.25);
            await(() -> tree.getColumns().getFirst() == priority);
            drag(window, priority, size, 0.75);
            await(() -> original.equals(List.copyOf(tree.getColumns())));

            int width = name.getWidth();
            Point edge = at(window, name.getButton(), 1.0, 0.5);
            pointer.drag(new Point(edge.x() - 1, edge.y()), new Point(edge.x() - 51, edge.y()));
            await(() -> name.getWidth() < width - 30);
            assertEquals(original, List.copyOf(tree.getColumns()), "resizing must not reorder columns");
        } finally {
            window.destroy();
            pump(100);
            java.lang.ref.Reference.reachabilityFence(builder);
        }
    }

    private static int sortColumn(ListStore store) {
        Out<Integer> column = new Out<>();
        ((TreeSortable) store).getSortColumnId(column, new Out<SortType>());
        return column.get();
    }

    private static void show(Window window) throws Exception {
        window.setDecorated(false);
        window.setDefaultSize(1100, 700);
        window.present();
        await(() -> window.getMapped() && window.getWidth() > 0);
        pump(150);
    }

    private static void drag(Window window, TreeViewColumn from, TreeViewColumn to, double fraction) throws Exception {
        pointer.drag(at(window, from.getButton(), 0.5, 0.5), at(window, to.getButton(), fraction, 0.5));
    }

    private static Point at(Window window, Widget widget, double x, double y) {
        Rect bounds = new Rect();
        assertTrue(widget.computeBounds(window, bounds));
        assertTrue(bounds.getWidth() > 0 && bounds.getHeight() > 0);
        Point origin = pointer.origin(window);
        return new Point(origin.x() + (int) (bounds.getX() + bounds.getWidth() * x),
                origin.y() + (int) (bounds.getY() + bounds.getHeight() * y));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { pump(20); }
        assertTrue(condition.getAsBoolean(), "GTK did not apply the pointer gesture");
    }

    private static void pump(long millis) throws Exception {
        long deadline = System.nanoTime() + millis * 1_000_000;
        do {
            while (MainContext.default_().iteration(false)) { }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
    }

    private record Point(int x, int y) { }

    /** XTest delivers actual input through GTK's controllers. AWT Robot cannot
     * run in this JVM because AWT loads GTK 3 alongside Java-GI's GTK 4. */
    private static final class XPointer implements AutoCloseable {
        private final Arena arena = Arena.ofConfined();
        private final SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", arena);
        private final SymbolLookup xtst = SymbolLookup.libraryLookup("libXtst.so.6", arena);
        private final SymbolLookup gtk = SymbolLookup.libraryLookup("libgtk-4.so.1", arena);
        private final MethodHandle open = function(x11, "XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS));
        private final MethodHandle close = function(x11, "XCloseDisplay", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private final MethodHandle flush = function(x11, "XSync", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        private final MethodHandle root = function(x11, "XDefaultRootWindow", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        private final MethodHandle translate = function(x11, "XTranslateCoordinates",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        private final MethodHandle xid = function(gtk, "gdk_x11_surface_get_xid", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
        private final MethodHandle move = function(xtst, "XTestFakeMotionEvent",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
        private final MethodHandle button = function(xtst, "XTestFakeButtonEvent",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));
        private final MemorySegment display = (MemorySegment) call(open, MemorySegment.NULL);

        XPointer() { assertNotEquals(MemorySegment.NULL, display, "Could not connect to Xvfb"); }

        Point origin(Window window) {
            try (Arena values = Arena.ofConfined()) {
                var x = values.allocate(JAVA_INT);
                var y = values.allocate(JAVA_INT);
                var child = values.allocate(JAVA_LONG);
                assertNotEquals(0, call(translate, display, call(xid, window.getSurface().handle()),
                        call(root, display), 0, 0, x, y, child));
                return new Point(x.get(JAVA_INT, 0), y.get(JAVA_INT, 0));
            }
        }

        void click(Point point) throws Exception {
            move(point);
            button(true);
            button(false);
            pump(100);
        }

        void drag(Point start, Point end) throws Exception {
            move(start);
            button(true);
            try {
                for (int step = 1; step <= 24; step++) {
                    move(new Point(start.x() + (end.x() - start.x()) * step / 24,
                            start.y() + (end.y() - start.y()) * step / 24));
                }
                pump(100);
            } finally {
                button(false);
            }
            pump(150);
        }

        private void move(Point point) throws Exception {
            assertNotEquals(0, call(move, display, -1, point.x(), point.y(), 0L));
            call(flush, display, 0);
            pump(20);
        }

        private void button(boolean pressed) throws Exception {
            assertNotEquals(0, call(button, display, 1, pressed ? 1 : 0, 0L));
            call(flush, display, 0);
            pump(30);
        }

        @Override public void close() {
            call(close, display);
            arena.close();
        }

        private static MethodHandle function(SymbolLookup library, String name, FunctionDescriptor signature) {
            return Linker.nativeLinker().downcallHandle(library.find(name).orElseThrow(), signature);
        }

        private static Object call(MethodHandle method, Object... args) {
            try { return method.invokeWithArguments(args); }
            catch (Throwable error) { throw new AssertionError(error); }
        }
    }
}

package org.odm.gtk4;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gnome.gio.File;
import org.gnome.glib.GLib;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.ListBoxRow;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.Popover;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.Widget;
import org.gnome.gtk.Window;
import org.gnome.pango.EllipsizeMode;
import org.javagi.base.Out;

/**
 * GTK4 replacement for the removed GTK3 {@code GtkFileChooserButton}.
 *
 * <p>File selection keeps a regular {@link Button} backed by
 * {@link FileDialog}. Folder selection uses a {@link MenuButton} with the
 * familiar GTK3 places popup and an “Other…” entry backed by the same modern
 * dialog API. The full selected path is retained separately and exposed as a
 * tooltip, so application state never depends on a truncated display label.</p>
 */
final class PathChooserButton {

    private static final Logger LOGGER = LoggerFactory.getLogger(PathChooserButton.class);

    private enum SelectionKind {
        FILE("document-open-symbolic", "Select file…"),
        FOLDER("folder-symbolic", "Select folder…");

        private final String iconName;
        private final String defaultPlaceholder;

        SelectionKind(String iconName, String defaultPlaceholder) {
            this.iconName = iconName;
            this.defaultPlaceholder = defaultPlaceholder;
        }
    }

    private final Widget control;
    private final Button fileButton;
    private final MenuButton folderButton;
    private final Window parent;
    private final String title;
    private final String placeholder;
    private final SelectionKind kind;
    private final Consumer<Path> selectionHandler;
    private final Supplier<List<FolderPlaces.Place>> folderPlacesSupplier;
    private final Label pathLabel;
    private Path path;

    static PathChooserButton forFile(Button button, Window parent, String title,
            Path initialPath, Consumer<Path> selectionHandler) {
        return new PathChooserButton(button, null, parent, title, initialPath,
                SelectionKind.FILE, selectionHandler, List::of);
    }

    static PathChooserButton forFolder(MenuButton button, Window parent, String title,
            Path initialPath, Consumer<Path> selectionHandler) {
        return forFolder(button, parent, title, initialPath, selectionHandler,
                FolderPlaces::discover);
    }

    static PathChooserButton forFolder(MenuButton button, Window parent, String title,
            Path initialPath, Consumer<Path> selectionHandler,
            Supplier<List<FolderPlaces.Place>> folderPlacesSupplier) {
        return new PathChooserButton(null, button, parent, title, initialPath,
                SelectionKind.FOLDER, selectionHandler, folderPlacesSupplier);
    }

    private PathChooserButton(Button fileButton, MenuButton folderButton, Window parent,
            String title, Path initialPath, SelectionKind kind,
            Consumer<Path> selectionHandler,
            Supplier<List<FolderPlaces.Place>> folderPlacesSupplier) {
        this.fileButton = fileButton;
        this.folderButton = folderButton;
        this.control = kind == SelectionKind.FILE
                ? Objects.requireNonNull(fileButton, "fileButton")
                : Objects.requireNonNull(folderButton, "folderButton");
        this.parent = parent;
        this.title = Objects.requireNonNull(title, "title");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.selectionHandler = Objects.requireNonNull(selectionHandler, "selectionHandler");
        this.folderPlacesSupplier = Objects.requireNonNull(folderPlacesSupplier,
                "folderPlacesSupplier");
        String currentLabel = fileButton == null ? folderButton.getLabel() : fileButton.getLabel();
        this.placeholder = currentLabel == null || currentLabel.isBlank()
                ? kind.defaultPlaceholder : currentLabel;

        Box content = new Box(Orientation.HORIZONTAL, 6);
        content.setHexpand(true);

        Image leadingIcon = Image.fromIconName(kind.iconName);
        leadingIcon.setValign(Align.CENTER);
        content.append(leadingIcon);

        this.pathLabel = new Label(placeholder);
        pathLabel.setEllipsize(EllipsizeMode.END);
        pathLabel.setSingleLineMode(true);
        pathLabel.setMaxWidthChars(48);
        pathLabel.setHexpand(true);
        pathLabel.setXalign(0.0f);
        content.append(pathLabel);

        if (kind == SelectionKind.FILE) {
            Image disclosureIcon = Image.fromIconName("pan-down-symbolic");
            disclosureIcon.setValign(Align.CENTER);
            content.append(disclosureIcon);
        }

        if (fileButton != null) {
            fileButton.setChild(content);
            fileButton.setCanShrink(true);
            fileButton.onClicked(this::openDialog);
        } else {
            folderButton.setChild(content);
            folderButton.setCanShrink(true);
            folderButton.setAlwaysShowArrow(true);
            folderButton.setCreatePopupFunc(ignored -> rebuildFolderPopover());
        }
        control.addCssClass("path-chooser");
        setPath(initialPath);
    }

    Path getPath() {
        return path;
    }

    void setPath(Path path) {
        this.path = path;
        if (path == null) {
            pathLabel.setLabel(placeholder);
            control.setTooltipText(null);
        } else {
            Path filename = path.getFileName();
            pathLabel.setLabel(filename == null ? path.toString() : filename.toString());
            control.setTooltipText(path.toString());
        }
        if (kind == SelectionKind.FOLDER) {
            rebuildFolderPopover();
        }
    }

    void clear() {
        setPath(null);
    }

    private void rebuildFolderPopover() {
        List<FolderPlaces.Place> places = currentFolderPlaces();
        ListBox list = new ListBox();
        list.setActivateOnSingleClick(true);
        list.setSelectionMode(SelectionMode.NONE);
        list.setShowSeparators(true);

        for (FolderPlaces.Place place : places) {
            ListBoxRow row = new ListBoxRow();
            row.setChild(placeRow(place.label(), place.iconName(), place.path(),
                    samePath(path, place.path())));
            row.setTooltipText(place.path().toString());
            list.append(row);
        }

        ListBoxRow other = new ListBoxRow();
        other.setChild(placeRow("Other…", "document-open-symbolic", null, false));
        list.append(other);
        list.onRowActivated(row -> {
            int index = row.getIndex();
            folderButton.popdown();
            if (index >= 0 && index < places.size()) {
                Path selectedPath = places.get(index).path();
                setPath(selectedPath);
                selectionHandler.accept(selectedPath);
            } else if (index == places.size()) {
                openDialog();
            }
        });

        ScrolledWindow scroller = new ScrolledWindow();
        scroller.setPolicy(PolicyType.NEVER, PolicyType.AUTOMATIC);
        scroller.setMaxContentHeight(420);
        scroller.setPropagateNaturalHeight(true);
        scroller.setPropagateNaturalWidth(false);
        scroller.setChild(list);

        Popover popover = new Popover();
        popover.setHasArrow(false);
        popover.setHalign(Align.START);
        popover.addCssClass("menu");
        popover.setChild(scroller);
        int buttonWidth = folderButton.getWidth();
        if (buttonWidth > 0) {
            popover.setSizeRequest(buttonWidth, -1);
            // Popover themes can add horizontal chrome only once mapped. Correct the
            // rendered allocation on the next GTK turn so its outer edges match the button.
            popover.onMap(() -> GLib.idleAddOnce(() -> correctMappedPopoverWidth(popover)));
        }
        folderButton.setPopover(popover);
    }

    private void correctMappedPopoverWidth(Popover popover) {
        int buttonWidth = folderButton.getWidth();
        int renderedWidth = popover.getWidth();
        if (buttonWidth <= 0 || renderedWidth <= 0) {
            return;
        }

        Out<Integer> requestedWidth = new Out<>();
        popover.getSizeRequest(requestedWidth, new Out<>());
        int request = requestedWidth.get() == null || requestedWidth.get() < 1
                ? buttonWidth : requestedWidth.get();
        int decorationWidth = Math.max(0, renderedWidth - request);
        int correctedRequest = Math.max(1, buttonWidth - decorationWidth);
        if (correctedRequest != request) {
            popover.setSizeRequest(correctedRequest, -1);
        }
    }

    private List<FolderPlaces.Place> currentFolderPlaces() {
        List<FolderPlaces.Place> places;
        try {
            places = new ArrayList<>(folderPlacesSupplier.get());
        } catch (RuntimeException e) {
            LOGGER.debug("Folder-place discovery failed", e);
            places = new ArrayList<>();
        }
        if (path != null && places.stream().noneMatch(place -> samePath(path, place.path()))) {
            Path filename = path.getFileName();
            places.add(0, new FolderPlaces.Place(
                    filename == null ? path.toString() : filename.toString(),
                    "folder-symbolic", path));
        }
        return List.copyOf(places);
    }

    private Box placeRow(String label, String iconName, Path rowPath, boolean selected) {
        Box content = new Box(Orientation.HORIZONTAL, 8);
        content.setMarginStart(10);
        content.setMarginEnd(10);
        content.setMarginTop(7);
        content.setMarginBottom(7);

        Image icon = Image.fromIconName(iconName);
        icon.setValign(Align.CENTER);
        content.append(icon);

        Label rowLabel = new Label(label);
        rowLabel.setXalign(0.0f);
        rowLabel.setHexpand(true);
        rowLabel.setEllipsize(EllipsizeMode.END);
        content.append(rowLabel);

        Image selectedIcon = Image.fromIconName("object-select-symbolic");
        selectedIcon.setValign(Align.CENTER);
        selectedIcon.setOpacity(selected ? 1.0 : 0.0);
        content.append(selectedIcon);
        if (rowPath != null) {
            content.setTooltipText(rowPath.toString());
        }
        return content;
    }

    private static boolean samePath(Path first, Path second) {
        return first != null && second != null
                && first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
    }

    private void openDialog() {
        FileDialog fileDialog = new FileDialog();
        DialogSupport.configureIndependent(fileDialog);
        fileDialog.setTitle(title);
        if (path != null) {
            File initial = File.forPath(path.toString());
            if (kind == SelectionKind.FOLDER) {
                fileDialog.setInitialFolder(initial);
            } else {
                fileDialog.setInitialFile(initial);
            }
        }
        if (kind == SelectionKind.FOLDER) {
            fileDialog.selectFolder(parent, null, result -> {
                try {
                    accept(fileDialog.selectFolderFinish(result));
                } catch (Exception e) {
                    LOGGER.debug(title + " selection cancelled or failed", e);
                }
            });
        } else {
            fileDialog.open(parent, null, result -> {
                try {
                    accept(fileDialog.openFinish(result));
                } catch (Exception e) {
                    LOGGER.debug(title + " selection cancelled or failed", e);
                }
            });
        }
    }

    private void accept(File selected) {
        if (selected == null || selected.getPath() == null) {
            return;
        }
        Path selectedPath = Path.of(selected.getPath().toString());
        setPath(selectedPath);
        selectionHandler.accept(selectedPath);
    }
}

package org.odm.gtk4;

import java.lang.ref.WeakReference;
import org.gnome.gdk.ContentProvider;
import org.gnome.gdk.DragAction;
import org.gnome.gobject.Value;
import org.gnome.gtk.DragSource;
import org.gnome.gtk.DropTarget;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.TextDirection;
import org.gnome.gtk.TreeView;
import org.gnome.gtk.TreeViewColumn;
import org.gnome.gtk.WidgetPaintable;

/** Header drag-and-drop shared by the download, file and import tables. */
final class TreeViewColumnReordering {
    private TreeViewColumnReordering() { }

    static void install(TreeView tree) {
        for (var column : tree.getColumns()) {
            // GtkTreeView's legacy header gesture cancels its parent's drag
            // gesture in GTK 4, leaving the drag stuck. Use GTK's public DnD
            // controllers instead; native column resizing stays in place.
            column.setReorderable(false);
            var reference = new WeakReference<>(column);
            var source = new DragSource();
            var sourceReference = new WeakReference<>(source);
            source.setActions(DragAction.MOVE);
            source.setPropagationPhase(PropagationPhase.CAPTURE);
            source.onPrepare((x, y) -> {
                TreeViewColumn current = reference.get();
                DragSource drag = sourceReference.get();
                if (current == null || drag == null) { return null; }
                // Snapshot the header so the drag icon does not retain a live
                // paintable pointing back to the button that owns this source.
                drag.setIcon(new WidgetPaintable(current.getButton()).getCurrentImage(), (int) x, (int) y);
                Value value = new Value().init(TreeViewColumn.getType());
                try {
                    value.setObject(current);
                    return ContentProvider.forValue(value);
                } finally {
                    value.unset();
                }
            });
            column.getButton().addController(source);

            var target = new DropTarget(TreeViewColumn.getType(), DragAction.MOVE);
            target.onDrop((value, x, y) -> {
                TreeViewColumn destination = reference.get();
                if (destination == null || value == null
                        || !(value.getObject() instanceof TreeViewColumn moved)
                        || !(destination.getTreeView() instanceof TreeView view)
                        || moved.getTreeView() != view) {
                    return false;
                }
                if (moved == destination) { return true; }
                boolean after = x >= destination.getButton().getWidth() / 2.0;
                if (view.getDirection() == TextDirection.RTL) { after = !after; }
                TreeViewColumn previous = null;
                if (after) {
                    previous = destination;
                } else {
                    for (var candidate : view.getColumns()) {
                        if (candidate == destination) { break; }
                        if (candidate != moved) { previous = candidate; }
                    }
                }
                view.moveColumnAfter(moved, previous);
                return true;
            });
            column.getButton().addController(target);
        }
    }
}

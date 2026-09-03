package org.odm.gtk4;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeRowReference;
import org.gnome.gtk.TreeStore;
import org.gnome.gtk.TreeView;
import org.javagi.interop.MemoryCleaner;

/** Shared hierarchical file model used by New Download and download details. */
final class FileTreeSupport {

    static final int SELECTED_COLUMN = 0;
    static final int NAME_COLUMN = 1;
    static final int SIZE_TEXT_COLUMN = 2;
    static final int PROGRESS_COLUMN = 3;
    static final int PRIORITY_TEXT_COLUMN = 4;
    static final int INDEX_COLUMN = 5;
    static final int PROGRESS_TEXT_COLUMN = 6;
    static final int SIZE_SORT_COLUMN = 7;
    static final int PROGRESS_SORT_COLUMN = 8;
    static final int PATH_COLUMN = 9;
    static final int FOLDER_COLUMN = 10;
    static final int INCONSISTENT_COLUMN = 11;
    static final int PRIORITY_SORT_COLUMN = 12;
    static final int ICON_NAME_COLUMN = 13;
    static final int COLUMN_COUNT = 14;

    static final String PRIORITY_LOW = "Low";
    static final String PRIORITY_NORMAL = "Normal";
    static final String PRIORITY_HIGH = "High";

    record Entry(boolean selected, String path, long length, long completedLength,
            int index, String priority) {

        Entry {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("File path cannot be blank");
            }
            length = Math.max(0, length);
            completedLength = Math.max(0, Math.min(length, completedLength));
            priority = normalizePriority(priority);
        }
    }

    private static final class Node {

        private final String key;
        private final String name;
        private final String displayPath;
        private final Entry entry;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private long length;
        private long completed;
        private int leafCount;
        private int selectedCount;
        private String priority;

        private Node(String key, String name, String displayPath, Entry entry) {
            this.key = key;
            this.name = name;
            this.displayPath = displayPath;
            this.entry = entry;
        }

        private boolean folder() {
            return entry == null;
        }

        private void aggregate() {
            if (!folder()) {
                length = entry.length();
                completed = entry.completedLength();
                leafCount = 1;
                selectedCount = entry.selected() ? 1 : 0;
                priority = entry.priority();
                return;
            }
            length = 0;
            completed = 0;
            leafCount = 0;
            selectedCount = 0;
            priority = null;
            for (Node child : children.values()) {
                child.aggregate();
                length += child.length;
                completed += child.completed;
                leafCount += child.leafCount;
                selectedCount += child.selectedCount;
                if (priority == null) {
                    priority = child.priority;
                } else if (!priority.equals(child.priority)) {
                    priority = "Mixed";
                }
            }
            if (priority == null) {
                priority = "—";
            }
        }
    }

    private FileTreeSupport() {
    }

    /** Reconciles the tree, retaining GTK row identities while its structure is unchanged. */
    static boolean reconcile(TreeStore store, Map<String, TreeRowReference> references,
            List<Entry> entries, Path displayBase) {
        Node root = build(entries, displayBase);
        LinkedHashMap<String, Node> rows = new LinkedHashMap<>();
        flatten(root, rows);

        boolean sameStructure = references.keySet().equals(rows.keySet());
        if (sameStructure) {
            for (Map.Entry<String, Node> row : rows.entrySet()) {
                TreeRowReference reference = references.get(row.getKey());
                TreePath path = reference == null ? null : reference.getPath();
                if (path == null) {
                    sameStructure = false;
                    break;
                }
                try {
                    TreeIter iter = new TreeIter();
                    if (!store.getIter(iter, path)) {
                        sameStructure = false;
                        break;
                    }
                    write(store, iter, row.getValue());
                } finally {
                    MemoryCleaner.free(path.handle());
                }
            }
        }
        if (sameStructure) {
            return false;
        }

        clear(store, references);
        appendChildren(store, null, root, references);
        return true;
    }

    static void clear(TreeStore store, Map<String, TreeRowReference> references) {
        freeReferences(references);
        store.clear();
    }

    static void freeReferences(Map<String, TreeRowReference> references) {
        for (TreeRowReference reference : references.values()) {
            MemoryCleaner.free(reference.handle());
        }
        references.clear();
    }

    /** Expands only top-level directories, leaving deeper folders navigable. */
    static void expandTopLevel(TreeView view, TreeStore store) {
        TreeIter iter = new TreeIter();
        if (!store.getIterFirst(iter)) {
            return;
        }
        do {
            if (TreeStoreCells.getBoolean(store, iter, FOLDER_COLUMN)) {
                TreePath path = store.getPath(iter);
                try {
                    view.expandRow(path, false);
                } finally {
                    MemoryCleaner.free(path.handle());
                }
            }
        } while (store.iterNext(iter));
    }

    /** Toggles a leaf or every descendant of a folder and refreshes tri-state parents. */
    static boolean toggleSelection(TreeStore store, String pathText) {
        TreeIter iter = new TreeIter();
        if (!store.getIterFromString(iter, pathText)) {
            return false;
        }
        boolean selected = TreeStoreCells.getBoolean(store, iter, SELECTED_COLUMN);
        boolean inconsistent = TreeStoreCells.getBoolean(store, iter, INCONSISTENT_COLUMN);
        setSelectionRecursively(store, iter, inconsistent || !selected);
        updateAncestors(store, iter);
        return true;
    }

    static boolean setPriority(TreeStore store, String pathText, String priority) {
        TreeIter iter = new TreeIter();
        if (!store.getIterFromString(iter, pathText)) {
            return false;
        }
        setPriorityRecursively(store, iter, normalizePriority(priority));
        updateAncestors(store, iter);
        return true;
    }

    static List<Integer> selectedIndexes(TreeStore store) {
        List<Integer> result = new ArrayList<>();
        visitLeaves(store, (iter) -> {
            if (TreeStoreCells.getBoolean(store, iter, SELECTED_COLUMN)) {
                result.add(TreeStoreCells.getInt(store, iter, INDEX_COLUMN));
            }
        });
        return result;
    }

    static List<Integer> allIndexes(TreeStore store) {
        List<Integer> result = new ArrayList<>();
        visitLeaves(store, iter -> result.add(
                TreeStoreCells.getInt(store, iter, INDEX_COLUMN)));
        return result;
    }

    static void selectAll(TreeStore store, boolean selected) {
        TreeIter iter = new TreeIter();
        if (store.getIterFirst(iter)) {
            do {
                setSelectionRecursively(store, iter, selected);
            } while (store.iterNext(iter));
        }
    }

    static Map<Integer, String> priorities(TreeStore store) {
        Map<Integer, String> result = new LinkedHashMap<>();
        visitLeaves(store, iter -> result.put(
                TreeStoreCells.getInt(store, iter, INDEX_COLUMN),
                normalizePriority(TreeStoreCells.getString(store, iter, PRIORITY_TEXT_COLUMN))));
        return result;
    }

    static String normalizePriority(String priority) {
        if (priority == null) {
            return PRIORITY_NORMAL;
        }
        return switch (priority.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "low" -> PRIORITY_LOW;
            case "high" -> PRIORITY_HIGH;
            default -> PRIORITY_NORMAL;
        };
    }

    static int prioritySortKey(String priority) {
        return switch (normalizePriority(priority)) {
            case PRIORITY_LOW -> 1;
            case PRIORITY_HIGH -> 3;
            default -> 2;
        };
    }

    private static Node build(List<Entry> entries, Path displayBase) {
        Node root = new Node("ROOT", "", "", null);
        int duplicate = 0;
        for (Entry entry : entries == null ? List.<Entry>of() : entries) {
            List<String> components = displayComponents(entry.path(), displayBase);
            Node parent = root;
            StringBuilder displayPath = new StringBuilder();
            for (int i = 0; i < components.size() - 1; i++) {
                String component = components.get(i);
                if (!displayPath.isEmpty()) {
                    displayPath.append('/');
                }
                displayPath.append(component);
                String childKey = "D:" + displayPath;
                Node existing = parent.children.get(childKey);
                if (existing == null) {
                    existing = new Node(childKey, component, displayPath.toString(), null);
                    parent.children.put(childKey, existing);
                }
                parent = existing;
            }
            String name = components.getLast();
            if (!displayPath.isEmpty()) {
                displayPath.append('/');
            }
            displayPath.append(name);
            String key = "F:" + entry.index() + ":" + entry.path();
            while (parent.children.containsKey(key)) {
                key = "F:" + entry.index() + ":" + entry.path() + "#" + (++duplicate);
            }
            parent.children.put(key, new Node(key, name, displayPath.toString(), entry));
        }
        root.aggregate();
        return root;
    }

    private static List<String> displayComponents(String rawPath, Path displayBase) {
        String path = rawPath;
        if (displayBase != null) {
            try {
                Path candidate = Path.of(rawPath);
                Path base = displayBase.toAbsolutePath().normalize();
                if (candidate.isAbsolute() && candidate.normalize().startsWith(base)) {
                    path = base.relativize(candidate.normalize()).toString();
                }
            } catch (RuntimeException ignored) {
                // aria2 may return platform-foreign separators; split textually below.
            }
        }
        List<String> components = new ArrayList<>();
        for (String component : path.replace('\\', '/').split("/+")) {
            if (!component.isBlank() && !".".equals(component)) {
                components.add(component);
            }
        }
        if (components.isEmpty()) {
            components.add(DetailTabsPresenter.fileName(rawPath));
        }
        return components;
    }

    private static void flatten(Node parent, LinkedHashMap<String, Node> rows) {
        for (Node child : parent.children.values()) {
            rows.put(child.key, child);
            flatten(child, rows);
        }
    }

    private static void appendChildren(TreeStore store, TreeIter parent, Node node,
            Map<String, TreeRowReference> references) {
        for (Node child : node.children.values()) {
            TreeIter iter = new TreeIter();
            store.append(iter, parent);
            write(store, iter, child);
            TreePath path = store.getPath(iter);
            try {
                references.put(child.key, new TreeRowReference(store, path));
            } finally {
                MemoryCleaner.free(path.handle());
            }
            appendChildren(store, iter, child, references);
        }
    }

    private static void write(TreeStore store, TreeIter iter, Node node) {
        boolean selected = node.leafCount > 0 && node.selectedCount == node.leafCount;
        boolean inconsistent = node.selectedCount > 0 && node.selectedCount < node.leafCount;
        double progress = node.length > 0 ? node.completed * 100.0 / node.length : 0;
        String priority = node.priority != null ? node.priority : PRIORITY_NORMAL;
        TreeStoreCells.setBoolean(store, iter, SELECTED_COLUMN, selected);
        TreeStoreCells.setString(store, iter, NAME_COLUMN, node.name);
        TreeStoreCells.setString(store, iter, SIZE_TEXT_COLUMN,
                DownloadFormats.size(node.length));
        TreeStoreCells.setInt(store, iter, PROGRESS_COLUMN,
                ProgressPresentation.wholePercentage(progress));
        TreeStoreCells.setString(store, iter, PRIORITY_TEXT_COLUMN, priority);
        TreeStoreCells.setInt(store, iter, INDEX_COLUMN,
                node.folder() ? -1 : node.entry.index());
        TreeStoreCells.setString(store, iter, PROGRESS_TEXT_COLUMN,
                ProgressPresentation.percentage(progress));
        TreeStoreCells.setLong(store, iter, SIZE_SORT_COLUMN, node.length);
        TreeStoreCells.setDouble(store, iter, PROGRESS_SORT_COLUMN, progress);
        TreeStoreCells.setString(store, iter, PATH_COLUMN,
                node.folder() ? node.displayPath : node.entry.path());
        TreeStoreCells.setBoolean(store, iter, FOLDER_COLUMN, node.folder());
        TreeStoreCells.setBoolean(store, iter, INCONSISTENT_COLUMN, inconsistent);
        TreeStoreCells.setInt(store, iter, PRIORITY_SORT_COLUMN,
                "Mixed".equals(priority) ? 0 : prioritySortKey(priority));
        TreeStoreCells.setString(store, iter, ICON_NAME_COLUMN,
                node.folder() ? "folder-symbolic" : "text-x-generic-symbolic");
    }

    private static void setSelectionRecursively(TreeStore store, TreeIter iter,
            boolean selected) {
        TreeStoreCells.setBoolean(store, iter, SELECTED_COLUMN, selected);
        TreeStoreCells.setBoolean(store, iter, INCONSISTENT_COLUMN, false);
        TreeIter child = new TreeIter();
        if (store.iterChildren(child, iter)) {
            do {
                setSelectionRecursively(store, child, selected);
            } while (store.iterNext(child));
        }
    }

    private static void setPriorityRecursively(TreeStore store, TreeIter iter,
            String priority) {
        TreeStoreCells.setString(store, iter, PRIORITY_TEXT_COLUMN, priority);
        TreeStoreCells.setInt(store, iter, PRIORITY_SORT_COLUMN, prioritySortKey(priority));
        TreeIter child = new TreeIter();
        if (store.iterChildren(child, iter)) {
            do {
                setPriorityRecursively(store, child, priority);
            } while (store.iterNext(child));
        }
    }

    private static void updateAncestors(TreeStore store, TreeIter iter) {
        TreePath path = store.getPath(iter);
        try {
            while (path.getDepth() > 1 && path.up()) {
                TreeIter parent = new TreeIter();
                if (!store.getIter(parent, path)) {
                    break;
                }
                updateFolderState(store, parent);
            }
        } finally {
            MemoryCleaner.free(path.handle());
        }
    }

    private static void updateFolderState(TreeStore store, TreeIter folder) {
        int children = 0;
        int selected = 0;
        boolean partial = false;
        String priority = null;
        TreeIter child = new TreeIter();
        if (store.iterChildren(child, folder)) {
            do {
                children++;
                boolean childSelected = TreeStoreCells.getBoolean(store, child, SELECTED_COLUMN);
                boolean childPartial = TreeStoreCells.getBoolean(store, child, INCONSISTENT_COLUMN);
                if (childSelected) {
                    selected++;
                }
                partial |= childPartial;
                String childPriority = TreeStoreCells.getString(
                        store, child, PRIORITY_TEXT_COLUMN);
                if (priority == null) {
                    priority = childPriority;
                } else if (!priority.equals(childPriority)) {
                    priority = "Mixed";
                }
            } while (store.iterNext(child));
        }
        boolean all = children > 0 && selected == children && !partial;
        boolean mixedSelection = partial || (selected > 0 && selected < children);
        TreeStoreCells.setBoolean(store, folder, SELECTED_COLUMN, all);
        TreeStoreCells.setBoolean(store, folder, INCONSISTENT_COLUMN, mixedSelection);
        String displayPriority = priority != null ? priority : PRIORITY_NORMAL;
        TreeStoreCells.setString(store, folder, PRIORITY_TEXT_COLUMN, displayPriority);
        TreeStoreCells.setInt(store, folder, PRIORITY_SORT_COLUMN,
                "Mixed".equals(displayPriority) ? 0 : prioritySortKey(displayPriority));
    }

    private static void visitLeaves(TreeStore store, LeafVisitor visitor) {
        TreeIter iter = new TreeIter();
        if (store.getIterFirst(iter)) {
            do {
                visitLeaves(store, iter, visitor);
            } while (store.iterNext(iter));
        }
    }

    private static void visitLeaves(TreeStore store, TreeIter iter, LeafVisitor visitor) {
        if (!TreeStoreCells.getBoolean(store, iter, FOLDER_COLUMN)) {
            visitor.visit(iter);
            return;
        }
        TreeIter child = new TreeIter();
        if (store.iterChildren(child, iter)) {
            do {
                visitLeaves(store, child, visitor);
            } while (store.iterNext(child));
        }
    }

    @FunctionalInterface
    private interface LeafVisitor {
        void visit(TreeIter iter);
    }
}

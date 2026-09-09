package org.odm.gtk4;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.gnome.gio.DBusConnection;
import org.gnome.gio.DBusInterfaceVTable;
import org.gnome.gio.DBusNodeInfo;
import org.javagi.base.GErrorException;
import org.gnome.glib.Variant;
import org.gnome.glib.VariantType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The desktop renders this DBusMenu; all GTK action state arrives as immutable snapshots. */
final class TrayMenu {
    static final String PATH = "/org/odm/odm/Menu";
    static final String INTERFACE = "com.canonical.dbusmenu";
    private static final Logger LOGGER = LoggerFactory.getLogger(TrayMenu.class);

    record ActionState(boolean enabled, boolean checked) { }
    record Entry(int id, String label, String action, boolean checkable) { }
    static final List<Entry> ENTRIES = List.of(
            new Entry(1, "Open", "open", false),
            new Entry(2, "New Download", "new-download", false),
            new Entry(3, "", null, false),
            new Entry(4, "Pause All", "pause-all", false),
            new Entry(5, "Resume All", "resume-all", false),
            new Entry(6, "", null, false),
            new Entry(7, "Clipboard", "clipboard-monitoring", true),
            new Entry(8, "Silent Mode", "clipboard-silent", true),
            new Entry(9, "Offline Mode", "offline", true),
            new Entry(10, "", null, false),
            new Entry(11, "Exit", "quit", false));

    // DBusMenu v3, also used by XFCE's StatusNotifier panel plugin.
    private static final String XML = """
            <node><interface name="com.canonical.dbusmenu">
              <property name="Version" type="u" access="read"/>
              <property name="TextDirection" type="s" access="read"/>
              <property name="Status" type="s" access="read"/>
              <property name="IconThemePath" type="as" access="read"/>
              <method name="GetLayout">
                <arg type="i" direction="in"/><arg type="i" direction="in"/><arg type="as" direction="in"/>
                <arg type="u" direction="out"/><arg type="(ia{sv}av)" direction="out"/>
              </method>
              <method name="GetGroupProperties">
                <arg type="ai" direction="in"/><arg type="as" direction="in"/>
                <arg type="a(ia{sv})" direction="out"/>
              </method>
              <method name="GetProperty">
                <arg type="i" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/>
              </method>
              <method name="Event">
                <arg type="i" direction="in"/><arg type="s" direction="in"/>
                <arg type="v" direction="in"/><arg type="u" direction="in"/>
              </method>
              <method name="EventGroup">
                <arg type="a(isvu)" direction="in"/><arg type="ai" direction="out"/>
              </method>
              <method name="AboutToShow">
                <arg type="i" direction="in"/><arg type="b" direction="out"/>
              </method>
              <method name="AboutToShowGroup">
                <arg type="ai" direction="in"/><arg type="ai" direction="out"/><arg type="ai" direction="out"/>
              </method>
              <signal name="ItemsPropertiesUpdated"><arg type="a(ia{sv})"/><arg type="a(ias)"/></signal>
              <signal name="LayoutUpdated"><arg type="u"/><arg type="i"/></signal>
              <signal name="ItemActivationRequested"><arg type="i"/><arg type="u"/></signal>
            </interface></node>
            """;

    private final DBusConnection connection;
    private final Consumer<String> activate;
    private final int registrationId;
    private volatile Map<String, ActionState> states = Map.of();
    private volatile boolean closed;

    TrayMenu(DBusConnection connection, Arena arena, Consumer<String> activate) throws GErrorException {
        this.connection = connection;
        this.activate = activate;
        var info = DBusNodeInfo.forXml(XML);
        var vtable = new DBusInterfaceVTable((conn, sender, path, iface, method, params, invocation) -> {
            try {
                invocation.returnValue(dispatch(method, params));
            } catch (IllegalArgumentException invalid) {
                invocation.returnDbusError("com.canonical.dbusmenu.Error.InvalidMenuItem", invalid.getMessage());
            } catch (Exception failure) {
                LOGGER.warn("Could not handle tray menu request", failure);
                invocation.returnDbusError("org.freedesktop.DBus.Error.Failed", "Tray menu unavailable");
            }
        }, (conn, sender, path, iface, property, error) -> switch (property) {
            case "Version" -> Variant.uint32(3);
            case "TextDirection" -> Variant.string("ltr");
            case "Status" -> Variant.string("normal");
            case "IconThemePath" -> array("s", List.of());
            default -> null;
        }, null, arena);
        registrationId = connection.registerObject(PATH, info.lookupInterface(INTERFACE), vtable,
                MemorySegment.NULL, null);
    }

    /** Property changes also update menus that are already open in the panel. */
    void update(Map<String, ActionState> next) {
        if (closed) { return; }
        Map<String, ActionState> previous = states;
        states = Map.copyOf(next);
        List<Variant> changed = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.action() != null && !java.util.Objects.equals(
                    previous.get(entry.action()), states.get(entry.action()))) {
                changed.add(tuple(Variant.int32(entry.id()), properties(entry.id(), List.of())));
            }
        }
        if (!changed.isEmpty()) {
            try {
                connection.emitSignal(null, PATH, INTERFACE, "ItemsPropertiesUpdated",
                        tuple(array("(ia{sv})", changed), array("(ias)", List.of())));
            } catch (GErrorException failure) {
                LOGGER.debug("Could not update tray menu", failure);
            }
        }
    }

    private Variant dispatch(String method, Variant parameters) {
        return switch (method) {
            case "GetLayout" -> {
                int parent = parameters.getChildValue(0).getInt32();
                requireItem(parent);
                int depth = parameters.getChildValue(1).getInt32();
                List<String> names = List.of(parameters.getChildValue(2).getStrv());
                List<Variant> children = new ArrayList<>();
                if (parent == 0 && depth != 0) {
                    for (Entry entry : ENTRIES) {
                        children.add(Variant.variant(layout(entry.id(), names, List.of())));
                    }
                }
                yield tuple(Variant.uint32(1), layout(parent, names, children));
            }
            case "GetGroupProperties" -> {
                Variant ids = parameters.getChildValue(0);
                List<String> names = List.of(parameters.getChildValue(1).getStrv());
                List<Variant> result = new ArrayList<>();
                if (ids.nChildren() == 0) {
                    result.add(tuple(Variant.int32(0), properties(0, names)));
                    for (Entry entry : ENTRIES) {
                        result.add(tuple(Variant.int32(entry.id()), properties(entry.id(), names)));
                    }
                } else {
                    for (int i = 0; i < ids.nChildren(); i++) {
                        int id = ids.getChildValue(i).getInt32();
                        if (exists(id)) { result.add(tuple(Variant.int32(id), properties(id, names))); }
                    }
                }
                yield tuple(array("(ia{sv})", result));
            }
            case "GetProperty" -> {
                int id = parameters.getChildValue(0).getInt32();
                requireItem(id);
                String name = parameters.getChildValue(1).dupString(null);
                Variant value = properties(id, List.of()).lookupValue(name, null);
                if (value == null) { throw new IllegalArgumentException("Unknown menu property"); }
                yield tuple(Variant.variant(value));
            }
            case "Event" -> {
                int id = parameters.getChildValue(0).getInt32();
                requireItem(id);
                event(id, parameters.getChildValue(1).dupString(null));
                yield tuple();
            }
            case "EventGroup" -> {
                List<Variant> errors = new ArrayList<>();
                Variant events = parameters.getChildValue(0);
                for (int i = 0; i < events.nChildren(); i++) {
                    Variant event = events.getChildValue(i);
                    int id = event.getChildValue(0).getInt32();
                    if (exists(id)) { event(id, event.getChildValue(1).dupString(null)); }
                    else { errors.add(Variant.int32(id)); }
                }
                yield tuple(array("i", errors));
            }
            case "AboutToShow" -> {
                requireItem(parameters.getChildValue(0).getInt32());
                yield tuple(Variant.boolean_(false)); // state updates are pushed immediately
            }
            case "AboutToShowGroup" -> {
                List<Variant> errors = new ArrayList<>();
                Variant ids = parameters.getChildValue(0);
                for (int i = 0; i < ids.nChildren(); i++) {
                    int id = ids.getChildValue(i).getInt32();
                    if (!exists(id)) { errors.add(Variant.int32(id)); }
                }
                yield tuple(array("i", List.of()), array("i", errors));
            }
            default -> throw new IllegalArgumentException("Unknown menu method");
        };
    }

    private void event(int id, String event) {
        if (closed || !"clicked".equals(event) || id == 0) { return; }
        Entry entry = ENTRIES.get(id - 1);
        if (entry.action() != null && state(entry).enabled()) { activate.accept(entry.action()); }
    }

    private ActionState state(Entry entry) {
        return states.getOrDefault(entry.action(), new ActionState(false, false));
    }

    private Variant layout(int id, List<String> names, List<Variant> children) {
        return tuple(Variant.int32(id), properties(id, names), array("v", children));
    }

    private Variant properties(int id, List<String> names) {
        Map<String, Variant> properties = new LinkedHashMap<>();
        if (id == 0) {
            properties.put("children-display", Variant.string("submenu"));
        } else {
            Entry entry = ENTRIES.get(id - 1);
            properties.put("type", Variant.string(entry.action() == null ? "separator" : "standard"));
            if (entry.action() != null) {
                ActionState state = state(entry);
                properties.put("label", Variant.string(entry.label()));
                properties.put("enabled", Variant.boolean_(state.enabled()));
                properties.put("visible", Variant.boolean_(true));
                if (entry.checkable()) {
                    properties.put("toggle-type", Variant.string("checkmark"));
                    properties.put("toggle-state", Variant.int32(state.checked() ? 1 : 0));
                }
            }
        }
        return array("{sv}", properties.entrySet().stream()
                .filter(entry -> names.isEmpty() || names.contains(entry.getKey()))
                .map(entry -> Variant.dictEntry(Variant.string(entry.getKey()), Variant.variant(entry.getValue())))
                .toList());
    }

    private static boolean exists(int id) { return id >= 0 && id <= ENTRIES.size(); }
    private static void requireItem(int id) {
        if (!exists(id)) { throw new IllegalArgumentException("Unknown menu item"); }
    }
    private static Variant tuple(Variant... values) { return Variant.tuple(values); }
    private static Variant array(String type, List<Variant> values) {
        return Variant.array(new VariantType(type), values.toArray(Variant[]::new));
    }

    void unregister() {
        if (!closed) {
            closed = true;
            connection.unregisterObject(registrationId);
        }
    }
}

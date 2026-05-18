# JGTK Changelog

## Version 2.0.0 - Glade-First Development Update

### 🎯 Major Philosophy Shift

The JGTK library has been completely refactored to prioritize **Glade-first development** over programmatic widget creation. This represents a fundamental shift in how GTK applications are built using this library.

### 🚀 New Features

#### Enhanced GladeLoader
- **Improved Signal Management**: Centralized signal handling with the new `SignalManager` class
- **Fluent Builder API**: Clean, chainable configuration API for easier setup
- **Automatic Widget Discovery**: Smart widget caching and type-safe retrieval
- **Resource Loading**: Support for loading Glade files from classpath resources
- **Comprehensive Error Handling**: Better validation and graceful error recovery
- **Batch Signal Connection**: Connect multiple signals efficiently with utility methods

#### New SignalManager Class
- **Centralized Signal Handling**: Unified system for all widget signal management
- **Fluent Connection API**: Builder pattern for intuitive signal connections
- **Multiple Signal Types**: Support for simple, event-based, and extended signal handlers
- **Connection Options**: Support for `after` and `swapped` connection flags
- **Batch Operations**: Connect multiple signals simultaneously
- **Safe Cleanup**: Automatic disconnection and memory management

#### New GladeUtils Class
- **Quick-Start Methods**: One-line application setup with `quickLoad()` and `quickLoadResource()`
- **Fluent Builder**: Comprehensive builder pattern for Glade configuration
- **Validation Utilities**: File and resource validation before loading
- **Common Patterns**: Pre-built handlers for quit, hide, debug operations
- **Batch Utilities**: Helper methods for connecting button and menu handlers
- **File Discovery**: Automatic Glade file location in common directories

#### Enhanced Widget Classes
- **New Widgets Added**:
  - `GtkEntry` - Single-line text input with comprehensive API
  - `GtkTextView` - Multi-line text display and editing
- **Interaction Focus**: All widgets now prioritize interaction over creation
- **Better Signal Handling**: Enhanced signal connection capabilities
- **Improved Error Handling**: Robust state management and validation

#### Updated GtkApplication
- **Glade Integration**: Built-in support for GladeLoader registration and management
- **Quick Start Methods**: `quickStart()` and `quickStartFromResource()` for rapid development
- **Resource Management**: Automatic cleanup of Glade resources
- **Enhanced Lifecycle**: Better application startup and shutdown handling

### 🔧 Technical Improvements

#### WidgetFactory Enhancements
- **Complete Widget Registry**: All common GTK widgets now registered
- **Type-Safe Creation**: Automatic widget wrapping from native pointers
- **Better Factory Methods**: More robust widget instantiation

#### Memory Management
- **Automatic Cleanup**: GladeLoader and SignalManager handle resource cleanup
- **Callback Management**: Proper native callback reference handling
- **Resource Tracking**: Application-level resource registration and cleanup

#### Error Handling
- **Comprehensive Validation**: Glade file validation before loading
- **Graceful Degradation**: Applications continue running with missing widgets
- **Detailed Logging**: Better error messages and debugging information

### 📋 API Changes

#### New Methods in GladeLoader
```java
// Signal Management
boolean connectSignal(String widgetId, String signal, SignalHandler handler)
boolean connectSignal(GtkWidget widget, String signal, SignalHandler handler)
boolean connectSignalWithEvent(String widgetId, String signal, SignalHandlerWithEvent handler)
int connectSignals(Map<String, SignalHandler> signalMap)

// Utilities
SignalManager getSignalManager()
Map<String, GtkWidget> getCachedWidgets()
void clearCache()
boolean showMainWindow()
boolean isDestroyed()
```

#### New GladeUtils Methods
```java
// Builder Pattern
static GladeBuilder builder()

// Quick Methods
static GladeLoader quickLoad(String filePath)
static GladeLoader quickLoadResource(String resourcePath)

// Batch Operations
static int connectButtonHandlers(GladeLoader loader, Map<String, WidgetSignalHandler> handlers)
static int connectMenuHandlers(GladeLoader loader, Map<String, WidgetSignalHandler> handlers)

// Validation
static boolean validateGladeFile(String filePath)
static boolean validateGladeResource(String resourcePath)
static String findGladeFile(String filename)

// Common Handlers
static WidgetSignalHandler debugHandler(String message)
static WidgetSignalHandler quitHandler()
static WidgetSignalHandler hideWindowHandler()
static WidgetSignalHandler messageHandler(String message)
```

#### Enhanced GtkApplication Methods
```java
// Glade Support
static void registerGladeLoader(GladeLoader loader)
static void unregisterGladeLoader(GladeLoader loader)
static List<GladeLoader> getGladeLoaders()

// Quick Start
static GladeLoader quickStart(String gladeFile, Consumer<GladeBuilder> configurer)
static GladeLoader quickStartFromResource(String resourcePath, Consumer<GladeBuilder> configurer)

// Enhanced Cleanup
static void cleanup()
```

### 📚 Usage Examples

#### Before (Widget Creation Focus)
```java
public class OldApp {
    public static void main(String[] args) {
        GtkApplication.initialize();
        
        GtkWindow window = new GtkWindow(GtkWindow.WindowType.TOPLEVEL);
        window.setTitle("My App");
        window.setDefaultSize(400, 300);
        
        GtkButton button = new GtkButton("Click Me");
        button.setOnClickListener(btn -> System.out.println("Clicked!"));
        
        window.add(button);
        window.showAll();
        
        GtkApplication.run();
    }
}
```

#### After (Glade-First Approach)
```java
public class NewApp {
    public static void main(String[] args) {
        GtkApplication.initialize();
        
        GladeLoader loader = GladeUtils.builder()
            .fromFile("my_app.glade")
            .onButtonClick("my_button", widget -> System.out.println("Clicked!"))
            .build();
        
        GtkApplication.run();
    }
}
```

#### Advanced Signal Handling
```java
SignalManager signals = loader.getSignalManager();

// Fluent signal connections
signals.connect()
    .widget(button)
    .signal("clicked")
    .after()
    .connect(this::handleButtonClick);

// Batch connections
Map<SignalManager.WidgetSignalPair, SignalManager.WidgetSignalHandler> connections = Map.of(
    new SignalManager.WidgetSignalPair(saveBtn, "clicked"), this::saveFile,
    new SignalManager.WidgetSignalPair(openBtn, "clicked"), this::openFile
);
signals.connectMultiple(connections);
```

### 🗂️ New Project Structure

```
jgtk/
├── src/main/java/org/jgtk/
│   ├── core/                    # Core interfaces and factories
│   ├── util/                    # New utility classes
│   │   ├── GladeLoader.java     # Enhanced Glade integration
│   │   ├── SignalManager.java   # New signal management
│   │   └── GladeUtils.java      # New utility methods
│   ├── widgets/                 # Widget interaction classes
│   └── app/                     # Application lifecycle
├── examples/                    # New example applications
│   └── simple-app/
│       ├── main.glade          # Example Glade file
│       ├── SimpleApp.java      # Example application
│       └── README.md           # Example documentation
├── GLADE_MIGRATION.md          # Migration guide
└── CHANGELOG.md                # This file
```

### 🛠️ Breaking Changes

#### Minimal Breaking Changes
- Existing widget creation APIs remain functional
- Old signal handling methods still work
- Migration can be done incrementally
- New and old approaches can coexist

#### Recommended Migration Path
1. **Design UI in Glade** instead of programmatic creation
2. **Replace widget creation** with Glade loading
3. **Update signal handling** to use new APIs
4. **Adopt fluent builder patterns** for cleaner code

### 📖 Documentation Updates

#### New Documentation
- **GLADE_MIGRATION.md**: Comprehensive migration guide
- **examples/simple-app/README.md**: Example application guide
- **Enhanced README.md**: Updated with Glade-first examples

#### Updated Documentation
- **Widget class documentation**: Focus on interaction rather than creation
- **Application setup guides**: Glade-based development patterns
- **Best practices**: Modern GTK development recommendations

### 🎉 Benefits of the Update

#### Developer Experience
- **Faster Development**: Visual UI design with Glade
- **Cleaner Code**: Less boilerplate, more focus on logic
- **Better Separation**: UI design separated from application logic
- **Easier Maintenance**: Visual UI updates without code changes

#### Application Quality
- **Professional UIs**: Native GTK look and feel through Glade
- **Better Resource Management**: Automatic cleanup and memory management
- **Improved Error Handling**: Graceful degradation and better error messages
- **Enhanced Performance**: Optimized widget loading and signal handling

#### Team Collaboration
- **Designer-Developer Workflow**: Designers can work with Glade independently
- **Rapid Prototyping**: Quick UI iteration without code changes
- **Better Testing**: UI logic separated from widget creation

### 🔮 Future Roadmap

#### Planned Features
- **GTK4 Builder Templates**: Enhanced template support
- **Annotation-Based Signals**: Automatic signal connection via annotations
- **IDE Integration**: Better tooling support for popular IDEs
- **Visual Debugging**: Signal connection visualization tools

#### Community
- **Example Gallery**: More comprehensive example applications
- **Best Practices Guide**: Community-driven development patterns
- **Plugin System**: Extensible widget and utility system

### 🙏 Acknowledgments

This major update represents a significant evolution in JGTK's approach to GTK development. The focus on Glade-first development aligns with modern GTK best practices and provides a more professional development experience.

Special thanks to the GTK community for promoting separation of UI design and application logic, which inspired this architectural shift.

---

For detailed migration instructions, see [GLADE_MIGRATION.md](GLADE_MIGRATION.md).

For examples of the new approach, see the [examples/](examples/) directory.
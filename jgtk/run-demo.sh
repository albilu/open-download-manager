#!/bin/bash

# ODM Main Window Demo Runner
# This script runs the ODM main window demo using the new jgtk implementation

echo "=== ODM jgtk Demo Runner ==="
echo "This demo loads ODM's main-window.glade and displays it using the new jgtk implementation"
echo ""

# Check if we're in the right directory
if [ ! -f "pom.xml" ] || [ ! -d "src/test/java/org/jgtk" ]; then
    echo "Error: Please run this script from the jgtk directory"
    echo "Usage: cd jgtk && ./run-demo.sh"
    exit 1
fi

# Check if ODM Glade file exists
GLADE_FILE="../odm-gtk/src/main/resources/glade/main-window/main-window.glade"
if [ ! -f "$GLADE_FILE" ]; then
    echo "Error: ODM Glade file not found at: $GLADE_FILE"
    echo "Make sure you're running from the open-download-manager project root"
    exit 1
fi

echo "✓ Found ODM Glade file: $GLADE_FILE"

# Compile the project
echo "Compiling jgtk project..."
mvn clean compile test-compile -q
if [ $? -ne 0 ]; then
    echo "Error: Compilation failed"
    exit 1
fi
echo "✓ Compilation successful"

# Check for display
if [ -z "$DISPLAY" ]; then
    echo ""
    echo "WARNING: No DISPLAY environment variable found"
    echo "The demo will run but you won't see the window in a headless environment"
    echo "To see the actual window, run this on a system with a GUI desktop"
    echo ""
fi

echo ""
echo "=== Starting ODM Main Window Demo ==="
echo "Loading ODM main window and starting GTK application..."
echo ""
echo "Instructions:"
echo "- The ODM main window should appear"
echo "- Click menu items and buttons to see signal handlers in action"
echo "- Close the window or press Ctrl+C to exit"
echo ""
echo "Press Enter to start the demo..."
read

# Run the demo
mvn exec:java -Dexec.mainClass="org.jgtk.ODMMainWindowDemo" -Dexec.classpathScope="test" -q

echo ""
echo "=== Demo Ended ==="
echo "Thank you for testing the ODM jgtk implementation!"

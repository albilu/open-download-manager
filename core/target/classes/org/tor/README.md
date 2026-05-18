

Java Utility classes to handle Tor service lifecycle

## Features

-   Start tor service (possibility to set options, ports or config file)
-  Get bin from dependency manager
-   Report bootstrap progress
- Start/stop/restart should return a boolean indicating success or failure
-   Check leakage (tor properly connected, dns leakage)
-   Implement method to change tor ip on fly if connection lost or too slow

## Implementation Complete!

The Tor utility package has been fully implemented with the following components:

### TorService
- Start/stop/restart Tor service with custom configurations
- Monitor service health and status
- Support for custom torrc files and configuration options
- Automatic data directory management
- Event-driven status notifications

### TorBootstrapMonitor
- Real-time bootstrap progress monitoring via Tor control interface
- Detailed status reporting (connecting to directory, establishing circuits, etc.)
- Configurable timeouts and connection settings
- Bootstrap completion and failure notifications

### TorLeakChecker
- Comprehensive IP and DNS leak detection
- Verification of proper Tor network connectivity
- External IP address checking through Tor
- DNS resolution testing to detect leaks
- Tor network verification using check.torproject.org

### TorController
- Full control interface for Tor service management
- Change IP address on demand (NEWNYM signal)
- Circuit management (list, close circuits)
- Configuration management (get/set Tor options)
- Automatic IP change on slow connections
- Authentication support (password and cookie)

### TorUtilityFactory
- Centralized factory for all Tor utilities
- Managed Tor setup with coordinated startup/shutdown
- Availability checking and feature detection
- Default configuration management
- Complete lifecycle management

All features from the original requirements are implemented:
- ✅ Start tor service (with options, ports, config file support)
- ✅ Get binary from dependency manager (via TorToolManager integration)
- ✅ Report bootstrap progress (detailed real-time monitoring)
- ✅ Start/stop/restart with success/failure indicators
- ✅ Check leakage (comprehensive IP and DNS leak detection)
- ✅ Change tor IP on demand when connection is slow or lost

The implementation follows the project's modular architecture and integrates seamlessly with the existing tool management system.

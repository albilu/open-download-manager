# jAria

A full featured wrapper library for aria2 download utility.
It provides a simple and intuitive API for interacting with aria2
Use modular architecture and prioritize performance, memory efficiency, and stability.
Follow the README for development steps and features.
Ignore unit tests for now.

## Notes

-   deb dependency

## Compatibility

-   Java 21

## aria2 known issues

-   max-connection-per-server 16
-   JSON-RPC random crash issue

## Features

-   Support JSON-RPC over HTTP (only for infrequent requests) and WebSocket (for frequent requests)
-   Utility classes for common methods (https://aria2.github.io/manual/en/html/aria2c.html#methods)
-   aria2 lifecycle management
-   Download progress monitoring
-   Error handling (https://aria2.github.io/manual/en/html/aria2c.html#error-handling)
-   Handle Notifications from RCP server (https://aria2.github.io/manual/en/html/aria2c.html#notifications)
-   Implement aria2 process restart method
-   Improve robutness to avoid communication issues using JSON-RPC over websocket (hangs, timeouts ...)
    -   clear stale connections to decrease workload on rpc

See `.github/copilot-instructions.md` for workspace-specific coding guidelines.

# Open Download Manager (ODM)

A full-featured native download manager for Linux that provides multi-protocol download capabilities with a GTK-based user interface.

## Core Features

-   **Multi-protocol support**: HTTP, BitTorrent, Magnet links, MetaLink
-   **External tool integration**: aria2, yt-dlp, httrack, curl, proxychains, Tor
-   **Advanced download management**: Multi-connection downloads, pause/resume, queue management
-   **Monitoring capabilities**: Clipboard monitoring for automatic URL detection, folder monitoring for torrent files
-   **Privacy features**: Tor support, proxy rotation, SOCKS4/5 proxy integration
-   **Automation**: After-completion actions (shutdown, file operations), scheduled downloads
-   **Cross-desktop compatibility**: GTK 3/4 support for GNOME, XFCE, Cinnamon, MATE, Fedora environments

## Target Platforms

-   **Primary**: Linux distributions with GTK desktop environments
-   **Packaging**: .deb (Debian/Ubuntu), .rpm (Red Hat/Fedora), .pkg.tar.gz (Arch)
-   **Java requirement**: Java 21+

## Architecture

Multi-module Maven project with clear separation of concerns:

-   `core`: Business logic and external tool integrations
-   `jgtk`: GTK JNA bindings library
-   `odm-gtk`: GTK user interface implementation

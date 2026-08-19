# Open Download Manager

Open Download Manager (ODM) is a native download manager for Linux. It combines proven download engines — aria2, yt-dlp, and HTTrack — with a GTK4 desktop interface, persistent history, queue controls, and recovery after restarts.

## Features

- Multi-connection HTTP, HTTPS, and FTP downloads
- BitTorrent, magnet, and Metalink support
- Video and media downloads through yt-dlp
- Website mirroring through HTTrack
- Pause, resume, cancel, reorder, and concurrent download limits
- Clipboard URL monitoring and torrent/Metalink folder monitoring
- Persistent download history and automatic resume
- Speed limits, mirrors, retries, scheduling, and batch imports
- Proxy, proxychains, and Tor routing
- After-completion actions (notify, open file, verify, suspend, shutdown)
- Native GTK4 interface with search, filtering, detail views, and tray/background mode

## Requirements

- GTK 4 (>= 4.10)
- aria2 (>= 1.34.0)
- yt-dlp (>= 2024.01.01)
- HTTrack (>= 3.49.0)
- curl (>= 7.80.0)

Optional but recommended: proxychains, Tor, and FFmpeg.

## Installation

Packages are produced for Debian/Ubuntu (`.deb`), Fedora/RHEL (`.rpm`), and Arch (`pkg.tar.zst`). Each package bundles a trimmed Java 25 runtime; GTK4 and the download tools above are still required from the host system.

```sh
# Debian/Ubuntu
sudo dpkg -i open-download-manager_*.deb

# Fedora/RHEL
sudo rpm -i open-download-manager-*.rpm

# Arch
sudo pacman -U open-download-manager-*.pkg.tar.zst
```

## Building from source

Source builds require JDK 25, Maven, a Linux native toolchain, and GTK4 development libraries. The supported development environment is Docker-based:

```sh
make build     # build the odm-dev Docker image
make compile   # compile and package the project
make test      # run the full test suite (starts Xvfb)
make package   # build .deb, .rpm, and pkg.tar.zst artifacts
make dev       # open an interactive development container
make run       # launch the application with GUI forwarding
make debug     # launch with a suspended debugger on port 5005
```

## Powered by

ODM builds on the work of these outstanding open-source projects:

- [aria2](https://github.com/aria2/aria2) ![GitHub stars](https://img.shields.io/github/stars/aria2/aria2?style=social) — multi-protocol download engine (HTTP/FTP, BitTorrent, Metalink) over local JSON-RPC
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) ![GitHub stars](https://img.shields.io/github/stars/yt-dlp/yt-dlp?style=social) — video and media extraction with format discovery
- [HTTrack](https://github.com/xroche/httrack) ![GitHub stars](https://img.shields.io/github/stars/xroche/httrack?style=social) — website mirroring with depth and filter controls
- [curl](https://github.com/curl/curl) ![GitHub stars](https://img.shields.io/github/stars/curl/curl?style=social) — process-based HTTP fallback and proxy-capable downloads
- [proxychains-ng](https://github.com/rofl0r/proxychains-ng) ![GitHub stars](https://img.shields.io/github/stars/rofl0r/proxychains-ng?style=social) — SOCKS/HTTP proxy chaining
- [Tor](https://www.torproject.org/) ![GitHub stars](https://img.shields.io/github/stars/torproject/tor?style=social) — optional privacy routing through the local SOCKS service
- [java-gi](https://github.com/jwharm/java-gi) ![GitHub stars](https://img.shields.io/github/stars/jwharm/java-gi?style=social) — native GTK4 desktop interface from Java

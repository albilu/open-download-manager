# Getting Started

## What you need

- GTK 4 (version 4.10 or newer)
- aria2 (1.34.0+), yt-dlp (2024.01.01+), HTTrack (3.49.0+), curl (7.80.0+)

Recommended for extra features: proxychains, Tor, and FFmpeg.

## Install

Pick the package for your system. Each package includes its own Java runtime — you only need GTK and the download tools above from your system.

```sh
# Debian / Ubuntu
sudo apt install ./open-download-manager_*.deb

# Fedora / RHEL
sudo rpm -i open-download-manager-*.rpm

# Arch
sudo pacman -U open-download-manager-*.pkg.tar.zst
```

## First launch

1. Open **Open Download Manager** from your applications menu.
2. The main window appears with an empty download list:

![Main window](images/main-window.png)

3. Choose where files go: open **Settings** (gear button) → **General** → **Default download directory**.

![Settings](images/settings.png)

*General settings: download folder, concurrent downloads, monitoring, and startup options.*

4. Add your first download: press **+** (New download), paste a link, and press **Start Download**.

## Where things live

- Downloads go to your chosen download folder.
- Settings and history are saved automatically, so pause, close the app, and resume later without losing progress.

Next: [Managing Downloads](Managing-Downloads.md).

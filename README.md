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

## How ODM compares

Generic HTTP/FTP, BitTorrent/magnet and yt-dlp media are table stakes. The rows below focus on the areas where ODM's Linux-native, privacy and recovery stack has no single-competitor equivalent — the report's own takeaway is that *no single reviewed product documents queue + recovery + verification + proxy/Tor + browser/media integration equally well*.

### Where ODM pulls ahead — beyond generic downloads

| Area (outside generic features) | ODM | Competitor landscape at a glance (2026-08-19 first-party) | Why it matters |
|---|---|---|---|
| **Native Linux desktop** | ✅ GTK4/Libadwaita, fully open-source | `◐` Varia GTK4 but `next` is dev branch · Motrix Electron · Persepolis Python/PySide · KGet KDE · AB multi-OS claim · JDownloader Java platform · FileCentipede partially closed deps · IDM ❌ Windows-only · uGet ❌ stopped | One toolkit-native Linux workflow instead of Electron/Python wrappers or a Windows-only binary |
| **Privacy routing** | ✅ Tor daemon lifecycle + SOCKS health + UI toggle, `proxychains` via separate aria2 process, and proxy-list rotation on 403/429/5xx (`ProxyRotationManager`, `RetryableDownloadHandler`) | `◐` Persepolis SOCKS5 · AB proxy/PAC · JDownloader SOCKS4/5 · KGet reconnect/retry · FileCentipede proxy mgmt · Varia/Motrix no Tor/proxy established | A single opt-in privacy layer — not just a proxy field — with automatic IP-rotation on rate-limits |
| **Recovery & persistence** | ✅ XDG `settings.json` + SQLite history + XDG data `~/.local/share/odm` state + aria2 session save + ordered `odm-events` executor + auto-resume with `INTERRUPTED` marking | `○/◐` KGet SQLite/XML history + restore is the closest; others `Unclear`/`Partial` — no XDG session + ordered-event contract documented | Survives crashes/restarts with ordered events and explicit interruption handling |
| **Unified multi-engine queue** | ✅ aria2 (HTTP/HTTPS/FTP/SFTP/BT/magnet/Metalink) + yt-dlp + HTTrack + curl fallback in one queue with engine-adaptive settings | `◐` Each competitor covers a subset: Varia aria2+yt-dlp · Motrix HTTP/FTP/BT/Magnet · Persepolis Python lib + yt-dlp (no longer aria2) · AB protocol list unclear · KGet FTP/HTTP/Metalink+BT plugin · FileCentipede HTTP/FTP/SSH/WebDAV/BT/m3u8 · JDownloader plugin-dependent | One queue instead of juggling separate tools per protocol |
| **Watch & clipboard automation** | ✅ simultaneous `.torrent` + `.metalink`/`.meta4` folder watch (`TorrentFolderMonitor`+`MetaLinkFolderMonitor`) + clipboard polling (`ManagerClipboardService`) | `◐` Varia drag/drop + ext · Motrix beta clipboard · Persepolis browser+clipboard · KGet Konqueror+clipboard · JDownloader clipboard+Folder Watch `.crawljob` · FileCentipede no watch established — none documents dual torrent+Metalink watch + clipboard together | Drop a torrent *or* Metalink file and ODM routes it to `addTorrent`/`addMetalink` automatically |
| **Weekly scheduler** | ✅ weekday time spans with inclusive/exclusive windows + presets (`ScheduleManager`, `WeeklySchedule`, `TimeRange`, `DownloadScheduler`, `pauseReason=SCHEDULE`) | `✅/◐/○` Varia weekday spans · Persepolis scheduling · AB queues/schedulers · IDM timed queues · KGet scheduler not found · Motrix/JDownloader/FileCentipede `Unclear`/`Partial` | Unattended downloads with explicit outside/inside-window policy |
| **Post-completion pipeline** | ✅ checksum (auto-detected `.sha256` via `ChecksumProbe`) + AV scan + subtitle via Subliminal + move + command + sound + suspend/shutdown — each with severity, persisted `CompletionActionResult` output logs and `contributesToFinalizingProgress` pulse | `◐` KGet checksum/verify · JDownloader SFV/CRC/MD5/SHA+extract+shutdown · AB checksum · FileCentipede checksum tool · IDM AV hook — none documents the full chain with output logs + severity + Actions-tab history | Verification → scan → subtitles → file ops → power actions in one auditable chain |
| **First-class site mirroring** | ✅ HTTrack as dedicated `Type.WEBSITE_SCRAPING` with depth/filter/rate controls | `○/◐` FileCentipede/KGet/JDownloader have broad or plugin crawling · IDM Grabber but Windows-only — no HTTrack-native type documented | Mirroring is a first-class type, not a plugin side-effect |
| **Distribution packaging** | ✅ `.deb` / `.rpm` / `.pkg.tar.zst` with bundled `jlink` Java 25 runtime (no system Java), desktop entry + icons | `◐` Motrix AppImage/Snap/AUR/Flatpak · Varia Flatpak-first · AB Linux/Win/macOS+ARM · Persepolis Linux bundle · FileCentipede Linux/Win artifacts · IDM ❌ Windows-only | Install natively on Debian/Fedora/Arch without a system JDK |

Legend: ✅ documented full · ◐ partial/documented with limits · ○ `Unclear` in inspected first-party sources (not a claim of absence) · ❌ unsupported / Windows-only / stopped.

<details>
<summary>Full per-product matrix — same sources, wide table</summary>

| Area | ODM | Varia | Motrix | Persepolis | AB DM | KGet | JDownloader | FileCentipede | IDM | uGet |
|---|---|---|---|---|---|---|---|---|---|---|
| Native Linux + fully open | ✅ GTK4/Libadwaita, open | ◐ GTK4, `next` dev | ◐ Electron | ◐ Python/PySide | ◐ Linux/Win/macOS | ◐ KDE | ◐ Java platform | ◐ closed deps | ❌ Win-only | ❌ stopped |
| Tor + proxychains + proxy rotation | ✅ Tor lifecycle+health+rotation | ○ no Tor/proxy | ○ mock UA only | ◐ SOCKS5 | ◐ proxy/PAC | ○ retry only | ◐ SOCKS4/5 | ◐ proxy mgmt | ◐ proxy | ○ Unclear |
| Recovery & XDG state + ordered events | ✅ JSON+SQLite+session+`odm-events` | ○ Unclear | ○ Unclear | ○ Unclear | ◐ queues/API | ◐ history/restore | ◐ plugin state | ○ Unclear | ◐ auto-resume | ○ Unclear |
| aria2+yt-dlp+HTTrack+curl together | ✅ all four | ◐ aria2+yt-dlp | ◐ aria2 only | ◐ own lib+yt-dlp | ○ unclear | ◐ HTTP/FTP/Metalink+BT | ◐ plugin-driven | ◐ HTTP/FTP/SSH/WebDAV/BT/m3u8 | ◐ HTTP/FTP/MMS | ○ Unclear |
| Dual `.torrent`+`.meta4` watch + clipboard | ✅ both + clipboard | ◐ drop+ext | ◐ beta clipboard | ◐ browser+clipboard | ◐ browser+CLI | ◐ clipboard+drop | ◐ clipboard+Folder Watch | ○ capture only | ◐ browser+Grabber | ○ Unclear |
| Weekly scheduler | ✅ weekday spans+presets | ✅ weekday spans | ○ Unclear | ✅ scheduling | ✅ queues/schedulers | ◐ groups, no scheduler | ◐ auto-confirm/watch | ○ concurrency only | ✅ timed queues | ○ Unclear |
| Completion: checksum/AV/subs/move/cmd/shutdown | ✅ all + persisted logs | ○ Unclear | ○ UA only | ○ Unclear | ◐ checksum | ◐ checksum/verify | ◐ hashes+extract+shutdown | ◐ checksum | ◐ AV hook | ○ Unclear |
| HTTrack mirroring as type | ✅ `WEBSITE_SCRAPING` | ○ Unclear | ○ Unclear | ○ Unclear | ○ Unclear | ○ Unclear | ◐ plugin crawl | ○ tool-assisted | ◐ Grabber | ○ Unclear |
| Bundled-runtime Linux packages | ✅ deb/rpm/pkg+`jlink` | ◐ Flatpak | ✅ AppImage/Snap/etc | ◐ Linux bundle | ✅ themes/ARM | ◐ KDE pkg | ◐ installer | ◐ artifacts | ❌ Win-only | ○ Unclear |

All cells derive from the same inspected first-party sources; `○` means the inspected sources did not establish the capability and is not a claim that the product lacks it.

</details>

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

- [yt-dlp](https://github.com/yt-dlp/yt-dlp) ![GitHub stars](https://img.shields.io/github/stars/yt-dlp/yt-dlp?style=social) — video and media extraction with format discovery
- [curl](https://github.com/curl/curl) ![GitHub stars](https://img.shields.io/github/stars/curl/curl?style=social) — process-based HTTP fallback and proxy-capable downloads
- [aria2](https://github.com/aria2/aria2) ![GitHub stars](https://img.shields.io/github/stars/aria2/aria2?style=social) — multi-protocol download engine (HTTP/FTP, BitTorrent, Metalink) over local JSON-RPC
- [Jackett](https://github.com/Jackett/Jackett) ![GitHub stars](https://img.shields.io/github/stars/Jackett/Jackett?style=social) — API Support for your favorite torrent trackers
- [proxychains-ng](https://github.com/rofl0r/proxychains-ng) ![GitHub stars](https://img.shields.io/github/stars/rofl0r/proxychains-ng?style=social) — SOCKS/HTTP proxy chaining
- [Tor](https://www.torproject.org/) ![GitHub stars](https://img.shields.io/github/stars/torproject/tor?style=social) — optional privacy routing through the local SOCKS service
- [HTTrack](https://github.com/xroche/httrack) ![GitHub stars](https://img.shields.io/github/stars/xroche/httrack?style=social) — website mirroring with depth and filter controls
- [java-gi](https://github.com/jwharm/java-gi) ![GitHub stars](https://img.shields.io/github/stars/jwharm/java-gi?style=social) — native GTK4 desktop interface from Java

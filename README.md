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

> Research snapshot 2026-08-19, first-party sources only — official pages, manuals, changelogs and source files. `Yes`/`Partial`/`No`/`Unclear` per the methodology in [`docs/competitor-feature-research.md`](docs/competitor-feature-research.md), which also holds the full source list [U1]…[F2]. That document is the audit trail for the table below.

Generic HTTP/FTP, BitTorrent/magnet and yt-dlp media are table stakes. The rows below focus on the areas where ODM's Linux-native, privacy and recovery stack has no single-competitor equivalent — the report's own takeaway is that *no single reviewed product documents queue + recovery + verification + proxy/Tor + browser/media integration equally well*.

### Where ODM pulls ahead — beyond generic downloads

| Area (outside generic features) | ODM | Competitor landscape at a glance (2026-08-19 first-party) | Why it matters |
|---|---|---|---|
| **Native Linux desktop** | ✅ GTK4/Libadwaita, fully open-source | `◐` Varia GTK4 but `next` is dev branch [V1] · Motrix Electron [M1] · Persepolis Python/PySide [P1][P2] · KGet KDE [K1] · AB multi-OS claim [A1] · JDownloader Java platform [J1] · FileCentipede partially closed deps [F1] · IDM ✕ Windows-only [I2] · uGet ✕ stopped [U1] | One toolkit-native Linux workflow instead of Electron/Python wrappers or a Windows-only binary |
| **Privacy routing** | ✅ Tor daemon lifecycle + SOCKS health + UI toggle, `proxychains` via separate aria2 process, and proxy-list rotation on 403/429/5xx (`ProxyRotationManager`, `RetryableDownloadHandler`) | `◐` Persepolis SOCKS5 [P1] · AB proxy/PAC [A2] · JDownloader SOCKS4/5 [J5] · KGet reconnect/retry [K7] · FileCentipede proxy mgmt [F1] · Varia/Motrix no Tor/proxy established [V3][V5][M1] | A single opt-in privacy layer — not just a proxy field — with automatic IP-rotation on rate-limits |
| **Recovery & persistence** | ✅ XDG `settings.json` + SQLite history + XDG data `~/.local/share/odm` state + aria2 session save + ordered `odm-events` executor + auto-resume with `INTERRUPTED` marking | `○/◐` KGet SQLite/XML history + restore [K9][K1] is the closest; others `Unclear`/`Partial` — no XDG session + ordered-event contract documented | Survives crashes/restarts with ordered events and explicit interruption handling |
| **Unified multi-engine queue** | ✅ aria2 (HTTP/HTTPS/FTP/SFTP/BT/magnet/Metalink) + yt-dlp + HTTrack + curl fallback in one queue with engine-adaptive settings | `◐` Each competitor covers a subset: Varia aria2+yt-dlp [V1] · Motrix HTTP/FTP/BT/Magnet [M1] · Persepolis Python lib + yt-dlp (no longer aria2) [P1] · AB protocol list unclear [A1] · KGet FTP/HTTP/Metalink+BT plugin [K1][K3] · FileCentipede HTTP/FTP/SSH/WebDAV/BT/m3u8 [F1] · JDownloader plugin-dependent [J1] | One queue instead of juggling separate tools per protocol |
| **Watch & clipboard automation** | ✅ simultaneous `.torrent` + `.metalink`/`.meta4` folder watch (`TorrentFolderMonitor`+`MetaLinkFolderMonitor`) + clipboard polling (`ManagerClipboardService`) | `◐` Varia drag/drop + ext [V1][V2] · Motrix beta clipboard [M2] · Persepolis browser+clipboard [P1] · KGet Konqueror+clipboard [K1][K5] · JDownloader clipboard+Folder Watch `.crawljob` [J2][J3] · FileCentipede no watch established [F1] — none documents dual torrent+Metalink watch + clipboard together | Drop a torrent *or* Metalink file and ODM routes it to `addTorrent`/`addMetalink` automatically |
| **Weekly scheduler** | ✅ weekday time spans with inclusive/exclusive windows + presets (`ScheduleManager`, `WeeklySchedule`, `TimeRange`, `DownloadScheduler`, `pauseReason=SCHEDULE`) | `✅/◐/○` Varia weekday spans [V4] · Persepolis scheduling [P1][P2] · AB queues/schedulers [A1] · IDM timed queues [I1][I4] · KGet scheduler not found [K2][K4] · Motrix/JDownloader/FileCentipede `Unclear`/`Partial` [J2][J3][F1] | Unattended downloads with explicit outside/inside-window policy |
| **Post-completion pipeline** | ✅ checksum (auto-detected `.sha256` via `ChecksumProbe`) + AV scan + subtitle via Subliminal + move + command + sound + suspend/shutdown — each with severity, persisted `CompletionActionResult` output logs and `contributesToFinalizingProgress` pulse | `◐` KGet checksum/verify [K8][K7] · JDownloader SFV/CRC/MD5/SHA [J8]+extract [J7]+shutdown [J10] · AB checksum [A2] · FileCentipede checksum tool [F1] · IDM AV hook [I1] — none documents the full chain with output logs + severity + Actions-tab history | Verification → scan → subtitles → file ops → power actions in one auditable chain |
| **First-class site mirroring** | ✅ HTTrack as dedicated `Type.WEBSITE_SCRAPING` with depth/filter/rate controls | `○/◐` FileCentipede/KGet/JDownloader have broad or plugin crawling [F1][J1] · IDM Grabber [I5] but Windows-only [I2] — no HTTrack-native type documented | Mirroring is a first-class type, not a plugin side-effect |
| **Distribution packaging** | ✅ `.deb` / `.rpm` / `.pkg.tar.zst` with bundled `jlink` Java 25 runtime (no system Java), desktop entry + icons | `◐` Motrix AppImage/Snap/AUR/Flatpak [M1] · Varia Flatpak-first [V1] · AB Linux/Win/macOS+ARM [A1][A2] · Persepolis Linux bundle [P1] · FileCentipede Linux/Win artifacts [F2] · IDM Windows-only [I2] | Install natively on Debian/Fedora/Arch without a system JDK |

Legend: ✅ documented full · ◐ partial/documented with limits · ○ `Unclear` in inspected first-party sources (not a claim of absence) · ✕ unsupported / Windows-only / stopped. Shorthand citations map to the research doc — e.g. [V1] is Varia's README, [I2] is IDM's Linux FAQ.

<details>
<summary>Full per-product matrix — same sources, wide table</summary>

| Area | ODM | Varia | Motrix | Persepolis | AB DM | KGet | JDownloader | FileCentipede | IDM | uGet |
|---|---|---|---|---|---|---|---|---|---|---|
| Native Linux + fully open | ✅ GTK4/Libadwaita, open | ◐ GTK4, `next` dev [V1] | ✕ Electron [M1] | ◐ Python/PySide [P1] | ◐ Linux/Win/macOS [A1] | ◐ KDE [K1] | ◐ Java platform [J1] | ◐ closed deps [F1] | ✕ Win-only [I2] | ✕ stopped [U1] |
| Tor + proxychains + proxy rotation | ✅ Tor lifecycle+health+rotation | ○ no Tor/proxy [V3][V5] | ○ mock UA only [M1] | ◐ SOCKS5 [P1] | ◐ proxy/PAC [A2] | ○ retry only [K7] | ◐ SOCKS4/5 [J5] | ◐ proxy mgmt [F1] | ◐ proxy [I1] | ○ Unclear |
| Recovery & XDG state + ordered events | ✅ JSON+SQLite+session+`odm-events` | ○ Unclear | ○ Unclear | ○ Unclear | ◐ queues/API [A3] | ◐ history/restore [K9] | ◐ plugin state [J9] | ○ Unclear | ◐ auto-resume [I1] | ○ Unclear |
| aria2+yt-dlp+HTTrack+curl together | ✅ all four | ◐ aria2+yt-dlp [V1] | ◐ aria2 only [M1] | ◐ own lib+yt-dlp [P1] | ○ unclear [A1] | ◐ HTTP/FTP/Metalink+BT [K1][K3] | ◐ plugin-driven [J1] | ◐ HTTP/FTP/SSH/WebDAV/BT/m3u8 [F1] | ◐ HTTP/FTP/MMS [I1] | ○ Unclear |
| Dual `.torrent`+`.meta4` watch + clipboard | ✅ both + clipboard | ◐ drop+ext [V1][V2] | ◐ beta clipboard [M2] | ◐ browser+clipboard [P1] | ◐ browser+CLI [A1] | ◐ clipboard+drop [K1][K5] | ◐ clipboard+Folder Watch [J2][J3] | ○ capture only [F1] | ◐ browser+Grabber [I1][I5] | ○ Unclear |
| Weekly scheduler | ✅ weekday spans+presets [V4] | ✅ weekday spans [V4] | ○ Unclear | ✅ scheduling [P1] | ✅ queues/schedulers [A1] | ◐ groups, no scheduler [K4] | ◐ auto-confirm/watch [J2][J3] | ○ concurrency only [F1] | ✅ timed queues [I1][I4] | ○ Unclear |
| Completion: checksum/AV/subs/move/cmd/shutdown | ✅ all + persisted logs | ○ Unclear | ○ UA only [M1] | ○ Unclear | ◐ checksum [A2] | ◐ checksum/verify [K8] | ◐ hashes+extract+shutdown [J7][J8][J10] | ◐ checksum [F1] | ◐ AV hook [I1] | ○ Unclear |
| HTTrack mirroring as type | ✅ `WEBSITE_SCRAPING` | ○ Unclear | ○ Unclear | ○ Unclear | ○ Unclear | ○ Unclear | ◐ plugin crawl [J1] | ○ tool-assisted [F1] | ◐ Grabber [I5] | ○ Unclear |
| Bundled-runtime Linux packages | ✅ deb/rpm/pkg+`jlink` | ◐ Flatpak [V1] | ✅ AppImage/Snap/etc [M1] | ◐ Linux bundle [P1] | ✅ themes/ARM [A1][A2] | ◐ KDE pkg [K1] | ◐ installer [J1] | ◐ artifacts [F2] | ✕ Win-only [I2] | ○ Unclear |

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

- [aria2](https://github.com/aria2/aria2) ![GitHub stars](https://img.shields.io/github/stars/aria2/aria2?style=social) — multi-protocol download engine (HTTP/FTP, BitTorrent, Metalink) over local JSON-RPC
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) ![GitHub stars](https://img.shields.io/github/stars/yt-dlp/yt-dlp?style=social) — video and media extraction with format discovery
- [HTTrack](https://github.com/xroche/httrack) ![GitHub stars](https://img.shields.io/github/stars/xroche/httrack?style=social) — website mirroring with depth and filter controls
- [curl](https://github.com/curl/curl) ![GitHub stars](https://img.shields.io/github/stars/curl/curl?style=social) — process-based HTTP fallback and proxy-capable downloads
- [proxychains-ng](https://github.com/rofl0r/proxychains-ng) ![GitHub stars](https://img.shields.io/github/stars/rofl0r/proxychains-ng?style=social) — SOCKS/HTTP proxy chaining
- [Tor](https://www.torproject.org/) ![GitHub stars](https://img.shields.io/github/stars/torproject/tor?style=social) — optional privacy routing through the local SOCKS service
- [java-gi](https://github.com/jwharm/java-gi) ![GitHub stars](https://img.shields.io/github/stars/jwharm/java-gi?style=social) — native GTK4 desktop interface from Java

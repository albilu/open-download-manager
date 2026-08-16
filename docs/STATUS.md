# Project Status

Last verified: 2026-08-16 (Steps 0–2 of `plans/complete-odm.md` complete — see commit log).

## Done (this round)

- **Step 0**: repo hygiene — `.gitignore`, untracked 655 `target/` paths, removed crash log/screenshot/torrents/glade backups/144 MB dead embedded binaries, fixed `docker-build.sh` + CI, purged false docs (archive in `docs/archive/`).
- **Step 1**: `changeSettings()` implemented in all 5 live handlers (aria2 via `changeOption` RPC; process tools restart-with-new-settings); YouTube URL detection fixed; `GlobalSettings.save()/load()` real XDG persistence + documented validation clamping; dead code deleted (proxy rotation, `RetryableDownloadHandler`, `DependencyValidator`, `TorUtilityFactory`, `MetaLinkFolderMonitor`, empty services, `logback.xml`, slf4j-simple).
- **Test infra**: surefire fork-per-class isolation — fixed the `ApplicationFactory` static-singleton poisoning between test classes. Suite was **120 red at baseline, now 92** (all pre-existing product/test bugs, now honestly isolated).
- **Step 2**: event threading contract — all `DownloadListener` notifications dispatched on a single `odm-events` thread (serial, ordered, exception-isolated); contract documented in `DownloadListener` javadoc; ODM state file moved to `~/.local/share/odm/`.
- **Feature restoration** (were unwired, not dead): **proxy rotation** (`ProxyRotationManager` + `RetryableDownloadHandler` — bypass rate limits/IP blocks by rotating proxies from a list; wired into the manager for ARIA2/CURL downloads, settings persisted) and **Metalink folder monitoring** (`MetaLinkFolderMonitor` wired alongside `TorrentFolderMonitor`; local `.metalink`/`.meta4`/`.torrent` files now dispatch correctly to aria2's `addMetalink`/`addTorrent`). Fixed while wiring: infinite-retry-loop bug (retry counter reset on proxy change), placeholder "default proxies" defeating empty detection, `addTorrent` ClassCastException, `addMetalink` array-result typing, `file://` dispatch gap, `fromTorrent` missing URI.
- **Step 3 (GO)**: UI spike passed the gate — JDK 25 + java-gi 1.0.0-RC3 (GTK 4.22.4 in Docker image), new `odm-gtk4` module, main window live from a GTK4 `.ui`, all core→UI events marshaled via one `GLib.idleAddOnce` point (`UiThread`). 10-minute soak under Xvfb with real aria2: 4 full download cycles, 0 errors, 0 GTK/thread warnings, RSS plateau (no leak). Decision: **full port proceeds** (Steps 4–7).
- **Step 4 (core scope)**: GTK4 window framework + 4 dialogs — `Widgets.require` (fail-fast widget lookup) + programmatic signal connections make the old missing-ID/dead-handler bug classes unshippable; `WindowSmokeTest` enforces it (4/4 green). Working: MainWindow (selection + add/pause/resume/cancel), NewDownloadDialog (URL/torrent/destination/aria2 options), SettingsDialog (all controls real, persists via `GlobalSettings.save()`, applies runtime toggles), ImportListDialog (executes — old one was a TODO shell), AboutDialog. `OdmApplication` boots clean under Xvfb. Deferred to Step 5: property/import-sequence/start-shutdown dialogs, menus, category filtering.

## What works

- **core**: aria2 JSON-RPC engine (HTTP + WebSocket, true pause/resume, session save, reconnect), curl/yt-dlp/httrack process-spawn handlers with parsed progress, download queue with concurrency cap, JSON state persistence + auto-resume, paginated download repository, after-completion actions (checksum, AV scan, move, sound, shutdown), clipboard monitor, torrent folder watch, external tool discovery, and a real-process integration test suite (runs in Docker).
- **Build**: Maven multi-module (`core`, `jgtk`, `odm-gtk`); Docker image provides aria2/yt-dlp/httrack/tor/proxychains/Xvfb. `make compile | test | run | package`.

## Known defects (verified; being fixed per plan)

- **jgtk/odm-gtk UI is unstable by design**: GTK calls from background threads (JVM SIGSEGV in native `gtk_list_store_set` — see commit history for the crash log), no native memory management (leaked windows/strings/signals), 29 unwired glade handlers, 34 phantom signal registrations, 32 missing widget IDs (25 in Settings). → **Replaced with java-gi (GTK4) — plan Steps 3–6.**
- **92 pre-existing red tests** remain (HttrackSettings validation, UrlDetector normalization, folder-monitor integration, yt-dlp/proxychains integration, misc). Suite was never green; these predate this round. → rehab backlog.
- Tray/background mode, scheduler UI, website scraper UI, import-list execution: unimplemented. → plan Steps 4–5.

## Doc policy

- Historical AI-generated docs live in `docs/archive/` — **accuracy not guaranteed**; several described code that never existed and were deleted.
- This file is the only status source of truth. Update it as work lands. **Do not create per-fix `*_SUMMARY.md` docs.**
- Roadmap: `plans/complete-odm.md`.


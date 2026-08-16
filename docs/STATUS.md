# Project Status

Last verified: 2026-08-16 (full codebase review — see `plans/complete-odm.md` for the roadmap).

## What works

- **core**: aria2 JSON-RPC engine (HTTP + WebSocket, true pause/resume, session save, reconnect), curl/yt-dlp/httrack process-spawn handlers with parsed progress, download queue with concurrency cap, JSON state persistence + auto-resume, paginated download repository, after-completion actions (checksum, AV scan, move, sound, shutdown), clipboard monitor, torrent folder watch, external tool discovery, and a real-process integration test suite (61 test files, runs in Docker).
- **Build**: Maven multi-module (`core`, `jgtk`, `odm-gtk`); Docker image provides aria2/yt-dlp/httrack/tor/proxychains/Xvfb. `make compile | test | run | package`.

## Known defects (verified; being fixed per plan)

- **jgtk/odm-gtk UI is unstable by design**: GTK calls from background threads (JVM SIGSEGV in native `gtk_list_store_set` — see commit history for the crash log), no native memory management (leaked windows/strings/signals), 29 unwired glade handlers, 34 phantom signal registrations, 32 missing widget IDs (25 in Settings). → **Replaced with java-gi (GTK4) — plan Steps 3–6.**
- `changeSettings()` unimplemented in all 6 download handlers. → Step 1
- YouTube URLs never auto-detected (unreachable branch in `Download(URI)`). → Step 1
- `GlobalSettings.save()` no-op; app state written into `~/Downloads`. → Steps 1–2
- Dead code: proxy rotation stack, scheduler (unwired), `DependencyValidator`, `TorUtilityFactory`, `MetaLinkFolderMonitor`. → Step 1

## Doc policy

- Historical AI-generated docs live in `docs/archive/` — **accuracy not guaranteed**; several described code that never existed and were deleted.
- This file is the only status source of truth. Update it as work lands. **Do not create per-fix `*_SUMMARY.md` docs.**
- Roadmap: `plans/complete-odm.md`.

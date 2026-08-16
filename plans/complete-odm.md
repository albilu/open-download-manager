# Plan: Stabilize Core, Replace jgtk, Complete Open Download Manager

**Objective:** Take ODM from its current state (working core engine, fatally unstable hand-rolled GTK3/JNA UI) to a complete, stable MVP.

**Key context (verified 2026-08-16):**
- `core` (100 files, ~38k lines): aria2 JSON-RPC + curl/yt-dlp/httrack process handlers work and have a real integration-test suite. Main defects: `changeSettings()` throws `UnsupportedOperationException` in all 6 handlers; `Download(URI)` never detects YouTube (http branch checked first); `GlobalSettings.save()` is a no-op; ~4k lines of dead code (proxy rotation, scheduler, DependencyValidator, embedded binaries).
- `jgtk` + `odm-gtk`: the source of all instability. GTK calls from background threads (crash log `hs_err_pid1.log` proves it: `gtk_list_store_set` from aria2 poller thread), `isMainGtkThread()` hard-coded `true`, `SwingUtilities.invokeLater` misused as "GTK dispatch", no native memory management (no `g_free`, windows never destroyed, signals never disconnected), 29 unwired glade handlers / 34 phantom registrations / 32 missing widget IDs (25 in Settings).
- Decision taken: **replace jgtk with java-gi** (`org.java-gi:gtk:1.0.0-RC3`, GTK 4.22, automatic memory management, type-safe signals, `@GtkTemplate`). Requires **JDK 25** (LTS). Core module is toolkit-agnostic and unaffected.
- Tests run in Docker: `make test` (or `docker-build.sh test`). Compile check: `make compile`.

**Dependency graph:** 0 → {1, 2} (parallel) → 3 → 4 → {5, 6} (parallel) → 7

---

## Step 0 — Repo hygiene & truth-telling (1 PR)

**Context:** No `.gitignore`; ~655 `target/` paths tracked; crash log, screenshot, `.glade~` backups and two copyrighted-content torrents committed; 144 MB of unused embedded binaries in `core/src/main/resources`; `docker-build.sh` header corrupted (`set -e` commented out); CI (`.github/workflows/build-linux.yml`) runs on a bare runner without aria2/Xvfb and uses `fpm` without installing it; ~70 docs in `docs/`, several describing code that does not exist.

**Tasks:**
- Add `.gitignore` (`target/`, `*.log`, `.glade~`, `data/`, `*.torrent`, IDE files optional); `git rm -r --cached` all tracked `target/` output.
- Delete: `hs_err_pid1.log`, `2025-08-30_14-56.png`, `docker-data/*.torrent` (legal liability), `.glade~`/`*.backup` files.
- Delete embedded binaries (`core/src/main/resources/{tor,curl,ytdlp}`) — the mechanism is unreachable dead code; rely on system tools + package dependencies.
- Fix `docker-build.sh` header; restore `set -e`.
- Fix CI: run build/tests inside the project Docker image (or install aria2/yt-dlp/httrack/tor/xvfb on the runner); remove or repair the `fpm` rpm step.
- Purge `docs/`: delete docs describing non-existent code (Cucumber suite, `DependencyManager`, `GtkMethodSignatureTest`, root-level `packaging/`); move the rest to `docs/archive/`; write one truthful `docs/STATUS.md`.

**Verify:** `make compile` green; `git ls-files | grep -c target/` → 0; repo size audit (`du -sh .git`).
**Exit criteria:** clean tree, honest docs, working build script.

## Step 1 — Core: kill stubs & dead code (parallel with Step 2)

**Context:** `changeSettings` stubs at `Aria2DownloadHandler:863`, `CurlDownloadHandler:143`, `YtDlpDownloadHandler:290`, `HttrackDownloadHandler:381`, `ProxychainsDownloadHandler:307`, `RetryableDownloadHandler:290`. YouTube-detection bug at `core/src/main/java/org/manager/download/Download.java:83-95`. Dead code: `org.manager.proxy` (~1,700 lines), `RetryableDownloadHandler`, `org.manager.schedule` (`DownloadScheduler` 494 + `ScheduleManager` 438 — keep for Step 5), `DependencyValidator`, `TorUtilityFactory`, `MetaLinkFolderMonitor`, duplicate `org/aria2/Aria2RpcException.java`, empty `org/manager/download/service/*.java`, 0-byte `logback.xml`.

**Tasks:**
- Implement `changeSettings` in all handlers: aria2 → `Aria2Client.changeOption` (already implemented); process-based tools → restart transfer with new settings (they already restart on resume).
- Fix `Download(URI)`: check YouTube patterns **before** the http/https branch.
- `GlobalSettings.save()` → persist to `~/.config/odm/settings.json`; move `odm-state.json` from `~/Downloads` to `~/.local/share/odm/` (XDG dirs).
- Delete dead code listed above (keep `org.manager.schedule` — wired in Step 5).
- Pick ONE logging approach (JUL per AGENTS.md): remove `logback.xml`, drop slf4j-simple if unused.

**Verify:** `make test` green in Docker; new tests for `changeSettings` (aria2 integration test exists to extend) and YouTube URL typing.
**Exit criteria:** zero `UnsupportedOperationException` in main sources; no dead packages.

## Step 2 — Core: event threading contract (parallel with Step 1)

**Context:** Listener notifications fan out on arbitrary tool threads (aria2 poller, yt-dlp monitor, curl reader) — `AbstractDownloadHandler.notify*` and `DownloadManagerImpl` (7 duplicated notify blocks, `:916-985`). This pushes thread-confinement onto every consumer and is the core-side enabler of the UI crash.

**Tasks:**
- Add a single dedicated `odm-events` executor in `DownloadManagerImpl`; ALL `DownloadListener` notifications are dispatched through it (serialized, order-preserving).
- Document the contract in `DownloadListener` Javadoc: "invoked on the odm-events thread; consumers must marshal to their UI thread."
- Remove `System.gc()` calls and `Thread.sleep` from notification paths; deduplicate the 7 notify blocks into one generic dispatcher.
- Do NOT put toolkit code in core — the seam stays toolkit-agnostic.

**Verify:** new test asserting ordered, single-threaded delivery; `make test` green.
**Exit criteria:** all listener callbacks provably on one thread.

## Step 3 — UI spike: JDK 25 + java-gi + main window (blocks 4–6)

**Context:** java-gi requires OpenJDK 25+. Glade files are GTK3-flavored; GTK4 still ships GtkBuilder and (deprecated) GtkTreeView, so a mechanical port is possible; migrate to `GtkColumnView` later. `odm-gtk` business logic (~15k lines in controllers/services) ports; jgtk does not.

**Tasks:**
- Bump project to JDK 25 (pom properties, Dockerfile: install temurin-25 or use `eclipse-temurin:25` base, CI).
- New Maven module `odm-gtk4` depending on `core` + `org.java-gi:gtk` + `org.java-gi:adw`.
- Port `main-window.glade` → GTK4 `.ui` (property renames; keep TreeView for now).
- Rebuild main window as `@GtkTemplate` class with `@GtkChild`/`@GtkCallback`; all core-event → UI updates via `GLib.idleAdd()` at exactly one marshal point (a small `UiThread` helper).
- Prove the loop: start app, run a real aria2 download, list updates live.

**Verify:** runs under Xvfb; 10-minute soak test with active download — no crash, stable RSS (`/proc` sampling); no GTK warnings about threads.
**Exit criteria:** spike demonstrates stable live updates; GO/NO-GO on full port.
**Rollback:** spike is a separate module; old UI untouched.

## Step 4 — Port remaining windows (depends on 3)

**Tasks:**
- Port remaining 7 glade files to GTK4 `.ui`: new-download, settings, download-property, import-list, import-sequence, about, start-shutdown.
- Rebuild each controller as `@GtkTemplate` class — `@GtkChild`/`@GtkCallback` give compile-time checking, eliminating the widget-ID/signal-mismatch bug class (currently 29 unwired handlers, 34 phantom registrations, 32 missing IDs).
- Settings dialog: wire the 25 previously-missing widgets; `applySettings()` persists via `GlobalSettings.save()`; implement `applyToTypeSpecificSettings` (aria2/yt-dlp/httrack/curl).
- Import-list: implement `processDownloads()`/`handleValidate()` (currently TODO shells).
- Boolean-returning signals (`delete-event`, `state-set`) return real values (java-gi is type-safe — fixes the undefined-behavior exit-cancel bug).

**Verify:** per-window checklist (open, interact, apply, close); `make compile`; manual run per window.
**Exit criteria:** all 8 windows functional; zero log warnings about missing widgets/handlers.

## Step 5 — Feature completion (depends on 4, parallel with 6)

**Tasks:**
- Background mode / tray: StatusNotifierItem via Gio D-Bus (or generated libayatana-appindicator binding); wire `close_to_tray`/`minimize_to_tray`.
- Scheduler UI wired to core `DownloadScheduler` (glade toggle `on_enable_scheduling_check_toggled` currently unwired).
- Website scraper UI → `HttrackDownloadHandler` (currently "coming soon").
- Speed limit + after-completion actions fully wired in UI.
- Window-state save/restore (currently no-op).
- **m4s/DASH manifest derivation (TODO.md item 3):** clarify scope first — yt-dlp already handles DASH/HLS natively; if direct m4s manifest URLs must be supported, implement detection in `UrlDetector` + route to yt-dlp handler (or a dedicated core handler deriving segment URLs for aria2). Size after scoping.

**Verify:** README MVP checklist executed end-to-end in the running app.
**Exit criteria:** every MVP feature demonstrably works.

## Step 6 — Delete jgtk & old odm-gtk (depends on 4, parallel with 5)

**Tasks:** remove `jgtk` and `odm-gtk` modules from parent pom; delete module dirs; drop `libgtk-3-dev` from Dockerfile; rename `odm-gtk4` → `odm-gtk` (artifact + main class `org.odm.OpenDownloadManager`).
**Verify:** full `mvn clean install` + `make test` green.
**Exit criteria:** no JNA GTK code remains.

## Step 7 — Packaging & release hardening (depends on 5, 6)

**Tasks:**
- Update `odm-gtk/packaging/{debian,rpm,arch}` metadata: fix stale version pins (`yt-dlp (<< 2025.01.01)` etc.), add GTK4 runtime dep, embed JDK-25 runtime (jpackage/jlink) or document system requirement.
- Build `.deb`/`.rpm`/`.pkg.tar.zst` in Docker; install-test each in clean containers.
- Re-enable JaCoCo gates (70% instruction / 60% branch) now that coverage is real.
- Consider Flatpak (java-gi app-template is Flatpak-oriented) — optional, post-MVP.

**Verify:** packages install and run on clean Debian/Fedora/Arch containers; CI green end-to-end.
**Exit criteria:** installable artifacts for the README deployment matrix (GTK row).

---

## Anti-patterns to avoid (from project history)

- Do NOT write per-fix `*_SUMMARY.md` docs — update `docs/STATUS.md` only.
- Do NOT keep jgtk alive "just in case" — parallel-module strategy already provides rollback.
- Do NOT add features before thread confinement (Steps 2–3) lands.
- Do NOT mock the core in tests (AGENTS.md); extend the existing real-process integration suites.
- Do NOT commit build output, binaries, crash logs, or licensed content ever again.

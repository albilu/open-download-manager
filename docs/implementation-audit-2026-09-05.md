**Current implementation audit — Open Download Manager**

Audited on 2026-09-05 at commit `3c676de`. Scope: the active `core` and `odm-gtk4` Maven reactor, application startup/shutdown, persistence, native download engines, queue and schedule behavior, proxy/Tor routing, import flows, GTK interactions, completion actions, tests, and Linux packaging/release workflows. `odm-lib` is excluded from the active reactor and was not treated as production functionality.

**Verdict: release blockers remain.** The audit identifies **24 findings: 12 P1 and 12 P2**. The most consequential failures concern routing outside the selected privacy policy, downloads becoming stuck during lifecycle transitions, and imported downloads losing their restart source. The canonical integration suite also fails. No current P0 finding was established. Production code was not changed during this audit.

P1 means a broken core transfer, recovery, privacy, or release guarantee that should be addressed before release. P2 means a narrower feature, user-interface, completion-action, or build-portability defect. Findings marked **reproduced** were exercised against production classes or native tools in isolated temporary directories. **Static** findings have a traced source path but were not exercised as a complete application/network scenario. Test doubles used at an RPC/process/handler boundary are identified explicitly.

**Verification**

| Check | Result |
|---|---|
| `make test` | Passed in 9m55s. Maven reports 2,091 core executions and 163 GTK tests; zero failures, errors, or skips. JaCoCo coverage checks passed. |
| `make test-integration` | Failed in 18m39s: 2,053 core tests, one failure, three errors, one skip. GTK was skipped after the core failure. |
| Focused rerun of the four failing integration methods | Two passed; the aria2 verifier connection error and Curl error-callback timeout failed again. This does not make the full integration suite green. |
| `make package` | Passed; produced Debian, RPM, and Arch packages at version 0.1.0. |
| Archive/runtime checks | Passed: JAR validation and manifest version, bundled `java.net.http`, package payload ownership and required license/launcher files, RPM digests, 98 Arch MTREE SHA256 checks, and 1024×1024 PNG dimensions. |
| Packaged GUI startup | Passed under isolated Docker/Xvfb using the bundled runtime: main window constructed/presented; no fatal startup error. Deliberately interrupted after 20 seconds; timeout exit 124, shutdown completed with 12 hooks and zero failures. |
| Isolated lifecycle, routing, recovery, GTK, and action probes | Reproductions and limitations are recorded with the findings below. |
| Working-tree checks | `git diff --check` passed; tracked production files and index unchanged. This report is local: the existing `.gitignore` ignores `docs/`. |

Full logs: [default suite](/tmp/odm-audit-unit-20260905.log), [integration suite](/tmp/odm-audit-integration-20260905.log), [focused rerun](/tmp/odm-audit-focused-20260905.log), [package build](/tmp/odm-audit-package-20260905.log), [archive checks](/tmp/odm-audit-package-checks-20260905.log), [packaged GUI](/tmp/odm-audit-package-launch-20260905.log). The RPM query initially printed a container database-lock warning while successfully verifying its digests; a [repeat with a temporary RPM database](/tmp/odm-audit-rpm-check-20260905.log) passed cleanly.

The default profile executes some nested tests more than once. Its saved XML contains 1,743 distinct core test cases, all of which are also present in the integration run; the integration run adds 310 cases. The counts above preserve Maven's actual summaries rather than implying that 2,091 distinct core cases exist.

Temporary probe sources, classpaths, and logs are under [/tmp/odm-audit-probes](/tmp/odm-audit-probes). Essential outputs are preserved in this report because temporary files may later be removed. The probes used Java 25 and isolated home/XDG directories; the GTK probes used a separate Xvfb display. Added native network probes used loopback endpoints; the repository's integration tests also exercise external DNS/Tor connectivity.

| Integration case | Full run | Focused rerun | Disposition |
|---|---|---|---|
| `TorServiceIntegrationTest#testTorServiceStart` | 30-second timeout | Passed in 14.3 seconds | External bootstrap timing plus a contradictory test budget: the method waits up to 60 seconds but inherits a 30-second timeout. Not evidence that Tor always fails. |
| `Aria2IntegrationTest#shouldApplyChangeSettingsThroughHandler` | RPC connection refused | Same error | Verifier is hard-coded to 6800; current application default is 6801 (F24). |
| `CurlIntegrationTest#shouldIntegrateCurlSettingsWithCurlClient` | 30-second timeout | Passed | Fixture starts the same model/output through two clients with only one enqueued response; its result depends on ordering (F24). |
| `CurlIntegrationTest#shouldIntegrateErrorHandlingAcrossComponents` | Callback not received within 25 seconds | Same failure | Uses an external nonexistent-domain request with a shorter wait than the default 30-second connect timeout, before retries. Replace this with a bounded local failure fixture; no distinct production defect was established from this timeout alone (F24). |

**Findings**

**F01 — P1 — Global Tor/SOCKS policy silently permits direct website downloads.**

Locations: [HttrackSettings.java:45](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/httrack/HttrackSettings.java:45), [DownloadSettingsFactory.java:237](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadSettingsFactory.java:237), [HttrackDownloadHandler.java:271](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/HttrackDownloadHandler.java:271), [DownloadManagerImpl.java:1660](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:1660).

Enable a global SOCKS proxy, including the Tor switch, then create a website scrape. ODM's `HttrackSettings.supports` declares `PROXY` but omits `SOCKS_PROXY`. Consequently, the factory's capability check rejects every SOCKS address and leaves the new download's proxy settings unset. The handler applies the same check and launches without the proxy's `-P` argument. Applying global Tor to an existing website scrape logs a warning and leaves its route unchanged. A global privacy control therefore does not cover these downloads.

Clarification verified on 2026-09-05: this is an ODM capability/routing defect, not a blanket claim that HTTrack lacks SOCKS support. The host's HTTrack **3.49-13** explicitly advertises `-P [socks5://|connect://][user:pass@]proxy:port`; the current [official command-line guide](https://www.httrack.com/html/cmdguide.html) documents SOCKS5 support too. ODM's Docker image contains **3.49-6**, whose help advertises only the older proxy syntax. Support must account for the actual executable's capabilities; ODM currently rejects SOCKS even for the capable host version.

Evidence: **reproduced settings selection, plus static launch/reroute trace**. With global `socks5h://127.0.0.1:9050`, production settings creation returned `torWebsite.useProxy=false` and `torWebsite.proxyAddress=null`. Host and Docker versions/help were checked separately in the clarification above; no packet-capture anonymity claim is made. Implement a supported SOCKS route for the actual executable, and reject or hold transfers when the selected global route cannot be honored.

**F02 — P1 — Automatic checksum discovery ignores the dialog's selected proxy/Tor route.**

Locations: [NewDownloadDialog.java:280](/home/pain/NetBeansProjects/open-download-manager/odm-gtk4/src/main/java/org/odm/gtk4/NewDownloadDialog.java:280), [ChecksumProbe.java:101](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/ChecksumProbe.java:101).

With global proxying disabled, select Tor or a proxy in the new-download dialog and enter a file URL. Automatic sibling-checksum requests use only `GlobalSettings`, so they receive a null proxy and contact the server directly. These requests happen before Start and reveal the requested resource through sibling URLs. Changing the per-download route also does not invalidate the URL-only probe cache.

Evidence: **static**; the dialog supplies the global proxy directly to `ChecksumProbe`, while the transfer uses `networkOptions.applyTo`. Use the dialog's effective route and route readiness for all preview traffic, and include routing changes in probe invalidation.

**F03 — P1 — Changing or disabling an inherited HTTP proxy does not update active aria2 tasks.**

Locations: [Aria2DownloadHandler.java:975](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/Aria2DownloadHandler.java:975), [DownloadManagerImpl.java:1632](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:1632).

Start an aria2 download with global HTTP proxy A, then switch to B or disable global proxying. The factory has already copied A into the download's settings. `applyGlobalRuntimeOptions` mistakes those inherited settings for a per-download override and skips the GID. The manager subsequently edits the Java settings but explicitly omits `changeSettings` for an aria2 handler. The running transfer keeps the old route while settings indicate the new one.

Evidence: **reproduced at the RPC boundary** using the real factory and handler with a recording `Aria2Client`: changing inherited A to B produced `httpProxy.updateCalls=0`. Track proxy provenance consistently and send the effective update to every owned GID.

**F04 — P1 — Proxychains ignores authenticated/IPv6 proxy selections.**

Locations: [ProxychainsClient.java:226](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/proxychains/ProxychainsClient.java:226), [ProxychainsClient.java:433](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/proxychains/ProxychainsClient.java:433).

The launch path splits the proxy authority on `:` and generates an explicit config only when it obtains exactly two components. Valid credential-bearing and IPv6 addresses fail that test. The command then omits `-f`, leaving proxychains to use its system configuration instead of the selected proxy. Credentials are not encoded by this parser. The blanket removal of `h` from the scheme also turns `http` into `ttp`.

Evidence: **reproduced command launch** with a capture executable. An ordinary `socks5h://127.0.0.1:9050` supplied `-f`; `socks5://audit:secret@127.0.0.1:1080` and `socks5://[::1]:1080` did not. This establishes wrong-route/config selection, not that every system configuration allows direct traffic. Use the existing structured proxy parser, serialize credentials correctly, and reject unsupported selections before launch.

**F05 — P1 — Local torrent and Metalink files cannot start through the SOCKS/proxychains route.**

Locations: [Download.java:273](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/Download.java:273), [DownloadHandlerFactory.java:295](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/DownloadHandlerFactory.java:295), [ProxychainsClient.java:532](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/proxychains/ProxychainsClient.java:532).

Local descriptors are represented as `file:` URIs. SOCKS routing correctly selects the required proxychains backend, but its CLI builder passes that URI unchanged as the final aria2 argument. aria2 expects a local descriptor filename in this position and rejects the URI before processing its contents. Curl fallback is intentionally unavailable for these protocols, leaving a broken supported route.

Evidence: **native reproduction**: `aria2c --no-conf --enable-dht=false --enable-dht6=false --enable-peer-exchange=false file:///tmp/…/source.torrent` exited 1 with `Unrecognized URI or unsupported protocol`. Convert local descriptor URIs to validated paths and use the correct torrent/Metalink CLI input form while retaining proxychains routing.

**F06 — P1 — Consumed staged descriptors are deleted while restart recovery still depends on them.**

Locations: [Aria2DownloadHandler.java:1801](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/Aria2DownloadHandler.java:1801), [Aria2DownloadHandler.java:1907](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/Aria2DownloadHandler.java:1907), [DownloadManagerImpl.java:1872](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:1872), [DownloadManagerImpl.java:924](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:924).

Folder imports and the manual descriptor-to-Trash flow create ODM-owned staged descriptor files. Successful `addTorrent`/`addMetalinkAll` immediately deletes those files, but the persisted download URI continues to reference them. Restart recovery clears old GIDs and re-ingests the source. The source no longer exists, so recovery or a fresh retry fails even if partial payload files remain.

Evidence: **reproduced filesystem and handler behavior with successful stub RPC ingestion** for both formats: `sourceExistsAfterIngestion=false`, followed by `Torrent file not found or not readable` / `Metalink file not found or not readable` on re-ingestion. Retain the descriptor, or durably persist an equivalent restart source, until the download no longer needs recovery.

**F07 — P1 — Curl/proxychains launch races can leave a download running in the UI with no process.**

Locations: [CurlDownloadHandler.java:89](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/CurlDownloadHandler.java:89), [CurlClient.java:183](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/curl/CurlClient.java:183), [CurlClient.java:474](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/curl/CurlClient.java:474), [ProxychainsClient.java:258](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/proxychains/ProxychainsClient.java:258), [DownloadManagerImpl.java:590](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:590).

The clients set `CONNECTING` and launch on their own executor, while the handler returns a successful start future immediately. The manager can write `DOWNLOADING` before the client performs its `CONNECTING -> DOWNLOADING` compare-and-set. That compare-and-set fails, so the client kills the new process and returns without a terminal callback. Resume has the same conflict because it emits `onDownloadResume` immediately after scheduling the new process. Proxychains has the equivalent launch guard and early resume notification.

Evidence: **controlled native Curl reproduction** held the worker before launch and applied the same status mutation as the manager's resume listener. Result: `workerFinished=true`, `status=DOWNLOADING`, `terminalCallbacks=0`. Proxychains' equivalent path was traced statically. Give process launch/status transitions one owner and settle start/resume futures only at a defined launch boundary; every abandoned launch must settle its lifecycle outcome.

**F08 — P1 — Late start/resume success can resurrect paused or completed downloads.**

Locations: [DownloadManagerImpl.java:580](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:580), [DownloadManagerImpl.java:873](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:873), [DownloadManagerImpl.java:937](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:937).

Two interleavings remain despite the new generation/admission protection. First, pause releases the slot without invalidating an outstanding start result; that result can overwrite `PAUSED`. Second, a completion callback during `resumeDownload().join()` releases the slot and marks the attempt terminal, but the resume success path checks only generation identity and writes `DOWNLOADING` afterward.

Evidence: **reproduced through the real manager with a controllable handler**. Both late-start-after-pause and completion-during-resume ended with `status=DOWNLOADING` and `runningCount=0`. This breaks both displayed state and concurrency accounting. Make terminal/pause transitions authoritative over outstanding operations and validate expected state as well as generation at each commit.

**F09 — P1 — A paused download waiting for capacity is restarted instead of resumed.**

Locations: [DownloadManagerImpl.java:919](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:919), [DownloadManagerImpl.java:1453](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:1453).

Set concurrency to one. Start A, pause A, start B, then resume A while B holds the slot. A becomes `QUEUED` while its paused handler/task remains owned. When B finishes, the queue pump calls `startDownloadInternal(A)` rather than resuming A's existing task. This creates a replacement submission and can leave the original paused aria2 task behind.

Evidence: **reproduced manager sequence with a counting handler**: `waiting=QUEUED`, three total start calls, zero resume calls. Preserve the requested operation when admission is deferred and resume an existing owned task when its slot becomes available.

**F10 — P1 — Proxy-retry error handling stops working after a normal resume.**

Locations: [RetryableDownloadHandler.java:146](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/RetryableDownloadHandler.java:146), [RetryableDownloadHandler.java:362](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/RetryableDownloadHandler.java:362), [RetryableDownloadHandler.java:514](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/RetryableDownloadHandler.java:514), [DownloadManagerImpl.java:2720](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:2720).

The wrapper captures the download's attempt generation only at start. The manager increments that generation during resume, but the same wrapper does not adopt it. All subsequent errors fail `ownsCurrentGeneration` and are returned as `STALE`. The manager deliberately skips error handling and slot release for that decision, so the resumed transfer neither retries correctly nor completes normal terminal cleanup.

Evidence: **reproduced wrapper lifecycle** with a simple delegate and the generation increment performed by the manager: `retry.resumeErrorDecision=STALE`. Keep wrapper ownership aligned with legitimate resume attempts while continuing to reject callbacks from superseded attempts.

**F11 — P1 — Automatic pause ownership is lost across application restarts.**

Locations: [DownloadScheduler.java:32](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadScheduler.java:32), [DownloadScheduler.java:503](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadScheduler.java:503), [OfflineModeController.java:26](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/OfflineModeController.java:26), [DownloadManagerImpl.java:1922](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:1922).

Let scheduling pause a transfer, exit, and reopen before the next permitted window. The schedule itself persists, but `pausedBySchedule` does not. The restored row is `PAUSED`, is absent from the active-download recovery set, and cannot pass the scheduler's ownership test. Offline Mode has the same persistence gap in `pausedByOffline`. Automatically paused downloads are therefore indistinguishable from manually paused ones after restart and remain stopped.

Evidence: **reproduced scheduler reconstruction with restored download settings**: an allowed per-download schedule was restored, but the row remained `PAUSED` with zero resume calls. The corresponding Offline Mode restart path was traced statically. Persist the reason/owner of a pause and restore that ownership without automatically resuming user-paused items.

**F12 — P2 — Removing all schedule restrictions leaves scheduler-paused downloads stopped.**

Locations: [DownloadScheduler.java:453](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadScheduler.java:453), [SettingsDialog.java:668](/home/pain/NetBeansProjects/open-download-manager/odm-gtk4/src/main/java/org/odm/gtk4/SettingsDialog.java:668).

After the global Never/restricted schedule pauses a transfer, select Always or disable scheduling. With no per-download schedules, `checkSchedules` immediately returns when the global schedule has no restrictions. It never reaches the resume logic for downloads it already paused.

Evidence: **reproduced production scheduler checks**: `afterNever=PAUSED`, `afterAlways=PAUSED`, `resumeCalls=0`. Process owned pauses before taking the unrestricted fast path, including when settings disable scheduling.

**F13 — P2 — Returning online does not start downloads queued while offline.**

Locations: [OfflineModeController.java:49](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/OfflineModeController.java:49), [OfflineModeController.java:85](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/OfflineModeController.java:85).

Enable Offline Mode with no active downloads, add a download, then return online. The controller resumes only IDs that it paused. It never asks the manager to reconsider existing queued work. With no other completion/admission event to trigger the queue pump, the newly queued download stays stopped even though the offline gate is open.

Evidence: **reproduced with the real manager and controller**: `offlineQueue.finalStatus=QUEUED`, `offlineQueue.startCalls=0`. Reevaluate eligible queued work after the online transition completes.

**F14 — P2 — “Delete with Files” leaves completed or recovered HTTrack output behind.**

Locations: [HttrackDownloadHandler.java:222](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/HttrackDownloadHandler.java:222), [HttrackDownloadHandler.java:321](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/HttrackDownloadHandler.java:321), [DownloadManagerImpl.java:1217](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:1217).

HTTrack completion removes the download-to-job mappings. Cancellation performs all work, including file deletion, only when that mapping exists. The manager treats the completed future as success and removes the history record anyway. A recovered record also lacks the in-memory mapping, so the advertised deletion silently becomes history-only removal.

Evidence: **reproduced completed-record handler call with a real temporary output file**: `fileStillExists=true` after `cancelDownload(download, true)`. Add a confined deletion path based on persisted owned output paths when no live job exists.

**F15 — P2 — Changes to a paused website scrape are ignored on Resume.**

Locations: [HttrackDownloadHandler.java:247](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/HttrackDownloadHandler.java:247), [HttrackDownloadHandler.java:422](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/HttrackDownloadHandler.java:422), [HttrackClient.java:217](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/httrack/HttrackClient.java:217).

The live job receives a copy of the download settings. Changing properties while paused updates the download but leaves the job's copy intact because `changeSettings` rebuilds only actively running jobs. Resume then copies the old job settings again. Updated limits, headers, proxy, or crawl options are not applied to that resumed process.

Evidence: **static** across copy, settings-change, and resume paths. Rebuild or replace the paused job's effective settings before it launches again; preserve the HTTrack continuation mode and cache.

**F16 — P2 — Website progress stores file counts in byte fields.**

Location: [HttrackDownloadHandler.java:308](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/handler/HttrackDownloadHandler.java:308).

Progress callbacks assign `totalFiles` to `Download.size`, and `filesDownloaded` to `Download.downloaded`, then emit those values through the shared byte-count listener. Completion again sets downloaded bytes to the total file count. Shared UI/statistics code formats those fields as byte sizes, so a site with hundreds of files can appear to contain only hundreds of bytes and distort aggregate totals.

Evidence: **static**, with explicit file-count assignments in the production callback. Keep file progress separate from byte totals and represent an unknown byte total as unknown.

**F17 — P2 — Media “Fetch Info” can remain disabled after input changes.**

Locations: [NewMediaDialog.java:190](/home/pain/NetBeansProjects/open-download-manager/odm-gtk4/src/main/java/org/odm/gtk4/NewMediaDialog.java:190), [NewMediaDialog.java:226](/home/pain/NetBeansProjects/open-download-manager/odm-gtk4/src/main/java/org/odm/gtk4/NewMediaDialog.java:226).

The method disables its buttons before synchronous URI/authentication/proxy validation, outside the asynchronous error handler. A malformed URI throws before any restoration path is installed. Separately, changing the URL during a successful fetch causes its success callback to be discarded without restoring the Fetch button. The URL-change handler restores only Start.

Evidence: **reproduced with real GTK widgets**, using a controllable metadata client for the second case. Invalid input raised `IllegalArgumentException`; Fetch remained insensitive after correction and after a successful response for a superseded URL. Validate before disabling controls and restore operation state when a request is superseded or completes.

**F18 — P2 — Download dialogs retain invalid drafts and can retry stale input.**

Locations: [NewDownloadDialog.java:537](/home/pain/NetBeansProjects/open-download-manager/odm-gtk4/src/main/java/org/odm/gtk4/NewDownloadDialog.java:537), [NewDownloadDialog.java:630](/home/pain/NetBeansProjects/open-download-manager/odm-gtk4/src/main/java/org/odm/gtk4/NewDownloadDialog.java:630), [NewMediaDialog.java:298](/home/pain/NetBeansProjects/open-download-manager/odm-gtk4/src/main/java/org/odm/gtk4/NewMediaDialog.java:298), [NewWebsiteDialog.java:149](/home/pain/NetBeansProjects/open-download-manager/odm-gtk4/src/main/java/org/odm/gtk4/NewWebsiteDialog.java:149), [DownloadManagerImpl.java:2340](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/DownloadManagerImpl.java:2340).

The manager's create methods insert a history record before dialog-specific validation runs. Invalid filenames, proxy choices, or media options can therefore leave a `CREATED` row even though the UI rejected submission. Some validation failures happen before `pendingDownload` is assigned, so repeating Start creates more rows. After an asynchronous queue failure, retry reuses the pending record even if the user changed its URL/destination; the general dialog also skips reapplying edited options. Closing a failed draft does not remove its record.

Evidence: **reproduced with real GTK widgets and the production manager**. Submitting an invalid filename added one repository row; repeating the same rejected submission left two rows. The stale retry URL/options path was traced statically. Validate a draft before repository insertion, and explicitly reconcile edited input or discard the draft when retrying/canceling.

**F19 — P2 — The chkrootkit/rkhunter completion backends do not scan the downloaded file.**

Location: [AntivirusCheckAction.java:228](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/action/AntivirusCheckAction.java:228).

The chkrootkit command receives `-p <download-parent>`, which selects the directory of trusted helper executables rather than a file to scan. This also incorrectly treats a download directory as a source of scanner utilities. rkhunter receives `--pkgmgr <download-file>`, although that option expects a package-manager selection. These are system-rootkit tools invoked with unrelated/invalid parameters, so their output cannot establish that the downloaded payload was scanned. The meanings are documented in the [chkrootkit FAQ](https://www.chkrootkit.org/faq/) and [rkhunter manual](https://manpages.debian.org/unstable/rkhunter/rkhunter.8.en.html).

Evidence: **reproduced production command construction and checked against primary documentation**. No system rootkit scan or execution of downloaded utilities was performed. Offer these tools only for an accurately described supported operation, or use a scanner capable of scanning the downloaded payload.

**F20 — P2 — Antivirus result parsing produces false threat reports from filenames.**

Location: [AntivirusCheckAction.java:144](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/action/AntivirusCheckAction.java:144).

The parser searches every stdout line for words such as `virus`, `found`, or `rootkit`, without distinguishing filenames, clean summaries, and scanner verdicts. A successful clean result such as `/tmp/antivirus-manual.txt: OK` contains `virus` in the path and becomes “Threats detected.”

Evidence: **reproduced action execution with a scanner stub emitting the clean-result line and exit code 0**: `cleanResultSuccess=true`, `cleanResultOutcome=Threats detected`. Parse each supported scanner's result/exit contract and separate the path from the verdict.

**F21 — P2 — Custom antivirus commands fail on spaces and quoting.**

Location: [AntivirusCheckAction.java:241](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/download/action/AntivirusCheckAction.java:241).

The implementation substitutes `{file}` into a string and splits it on whitespace. A filename containing spaces becomes several arguments, and quoting the placeholder does not help because this is not a quote-aware tokenizer. Executable paths or other quoted arguments containing spaces fail similarly.

Evidence: **reproduced action execution**: `/usr/bin/test -f {file}` against an existing `file with spaces.txt` returned false, scanner exit 2. Parse command arguments before replacing placeholders so each path stays one argument; reuse the repository's established command parsing policy without introducing a shell.

**F22 — P1 — Tagged releases can publish without passing the test workflows.**

Locations: [release-ci.yml:7](/home/pain/NetBeansProjects/open-download-manager/.github/workflows/release-ci.yml:7), [test-ci.yml:3](/home/pain/NetBeansProjects/open-download-manager/.github/workflows/test-ci.yml:3), [build-packages.sh:31](/home/pain/NetBeansProjects/open-download-manager/packaging/build-packages.sh:31).

The tag-triggered release job builds packages with tests and JaCoCo skipped, checks only that a Debian-installed launcher is executable, then publishes the release. It has no dependency on the branch/PR test workflow and does not run equivalent checks itself. A tag pointing at untested or failing code can therefore reach publication.

Evidence: **static workflow trace**. Current remote Actions history and repository protection settings were not queried; no claims from the old audit's remote-run snapshot are reused. Gate publication on successful unit/integration validation of the exact tagged commit and the intended package checks.

**F23 — P2 — Docker builds fail when the checkout owner is not UID 1000.**

Locations: [Dockerfile:43](/home/pain/NetBeansProjects/open-download-manager/Dockerfile:43), [docker-build.sh:104](/home/pain/NetBeansProjects/open-download-manager/docker-build.sh:104), [docker-build.sh:119](/home/pain/NetBeansProjects/open-download-manager/docker-build.sh:119).

The image runs as fixed UID 1000, and build/test/package commands bind-mount the checkout without matching the host UID or providing a writable build copy. A checkout owned by another UID with ordinary 0755 permissions is readable but cannot receive Maven targets or package output. The Maven-cache permission preparation does not repair checkout permissions.

Evidence: **reproduced in the current Docker image** with an isolated temporary mount owned by UID 1001: container UID `1000`, mount owner `1001`, `mkdir: Permission denied`. The host checkout was not re-owned. Support the caller's UID/GID or an explicitly writable build workspace; do not assume a current hosted runner's UID without checking it.

**F24 — P2 — Integration fixtures contain stale endpoints, duplicate starts, and incompatible timeout budgets.**

Locations: [Aria2IntegrationTest.java:702](/home/pain/NetBeansProjects/open-download-manager/core/src/test/java/org/aria2/Aria2IntegrationTest.java:702), [GlobalSettings.java:35](/home/pain/NetBeansProjects/open-download-manager/core/src/main/java/org/manager/GlobalSettings.java:35), [CurlIntegrationTest.java:122](/home/pain/NetBeansProjects/open-download-manager/core/src/test/java/org/curl/CurlIntegrationTest.java:122), [CurlIntegrationTest.java:314](/home/pain/NetBeansProjects/open-download-manager/core/src/test/java/org/curl/CurlIntegrationTest.java:314), [TorServiceIntegrationTest.java:144](/home/pain/NetBeansProjects/open-download-manager/core/src/test/java/org/tor/TorServiceIntegrationTest.java:144), [junit-platform.properties:31](/home/pain/NetBeansProjects/open-download-manager/core/src/test/resources/junit-platform.properties:31).

The canonical full-suite failure is partly caused by defects in its verification code. The aria2 test queries port 6800 instead of the handler's actual RPC endpoint. The Curl settings test starts the same download twice through separate clients, sharing status/output while its mock server supplies only one response. The Curl error test uses public DNS and a 25-second callback deadline despite a 30-second connection budget and retries. The Tor start test allows a 60-second future wait inside the inherited 30-second method limit.

Evidence: **fresh full-suite results plus a four-method rerun**, detailed in the verification table. Repair every listed fixture: derive the RPC endpoint from the running handler, make each test own one transfer and sufficient server responses, use local deterministic failure responses, and align Tor's outer timeout with the operation being tested. Preserve meaningful production assertions. These fixture problems do not explain away the separate production-class reproductions F01–F21.

**Feature coverage and current state**

| Area | Assessment |
|---|---|
| HTTP/HTTPS/FTP/SFTP and aria2 RPC | Broad implementation and tests, including protocol settings, mirrors, typed output paths, and authenticated daemon ownership. Active proxy updates and lifecycle boundaries remain defective (F03, F07–F10). |
| BitTorrent/magnets/Metalink | Multi-GID handling and file selection exist. Staged descriptor recovery and local descriptors through SOCKS are broken (F05–F06). This audit did not perform a long public swarm/seeding soak. |
| Curl/proxychains | Native commands, cancellation, and routing are implemented. Manager/client state ownership and proxy parsing still break supported flows (F04, F07). |
| Tor and proxy changes | Native aria2 SOCKS incompatibility from the old report has a proxychains route now. Global policy coverage, preview routing, and inherited HTTP updates remain incomplete (F01–F05). |
| yt-dlp media/playlist/subtitle controls | Unique operation keys replace millisecond IDs; native integrations and option mapping are covered by existing tests. Metadata-dialog state and draft handling have defects (F17–F18). Provider/login/browser-cookie combinations were not exhaustively tested. |
| HTTrack website scraping | Start/continue/update paths and crawl options exist. Global SOCKS handling, completed-output deletion, paused settings, and displayed byte counts have defects (F01, F14–F16). |
| Queue/admission/lifecycle | Exclusive admission and attempt tracking improve the old implementation. Cross-operation races, queued resume identity, and retry generations remain problematic (F07–F10). |
| Persistence, scheduling, Offline Mode | SQLite snapshots are transactional and per-download schedules now persist. Recovery sources and pause provenance remain incomplete; reopening admission does not always release waiting work (F06, F11–F13). |
| Checksums and data recheck | Parsing, bounded checksum fetches, completion verification, and aria2 request handling were reviewed. Preview routing is defective (F02). A complete corrupt-payload repair/recheck scenario was not exercised. |
| Clipboard/folder/manual imports | Background manual-start holds and asynchronous acceptance are implemented. Successful descriptor ingestion still destroys the source required for later recovery (F06), and dialogs can leave invalid drafts (F18). |
| Completion actions | Global mutable actions are serialized per action; completion ordering/result storage exists. Antivirus backends, result parsing, and argument construction are defective (F19–F21). Real power-off/reboot and scans of user files were not performed. |
| GTK, threading, selection, sorting, accessibility | Strict UI loading, stable selection IDs, typed numeric sorting, worker-based refresh/tray initialization, and `UiThread` callback marshalling are present. Real widget probes found F17–F18. No comprehensive screen-reader, desktop-shell, narrow-window, or very-large-history performance certification is implied. |
| Builds/packages/releases | Java 25 default tests, package builds, archive checks, and a packaged GUI launch passed. The integration profile failed. Release gating, UID portability, and integration fixtures remain issues (F22–F24). Packages were not installed on the user's host. |

**Disposition of the previous `session-ses_face.md` audit**

The earlier report assessed `4853dec`. Its findings were rechecked against current source and available test evidence; its old release verdict, test counts, and remote CI history were not treated as current results.

| Previous finding | Current disposition |
|---|---|
| P0: unconditional pre-start output deletion | The cited pre-deletion has been removed. Current handlers delegate output collision policy to engines; this is not a guarantee that every explicit overwrite mode preserves existing content. |
| P0: aria2 receives an unsupported SOCKS `all-proxy` | The factory now routes through proxychains, with a SOCKS-preserving Curl fallback for eligible plain URLs. New routing defects are F01–F05. |
| P0: untrustworthy release pipeline / old failed Actions runs | Missing publication gate remains F22, and UID portability is reproduced as F23. Old remote-run counts were not revalidated. |
| P1: duplicate start and terminal-state races | Duplicate admission/terminal-start checks were added. Remaining interleavings are F07–F10. |
| P1: yt-dlp millisecond operation-key collision / exit-zero without output | Operation IDs now use UUIDs and successful terminal handling has been strengthened. Fresh integration results are recorded above. |
| P1: deferred downloads start without consent | `CREATED` state and manual-start holds now protect background/deferred requests. The cited old default-queued behavior is no longer the implementation. |
| P1: swallowed queue failures / dialogs close early | Queue failures propagate and dialogs await the future. Invalid/stale drafts are a separate remaining issue, F18. |
| P1: recovery ignores connecting/queued work; aria2 session duplication | Recovery includes additional active states and queued work; independent aria2 session replay is disabled. Descriptor loss and automatic-pause recovery remain F06/F11. |
| P1: per-download schedules disappear in SQLite | Schedule values are now serialized/restored. Pause ownership still disappears (F11), and removing restrictions can strand work (F12). |
| P1: HTTrack path and option injection | Filename validation/confinement and restrictive filter handling address the cited source paths. No current reproduction of the old traversal/execution path was established. |
| P1: proxy bookkeeping forwarded as aria2 options; detached health objects | Internal bookkeeping is filtered and exact proxy ownership objects are retained. Parser and resume-generation defects remain F04/F10. |
| P1: folder descriptors dispatched before asynchronous acceptance | Acceptance is now awaited. Deletion after successful ingestion still prevents recovery (F06). |
| P1: shared mutable completion actions race | Shared action execution is now synchronized on the action. Current antivirus defects have separate causes (F19–F21). |
| P1: selection/queue-order and numeric-sort errors | Stable-ID selection restoration, repository queue ordering, and typed sort keys are implemented and tested. |
| P2: inaccessible labels/errors and old-GTK announcements | Label relationships and status/error reporting have expanded; old-GTK status fallback exists. Full assistive-technology testing remains outside this audit's executed checks. |
| P2: GTK-thread settings/RPC/tray work and full-history refresh | Runtime settings, refresh loading, and tray construction have asynchronous paths now. No new reproducible latency defect was established; large-history/desktop performance was not benchmarked. |
| P2: Delete with Files has no confirmation | The UI has an explicit deletion-confirmation flow now. Completed HTTrack deletion itself remains broken (F14). |
| P2: Curl integration double-start fixture | Still present in the settings/client test at lines 122–124. It timed out in the full run and passed in the focused rerun; F24 records the remaining defect. |
| P2: invalid JUnit parallel keys / disabled JaCoCo | JUnit uses the Jupiter keys; Java-25-compatible JaCoCo is enabled, and the default suite passed its coverage checks. |
| P2: old Jackson 2.18.2 dependency | Current source uses 2.18.9, so the cited old-version finding is outdated. This audit is not a full current dependency-vulnerability scan. |
| P2: inconsistent package/JAR/About versions | Source metadata is aligned at 0.1.0 and manifest versioning exists. Fresh artifact checks are recorded above. |
| P2: malformed multi-release JAR / incorrectly sized icon destinations | Shade/resource packaging has changed to remove the cited mismatch. Fresh artifact checks are recorded above. |

**Recommended remediation order**

1. Enforce effective proxy/Tor routing consistently, including previews and unsupported engines (F01–F05).
2. Preserve recovery inputs and make start/pause/resume/terminal transitions coherent across the manager, wrappers, and native clients (F06–F11).
3. Repair schedule/offline admission reopening, HTTrack actions/settings/progress, and dialog draft/operation state (F12–F18).
4. Correct the antivirus backend contracts and parsing, repair the integration fixtures, then gate releases and support non-1000 build users (F19–F24).

Regression coverage should exercise the combined manager/handler flows demonstrated here, especially events arriving before operation futures settle, pause-to-queue-to-resume, and restart after an automatically imposed pause. Existing passing suites alone do not exercise these observed failures.

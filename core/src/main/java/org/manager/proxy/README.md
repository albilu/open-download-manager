# Proxy rotation

ODM can choose a proxy from a user-provided list when starting a download and try another proxy after a retryable failure. Rotation is disabled by default and currently configured through `settings.json`; the Settings panel has no proxy-list or rotation controls.

## Enable rotation with your own list

1. Exit ODM before editing its settings so that saving or shutting down the application does not overwrite your changes.
2. Create a UTF-8 text file containing your proxies, for example `/home/alex/.config/odm/proxies.txt`. Use one proxy per line. Replace these example addresses and credentials with your own:

   ```text
   # HTTP proxies
   http://proxy1.example.com:8080
   http://username:password@proxy2.example.com:3128
   ```

3. Open `${XDG_CONFIG_HOME:-$HOME/.config}/odm/settings.json` (normally `~/.config/odm/settings.json`). Merge these entries into the existing JSON object, preserving the other settings:

   ```json
   {
     "proxyRotationEnabled": "true",
     "proxyListFilePath": "/home/alex/.config/odm/proxies.txt",
     "proxyRotationMaxRetries": "5"
   }
   ```

   ODM saves settings values as strings. Set `proxyListFilePath` to your file's absolute path; `~` and environment variables inside this JSON value are not expanded.

4. Start ODM. The list is loaded when the first eligible download starts.

To disable rotation, exit ODM, set `proxyRotationEnabled` to `"false"`, and restart. Restart after changing the list or its path, too: ODM retains the loaded pool in memory and does not watch the file for changes.

| Setting | Default | Effect |
|---|---|---|
| `proxyRotationEnabled` | `"false"` | Enables proxy selection and retries for eligible downloads. |
| `proxyListFilePath` | Unset | Local file from which to load the shared proxy pool. |
| `proxyRotationMaxRetries` | `"5"` | Additional ODM attempts after the first attempt; clamped to 0–20. |

## Proxy-list format

- Blank lines and lines beginning with `#` after trimming whitespace are ignored. Put comments on separate lines.
- Duplicate entries are ignored. Entries that cannot be parsed are skipped and logged.
- `http://host:port` and `http://username:password@host:port` are supported forms. Plain `host:port` defaults to HTTP.
- The parser also recognizes `https://`, `socks4://`, `socks5://`, and `socks5h://`. Whether a selected proxy works depends on the download engine; see the next section.
- Use hostnames or IPv4 addresses. The current parser does not support bracketed IPv6 addresses.

The bundled [`proxy-list.txt`](../../../../resources/proxy-list.txt) contains placeholder addresses and is not loaded automatically. ODM does not fetch the public lists mentioned in its comments. Supply your own file with working proxies.

## Downloads and routes affected

| Download engine or route | Rotation behavior |
|---|---|
| aria2 (`ARIA2`) | Eligible. Use HTTP proxy entries. |
| curl (`CURL`) | Eligible, subject to curl's supported proxy and transfer combinations. |
| yt-dlp media (`YOUTUBE`) | Not wrapped by proxy rotation. |
| HTTrack (`WEBSITE_SCRAPING`) | Not wrapped by proxy rotation. |
| proxychains (`PROXYCHAINS`) | Not wrapped by proxy rotation. |
| An enabled SOCKS/Tor route already selected for the download | Preserved; rotation is skipped, including after a curl fallback. |

The engine is selected before the rotation wrapper runs. Selecting a SOCKS or HTTPS proxy from the pool does not select another engine or perform a proxychains handoff. aria2 rejects these routes, so use `http://` entries in a pool shared with aria2 downloads. An HTTP proxy can still be used for downloads from HTTPS websites; `http://` here describes the connection to the proxy.

Rotation selects a pool entry on the **first attempt**, even before any failure. For an eligible download, this enables proxy use and replaces an existing HTTP/HTTPS proxy address, including one selected in per-download Options. The explicit route-preservation exception is an already enabled SOCKS/Tor route.

The eligibility check uses the download's engine type; it has no separate HTTP-only URL filter. Engine protocol restrictions still apply. This feature does not provide routing for torrent peer traffic or application-wide requests such as media information fetching, browser probing, indexer searches, and tool updates.

If the configured file is missing, unreadable, empty, or has no accepted entries and the pool is empty, ODM logs a warning and starts the original handler **without rotation**. It keeps the download's existing route, which may be direct when no proxy is selected. Enabling rotation alone does not require every download to use a proxy.

## Retries and proxy selection

ODM chooses randomly, weighted by the pool's recorded health scores. The initial selection prefers healthy proxies and proxies not currently used by other downloads, but a proxy may serve several downloads. Retries try an alternative to the immediately preceding proxy. If no healthy candidates exist, selection can use other pool entries; if no alternative exists, the retry may reuse the last configured proxy. A different proxy or exit IP is not guaranteed.

The wrapper retries failures whose reported error text contains a recognized HTTP status code (`401`, `403`, `429`, `502`, `503`, or `504`) or a configured keyword, such as `too many requests`, `rate limit`, `blocked`, `forbidden`, `access denied`, `connection timeout`, `connection refused`, `proxy error`, or `proxy timeout`. Other failures end the retry sequence. Detection depends on the error reported by the engine.

`proxyRotationMaxRetries = 5` allows the initial attempt plus up to five additional attempts in one ODM retry sequence. Changing proxies does not reset this budget. A value of `0` still selects a proxy for the initial attempt but disables additional wrapper attempts.

The first retry waits about two seconds. Later delays grow by a factor of 1.5, with a base delay capped at two minutes and a small random variation applied afterward. These delay and error-matching defaults are defined in code, not exposed in the Settings panel.

### Difference from Retry Limit in Network/Options

| Control | Retry layer |
|---|---|
| **Retry Limit** in Settings → Network | Default retry/attempt setting passed to the selected engine when supported (`network.maxRetries`). |
| **Retry Limit** in a download's Options | Per-download override of that engine setting. |
| `proxyRotationMaxRetries` in `settings.json` | Additional attempts initiated by ODM's rotation wrapper after a retryable failure. |

The engine can make its own attempts before reporting a failure to ODM. The wrapper may then start another engine attempt with another proxy. Both global limits default to `5`, but changing the visible Retry Limit does not change the rotation budget. Exact engine request counts depend on that engine's retry semantics; six ODM attempts need not mean six network requests. The Network/Options retry-delay control also remains separate from the wrapper's backoff above.

## Pool lifetime and status

The pool and its health records are shared across eligible downloads within the running ODM instance. They are not saved across application exits. Outcomes recorded by the wrapper update proxy health; by default a proxy is removed from the pool after five recorded failures. A cleanup task runs every 30 minutes to remove blocked entries and reset old unhealthy entries for another try.

This cleanup does **not** contact proxies to test connectivity, fetch a new proxy list, or run the Tor circuit monitor. Health reflects recorded download attempts. If the pool becomes empty, a later eligible download start can load the configured file again, including previously removed entries still present in that file.

Useful log messages include `Loaded ... proxies for rotation from ...`, `Using a rotation proxy for download ...`, and `Retrying download ... (attempt .../...)`. A missing or unusable list produces `Could not load proxy list ...` or `Proxy rotation is enabled but the proxy list is empty; starting without rotation`. Parse failures identify the relevant line.

## Implementation references

The production path is `DownloadManagerImpl` → `ProxyRotationSupport` → `RetryableDownloadHandler` → the selected engine handler.

- [`GlobalSettings`](../GlobalSettings.java): persisted keys, defaults, and settings-file location.
- [`ProxyRotationSupport`](../download/ProxyRotationSupport.java): eligibility, SOCKS/Tor preservation, file loading, and cleanup scheduling.
- [`ProxyRotationManager`](ProxyRotationManager.java) and [`Proxy`](Proxy.java): list parsing, pool selection, and health records.
- [`RetryableDownloadHandler`](../download/handler/RetryableDownloadHandler.java) and [`ProxyRetrySettings`](ProxyRetrySettings.java): attempts, failure detection, and delays.

`ProxyAwareDownloadSettings` contains helper properties such as `proxy-list-file`, per-download rotation flags, and direct-fallback preferences. The production wiring does not read these properties. `ProxyRotationHandlerFactory` is also not the factory used by `DownloadManagerImpl`; its APIs should not be treated as supported Settings-panel configuration.

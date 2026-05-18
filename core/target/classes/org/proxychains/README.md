# Proxychains Client for Open Download Manager

This package provides a Java implementation for downloading files using the proxychains utility. Proxychains is a tool that forces any TCP connection made by any program to follow through a chain of proxies (e.g. SOCKS4, SOCKS5, HTTP).

## Components

The proxychains client implementation consists of the following classes:

1. **`ProxychainsClient`**: Core class that executes commands through proxychains.
2. **`ProxychainsDownloadHandler`**: Integrates with the Download Manager to handle proxychains downloads (located in `org.manager.download.handler` package).
3. **`ProxychainsDownloadFactory`**: Factory for creating proxychains downloads with various configurations.
4. **`ProxychainsConfig`**: Utility class for creating and managing proxychains configuration files.
5. **`ProxychainsExample`**: Example class demonstrating the use of the proxychains client.

## Features

-   Integration with aria2c for multi-connection downloads
-   Proxy support (SOCKS4/5, HTTP)
-   Custom configuration file support
-   DNS resolution through proxy (prevents DNS leaks)
-   Tor integration via SOCKS proxy
-   Dynamic, strict, and random chain types

## Usage Example

```java
// Create a proxychains download handler (normally created by DownloadHandlerFactory)
ProxychainsDownloadHandler handler = new ProxychainsDownloadHandler(
    globalSettings, settingsFactory, executor);

// Initialize the handler
handler.initialize().join();

// Create a download with specific options
URI uri = new URI("https://example.com/large-file.iso");
Map<String, String> options = new HashMap<>();
options.put("max-connections", "10");
options.put("timeout", "30");

// Create and start the download
Download download = handler.download(uri, destinationPath, options);

// Or use the handler through the download manager system
String gid = handler.startDownload(download).join();
```

## Proxychains Commands

The proxychains client uses commands like:

```
proxychains4 -f /etc/proxychains4.conf aria2c --async-dns=false http://file.iso
```

Key options:

-   `-f`: Specify configuration file
-   `--async-dns=false`: Disable async DNS resolution (important for proxychains)
-   Additional aria2c parameters for multi-connection downloads

## Configuration Options

Example configuration file:

```
dynamic_chain
proxy_dns
tcp_read_time_out 15000
tcp_connect_time_out 8000

[ProxyList]
socks5 127.0.0.1 9050
```

Key configuration options:

-   `dynamic_chain`: Proxy servers will be used in the order they appear in the list
-   `strict_chain`: All proxies must be online to establish a connection
-   `random_chain`: Proxy servers will be used in random order
-   `proxy_dns`: DNS requests are also passed through the proxy chain

## Implementation Notes

-   Requires proxychains and aria2c to be installed on the system
-   Falls back to curl if proxychains is unavailable
-   Creates temporary configuration files when needed
-   Parses aria2c output for progress information
-   Integrates with ODM's download manager system

## Notes

-   proxychains dependency

| Method                | Supports SOCKS5 Native | DNS via Tor        | Supports chunked multi-threaded speed | Complexity |
| --------------------- | ---------------------- | ------------------ | ------------------------------------- | ---------- |
| `torsocks aria2c`     | ❌                     | ✅                 | ✅ (via aria2)                        | Low–Medium |
| `proxychains aria2c`  | ❌                     | ✅ if configured   | ✅ (via aria2)                        | Medium     |
| `curl -x socks5h://…` | ✅                     | ✅ (via `socks5h`) | ❌ (no splitting)                     | Low        |

config:

```
dynamic_chain
proxy_dns

[ProxyList]
socks5 127.0.0.1 1010
```

```
proxychains4 -f /etc/proxychains4.conf aria2c --async-dns=false http://file.iso
```

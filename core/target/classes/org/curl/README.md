# Curl Client for Open Download Manager

This package provides a Java implementation for downloading files using the curl command-line tool. It serves as a fallback mechanism when proxychains fails or for specific protocols that require curl's capabilities.

## Components

The curl client implementation consists of the following classes:

1. **`CurlClient`**: Core class that executes curl commands and manages downloads.
2. **`CurlDownloadHandler`**: Integrates with the Download Manager to handle curl downloads.
3. **`CurlDownloadFactory`**: Factory for creating curl downloads with various configurations.
4. **`CurlUtils`**: Utility methods for working with curl, including version checking and header extraction.
5. **`CurlExample`**: Example class demonstrating the use of the curl client.

## Features

- HTTP/HTTPS and FTP downloads via curl
- Resume support for interrupted downloads
- Progress monitoring and reporting
- Proxy support (SOCKS4/5, HTTP)
- Tor integration via SOCKS proxy
- Custom curl options support
- Automatic fallback mechanism

## Usage Example

```java
// Create a download factory
CurlDownloadFactory factory = new CurlDownloadFactory();

// Create a download with proxy
URI uri = new URI("https://example.com/large-file.iso");
Download download = factory.createProxiedDownload(uri, "socks5h://127.0.0.1:9050");

// Start the download
factory.getHandler().startDownload(download);
```

## Curl Commands

The curl client uses commands like:

```
curl -L -C - -# --create-dirs -x socks5h://127.0.0.1:1212 -o /path/to/output.file http://file.iso
```

Key options:
- `-L`: Follow redirects
- `-C -`: Resume download
- `-#`: Show progress
- `--create-dirs`: Create directories in output path
- `-x`: Specify proxy
- `-o`: Output file

## Implementation Notes

- Requires curl to be installed on the system
- Falls back to direct connection if proxy fails
- Supports all curl-compatible protocols
- Integrates with ODM's download manager system
- Uses Java ProcessBuilder to execute curl commands
- Parses curl output for progress information
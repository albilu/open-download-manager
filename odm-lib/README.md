## odm library

Ultra fast Java library for file download. Support regular file download, BitTorent (using jlibtorrent), Website crawl (using crawler4j), Metalink

This library is a replacement to the clients (aria2, curl, httrack, proxychains) based implementations in core module and serves as a library for ODM.

# Dev notes:
-   Prioritize speed, performance, memory efficiency, and stability

# ODM Lib features
-   Download files through HTTP(S)/FTP/SFTP/BitTorrent
-   Segmented downloading
-   Resumable downloading
-   Metalink version 4 (RFC 5854) support(HTTP/FTP/SFTP/BitTorrent)
-   Metalink version 3.0 support(HTTP/FTP/SFTP/BitTorrent)
-   Metalink/HTTP (RFC 6249) support
-   HTTP/1.1 implementation
-   HTTP Proxy support
-   SOCKS Proxy support
-   HTTP BASIC authentication support
-   Proxy authentication support
-   HTTP gzip, deflate content encoding support
-   Persistent Connections support
-   Download/Upload speed throttling
-   BitTorrent extensions: Fast extension, DHT, PEX, MSE/PSE, Multi-Tracker, UDP tracker
-   BitTorrent WEB-Seeding
-   BitTorrent Local Peer Discovery
-   Rename/change the directory structure of BitTorrent downloads completely
-   Selective download in multi-file torrent/Metalink
-   Chunk checksum validation in Metalink
-   Can disable segmented downloading in Metalink
-   Download URIs found in a text file or stdin and the destination directory and output file name can be specified     optionally
-   Parameterized URI support
-   IPv6 support with Happy Eyeballs
-   Disk cache to reduce disk activity
-   Keep downloads history in sqlite database


Prompt:
generate the appropriate requirements, design document and task list to implement the odm library

I want you to perform a full deep review of the code base.

We want to resume the work and complete the project.

1-Critique the initial architecture, implementation and tech

One of the pain points previously was the incomplete jgtk implementation which cause a lot of memory issue and instability.

2-From the current state propose a direction and plan the remaining work to do to complete the project



3-[LATER] Downloader: derive m4s manifestv
-the m4s support means that the download manager can download media from streaming services that use fragmented mp4 files. The download manager should be able to detect such format:
1. by its manifest (ex: http://example.com/playlist.m3u8) => yt-dlp http://example.com/manifest.m3u8.
2. or derive the manifest or media segments from the streaming service video page (ex: http://example.com/video-page) .
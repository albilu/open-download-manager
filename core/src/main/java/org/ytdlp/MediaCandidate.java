package org.ytdlp;

/** A complete media source observed in a successful browser response. */
public record MediaCandidate(String url, int confidence, long size,
        MediaRequestContext context) { }

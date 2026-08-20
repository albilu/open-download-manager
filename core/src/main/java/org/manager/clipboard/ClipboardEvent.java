package org.manager.clipboard;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Represents a clipboard change event containing detected URLs and metadata.
 * This class encapsulates information about clipboard content changes
 * and any URLs that were extracted from the content.
 */
public class ClipboardEvent {

    private final String content;
    private final List<URI> detectedUrls;
    private final Instant timestamp;
    private final ClipboardEventType eventType;

    /**
     * Creates a new clipboard event.
     *
     * @param content The clipboard content
     * @param detectedUrls List of URLs detected in the content
     * @param eventType The type of clipboard event
     */
    public ClipboardEvent(String content, List<URI> detectedUrls, ClipboardEventType eventType) {
        this.content = content;
        this.detectedUrls = Collections.unmodifiableList(new ArrayList<>(detectedUrls != null ? detectedUrls : new ArrayList<>()));
        this.eventType = eventType;
        this.timestamp = Instant.now();
    }

    /**
     * Gets the clipboard content that triggered this event.
     *
     * @return The clipboard content
     */
    public String getContent() {
        return content;
    }

    /**
     * Gets the URLs detected in the clipboard content.
     *
     * @return An immutable list of detected URLs
     */
    public List<URI> getDetectedUrls() {
        return detectedUrls;
    }

    /**
     * Gets the timestamp when this event was created.
     *
     * @return The event timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Gets the type of clipboard event.
     *
     * @return The event type
     */
    public ClipboardEventType getEventType() {
        return eventType;
    }

    /**
     * Checks if this event contains any detected URLs.
     *
     * @return true if URLs were detected, false otherwise
     */
    public boolean hasUrls() {
        return !detectedUrls.isEmpty();
    }

    /**
     * Gets the number of URLs detected in this event.
     *
     * @return The number of detected URLs
     */
    public int getUrlCount() {
        return detectedUrls.size();
    }

    /**
     * Checks if the clipboard content is empty or null.
     *
     * @return true if content is empty or null, false otherwise
     */
    public boolean isContentEmpty() {
        return content == null || content.trim().isEmpty();
    }

    /**
     * Gets a truncated version of the content for display purposes.
     *
     * @param maxLength Maximum length of the truncated content
     * @return Truncated content with ellipsis if necessary
     */
    public String getTruncatedContent(int maxLength) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength - 3) + "...";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ClipboardEvent that = (ClipboardEvent) obj;
        return Objects.equals(content, that.content) &&
               Objects.equals(detectedUrls, that.detectedUrls) &&
               Objects.equals(timestamp, that.timestamp) &&
               eventType == that.eventType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(content, detectedUrls, timestamp, eventType);
    }

    @Override
    public String toString() {
        return String.format("ClipboardEvent{type=%s, urlCount=%d, timestamp=%s, content='%s'}",
                eventType, detectedUrls.size(), timestamp,
                content == null ? "null" : getTruncatedContent(50));
    }

    /**
     * Enumeration of clipboard event types.
     */
    public enum ClipboardEventType {
        /**
         * Clipboard content changed and contains valid URLs.
         */
        URLS_DETECTED,

        /**
         * Clipboard content changed but no URLs were found.
         */
        CONTENT_CHANGED,

        /**
         * Clipboard became empty.
         */
        CONTENT_CLEARED,

        /**
         * Error occurred while accessing clipboard.
         */
        ACCESS_ERROR
    }
}

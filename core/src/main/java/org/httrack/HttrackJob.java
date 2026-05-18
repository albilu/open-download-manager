package org.httrack;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents an httrack mirroring job with status and progress information.
 */
public class HttrackJob {

    /**
     * Status of an httrack job.
     */
    public enum Status {
        PENDING,
        RUNNING,
        PAUSED,
        COMPLETED,
        CANCELED,
        ERROR
    }

    private final String jobId;
    private final HttrackSettings settings;
    private final LocalDateTime createdAt;
    private final AtomicReference<Status> status;
    private final AtomicReference<LocalDateTime> startedAt;
    private final AtomicReference<LocalDateTime> completedAt;

    // Progress tracking
    private final AtomicLong filesDownloaded;
    private final AtomicLong totalFiles;
    private final AtomicLong bytesDownloaded;
    private final AtomicLong totalBytes;
    private volatile float progress;
    private volatile int transferRate; // bytes per second

    // Error tracking
    private volatile String errorMessage;

    /**
     * Creates a new HttrackJob.
     *
     * @param jobId The unique job identifier
     * @param settings The httrack settings for this job
     */
    public HttrackJob(String jobId, HttrackSettings settings) {
        this.jobId = jobId;
        this.settings = settings.copySettings(); // Make a copy to avoid external modifications
        this.createdAt = LocalDateTime.now();
        this.status = new AtomicReference<>(Status.PENDING);
        this.startedAt = new AtomicReference<>();
        this.completedAt = new AtomicReference<>();

        this.filesDownloaded = new AtomicLong(0);
        this.totalFiles = new AtomicLong(0);
        this.bytesDownloaded = new AtomicLong(0);
        this.totalBytes = new AtomicLong(0);
        this.progress = 0.0f;
        this.transferRate = 0;
    }

    /**
     * Gets the unique job identifier.
     *
     * @return The job ID
     */
    public String getJobId() {
        return jobId;
    }

    /**
     * Gets the httrack settings for this job.
     *
     * @return The httrack settings
     */
    public HttrackSettings getSettings() {
        return settings;
    }

    /**
     * Gets the job creation timestamp.
     *
     * @return The creation timestamp
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Gets the current job status.
     *
     * @return The current status
     */
    public Status getStatus() {
        return status.get();
    }

    /**
     * Sets the job status.
     *
     * @param newStatus The new status
     */
    public void setStatus(Status newStatus) {
        Status oldStatus = this.status.getAndSet(newStatus);

        // Update timestamps based on status change
        if (oldStatus != Status.RUNNING && newStatus == Status.RUNNING) {
            startedAt.set(LocalDateTime.now());
        } else if ((newStatus == Status.COMPLETED || newStatus == Status.ERROR || newStatus == Status.CANCELED) &&
                   completedAt.get() == null) {
            completedAt.set(LocalDateTime.now());
        }
    }

    /**
     * Gets the job start timestamp.
     *
     * @return The start timestamp, or null if not started
     */
    public LocalDateTime getStartedAt() {
        return startedAt.get();
    }

    /**
     * Gets the job completion timestamp.
     *
     * @return The completion timestamp, or null if not completed
     */
    public LocalDateTime getCompletedAt() {
        return completedAt.get();
    }

    /**
     * Gets the number of files downloaded.
     *
     * @return The number of files downloaded
     */
    public long getFilesDownloaded() {
        return filesDownloaded.get();
    }

    /**
     * Sets the number of files downloaded.
     *
     * @param count The number of files downloaded
     */
    public void setFilesDownloaded(long count) {
        this.filesDownloaded.set(count);
    }

    /**
     * Gets the total number of files to download.
     *
     * @return The total number of files
     */
    public long getTotalFiles() {
        return totalFiles.get();
    }

    /**
     * Sets the total number of files to download.
     *
     * @param total The total number of files
     */
    public void setTotalFiles(long total) {
        this.totalFiles.set(total);
    }

    /**
     * Gets the number of bytes downloaded.
     *
     * @return The number of bytes downloaded
     */
    public long getBytesDownloaded() {
        return bytesDownloaded.get();
    }

    /**
     * Sets the number of bytes downloaded.
     *
     * @param bytes The number of bytes downloaded
     */
    public void setBytesDownloaded(long bytes) {
        this.bytesDownloaded.set(bytes);
    }

    /**
     * Gets the total number of bytes to download.
     *
     * @return The total number of bytes
     */
    public long getTotalBytes() {
        return totalBytes.get();
    }

    /**
     * Sets the total number of bytes to download.
     *
     * @param total The total number of bytes
     */
    public void setTotalBytes(long total) {
        this.totalBytes.set(total);
    }

    /**
     * Gets the current progress percentage.
     *
     * @return The progress percentage (0-100)
     */
    public float getProgress() {
        return progress;
    }

    /**
     * Sets the current progress percentage.
     *
     * @param progress The progress percentage (0-100)
     */
    public void setProgress(float progress) {
        this.progress = Math.max(0, Math.min(100, progress));
    }

    /**
     * Gets the current transfer rate.
     *
     * @return The transfer rate in bytes per second
     */
    public int getTransferRate() {
        return transferRate;
    }

    /**
     * Sets the current transfer rate.
     *
     * @param rate The transfer rate in bytes per second
     */
    public void setTransferRate(int rate) {
        this.transferRate = Math.max(0, rate);
    }

    /**
     * Gets the error message if the job failed.
     *
     * @return The error message, or null if no error
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Sets the error message.
     *
     * @param errorMessage The error message
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Checks if the job is currently active (running or paused).
     *
     * @return true if the job is active
     */
    public boolean isActive() {
        Status currentStatus = getStatus();
        return currentStatus == Status.RUNNING || currentStatus == Status.PAUSED;
    }

    /**
     * Checks if the job has completed (successfully or with error).
     *
     * @return true if the job has completed
     */
    public boolean isCompleted() {
        Status currentStatus = getStatus();
        return currentStatus == Status.COMPLETED ||
               currentStatus == Status.ERROR ||
               currentStatus == Status.CANCELED;
    }

    /**
     * Gets the job duration in milliseconds.
     * If the job is still running, returns the duration so far.
     * If the job hasn't started, returns 0.
     *
     * @return The job duration in milliseconds
     */
    public long getDurationMillis() {
        LocalDateTime start = getStartedAt();
        if (start == null) {
            return 0;
        }

        LocalDateTime end = getCompletedAt();
        if (end == null) {
            end = LocalDateTime.now();
        }

        return java.time.Duration.between(start, end).toMillis();
    }

    /**
     * Gets the estimated time remaining in milliseconds.
     * Returns -1 if cannot be estimated.
     *
     * @return The estimated time remaining in milliseconds, or -1
     */
    public long getEstimatedTimeRemainingMillis() {
        if (progress <= 0 || progress >= 100 || transferRate <= 0) {
            return -1;
        }

        long remainingBytes = getTotalBytes() - getBytesDownloaded();
        if (remainingBytes <= 0) {
            return 0;
        }

        return (remainingBytes * 1000L) / transferRate;
    }

    /**
     * Gets a human-readable summary of the job.
     *
     * @return A string summary of the job
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("HttrackJob{");
        sb.append("id='").append(jobId).append('\'');
        sb.append(", url='").append(settings.getUrl()).append('\'');
        sb.append(", status=").append(getStatus());
        sb.append(", progress=").append(String.format("%.1f%%", progress));

        if (getFilesDownloaded() > 0 || getTotalFiles() > 0) {
            sb.append(", files=").append(getFilesDownloaded()).append("/").append(getTotalFiles());
        }

        if (transferRate > 0) {
            sb.append(", rate=").append(formatBytes(transferRate)).append("/s");
        }

        if (errorMessage != null) {
            sb.append(", error='").append(errorMessage).append('\'');
        }

        sb.append('}');
        return sb.toString();
    }

    /**
     * Formats bytes into a human-readable string.
     *
     * @param bytes The number of bytes
     * @return A formatted string (e.g., "1.5 MB")
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    @Override
    public String toString() {
        return getSummary();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HttrackJob that = (HttrackJob) o;
        return jobId.equals(that.jobId);
    }

    @Override
    public int hashCode() {
        return jobId.hashCode();
    }
}

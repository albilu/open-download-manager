package org.manager.proxy;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Configuration settings for proxy rotation and retry behavior.
 * This class defines when and how proxy rotation should occur during downloads.
 */
public class ProxyRetrySettings {

    // Default configuration values
    public static final int DEFAULT_MAX_RETRIES = 5;
    public static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(2);
    public static final Duration DEFAULT_MAX_RETRY_DELAY = Duration.ofMinutes(2);
    public static final double DEFAULT_BACKOFF_MULTIPLIER = 1.5;
    public static final boolean DEFAULT_ENABLE_PROXY_ROTATION = true;
    public static final boolean DEFAULT_ROTATE_ON_FIRST_ERROR = false;

    // HTTP status codes that trigger proxy rotation
    private static final Set<Integer> DEFAULT_RETRY_STATUS_CODES = new HashSet<>(Arrays.asList(
        403, // Forbidden
        429, // Too Many Requests
        503, // Service Unavailable
        502, // Bad Gateway
        504, // Gateway Timeout
        401  // Unauthorized (sometimes IP-based)
    ));

    // Error keywords that trigger proxy rotation
    private static final Set<String> DEFAULT_RETRY_ERROR_KEYWORDS = new HashSet<>(Arrays.asList(
        "too many requests",
        "rate limit",
        "rate-limit",
        "blocked",
        "forbidden",
        "access denied",
        "ip blocked",
        "ip banned",
        "connection timeout",
        "connection refused",
        "proxy error",
        "proxy timeout"
    ));

    private int maxRetries = DEFAULT_MAX_RETRIES;
    private Duration initialRetryDelay = DEFAULT_RETRY_DELAY;
    private Duration maxRetryDelay = DEFAULT_MAX_RETRY_DELAY;
    private double backoffMultiplier = DEFAULT_BACKOFF_MULTIPLIER;
    private boolean enableProxyRotation = DEFAULT_ENABLE_PROXY_ROTATION;
    private boolean rotateOnFirstError = DEFAULT_ROTATE_ON_FIRST_ERROR;
    private Set<Integer> retryStatusCodes = new HashSet<>(DEFAULT_RETRY_STATUS_CODES);
    private Set<String> retryErrorKeywords = new HashSet<>(DEFAULT_RETRY_ERROR_KEYWORDS);
    private boolean enableJitterInDelay = true;
    private boolean resetRetriesOnProxyChange = true;

    /**
     * Creates default proxy retry settings.
     */
    public ProxyRetrySettings() {
        // Use default values
    }

    /**
     * Creates a copy of the provided settings.
     */
    public ProxyRetrySettings(ProxyRetrySettings other) {
        this.maxRetries = other.maxRetries;
        this.initialRetryDelay = other.initialRetryDelay;
        this.maxRetryDelay = other.maxRetryDelay;
        this.backoffMultiplier = other.backoffMultiplier;
        this.enableProxyRotation = other.enableProxyRotation;
        this.rotateOnFirstError = other.rotateOnFirstError;
        this.retryStatusCodes = new HashSet<>(other.retryStatusCodes);
        this.retryErrorKeywords = new HashSet<>(other.retryErrorKeywords);
        this.enableJitterInDelay = other.enableJitterInDelay;
        this.resetRetriesOnProxyChange = other.resetRetriesOnProxyChange;
    }

    // Getters and setters

    public int getMaxRetries() {
        return maxRetries;
    }

    public ProxyRetrySettings setMaxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries cannot be negative");
        }
        this.maxRetries = maxRetries;
        return this;
    }

    public Duration getInitialRetryDelay() {
        return initialRetryDelay;
    }

    public ProxyRetrySettings setInitialRetryDelay(Duration initialRetryDelay) {
        if (initialRetryDelay == null || initialRetryDelay.isNegative()) {
            throw new IllegalArgumentException("Initial retry delay must be positive");
        }
        this.initialRetryDelay = initialRetryDelay;
        return this;
    }

    public Duration getMaxRetryDelay() {
        return maxRetryDelay;
    }

    public ProxyRetrySettings setMaxRetryDelay(Duration maxRetryDelay) {
        if (maxRetryDelay == null || maxRetryDelay.isNegative()) {
            throw new IllegalArgumentException("Max retry delay must be positive");
        }
        this.maxRetryDelay = maxRetryDelay;
        return this;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public ProxyRetrySettings setBackoffMultiplier(double backoffMultiplier) {
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("Backoff multiplier must be >= 1.0");
        }
        this.backoffMultiplier = backoffMultiplier;
        return this;
    }

    public boolean isEnableProxyRotation() {
        return enableProxyRotation;
    }

    public ProxyRetrySettings setEnableProxyRotation(boolean enableProxyRotation) {
        this.enableProxyRotation = enableProxyRotation;
        return this;
    }

    public boolean isRotateOnFirstError() {
        return rotateOnFirstError;
    }

    public ProxyRetrySettings setRotateOnFirstError(boolean rotateOnFirstError) {
        this.rotateOnFirstError = rotateOnFirstError;
        return this;
    }

    public Set<Integer> getRetryStatusCodes() {
        return new HashSet<>(retryStatusCodes);
    }

    public ProxyRetrySettings setRetryStatusCodes(Set<Integer> retryStatusCodes) {
        this.retryStatusCodes = retryStatusCodes != null ? new HashSet<>(retryStatusCodes) : new HashSet<>();
        return this;
    }

    public ProxyRetrySettings addRetryStatusCode(int statusCode) {
        this.retryStatusCodes.add(statusCode);
        return this;
    }

    public ProxyRetrySettings removeRetryStatusCode(int statusCode) {
        this.retryStatusCodes.remove(statusCode);
        return this;
    }

    public Set<String> getRetryErrorKeywords() {
        return new HashSet<>(retryErrorKeywords);
    }

    public ProxyRetrySettings setRetryErrorKeywords(Set<String> retryErrorKeywords) {
        this.retryErrorKeywords = retryErrorKeywords != null ?
            new HashSet<>(retryErrorKeywords) : new HashSet<>();
        return this;
    }

    public ProxyRetrySettings addRetryErrorKeyword(String keyword) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            this.retryErrorKeywords.add(keyword.toLowerCase().trim());
        }
        return this;
    }

    public ProxyRetrySettings removeRetryErrorKeyword(String keyword) {
        if (keyword != null) {
            this.retryErrorKeywords.remove(keyword.toLowerCase().trim());
        }
        return this;
    }

    public boolean isEnableJitterInDelay() {
        return enableJitterInDelay;
    }

    public ProxyRetrySettings setEnableJitterInDelay(boolean enableJitterInDelay) {
        this.enableJitterInDelay = enableJitterInDelay;
        return this;
    }

    public boolean isResetRetriesOnProxyChange() {
        return resetRetriesOnProxyChange;
    }

    public ProxyRetrySettings setResetRetriesOnProxyChange(boolean resetRetriesOnProxyChange) {
        this.resetRetriesOnProxyChange = resetRetriesOnProxyChange;
        return this;
    }

    /**
     * Determines if an error should trigger a retry based on HTTP status code.
     *
     * @param statusCode HTTP status code
     * @return true if this status code should trigger a retry
     */
    public boolean shouldRetryForStatusCode(int statusCode) {
        return retryStatusCodes.contains(statusCode);
    }

    /**
     * Determines if an error should trigger a retry based on error message.
     *
     * @param errorMessage Error message to check
     * @return true if this error should trigger a retry
     */
    public boolean shouldRetryForError(String errorMessage) {
        if (errorMessage == null || errorMessage.trim().isEmpty()) {
            return false;
        }

        String lowerErrorMessage = errorMessage.toLowerCase();
        return retryErrorKeywords.stream()
                .anyMatch(lowerErrorMessage::contains);
    }

    /**
     * Calculates the delay for a retry attempt with exponential backoff.
     *
     * @param attemptNumber The attempt number (starting from 1)
     * @return Duration to wait before retry
     */
    public Duration calculateRetryDelay(int attemptNumber) {
        if (attemptNumber <= 0) {
            return initialRetryDelay;
        }

        // Calculate exponential backoff
        double multiplier = Math.pow(backoffMultiplier, attemptNumber - 1);
        long delayMillis = (long) (initialRetryDelay.toMillis() * multiplier);

        // Apply maximum delay limit
        if (delayMillis > maxRetryDelay.toMillis()) {
            delayMillis = maxRetryDelay.toMillis();
        }

        // Add jitter to prevent thundering herd
        if (enableJitterInDelay && delayMillis > 0) {
            double jitter = Math.random() * 0.1; // ±10% jitter
            delayMillis = (long) (delayMillis * (1.0 + (jitter - 0.05)));
        }

        return Duration.ofMillis(Math.max(0, delayMillis));
    }

    /**
     * Creates a builder for ProxyRetrySettings.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for creating ProxyRetrySettings instances.
     */
    public static class Builder {
        private final ProxyRetrySettings settings = new ProxyRetrySettings();

        public Builder maxRetries(int maxRetries) {
            settings.setMaxRetries(maxRetries);
            return this;
        }

        public Builder initialRetryDelay(Duration delay) {
            settings.setInitialRetryDelay(delay);
            return this;
        }

        public Builder maxRetryDelay(Duration delay) {
            settings.setMaxRetryDelay(delay);
            return this;
        }

        public Builder backoffMultiplier(double multiplier) {
            settings.setBackoffMultiplier(multiplier);
            return this;
        }

        public Builder enableProxyRotation(boolean enable) {
            settings.setEnableProxyRotation(enable);
            return this;
        }

        public Builder rotateOnFirstError(boolean rotate) {
            settings.setRotateOnFirstError(rotate);
            return this;
        }

        public Builder addRetryStatusCode(int statusCode) {
            settings.addRetryStatusCode(statusCode);
            return this;
        }

        public Builder addRetryErrorKeyword(String keyword) {
            settings.addRetryErrorKeyword(keyword);
            return this;
        }

        public Builder enableJitter(boolean enable) {
            settings.setEnableJitterInDelay(enable);
            return this;
        }

        public Builder resetRetriesOnProxyChange(boolean reset) {
            settings.setResetRetriesOnProxyChange(reset);
            return this;
        }

        public ProxyRetrySettings build() {
            return new ProxyRetrySettings(settings);
        }
    }

    @Override
    public String toString() {
        return "ProxyRetrySettings{" +
                "maxRetries=" + maxRetries +
                ", initialRetryDelay=" + initialRetryDelay +
                ", maxRetryDelay=" + maxRetryDelay +
                ", backoffMultiplier=" + backoffMultiplier +
                ", enableProxyRotation=" + enableProxyRotation +
                ", rotateOnFirstError=" + rotateOnFirstError +
                ", retryStatusCodes=" + retryStatusCodes.size() +
                ", retryErrorKeywords=" + retryErrorKeywords.size() +
                '}';
    }
}

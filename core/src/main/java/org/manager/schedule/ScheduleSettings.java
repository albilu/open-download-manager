package org.manager.schedule;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Settings class that manages scheduling configuration for downloads.
 * This class can be attached to downloads to control when they should be active.
 */
public class ScheduleSettings {

    private WeeklySchedule weeklySchedule;
    private boolean respectGlobalSchedule;
    private SchedulePolicy policy;
    private boolean pauseOnScheduleEnd;
    private boolean resumeOnScheduleStart;

    /**
     * Policy for how scheduling should behave when downloads are active.
     */
    public enum SchedulePolicy {
        /**
         * Pause downloads immediately when outside schedule.
         */
        STRICT,

        /**
         * Allow current downloads to finish but don't start new ones when outside schedule.
         */
        GRACEFUL,

        /**
         * Only apply scheduling to new downloads, don't affect running ones.
         */
        NEW_ONLY
    }

    /**
     * Creates new schedule settings with default values.
     */
    public ScheduleSettings() {
        this.weeklySchedule = WeeklySchedule.alwaysActive();
        this.respectGlobalSchedule = true;
        this.policy = SchedulePolicy.GRACEFUL;
        this.pauseOnScheduleEnd = true;
        this.resumeOnScheduleStart = true;
    }

    /**
     * Creates schedule settings with a specific weekly schedule.
     *
     * @param weeklySchedule The weekly schedule to use
     */
    public ScheduleSettings(WeeklySchedule weeklySchedule) {
        this();
        this.weeklySchedule = Objects.requireNonNull(weeklySchedule, "Weekly schedule cannot be null");
    }

    /**
     * Creates a copy of existing schedule settings.
     *
     * @param other The settings to copy from
     */
    public ScheduleSettings(ScheduleSettings other) {
        Objects.requireNonNull(other, "Other schedule settings cannot be null");
        this.weeklySchedule = other.weeklySchedule.copy();
        this.respectGlobalSchedule = other.respectGlobalSchedule;
        this.policy = other.policy;
        this.pauseOnScheduleEnd = other.pauseOnScheduleEnd;
        this.resumeOnScheduleStart = other.resumeOnScheduleStart;
    }

    /**
     * Creates schedule settings that are always active (no restrictions).
     *
     * @return Settings that allow downloads at all times
     */
    public static ScheduleSettings alwaysActive() {
        ScheduleSettings settings = new ScheduleSettings();
        settings.weeklySchedule = WeeklySchedule.alwaysActive();
        return settings;
    }

    /**
     * Creates schedule settings that are never active (downloads disabled).
     *
     * @return Settings that never allow downloads
     */
    public static ScheduleSettings neverActive() {
        ScheduleSettings settings = new ScheduleSettings();
        settings.weeklySchedule = WeeklySchedule.neverActive();
        return settings;
    }

    /**
     * Creates schedule settings for business hours (9 AM to 5 PM, weekdays only).
     *
     * @return Settings for business hours scheduling
     */
    public static ScheduleSettings businessHours() {
        ScheduleSettings settings = new ScheduleSettings();
        settings.weeklySchedule = WeeklySchedule.businessHours();
        return settings;
    }

    /**
     * Creates schedule settings for night hours (10 PM to 6 AM, all days).
     *
     * @return Settings for night hours scheduling
     */
    public static ScheduleSettings nightHours() {
        ScheduleSettings settings = new ScheduleSettings();
        settings.weeklySchedule = WeeklySchedule.nightHours();
        return settings;
    }

    /**
     * Gets the weekly schedule.
     *
     * @return The weekly schedule
     */
    public WeeklySchedule getWeeklySchedule() {
        return weeklySchedule;
    }

    /**
     * Sets the weekly schedule.
     *
     * @param weeklySchedule The weekly schedule to set
     * @return This settings object for method chaining
     */
    public ScheduleSettings setWeeklySchedule(WeeklySchedule weeklySchedule) {
        this.weeklySchedule = Objects.requireNonNull(weeklySchedule, "Weekly schedule cannot be null");
        return this;
    }

    /**
     * Checks if this download should respect the global schedule in addition to its own.
     *
     * @return true if global schedule should be respected, false otherwise
     */
    public boolean isRespectGlobalSchedule() {
        return respectGlobalSchedule;
    }

    /**
     * Sets whether this download should respect the global schedule.
     *
     * @param respectGlobalSchedule true to respect global schedule, false otherwise
     * @return This settings object for method chaining
     */
    public ScheduleSettings setRespectGlobalSchedule(boolean respectGlobalSchedule) {
        this.respectGlobalSchedule = respectGlobalSchedule;
        return this;
    }

    /**
     * Gets the schedule policy.
     *
     * @return The schedule policy
     */
    public SchedulePolicy getPolicy() {
        return policy;
    }

    /**
     * Sets the schedule policy.
     *
     * @param policy The schedule policy to set
     * @return This settings object for method chaining
     */
    public ScheduleSettings setPolicy(SchedulePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "Policy cannot be null");
        return this;
    }

    /**
     * Checks if downloads should be automatically paused when schedule ends.
     *
     * @return true if downloads should be paused on schedule end, false otherwise
     */
    public boolean isPauseOnScheduleEnd() {
        return pauseOnScheduleEnd;
    }

    /**
     * Sets whether downloads should be automatically paused when schedule ends.
     *
     * @param pauseOnScheduleEnd true to pause on schedule end, false otherwise
     * @return This settings object for method chaining
     */
    public ScheduleSettings setPauseOnScheduleEnd(boolean pauseOnScheduleEnd) {
        this.pauseOnScheduleEnd = pauseOnScheduleEnd;
        return this;
    }

    /**
     * Checks if downloads should be automatically resumed when schedule starts.
     *
     * @return true if downloads should be resumed on schedule start, false otherwise
     */
    public boolean isResumeOnScheduleStart() {
        return resumeOnScheduleStart;
    }

    /**
     * Sets whether downloads should be automatically resumed when schedule starts.
     *
     * @param resumeOnScheduleStart true to resume on schedule start, false otherwise
     * @return This settings object for method chaining
     */
    public ScheduleSettings setResumeOnScheduleStart(boolean resumeOnScheduleStart) {
        this.resumeOnScheduleStart = resumeOnScheduleStart;
        return this;
    }

    /**
     * Checks if downloads should be active at the given date and time based on this schedule.
     *
     * @param dateTime The date and time to check
     * @return true if downloads should be active, false otherwise
     */
    public boolean isActiveAt(LocalDateTime dateTime) {
        return weeklySchedule.isActiveAt(dateTime);
    }

    /**
     * Checks if downloads should be active at the current time based on this schedule.
     *
     * @return true if downloads should be active now, false otherwise
     */
    public boolean isActiveNow() {
        return weeklySchedule.isActiveNow();
    }

    /**
     * Checks if the schedule is enabled.
     *
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled() {
        return weeklySchedule.isEnabled();
    }

    /**
     * Enables or disables the entire schedule.
     *
     * @param enabled true to enable, false to disable
     * @return This settings object for method chaining
     */
    public ScheduleSettings setEnabled(boolean enabled) {
        weeklySchedule.setEnabled(enabled);
        return this;
    }

    /**
     * Creates a copy of these schedule settings.
     *
     * @return A new ScheduleSettings with the same configuration
     */
    public ScheduleSettings copy() {
        return new ScheduleSettings(this);
    }

    /**
     * Checks if these settings have any scheduling restrictions.
     *
     * @return true if there are restrictions, false if always active
     */
    public boolean hasRestrictions() {
        // Any schedule other than the explicit 24/7 schedule can deny a
        // start. This includes neverActive() (enabled=false) and an enabled
        // but empty grid. Treating those as unrestricted made the scheduler's
        // fast path skip pause enforcement entirely.
        return !weeklySchedule.equals(WeeklySchedule.alwaysActive());
    }

    @Override
    public String toString() {
        return "ScheduleSettings{" +
                "weeklySchedule=" + weeklySchedule +
                ", respectGlobalSchedule=" + respectGlobalSchedule +
                ", policy=" + policy +
                ", pauseOnScheduleEnd=" + pauseOnScheduleEnd +
                ", resumeOnScheduleStart=" + resumeOnScheduleStart +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        ScheduleSettings that = (ScheduleSettings) obj;
        return respectGlobalSchedule == that.respectGlobalSchedule &&
               pauseOnScheduleEnd == that.pauseOnScheduleEnd &&
               resumeOnScheduleStart == that.resumeOnScheduleStart &&
               Objects.equals(weeklySchedule, that.weeklySchedule) &&
               policy == that.policy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(weeklySchedule, respectGlobalSchedule, policy,
                           pauseOnScheduleEnd, resumeOnScheduleStart);
    }
}

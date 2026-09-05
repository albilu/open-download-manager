package org.manager.download;

import java.util.ArrayList;
import java.util.List;

/** Bounded speed samples and time-weighted averages belonging to one download. */
public final class DownloadSpeedHistory {

    public static final int MAX_SAMPLES = 512;
    private static final int STATE_VERSION = 1;

    public record Sample(long elapsedMillis, long downloadedBytes, double bytesPerSecond) {
        public Sample {
            if (elapsedMillis < 0 || downloadedBytes < 0
                    || !Double.isFinite(bytesPerSecond) || bytesPerSecond < 0) {
                throw new IllegalArgumentException("Invalid speed sample");
            }
        }
    }

    public record Snapshot(List<Sample> samples, double averageBytesPerSecond) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), 0);

        public Snapshot {
            samples = List.copyOf(samples);
        }
    }

    /** Persist the original integration totals; compacted samples cannot reproduce them. */
    public record State(int version, List<Sample> samples, long durationMillis, double speedMillis) {
        public State {
            samples = List.copyOf(samples);
            if (version != STATE_VERSION || samples.size() > MAX_SAMPLES
                    || durationMillis < 0 || !Double.isFinite(speedMillis) || speedMillis < 0
                    || (durationMillis == 0 && speedMillis != 0)) {
                throw new IllegalArgumentException("Invalid speed history state");
            }
            Sample previous = null;
            for (Sample sample : samples) {
                if (previous != null && (sample.elapsedMillis() <= previous.elapsedMillis()
                        || sample.downloadedBytes() < previous.downloadedBytes())) {
                    throw new IllegalArgumentException("Unordered speed history");
                }
                previous = sample;
            }
            long span = samples.isEmpty() ? 0
                    : samples.getLast().elapsedMillis() - samples.getFirst().elapsedMillis();
            if (durationMillis > span) {
                throw new IllegalArgumentException("Invalid speed history duration");
            }
        }
    }

    private final List<Sample> samples = new ArrayList<>();
    private long durationMillis;
    private double speedMillis;
    private Sample latest;
    private boolean continueInterval;

    /** Engine-side collection, before asynchronous UI event delivery. */
    synchronized void record(long elapsedMillis, long downloadedBytes, double speed) {
        if (downloadedBytes < 0 || !Double.isFinite(speed)) {
            return;
        }
        Sample sample = new Sample(Math.max(0, elapsedMillis), downloadedBytes, Math.max(0, speed));
        if (latest != null && (sample.downloadedBytes() < latest.downloadedBytes()
                || sample.elapsedMillis() < latest.elapsedMillis())) {
            // A restart/recheck can discard bytes from the previous attempt.
            clear();
        }
        if (latest != null) {
            long interval = sample.elapsedMillis() - latest.elapsedMillis();
            if (continueInterval) {
                durationMillis += interval;
                speedMillis += interval * (latest.bytesPerSecond() + sample.bytesPerSecond()) / 2;
            }
            if (interval == 0) {
                samples.set(samples.size() - 1, sample);
                latest = sample;
                continueInterval = true;
                return;
            }
        }
        samples.add(sample);
        latest = sample;
        continueInterval = true;
        if (samples.size() > MAX_SAMPLES) {
            compact();
        }
    }

    /** Do not invent speeds across unobserved pause, seeding or application-exit gaps. */
    synchronized void startSegment() {
        continueInterval = false;
    }

    synchronized void clear() {
        samples.clear();
        durationMillis = 0;
        speedMillis = 0;
        latest = null;
        continueInterval = false;
    }

    synchronized Snapshot snapshot() {
        return latest == null ? Snapshot.EMPTY : new Snapshot(samples, durationMillis > 0
                ? speedMillis / durationMillis : latest.bytesPerSecond());
    }

    synchronized State state() {
        return latest == null ? null : new State(STATE_VERSION, samples, durationMillis, speedMillis);
    }

    synchronized void restore(State state) {
        clear();
        if (state != null) {
            samples.addAll(state.samples());
            durationMillis = state.durationMillis();
            speedMillis = state.speedMillis();
            latest = samples.isEmpty() ? null : samples.getLast();
        }
    }

    private void compact() {
        // Keep both endpoints and an overview of the entire transfer.
        List<Sample> compacted = new ArrayList<>(MAX_SAMPLES / 2 + 2);
        compacted.add(samples.getFirst());
        for (int i = 1; i < samples.size() - 1; i += 2) {
            Sample first = samples.get(i);
            if (i + 1 == samples.size() - 1) {
                compacted.add(first);
                break;
            }
            Sample second = samples.get(i + 1);
            long firstDuration = first.elapsedMillis() - samples.get(i - 1).elapsedMillis();
            long secondDuration = second.elapsedMillis() - first.elapsedMillis();
            double mean = (first.bytesPerSecond() * firstDuration
                    + second.bytesPerSecond() * secondDuration) / (firstDuration + secondDuration);
            compacted.add(new Sample(second.elapsedMillis(), second.downloadedBytes(), mean));
        }
        compacted.add(samples.getLast());
        samples.clear();
        samples.addAll(compacted);
    }
}

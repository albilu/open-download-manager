package org.manager.perf;

/**
 * Minimal shared formatter for the performance suite.
 *
 * <p>Each perf test warms up the JIT, measures with {@link System#nanoTime},
 * prints one {@code PERF ...} line per case (picked up from surefire output),
 * and asserts a generous upper bound so the suite acts as a regression trip
 * wire without flaking under parallel Docker load. This class performs no
 * measurement itself; it only formats the numbers the tests collect.
 */
public final class PerfReporter {

    private PerfReporter() {
    }

    /**
     * Prints a single benchmark result line to stdout.
     *
     * @param suite   test class simple name
     * @param kase    case name without spaces
     * @param units   number of logical operations measured
     * @param nanos   elapsed nanoseconds for those operations
     * @param extra   trailing context (workload shape, hit rate, ...), may be empty
     * @return throughput in operations per second, for convenience
     */
    public static double report(String suite, String kase, long units, long nanos, String extra) {
        double seconds = nanos / 1_000_000_000.0;
        double opsPerSec = seconds > 0 ? units / seconds : Double.POSITIVE_INFINITY;
        double avgMicros = units > 0 ? (nanos / 1000.0) / units : 0;
        String line = String.format("PERF %s.%s | n=%d | total=%.1fms | avg=%.2fus/op | throughput=%.0f ops/s%s",
                suite, kase, units, nanos / 1_000_000.0, avgMicros, opsPerSec,
                extra == null || extra.isEmpty() ? "" : " | " + extra);
        System.out.println(line);
        return opsPerSec;
    }
}

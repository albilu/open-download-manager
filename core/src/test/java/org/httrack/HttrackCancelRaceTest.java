package org.httrack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for the cancel race: cancelJob destroys the process, the
 * monitor thread then observes the kill exit code and handleProcessCompletion
 * must NOT overwrite the CANCELED status with ERROR or fire a spurious
 * error notification for a job the user canceled.
 */
@DisplayName("HttrackClient cancel is a terminal state")
class HttrackCancelRaceTest {

    @TempDir
    Path tempDir;

    private Path writeFakeHttrack() throws Exception {
        Path script = tempDir.resolve("fake-httrack");
        // exec: the script process becomes sleep itself, so destroying it
        // closes the output pipe the monitor reads (a child sleep would keep
        // the pipe open and the monitor would never observe the kill).
        Files.writeString(script, "#!/bin/bash\nexec sleep 300\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    @Test
    @DisplayName("Canceling a running job keeps status CANCELED with no error notification")
    void canceledJobIsNotOverwrittenWithError() throws Exception {
        HttrackClient client = new HttrackClient(writeFakeHttrack().toString());
        try {
            AtomicInteger cancelNotifications = new AtomicInteger();
            AtomicReference<String> errorAfterCancel = new AtomicReference<>();

            client.addNotificationListener(new HttrackClient.HttrackNotificationListener() {
                @Override
                public void onJobCanceled(HttrackJob job) {
                    cancelNotifications.incrementAndGet();
                }

                @Override
                public void onJobError(HttrackJob job, String errorMessage) {
                    errorAfterCancel.compareAndSet(null, errorMessage);
                }
            });

            HttrackSettings settings = new HttrackSettings(
                    "http://example.test/site", tempDir.resolve("mirror"));
            String jobId = client.startMirror(settings).get(10, TimeUnit.SECONDS);

            // Wait until the job is actually RUNNING with a live process,
            // then hold onto the job object (cancel removes it from the map)
            HttrackJob job = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (job == null || job.getStatus() != HttrackJob.Status.RUNNING) {
                assertTrue(System.nanoTime() < deadline, "job should reach RUNNING");
                job = client.getJobStatus(jobId);
                Thread.sleep(50);
            }

            client.cancelJob(jobId, false).get(10, TimeUnit.SECONDS);

            // Give the monitor thread time to observe the killed process and
            // (before the fix) overwrite CANCELED with ERROR.
            Thread.sleep(1000);

            assertEquals(HttrackJob.Status.CANCELED, job.getStatus(),
                    "a canceled job must keep its terminal CANCELED state, not become ERROR");
            assertEquals(1, cancelNotifications.get(),
                    "exactly one cancel notification must fire");
            assertNull(errorAfterCancel.get(),
                    "no error notification may fire for a user-canceled job");
        } finally {
            client.shutdown();
        }
    }
}

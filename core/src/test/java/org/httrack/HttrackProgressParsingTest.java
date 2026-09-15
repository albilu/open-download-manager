package org.httrack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HttrackProgressParsingTest {

    @Test
    void extractsLiveConnectionsFromAnsiStatusOutputAndPreservesReportedZero() {
        HttrackJob job = new HttrackJob("connections", new HttrackSettings().setConnections(8));
        job.setStatus(HttrackJob.Status.RUNNING);

        HttrackClient.parseProgressLine(
                "\u001b[1;4H\u001b[KActive connections: \t\u001b[1m3\t\u001b[40;4HErrors: 0", job);
        assertEquals(3, job.getConnectionCount());
        assertEquals(8, job.getSettings().getConnections());
        HttrackClient.parseProgressLine("Files written: 12", job);
        assertEquals(3, job.getConnectionCount());
        HttrackClient.parseProgressLine("Active connections: 999999999999999999999", job);
        assertEquals(3, job.getConnectionCount());
        HttrackClient.parseProgressLine("Active connections: 0\tErrors: 0", job);
        assertEquals(0, job.getConnectionCount());
    }

    @ParameterizedTest
    @EnumSource(value = HttrackJob.Status.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
    void stoppingAndRestartingNeverReuseThePreviousConnectionCount(HttrackJob.Status status) {
        HttrackJob job = new HttrackJob("lifecycle", new HttrackSettings());
        job.setStatus(HttrackJob.Status.RUNNING);
        job.setConnectionCount(4);
        job.setStatus(status);
        assertEquals(0, job.getConnectionCount());
        job.setConnectionCount(4); // A buffered old status line cannot expose stopped sockets.
        assertEquals(0, job.getConnectionCount());
        job.setStatus(HttrackJob.Status.RUNNING);
        assertEquals(0, job.getConnectionCount());
    }
}

package org.manager.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executor pool lifecycle contract:
 *
 * 1. Pool threads are daemon threads: the ShutdownCoordinator drains the
 *    pools during ordered shutdown, and no self-registered JVM hook may kill
 *    them concurrently with (and racing) the coordinator's persistence phase.
 * 2. shutdown() covers the event executor too — a blocked odm-events task
 *    must not be able to hang JVM exit with no force-kill path.
 */
@DisplayName("ExecutorServiceManager pool lifecycle contract")
class ExecutorServiceManagerLifecycleTest {

    @Test
    @DisplayName("Pool threads are daemon and shutdown() covers the event executor")
    void poolThreadsAreDaemonAndShutdownDrainsEventExecutor() throws Exception {
        ExecutorServiceManager manager = ExecutorServiceManager.getInstance();
        ExecutorService events = manager.getEventExecutor();

        // 1. Daemon contract
        AtomicBoolean daemon = new AtomicBoolean(true);
        CountDownLatch ran = new CountDownLatch(1);
        events.execute(() -> {
            daemon.set(Thread.currentThread().isDaemon());
            ran.countDown();
        });
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertTrue(daemon.get(), "event executor threads must be daemon");

        // 2. shutdown() awaits the event executor too
        manager.shutdown();
        assertTrue(events.isTerminated() || events.awaitTermination(5, TimeUnit.SECONDS),
                "event executor must be terminated by shutdown()");
    }
}

package io.github.yuu518.hyperosime;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class ReloadTaskTest {
    @Test
    public void timedOutQueuedCleanupCannotRunLater() throws Exception {
        AtomicBoolean cleaned = new AtomicBoolean();
        ReloadTask<Boolean> task = new ReloadTask<>(() -> {
            cleaned.set(true);
            return true;
        });
        assertThrows(TimeoutException.class, () -> task.await(0));
        task.run();
        assertFalse(cleaned.get());
    }

    @Test(timeout = 5000)
    public void alreadyStartedCleanupCompletesBeforeCallerReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReloadTask<String> task = new ReloadTask<>(() -> {
            started.countDown();
            release.await();
            return "finished";
        });
        Thread worker = new Thread(task);
        worker.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            new Thread(release::countDown).start();
            assertEquals("finished", task.await(0));
        } finally {
            release.countDown();
            worker.join(2000);
        }
    }

    @Test
    public void cleanupFailureIsReportedToCaller() {
        IllegalStateException failure = new IllegalStateException("failure");
        ReloadTask<Void> task = new ReloadTask<>(() -> { throw failure; });
        task.run();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> task.await(0)));
    }
}

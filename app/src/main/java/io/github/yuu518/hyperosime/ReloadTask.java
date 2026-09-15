package io.github.yuu518.hyperosime;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

final class ReloadTask<T> implements Runnable {
    private final Callable<T> action;
    private final AtomicInteger state = new AtomicInteger();
    private final CountDownLatch done = new CountDownLatch(1);
    private T result;
    private Throwable error;

    ReloadTask(Callable<T> action) {
        this.action = action;
    }

    @Override
    public void run() {
        if (!state.compareAndSet(0, 1)) {
            return;
        }
        try {
            result = action.call();
        } catch (Throwable failure) {
            error = failure;
        } finally {
            done.countDown();
        }
    }

    T await(long timeoutMillis) throws Exception {
        boolean interrupted = false;
        try {
            try {
                if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS) && state.compareAndSet(0, 2)) {
                    throw new TimeoutException("Main thread is busy; restart the scoped app");
                }
            } catch (InterruptedException failure) {
                if (state.compareAndSet(0, 2)) {
                    throw failure;
                }
                interrupted = true;
            }
            while (done.getCount() != 0) {
                try {
                    done.await();
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
            if (error instanceof Exception exception) {
                throw exception;
            }
            if (error instanceof Error fatal) {
                throw fatal;
            }
            return result;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

package io.github.yuu518.hyperosime;

final class SampleSchedule {
    private boolean dirty;
    private boolean pending;
    private boolean copying;
    private boolean closed;
    private long nextSample;

    long onDraw(long now) {
        if (!closed) {
            dirty = true;
        }
        return schedule(now);
    }

    private long schedule(long now) {
        if (closed || !dirty || pending || copying) {
            return -1;
        }
        pending = true;
        return Math.max(50, nextSample - now);
    }

    boolean beginSample() {
        pending = false;
        dirty = false;
        return !closed;
    }

    void copyStarted(long now) {
        copying = true;
        nextSample = now + 750;
    }

    long copyFinished(long now) {
        copying = false;
        return schedule(now);
    }

    boolean canReload() {
        return !copying;
    }

    boolean isClosed() {
        return closed;
    }

    void close() {
        closed = true;
        dirty = false;
        pending = false;
    }
}

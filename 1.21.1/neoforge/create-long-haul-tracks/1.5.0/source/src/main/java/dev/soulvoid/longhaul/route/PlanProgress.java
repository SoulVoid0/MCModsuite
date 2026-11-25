package dev.soulvoid.longhaul.route;

import java.util.concurrent.atomic.AtomicBoolean;

/** Shared between the planning thread and the server thread: how far a plan has got, and whether to stop it. */
public final class PlanProgress {

    public final AtomicBoolean cancel = new AtomicBoolean();
    public final long started = System.nanoTime();
    /** 0..1, never decreasing. */
    private volatile float fraction;

    public void report(final double f) {
        final float v = (float) Math.max(0, Math.min(0.99, f));
        if (v > fraction)
            fraction = v;
    }

    public float fraction() {
        return fraction;
    }

    public boolean cancelled() {
        return cancel.get();
    }

    /** Seconds left, extrapolated from the time taken so far; -1 while too early to tell. */
    public int etaSeconds() {
        final double elapsed = (System.nanoTime() - started) / 1e9;
        final float f = fraction;
        if (f < 0.03 || elapsed < 1)
            return -1;
        return (int) Math.ceil(elapsed * (1 - f) / f);
    }
}

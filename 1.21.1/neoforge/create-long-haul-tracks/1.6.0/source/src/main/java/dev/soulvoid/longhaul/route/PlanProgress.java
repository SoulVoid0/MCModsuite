package dev.soulvoid.longhaul.route;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

/** Shared between the planning thread and the server thread: how far a plan has got, and whether to stop it. */
public final class PlanProgress {

    public final AtomicBoolean cancel;
    public final long started = System.nanoTime();
    /** 0..1, never decreasing. */
    private volatile float fraction;
    /** When set, reports go here (a part of a larger plan) instead of {@link #fraction}. */
    private final DoubleConsumer sink;

    public PlanProgress() {
        this(new AtomicBoolean(), null);
    }

    private PlanProgress(final AtomicBoolean cancel, final DoubleConsumer sink) {
        this.cancel = cancel;
        this.sink = sink;
    }

    /** A progress for one part of this plan: cancelled with it, its reports passed to {@code sink}. */
    public PlanProgress part(final DoubleConsumer sink) {
        return new PlanProgress(cancel, sink);
    }

    public void report(final double f) {
        final float v = (float) Math.max(0, Math.min(0.99, f));
        if (sink != null) {
            sink.accept(v);
            return;
        }
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

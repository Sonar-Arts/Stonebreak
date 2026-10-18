package com.stonebreak.world.weather;

/**
 * The world's weather clock and mode. The server owns the authoritative copy
 * (ticked, persisted, synced); each client keeps one that free-runs between
 * syncs and converges on the server's clock the way {@code TimeOfDay} does.
 *
 * <p>The clock is monotonic weather time in seconds. It never wraps, because
 * it drives the cloud drift and a wrap would teleport every cloud.</p>
 */
public final class WeatherState {

    /** A clock error past this snaps instead of converging (join, long stall). */
    static final double SNAP_THRESHOLD_SECONDS = 10.0;
    /** Fraction of a small clock error removed per sync. */
    private static final double CONVERGE_FRACTION = 0.25;

    // Volatile: the server ticks these while the save thread snapshots them.
    private volatile double seconds;
    private volatile WeatherMode mode;

    public WeatherState(double seconds, WeatherMode mode) {
        this.seconds = seconds;
        this.mode = mode;
    }

    public void advance(float deltaSeconds) {
        if (deltaSeconds > 0f) seconds += deltaSeconds;
    }

    /** Converges toward the server's clock; large errors snap. */
    public void nudgeTo(double serverSeconds) {
        double delta = serverSeconds - seconds;
        seconds += Math.abs(delta) > SNAP_THRESHOLD_SECONDS ? delta : delta * CONVERGE_FRACTION;
    }

    public double seconds() {
        return seconds;
    }

    public WeatherMode mode() {
        return mode;
    }

    public void setMode(WeatherMode mode) {
        this.mode = mode;
    }
}

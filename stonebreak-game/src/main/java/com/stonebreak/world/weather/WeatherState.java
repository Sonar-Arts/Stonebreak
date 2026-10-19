package com.stonebreak.world.weather;

import java.util.ArrayList;
import java.util.List;

/**
 * The world's weather clock, mode and zones. The server owns the authoritative
 * copy (ticked, persisted, synced); each client keeps one that free-runs between
 * syncs and converges on the server's clock the way {@code TimeOfDay} does.
 *
 * <p>The clock is monotonic weather time in seconds. It never wraps, because
 * it drives the cloud drift and a wrap would teleport every cloud.</p>
 *
 * <p>The mode is the weather of the whole world ({@code /weather <mode> world});
 * {@link WeatherZone}s ({@code /weather <mode>}) hold regions at their own mode
 * on top of it.</p>
 */
public final class WeatherState {

    /** A clock error past this snaps instead of converging (join, long stall). */
    static final double SNAP_THRESHOLD_SECONDS = 10.0;
    /** Fraction of a small clock error removed per sync. */
    private static final double CONVERGE_FRACTION = 0.25;
    /** Most zones a world keeps; placing another drops the oldest. */
    public static final int MAX_ZONES = 16;

    // Volatile: the server ticks these while the save thread snapshots them.
    private volatile double seconds;
    private volatile WeatherMode mode;
    /** Immutable; replaced whole, so readers on other threads see a consistent list. */
    private volatile List<WeatherZone> zones;

    public WeatherState(double seconds, WeatherMode mode) {
        this(seconds, mode, List.of());
    }

    public WeatherState(double seconds, WeatherMode mode, List<WeatherZone> zones) {
        this.seconds = seconds;
        this.mode = mode;
        this.zones = List.copyOf(zones);
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

    /** Sets the whole world's weather; every zone is lifted, so the new weather is seen everywhere. */
    public void setMode(WeatherMode mode) {
        this.mode = mode;
        this.zones = List.of();
    }

    public List<WeatherZone> zones() {
        return zones;
    }

    /** Replaces the zones wholesale (a sync from the server). */
    public void setZones(List<WeatherZone> zones) {
        this.zones = List.copyOf(zones);
    }

    /**
     * Holds the region around {@code zone}'s centre at its mode. Zones whose
     * centres it covers are replaced, so re-running {@code /weather} in place
     * changes the weather instead of stacking zones.
     */
    public synchronized void placeZone(WeatherZone zone) {
        List<WeatherZone> next = new ArrayList<>(zones.size() + 1);
        for (WeatherZone z : zones) {
            if (!zone.covers(z.x(), z.z())) next.add(z);
        }
        next.add(zone);
        while (next.size() > MAX_ZONES) next.removeFirst();
        zones = List.copyOf(next);
    }

    /** Lifts every zone that reaches ({@code x}, {@code z}); returns how many. */
    public synchronized int clearZonesAt(double x, double z) {
        List<WeatherZone> next = new ArrayList<>(zones.size());
        for (WeatherZone zone : zones) {
            if (!zone.covers(x, z)) next.add(zone);
        }
        int removed = zones.size() - next.size();
        zones = List.copyOf(next);
        return removed;
    }
}

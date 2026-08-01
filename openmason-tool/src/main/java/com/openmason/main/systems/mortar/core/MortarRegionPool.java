package com.openmason.main.systems.mortar.core;

import com.openmason.main.systems.skija.SkijaContext;

import java.util.ArrayList;
import java.util.List;

/**
 * A grow-on-demand pool of {@link MortarRegion}s for list UIs that paint one
 * region per visible row (each region owns one FBO, so regions must be reused
 * across frames and released when rows disappear). Index {@code i} always maps
 * to row {@code i}; call {@link #trim} after rendering with the live row count
 * so shrinking lists release their trailing regions promptly.
 *
 * <p>Not thread-safe; use and {@link #close()} on the GL thread, before the
 * SkijaContext closes — same contract as {@link MortarRegion} itself.
 */
public final class MortarRegionPool implements AutoCloseable {

    private final List<MortarRegion> regions = new ArrayList<>();

    /** True when a Skija context exists and pooled regions can paint. */
    public boolean isAvailable() {
        return SkijaContext.getInstance() != null;
    }

    /** The region for row {@code i}, creating intermediate regions as needed. */
    public MortarRegion get(int i) {
        while (regions.size() <= i) {
            regions.add(new MortarRegion());
        }
        return regions.get(i);
    }

    /** Close and drop regions beyond {@code liveCount} rows. */
    public void trim(int liveCount) {
        while (regions.size() > liveCount) {
            regions.remove(regions.size() - 1).close();
        }
    }

    @Override
    public void close() {
        trim(0);
    }
}

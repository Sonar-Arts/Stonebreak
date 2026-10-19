package com.stonebreak.world.weather;

import java.util.ArrayList;
import java.util.List;

/**
 * Where and when lightning strikes, with no network traffic: weather time is
 * cut into {@link #BUCKET_SECONDS} buckets and the sky into
 * {@link #CELL_BLOCKS}-block cloud-space cells; a hash of (seed, bucket, cell)
 * decides whether that cell flashes in that bucket, and the strike only lands
 * if the cloud there is a storm cloud. Every client with the same seed and
 * weather clock sees the same bolt at the same moment.
 */
public final class LightningScheduler {

    static final double BUCKET_SECONDS = 2.0;
    static final int CELL_BLOCKS = 64;
    /**
     * Chance a storm-covered cell strikes in one bucket. Real thunderstorms
     * average about 4 flashes a minute (ordinary cells 1-10); this gives a
     * full STORM sky several strikes a minute within earshot, so thunder
     * rolls one clap at a time instead of piling up.
     */
    static final double STRIKE_CHANCE = 0.0005;
    /** Longest window one query evaluates; a longer gap (stall, clock snap) skips the backlog. */
    static final double MAX_WINDOW_SECONDS = 10.0;

    /**
     * One strike.
     *
     * @param seconds weather time of the flash
     * @param x       world position of the bolt
     * @param z       world position of the bolt
     * @param seed    per-strike randomness for the bolt's shape and sound
     */
    public record Strike(double seconds, double x, double z, long seed) {}

    /** The cloud over a world column at a weather time, as the weather resolves it there. */
    @FunctionalInterface
    public interface Columns {
        CloudColumn at(double worldX, double worldZ, double weatherSeconds);
    }

    private final long worldSeed;

    public LightningScheduler(long worldSeed) {
        this.worldSeed = worldSeed;
    }

    /**
     * Strikes with {@code from <= seconds < to} within {@code range} blocks of
     * world column ({@code centerX}, {@code centerZ}), in time order.
     */
    public List<Strike> strikesBetween(double from, double to, double centerX, double centerZ,
                                       double range, Columns columns) {
        List<Strike> out = new ArrayList<>();
        if (to <= from) return out;
        from = Math.max(from, to - MAX_WINDOW_SECONDS);
        long firstBucket = (long) Math.floor(from / BUCKET_SECONDS);
        long lastBucket = (long) Math.floor(to / BUCKET_SECONDS);
        for (long bucket = firstBucket; bucket <= lastBucket; bucket++) {
            collectBucket(bucket, from, to, centerX, centerZ, range, columns, out);
        }
        out.sort((a, b) -> Double.compare(a.seconds(), b.seconds()));
        return out;
    }

    private void collectBucket(long bucket, double from, double to, double centerX, double centerZ,
                               double range, Columns columns, List<Strike> out) {
        double bucketStart = bucket * BUCKET_SECONDS;
        double driftX = CloudField.driftX(bucketStart);
        double driftZ = CloudField.driftZ(bucketStart);
        // Cells are fixed in cloud space, so a storm carries its lightning with it.
        double cx = centerX - driftX, cz = centerZ - driftZ;
        int minX = (int) Math.floor((cx - range) / CELL_BLOCKS), maxX = (int) Math.floor((cx + range) / CELL_BLOCKS);
        int minZ = (int) Math.floor((cz - range) / CELL_BLOCKS), maxZ = (int) Math.floor((cz + range) / CELL_BLOCKS);
        for (int i = minX; i <= maxX; i++) {
            for (int j = minZ; j <= maxZ; j++) {
                long h = mix(worldSeed ^ mix(bucket * 0x9E3779B97F4A7C15L ^ mix(((long) i << 32) ^ (j & 0xFFFFFFFFL))));
                if (unit(h) >= STRIKE_CHANCE) continue;
                double seconds = bucketStart + unit(mix(h + 1)) * BUCKET_SECONDS;
                if (seconds < from || seconds >= to) continue;
                double cloudX = (i + unit(mix(h + 2))) * CELL_BLOCKS;
                double cloudZ = (j + unit(mix(h + 3))) * CELL_BLOCKS;
                double x = cloudX + driftX, z = cloudZ + driftZ;
                if ((x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ) > range * range) continue;
                // Judged at the bucket's start, so every client asks the same question.
                CloudColumn column = columns.at(x, z, bucketStart);
                if (column.isClear() || !column.type().storm) continue;
                out.add(new Strike(seconds, x, z, mix(h + 4)));
            }
        }
    }

    /** SplitMix64 finaliser. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Uniform in [0, 1) from a hash. */
    static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}

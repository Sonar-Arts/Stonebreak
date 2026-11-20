package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.TerrainTile;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An idle pipeline gives its memory back: after {@code stonebreak.terrain.idleTrimSeconds} the unused pooled blocks,
 * after {@code stonebreak.terrain.idleReleaseSeconds} every cached window; tiles still build afterwards (that they are
 * identical is {@link TerrainCacheParityLiveTest}'s release-between-tiles run). Opt-in
 * with short timers: {@code -Dstonebreak.terrain.idle.live=true -Dstonebreak.terrain.idleTrimSeconds=2
 * -Dstonebreak.terrain.idleReleaseSeconds=6} (the timers are read when the service class loads).
 */
@RequiresDaedalus
class NativeTerrainIdleLiveTest {

    @Test
    void anIdlePipelineTrimsThenReleasesAndStillBuildsTiles() throws Exception {
        assumeTrue(Boolean.getBoolean("stonebreak.terrain.idle.live"), "opt-in: -Dstonebreak.terrain.idle.live=true");
        assumeTrue(NativeTerrainService.IDLE_RELEASE_SECONDS <= 20, "run with short idle timers");
        try (NativeTerrainService s = NativeTerrainLauncher.start()) {
            tile(s, fresh());
            System.out.println("busy:     " + memory(s.statusJson()));
            Thread.sleep(TimeUnit.SECONDS.toMillis(NativeTerrainService.IDLE_TRIM_SECONDS + 2));
            String trimmed = s.statusJson();
            System.out.println("trimmed:  " + memory(trimmed));
            assertEquals(0, value(trimmed, "pool_cached"), "unused pooled blocks handed back");
            assertTrue(value(trimmed, "host_cache_bytes") > 0, "cached windows kept by a trim");

            Thread.sleep(TimeUnit.SECONDS.toMillis(NativeTerrainService.IDLE_RELEASE_SECONDS));
            String released = s.statusJson();
            System.out.println("released: " + memory(released));
            assertEquals(0, value(released, "host_cache_bytes"), "window caches released");
            assertEquals(1, value(released, "releases"));

            TerrainTile after = tile(s, fresh());
            assertEquals(256, after.width(), "a tile builds after a release");
            assertTrue(value(s.statusJson(), "host_cache_bytes") > 0, "and the caches refill");
        }
    }

    private static TerrainTile tile(NativeTerrainService s, long seed) throws Exception {
        var r = new TileRequest(seed, 0, 0, 1, TGMPipe.PRIORITY_WORLD, 0, new CompletableFuture<>());
        s.submit(r);
        return r.future().get(3, TimeUnit.MINUTES);
    }

    /** Never in the disk tile cache, so the engine builds it. */
    private static long fresh() {
        return java.util.concurrent.ThreadLocalRandom.current().nextLong();
    }

    private static long value(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static String memory(String json) {
        StringBuilder b = new StringBuilder();
        for (String k : new String[] {"host_cache_bytes", "pool_cached", "pool_host_cached", "releases"}) {
            long v = value(json, k);
            b.append(k).append('=').append(k.equals("releases") ? v : v >> 20).append(' ');
        }
        return b.toString().strip();
    }
}

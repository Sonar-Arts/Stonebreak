package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.DiffusionTileCache;
import com.stonebreak.world.generation.diffusion.TGMPipeException;
import com.stonebreak.world.generation.diffusion.TerrainScale;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.TileRequestCancelledException;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real TGMPipe, driven the way the game drives it: model on the GPU, frames over the
 * child's pipes. Opt-in ({@code -Dstonebreak.tgmpipe.live=true}): it needs the packaged model,
 * its venv and a CUDA device, and takes about a minute.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TGMPipeLiveTest {

    private static final int OVERVIEW_LOD = 8;
    private static TGMPipe service;

    @BeforeAll
    static void start() {
        assumeTrue(Boolean.getBoolean("stonebreak.tgmpipe.live"), "live TGMPipe test is opt-in");
        service = TGMPipe.getInstance();
        List<Double> progress = new ArrayList<>();
        service.ensureRunning(progress::add);
        assertEquals(List.of(0.0, 1.0), progress);
        service.ensureRunning(fraction -> { throw new AssertionError("already running: no restart"); });
    }

    @AfterAll
    static void stop() {
        if (service != null) service.shutdown();
    }

    private static long freshSeed() {
        return ThreadLocalRandom.current().nextLong(); // never in the disk cache
    }

    @Test
    @Order(1)
    void oneServiceServesAnySeed() throws Exception {
        long a = freshSeed();
        long b = freshSeed();
        try (var first = new DiffusionTileCache(a, OVERVIEW_LOD, TGMPipe.PRIORITY_PREVIEW);
             var second = new DiffusionTileCache(b, OVERVIEW_LOD, TGMPipe.PRIORITY_PREVIEW)) {
            TerrainTile ta = first.getTileAsync(0, 0).get(3, TimeUnit.MINUTES);
            TerrainTile tb = second.getTileAsync(0, 0).get(3, TimeUnit.MINUTES);
            int n = TerrainScale.TILE_SIZE_BLOCKS;
            assertEquals(n, ta.width());
            assertEquals(n, ta.height());
            for (short h : ta.blockHeights()) {
                assertTrue(h >= 0 && h < WorldConfiguration.WORLD_HEIGHT, "height " + h);
            }
            assertFalse(Arrays.equals(ta.blockHeights(), tb.blockHeights()), "two seeds, two worlds");
        }
        String status = service.status().get(10, TimeUnit.SECONDS);
        assertTrue(status.contains(TGMPipe.MODEL_NAME), status);
    }

    @Test
    @Order(2)
    void aClosedCacheWithdrawsItsTiles() {
        var cache = new DiffusionTileCache(freshSeed(), 1, TGMPipe.PRIORITY_PREVIEW);
        CompletableFuture<TerrainTile> pending = cache.getTileAsync(0, 0);
        cache.close();
        ExecutionException e = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
        assertInstanceOf(TileRequestCancelledException.class, e.getCause());
    }

    @Test
    @Order(3)
    void aCrashedServiceIsRestartedAndItsTilesResent() throws Exception {
        try (var cache = new DiffusionTileCache(freshSeed(), OVERVIEW_LOD, TGMPipe.PRIORITY_WORLD)) {
            CompletableFuture<TerrainTile> inFlight = cache.getTileAsync(0, 0);
            ProcessHandle child = ProcessHandle.current().children()
                    .filter(p -> p.info().arguments().map(args -> Arrays.asList(args).contains(TGMPipe.MODULE))
                            .orElse(false))
                    .findFirst().orElseThrow(() -> new AssertionError("no TGMPipe child process"));
            child.destroyForcibly();
            child.onExit().get(30, TimeUnit.SECONDS);

            TerrainTile tile = inFlight.get(4, TimeUnit.MINUTES);
            assertEquals(TerrainScale.TILE_SIZE_BLOCKS, tile.width());
        }
    }

    @Test
    @Order(4)
    void aStoppedServiceFailsFastAndLeavesNoProcess() throws Exception {
        service.shutdown();
        assertEquals(0, ProcessHandle.current().children()
                .filter(p -> p.info().arguments().map(args -> Arrays.asList(args).contains(TGMPipe.MODULE))
                        .orElse(false))
                .count());
        var cache = new DiffusionTileCache(freshSeed(), OVERVIEW_LOD, TGMPipe.PRIORITY_WORLD);
        assertThrows(TGMPipeException.class, () -> cache.getTile(0, 0));
    }
}

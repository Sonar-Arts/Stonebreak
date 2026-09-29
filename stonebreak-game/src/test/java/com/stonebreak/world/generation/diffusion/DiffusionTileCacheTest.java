package com.stonebreak.world.generation.diffusion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link DiffusionTileCache}'s bucketing, in-flight de-dup, failure eviction and
 * close-to-withdraw against a stub fetcher (never touches TGMPipe).
 */
class DiffusionTileCacheTest {

    private static final int TILE = 16;

    private static TerrainTile stubTile(int tileX, int tileZ) {
        int i1 = tileX * TILE;
        int j1 = tileZ * TILE;
        short[] w = new short[TILE * TILE];
        java.util.Arrays.fill(w, TerrainTile.NO_WATER);
        return new TerrainTile(tileX, tileZ, i1, j1, i1 + TILE, j1 + TILE, TILE, TILE,
                new short[TILE * TILE], new short[TILE * TILE], w);
    }

    private static DiffusionTileCache cache(DiffusionTileCache.TileFetcher fetcher) {
        return new DiffusionTileCache(fetcher, 7L, 1, 0, TILE, 8);
    }

    @Test
    void dedupesRequestsForTheSameTile() {
        AtomicInteger fetchCount = new AtomicInteger();
        DiffusionTileCache cache = cache((seed, x, z, lod, prio) -> {
            fetchCount.incrementAndGet();
            return CompletableFuture.completedFuture(stubTile(x, z));
        });

        TerrainTile a = cache.getTile(3, 3);
        TerrainTile b = cache.getTile(10, 10); // same tile (0,0)
        TerrainTile c = cache.getTile(20, 20); // tile (1,1)

        assertSame(a, b);
        assertNotSame(a, c);
        assertEquals(2, fetchCount.get());
    }

    @Test
    void doesNotCacheFailures() {
        AtomicInteger fetchCount = new AtomicInteger();
        DiffusionTileCache cache = cache((seed, x, z, lod, prio) -> fetchCount.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new TGMPipeException("boom"))
                : CompletableFuture.completedFuture(stubTile(x, z)));

        assertThrows(TGMPipeException.class, () -> cache.getTile(0, 0));
        assertNotNull(cache.getTile(0, 0)); // the failure was not cached — this asked again
        assertEquals(2, fetchCount.get());
    }

    @Test
    void bucketsCoordinatesWithFloorDivAndPassesSeedLodAndPriority() {
        List<long[]> asked = new CopyOnWriteArrayList<>();
        DiffusionTileCache cache = new DiffusionTileCache((seed, x, z, lod, prio) -> {
            asked.add(new long[] {seed, x, z, lod, prio});
            return CompletableFuture.completedFuture(stubTile(x, z));
        }, 42L, 8, 1, TILE, 8);

        // -1 truncates to tile 0 with plain integer division; floorDiv must put it in tile -1.
        cache.getTile(-1, -1);

        assertEquals(1, asked.size());
        assertArrayEquals(new long[] {42L, -1, -1, 8, 1}, asked.get(0));
    }

    @Test
    void closingWithdrawsRequestsStillInFlight() {
        CompletableFuture<TerrainTile> never = new CompletableFuture<>();
        DiffusionTileCache cache = cache((seed, x, z, lod, prio) -> never);
        CompletableFuture<TerrainTile> waiting = cache.getTileAsync(0, 0);

        cache.close();

        assertTrue(never.isCompletedExceptionally(), "the service request must be withdrawn");
        assertThrows(TileRequestCancelledException.class, () -> cache.getTile(5, 5));
        assertTrue(waiting.isCompletedExceptionally());
    }

    @Test
    void deferredWorkAsksAtItsOwnPriorityButNeverSoonerThanTheCache() {
        List<Integer> asked = new CopyOnWriteArrayList<>();
        DiffusionTileCache.TileFetcher fetcher = (seed, x, z, lod, prio) -> {
            asked.add(prio);
            return CompletableFuture.completedFuture(stubTile(x, z));
        };
        DiffusionTileCache world = new DiffusionTileCache(fetcher, 7L, 1, 0, TILE, 8);
        DiffusionTileCache preview = new DiffusionTileCache(fetcher, 7L, 1, 2, TILE, 8);

        DiffusionTileCache.deferred(1, () -> world.getTile(0, 0));
        DiffusionTileCache.deferred(1, () -> preview.getTile(0, 0));
        world.getTile(100, 100); // outside deferred(): the cache's own priority again

        assertEquals(List.of(1, 2, 0), asked);
    }

    /**
     * FastLOD queues a tile at its low priority; then the player walks up and a chunk needs the
     * same tile. The chunk must not wait out the LOD queue: the cache asks again at the urgent
     * priority (the service moves its one job up), and the first answer serves everyone.
     */
    @Test
    void urgentWorkExpeditesATileQueuedAtALowerPriority() {
        List<CompletableFuture<TerrainTile>> requests = new CopyOnWriteArrayList<>();
        List<Integer> priorities = new CopyOnWriteArrayList<>();
        DiffusionTileCache cache = cache((seed, x, z, lod, prio) -> {
            CompletableFuture<TerrainTile> f = new CompletableFuture<>();
            requests.add(f);
            priorities.add(prio);
            return f;
        });

        CompletableFuture<TerrainTile> lodWait = DiffusionTileCache.deferred(1, () -> cache.getTileAsync(3, 3));
        CompletableFuture<TerrainTile> chunkWait = cache.getTileAsync(5, 5);
        cache.getTileAsync(6, 6); // already expedited: no third request

        assertEquals(List.of(1, 0), priorities);
        TerrainTile tile = stubTile(0, 0);
        requests.get(1).complete(tile); // the urgent answer lands first
        assertSame(tile, lodWait.join());
        assertSame(tile, chunkWait.join());

        DiffusionTileCache.deferred(1, () -> cache.getTile(4, 4)); // resolved: nothing to expedite
        assertEquals(2, priorities.size());
    }
}

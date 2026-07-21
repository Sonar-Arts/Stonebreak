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

/**
 * Exercises {@link DiffusionTileCache}'s bucketing, in-flight de-dup, and
 * failure-eviction logic against a stub {@link DiffusionTerrainClient}
 * (fetchTile overridden, never touches the network).
 */
class DiffusionTileCacheTest {

    private static DiffusionBridgeConfig config(int tileSize, int maxCachedTiles) {
        return new DiffusionBridgeConfig("http://unused", tileSize, 1000, 1000, 0, 10, 50, maxCachedTiles);
    }

    private static TerrainTile stubTile(int tileX, int tileZ, int tileSize) {
        int i1 = tileX * tileSize;
        int j1 = tileZ * tileSize;
        short[] h = new short[tileSize * tileSize];
        short[] b = new short[tileSize * tileSize];
        return new TerrainTile(tileX, tileZ, i1, j1, i1 + tileSize, j1 + tileSize, tileSize, tileSize, h, b);
    }

    @Test
    void dedupesRequestsForTheSameTile() {
        int tileSize = 16;
        AtomicInteger fetchCount = new AtomicInteger();
        DiffusionTerrainClient stubClient = new DiffusionTerrainClient(config(tileSize, 8), 1L) {
            @Override
            public CompletableFuture<TerrainTile> fetchTile(int worldX, int worldZ) {
                fetchCount.incrementAndGet();
                int tileX = Math.floorDiv(worldX, tileSize);
                int tileZ = Math.floorDiv(worldZ, tileSize);
                return CompletableFuture.completedFuture(stubTile(tileX, tileZ, tileSize));
            }
        };
        DiffusionTileCache cache = new DiffusionTileCache(config(tileSize, 8), stubClient);

        TerrainTile a = cache.getTile(3, 3);
        TerrainTile b = cache.getTile(10, 10); // same tile (0,0) for tileSize=16
        TerrainTile c = cache.getTile(20, 20); // different tile (1,1)

        assertSame(a, b);
        assertNotSame(a, c);
        assertEquals(2, fetchCount.get());
    }

    @Test
    void doesNotCacheFailures() {
        int tileSize = 16;
        AtomicInteger fetchCount = new AtomicInteger();
        DiffusionTerrainClient stubClient = new DiffusionTerrainClient(config(tileSize, 8), 1L) {
            @Override
            public CompletableFuture<TerrainTile> fetchTile(int worldX, int worldZ) {
                if (fetchCount.incrementAndGet() == 1) {
                    CompletableFuture<TerrainTile> failed = new CompletableFuture<>();
                    failed.completeExceptionally(new TerrainBridgeException("boom"));
                    return failed;
                }
                return CompletableFuture.completedFuture(stubTile(0, 0, tileSize));
            }
        };
        DiffusionTileCache cache = new DiffusionTileCache(config(tileSize, 8), stubClient);

        assertThrows(TerrainBridgeException.class, () -> cache.getTile(0, 0));
        TerrainTile tile = cache.getTile(0, 0); // the failed attempt must not be cached — this retries
        assertNotNull(tile);
        assertEquals(2, fetchCount.get());
    }

    @Test
    void bucketsCoordinatesWithFloorDivNotTruncatingDivision() {
        int tileSize = 16;
        List<int[]> requestedTiles = new CopyOnWriteArrayList<>();
        DiffusionTerrainClient stubClient = new DiffusionTerrainClient(config(tileSize, 8), 1L) {
            @Override
            public CompletableFuture<TerrainTile> fetchTile(int worldX, int worldZ) {
                int tileX = Math.floorDiv(worldX, tileSize);
                int tileZ = Math.floorDiv(worldZ, tileSize);
                requestedTiles.add(new int[]{tileX, tileZ});
                return CompletableFuture.completedFuture(stubTile(tileX, tileZ, tileSize));
            }
        };
        DiffusionTileCache cache = new DiffusionTileCache(config(tileSize, 8), stubClient);

        // -1 truncates to tile 0 with plain integer division; floorDiv must put it in tile -1.
        cache.getTile(-1, -1);

        assertEquals(1, requestedTiles.size());
        assertArrayEquals(new int[]{-1, -1}, requestedTiles.get(0));
    }
}

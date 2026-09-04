package com.stonebreak.world.generation.water;

import com.stonebreak.world.generation.diffusion.DiffusionBridgeConfig;
import com.stonebreak.world.generation.diffusion.TerrainBridgeException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The coarse elevation field the river walker descends: fractional block
 * heights on a 16-block lattice, fetched from the bridge's
 * {@code POST /coarse_elevation} in 2048-block chunks and cached in memory.
 *
 * <p>Rivers are planned by a budgeted downhill walk over kilometres, which is
 * far more ground than the water kernel's 3x3 tile window holds. An earlier
 * design routed on procedural noise instead, so no elevation had to be fetched
 * at all — it did not work: real terrain has a continental spread of ~160
 * blocks while being locally flat, so an independent field of the same spread
 * disagrees pointwise by ~220 blocks RMS and only 27 % of planned route length
 * survived. A route that cannot see the terrain cannot sit on it.
 *
 * <p><b>Chunks, not boxes.</b> Upstream's determinism guarantee holds only for
 * an identical request shape (terrain-bridge/bridge/tiling.py), so a chunk's
 * shape is derived from its ID alone on the bridge side. This class must agree
 * with the bridge about chunk and cell size or it would assemble windows from
 * differently-shaped ground; rather than trust the configuration, it verifies
 * both against the response headers on every fetch.
 *
 * <p><b>Never rounds.</b> Values stay fractional all the way to the kernel:
 * quantised to whole blocks (15 m each) 40 % of land is perfectly flat, and
 * downhill routing on that degenerates into a distance field that produces
 * herringbone artifacts instead of rivers (terrain-bridge/hydrology/README.md).
 *
 * <p><b>No fallback on failure.</b> A fetch that cannot be satisfied throws.
 * Substituting a river-less window would bake a permanently inconsistent tile
 * into the caches downstream — the same reason
 * {@code DiffusionTerrainClient} refuses to invent terrain.
 *
 * <p>Only a memory cache lives here. The bridge already persists chunks to
 * disk, which is what protects the expensive part (GPU generation); a second
 * copy on this side would buy only a localhost round trip for 64 KB.
 */
public final class CoarseDem {

    /** A DEM window ready to hand to {@code ck_carve_water}. */
    public record Window(float[] cells, int cellCount, int cellBlocks,
                         long originX, long originZ) {}

    private record ChunkKey(long chunkX, long chunkZ) {}

    private final DiffusionBridgeConfig config;
    private final long seed;
    private final HttpClient httpClient;
    private final int chunkBlocks;
    private final int cellBlocks;
    private final int cellsPerChunk;

    private final Map<ChunkKey, float[]> cache;
    private final SingleFlight<ChunkKey, float[]> inFlight = new SingleFlight<>();

    public CoarseDem(DiffusionBridgeConfig config, long seed) {
        this.config = config;
        this.seed = seed;
        this.chunkBlocks = config.coarseChunkBlocks();
        this.cellBlocks = config.coarseCellBlocks();
        if (chunkBlocks <= 0 || cellBlocks <= 0 || chunkBlocks % cellBlocks != 0) {
            throw new IllegalArgumentException("coarse chunk " + chunkBlocks
                + " must be a positive multiple of cell " + cellBlocks);
        }
        this.cellsPerChunk = chunkBlocks / cellBlocks;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                // uvicorn speaks HTTP/1.1 only; the default h2c upgrade attempt silently
                // drops the POST body and FastAPI answers 422. Same fix as
                // DiffusionTerrainClient, same reason.
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        int capacity = Math.max(16, config.maxCachedCoarseChunks());
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkKey, float[]> eldest) {
                return size() > capacity;
            }
        };
    }

    public int cellBlocks() {
        return cellBlocks;
    }

    /**
     * Assemble a square window of at least {@code spanBlocks} on a side, centred
     * on {@code (centreX, centreZ)}.
     *
     * <p>The origin is snapped down to the absolute cell lattice, so a given
     * world cell carries the same value no matter which window contains it —
     * without that, two tiles could sample the same ground at different offsets
     * and route rivers differently.
     */
    public Window window(long centreX, long centreZ, int spanBlocks) {
        return windowAt(Math.floorDiv(centreX - spanBlocks / 2, cellBlocks) * (long) cellBlocks,
                        Math.floorDiv(centreZ - spanBlocks / 2, cellBlocks) * (long) cellBlocks,
                        Math.ceilDiv(spanBlocks, cellBlocks));
    }

    /**
     * A square window of {@code cells} cells whose first cell begins exactly at
     * {@code (originX, originZ)}, which must be on the cell lattice.
     *
     * <p>The addressed form, for a caller that already knows the window it
     * wants — a basin solve covers a region plus its halo, and that rectangle
     * is fixed by the region id, not chosen around a point.
     */
    public Window windowAt(long originX, long originZ, int cells) {
        if (originX % cellBlocks != 0 || originZ % cellBlocks != 0) {
            throw new IllegalArgumentException("window origin (" + originX + "," + originZ
                + ") is off the " + cellBlocks + "-block cell lattice");
        }
        float[] out = new float[cells * cells];
        long endX = originX + (long) cells * cellBlocks;
        long endZ = originZ + (long) cells * cellBlocks;

        long chunkX0 = Math.floorDiv(originX, chunkBlocks);
        long chunkX1 = Math.floorDiv(endX - 1, chunkBlocks);
        long chunkZ0 = Math.floorDiv(originZ, chunkBlocks);
        long chunkZ1 = Math.floorDiv(endZ - 1, chunkBlocks);

        for (long cx = chunkX0; cx <= chunkX1; cx++) {
            for (long cz = chunkZ0; cz <= chunkZ1; cz++) {
                blitChunk(chunk(cx, cz), cx, cz, out, cells, originX, originZ);
            }
        }
        return new Window(out, cells, cellBlocks, originX, originZ);
    }

    /** Copy the part of one chunk that falls inside the window. */
    private void blitChunk(float[] src, long chunkX, long chunkZ,
                           float[] dst, int cells, long originX, long originZ) {
        // Window cell index of this chunk's first cell; may be negative or past
        // the end, so both ends are clamped into the window.
        long baseI = (chunkX * chunkBlocks - originX) / cellBlocks;
        long baseJ = (chunkZ * chunkBlocks - originZ) / cellBlocks;
        int i0 = (int) Math.max(0, baseI);
        int j0 = (int) Math.max(0, baseJ);
        int i1 = (int) Math.min(cells, baseI + cellsPerChunk);
        int j1 = (int) Math.min(cells, baseJ + cellsPerChunk);
        for (int i = i0; i < i1; i++) {
            int srcRow = (int) (i - baseI);
            int srcOff = srcRow * cellsPerChunk + (int) (j0 - baseJ);
            System.arraycopy(src, srcOff, dst, i * cells + j0, j1 - j0);
        }
    }

    /** Cells along one side of a chunk. */
    public int cellsPerChunk() {
        return cellsPerChunk;
    }

    /** Blocks along one side of a chunk. */
    public int chunkBlocks() {
        return chunkBlocks;
    }

    /**
     * One whole chunk, {@code cellsPerChunk()^2} cells, row = world X.
     *
     * <p>Exposed for a caller that cannot afford {@link #windowAt} — the L0
     * basin level's window is 65,536 blocks a side, which is 4096^2 cells and
     * 67 MB if materialized at this resolution. Such a caller walks the chunks
     * and downsamples each as it arrives. The returned array is the cached
     * instance and must not be modified.
     */
    public float[] chunk(long chunkX, long chunkZ) {
        ChunkKey key = new ChunkKey(chunkX, chunkZ);
        float[] hit = cached(key);
        if (hit != null) {
            return hit;
        }
        // A concurrent fetch of the same chunk would be correct — the payload
        // is a pure function of its ID — but it is a second cold GPU request
        // for ground the first one is already generating, and a window is
        // assembled from sixteen of these. One fetch per chunk, everyone else
        // waits for it.
        return inFlight.compute(key, k -> {
            float[] raced = cached(k);
            if (raced != null) {
                return raced;
            }
            float[] fetched = fetchChunk(k.chunkX(), k.chunkZ());
            synchronized (cache) {
                cache.put(k, fetched);
            }
            return fetched;
        });
    }

    private float[] cached(ChunkKey key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    private float[] fetchChunk(long chunkX, long chunkZ) {
        String body = "{\"chunk_x\":" + chunkX + ",\"chunk_z\":" + chunkZ
            + ",\"seed\":" + seed + "}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/coarse_elevation"))
                .timeout(Duration.ofMillis(config.requestTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new TerrainBridgeException("coarse elevation chunk (" + chunkX + ","
                + chunkZ + ") could not be fetched from " + config.baseUrl(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TerrainBridgeException("interrupted fetching coarse elevation chunk ("
                + chunkX + "," + chunkZ + ")", e);
        }
        if (response.statusCode() != 200) {
            throw new TerrainBridgeException("coarse elevation chunk (" + chunkX + ","
                + chunkZ + ") returned HTTP " + response.statusCode() + ": "
                + new String(response.body(), StandardCharsets.UTF_8).trim());
        }
        return parseChunk(response, chunkX, chunkZ);
    }

    private float[] parseChunk(HttpResponse<byte[]> response, long chunkX, long chunkZ) {
        // The body is a bare float plane with no header of its own, so a
        // geometry mismatch would slice into plausible garbage rather than
        // fail. These checks are the only thing that makes it loud.
        requireHeader(response, "X-Dtype", "float32-le", chunkX, chunkZ);
        requireHeader(response, "X-Chunk-Blocks", Integer.toString(chunkBlocks), chunkX, chunkZ);
        requireHeader(response, "X-Cell-Blocks", Integer.toString(cellBlocks), chunkX, chunkZ);
        requireHeader(response, "X-Height", Integer.toString(cellsPerChunk), chunkX, chunkZ);
        requireHeader(response, "X-Width", Integer.toString(cellsPerChunk), chunkX, chunkZ);

        byte[] payload = response.body();
        int expected = cellsPerChunk * cellsPerChunk * Float.BYTES;
        if (payload.length != expected) {
            throw new TerrainBridgeException("coarse elevation chunk (" + chunkX + ","
                + chunkZ + ") is " + payload.length + " bytes, expected " + expected);
        }
        float[] cells = new float[cellsPerChunk * cellsPerChunk];
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(cells);
        return cells;
    }

    private static void requireHeader(HttpResponse<byte[]> response, String name,
                                      String expected, long chunkX, long chunkZ) {
        String actual = response.headers().firstValue(name).orElse(null);
        if (!expected.equals(actual)) {
            throw new TerrainBridgeException("coarse elevation chunk (" + chunkX + ","
                + chunkZ + ") reported " + name + "=" + actual + ", expected " + expected
                + " — the bridge's coarse chunk geometry must match this client's");
        }
    }

    /** Test seam: how many distinct chunks are resident. */
    int cachedChunkCount() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** Test seam for offline assembly: pre-populate the cache. */
    void putChunkForTesting(long chunkX, long chunkZ, float[] cells) {
        if (cells.length != cellsPerChunk * cellsPerChunk) {
            throw new IllegalArgumentException("chunk must be " + cellsPerChunk + "^2 cells");
        }
        synchronized (cache) {
            cache.put(new ChunkKey(chunkX, chunkZ), cells);
        }
    }

    /** Backing map for a subclass that serves chunks without HTTP (tests). */
    Map<ChunkKey, float[]> cacheForTesting() {
        return new HashMap<>(cache);
    }
}

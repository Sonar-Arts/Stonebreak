package com.stonebreak.world;

import com.stonebreak.world.operations.WorldConfiguration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Manages snow layer data for the world.
 *
 * <p>Keyed by packed world coordinates (no string allocation per access) and
 * purged per chunk on unload — previously this map grew unbounded for the
 * lifetime of the world. Layout matches the water sim's packing: [x:24][z:24][y:16].
 *
 * <p>Note: snow layers are not persisted, so purging on unload means layer
 * counts reset to the 1-layer default when a chunk reloads (previously they
 * only reset on world reload).
 */
public class SnowLayerManager {

    private static final int X_SHIFT = 40;
    private static final int Z_SHIFT = 16;
    private static final long XZ_MASK = 0xFFFFFFL;
    private static final long Y_MASK = 0xFFFFL;
    private static final int XZ_SIGN_BIT = 0x00800000;
    private static final int XZ_SIGN_EXT = 0xFF000000;

    // Map from packed world position to snow layer count (1-8)
    private final Map<Long, Integer> snowLayers = new ConcurrentHashMap<>();

    /**
     * Observer for gameplay snow mutations ({@code layers == 0} means removed). Fired by
     * {@link #setSnowLayers}/{@link #removeSnowLayers} but NOT by {@link #putRaw} hydration —
     * loading a chunk must not re-dirty it or re-broadcast its snow. The owning {@code World}
     * wires this to save-dirty marking (+ server replication on the headless world).
     */
    @FunctionalInterface
    public interface SnowMutationListener {
        void onSnowChanged(int x, int y, int z, int layers);
    }

    private volatile SnowMutationListener mutationListener;

    public void setMutationListener(SnowMutationListener listener) {
        this.mutationListener = listener;
    }

    private void fireChanged(int x, int y, int z, int layers) {
        SnowMutationListener l = mutationListener;
        if (l != null) {
            l.onSnowChanged(x, y, z, layers);
        }
    }

    private static long packKey(int x, int y, int z) {
        return ((long) (x & 0xFFFFFF) << X_SHIFT)
             | ((long) (z & 0xFFFFFF) << Z_SHIFT)
             | (y & Y_MASK);
    }

    private static int unpackX(long key) {
        int x = (int) ((key >>> X_SHIFT) & XZ_MASK);
        return (x & XZ_SIGN_BIT) != 0 ? x | XZ_SIGN_EXT : x;
    }

    private static int unpackZ(long key) {
        int z = (int) ((key >>> Z_SHIFT) & XZ_MASK);
        return (z & XZ_SIGN_BIT) != 0 ? z | XZ_SIGN_EXT : z;
    }

    /**
     * Gets the snow layer count at a specific position
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @return Number of snow layers (1-8), or 1 if untracked (snow block default)
     */
    public int getSnowLayers(int x, int y, int z) {
        return snowLayers.getOrDefault(packKey(x, y, z), 1); // Default to 1 layer if snow exists
    }

    /**
     * Sets the snow layer count at a specific position
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @param layers Number of snow layers (1-8)
     */
    public void setSnowLayers(int x, int y, int z, int layers) {
        if (layers < 1 || layers > 8) {
            throw new IllegalArgumentException("Snow layers must be between 1 and 8");
        }
        Integer previous = snowLayers.put(packKey(x, y, z), layers);
        if (previous == null || previous != layers) {
            fireChanged(x, y, z, layers);
        }
    }

    /**
     * Removes snow layer data at a specific position
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     */
    public void removeSnowLayers(int x, int y, int z) {
        if (snowLayers.remove(packKey(x, y, z)) != null) {
            fireChanged(x, y, z, 0);
        }
    }

    /**
     * Attempts to add a snow layer at the specified position
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @return true if layer was added, false if already at max layers (8)
     */
    public boolean addSnowLayer(int x, int y, int z) {
        int currentLayers = getSnowLayers(x, y, z);
        if (currentLayers < 8) {
            setSnowLayers(x, y, z, currentLayers + 1);
            return true;
        }
        return false;
    }

    /**
     * Gets the visual height of snow at a position (0.125 to 1.0)
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @return Height as fraction of full block (0.125 * layers)
     */
    public float getSnowHeight(int x, int y, int z) {
        int layers = getSnowLayers(x, y, z);
        return layers * 0.125f;
    }

    /** Visitor for per-chunk snow iteration (world coords + layer count). */
    @FunctionalInterface
    public interface SnowEntryConsumer {
        void accept(int x, int y, int z, int layers);
    }

    /**
     * Visits every tracked snow entry inside one chunk (world coordinates). Used by chunk
     * save gathering and network chunk-meta encoding.
     */
    public void forEachInChunk(int chunkX, int chunkZ, SnowEntryConsumer consumer) {
        for (Map.Entry<Long, Integer> e : snowLayers.entrySet()) {
            long key = e.getKey();
            int x = unpackX(key);
            int z = unpackZ(key);
            if (Math.floorDiv(x, WorldConfiguration.CHUNK_SIZE) == chunkX
                && Math.floorDiv(z, WorldConfiguration.CHUNK_SIZE) == chunkZ) {
                consumer.accept(x, (int) (key & Y_MASK), z, e.getValue());
            }
        }
    }

    /**
     * Hydrates one entry from persistence / the network without range ceremony (still
     * clamped 1-8 defensively). Layer 1 entries are stored too — absence means "untracked",
     * which also READS as 1, but keeping the entry preserves save/wire round-trip fidelity.
     */
    public void putRaw(int x, int y, int z, int layers) {
        snowLayers.put(packKey(x, y, z), Math.max(1, Math.min(8, layers)));
    }

    /**
     * Drops all snow layer entries inside an unloading chunk. Without this,
     * the map grows for every snowfall the player ever walks past.
     */
    public void onChunkUnloaded(int chunkX, int chunkZ) {
        snowLayers.keySet().removeIf(key ->
            Math.floorDiv(unpackX(key), WorldConfiguration.CHUNK_SIZE) == chunkX &&
            Math.floorDiv(unpackZ(key), WorldConfiguration.CHUNK_SIZE) == chunkZ
        );
    }

    /**
     * Clears all snow layer data (world unload).
     */
    public void clear() {
        snowLayers.clear();
    }
}

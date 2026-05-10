package com.stonebreak.rendering.UI.masonryUI.textures;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide cache of {@link MTexture} instances keyed by classpath
 * resource path.
 *
 * <p>SBT decoding + Skija compositing happens once per resource. Subsequent
 * lookups are an O(1) map hit. Resources that fail to load are remembered
 * separately so a missing asset is not re-attempted every frame.
 *
 * <p>Call {@link #disposeAll()} on shutdown to release Skija {@code Image}
 * handles deterministically.
 */
public final class MTextureRegistry {

    private static final Map<String, MTexture> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private MTextureRegistry() {}

    /**
     * Return a cached MTexture for {@code classpathResource}, loading it on
     * the first call. Returns {@code null} if the resource cannot be loaded.
     */
    /**
     * Return a cached MTexture for an SBO-backed item, loading it from the
     * item registry on first call. Cache key is the item's namespaced object
     * ID (e.g. {@code "sbo:stonebreak:sword"}). Returns {@code null} if the
     * item isn't registered or its OMT cannot be decoded.
     */
    public static MTexture getForSboItem(com.stonebreak.items.ItemType itemType) {
        return getForSboItem(itemType, null);
    }

    /**
     * State-aware variant — returns the texture for a specific SBO state
     * (1.3+). Each state gets its own cache entry so an empty bucket and a
     * water bucket render with the correct OMT independently. Pass
     * {@code null} (or a state name the item doesn't declare) to fall back
     * to the default-state texture.
     */
    public static MTexture getForSboItem(com.stonebreak.items.ItemType itemType, String state) {
        if (itemType == null) return null;
        String objectId = com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer.sboItemId(itemType);
        String key = (state != null && !state.isBlank())
                ? "sbo:" + objectId + "#" + state
                : "sbo:" + objectId;

        MTexture cached = CACHE.get(key);
        if (cached != null) return cached;
        if (FAILED.contains(key)) return null;

        var entry = com.stonebreak.items.registry.ItemRegistry.getInstance()
                .get(objectId)
                .orElse(null);
        if (entry == null) {
            FAILED.add(key);
            return null;
        }
        byte[] bytes = entry.omtBytesFor(state);
        MTexture loaded = MTexture.loadFromOmtBytes(key, bytes);
        if (loaded != null) {
            CACHE.put(key, loaded);
        } else {
            FAILED.add(key);
        }
        return loaded;
    }

    public static MTexture get(String classpathResource) {
        if (classpathResource == null || classpathResource.isBlank()) return null;

        MTexture cached = CACHE.get(classpathResource);
        if (cached != null) return cached;
        if (FAILED.contains(classpathResource)) return null;

        MTexture loaded = MTexture.loadFromResource(classpathResource);
        if (loaded != null) {
            CACHE.put(classpathResource, loaded);
        } else {
            FAILED.add(classpathResource);
        }
        return loaded;
    }

    /** Release every loaded texture and clear the cache. */
    public static void disposeAll() {
        for (MTexture tex : CACHE.values()) {
            tex.close();
        }
        CACHE.clear();
        FAILED.clear();
    }
}

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

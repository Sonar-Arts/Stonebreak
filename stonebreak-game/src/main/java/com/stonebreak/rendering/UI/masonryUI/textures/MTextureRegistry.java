package com.stonebreak.rendering.UI.masonryUI.textures;

import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.masonry.textures.MTextureCache;

/**
 * Stonebreak's view of the shared Masonry texture cache: game classpath SBTs and SBO item
 * icons. The engine {@link MTextureCache} owns every texture (one decode per key, failures
 * remembered); this class only knows where game bytes come from, since the engine cannot read
 * this module's resources.
 *
 * <p>Call {@link #disposeAll()} on shutdown, on the render thread, to release Skija
 * {@code Image} handles deterministically.
 */
public final class MTextureRegistry {

    private static final MTextureCache CACHE = new MTextureCache();

    private MTextureRegistry() {}

    /** The game's single texture owner, shared with document-driven UI (#287) so bytes decode once. */
    public static MTextureCache cache() {
        return CACHE;
    }

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
        return CACHE.get(key, k -> com.stonebreak.items.registry.ItemRegistry.getInstance()
                .get(objectId)
                .map(entry -> MTexture.loadFromOmtBytes(k, entry.omtBytesFor(state)))
                .orElse(null));
    }

    /**
     * Return a cached MTexture for {@code classpathResource}, loading it on
     * the first call. Returns {@code null} if the resource cannot be loaded.
     */
    public static MTexture get(String classpathResource) {
        return CACHE.get(classpathResource,
                path -> MTexture.loadFromResource(path, MTextureRegistry.class::getResourceAsStream));
    }

    /** Release every loaded texture and clear the cache. */
    public static void disposeAll() {
        CACHE.disposeAll();
    }
}

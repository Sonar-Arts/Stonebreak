package com.openmason.engine.ui.masonry.textures;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Shared {@link MTexture}s keyed by resource path or synthetic id. The cache is the single owner
 * of every texture it returns: screens borrow them and never close them, so closing one screen
 * cannot invalidate an image another screen still draws. Decoding happens once per key; a key
 * that fails is remembered and not retried every frame.
 *
 * <p>{@link #disposeAll()} releases the Skija images and belongs on the render thread, at
 * shutdown or context loss. Thread-safe for lookups.
 */
public final class MTextureCache {

    private final Map<String, MTexture> textures = new ConcurrentHashMap<>();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    /**
     * The texture for {@code key}, decoding it with {@code loader} on first use.
     *
     * @return {@code null} when the loader produced nothing (remembered until {@link #forget}
     *         or {@link #disposeAll})
     */
    public MTexture get(String key, Function<String, MTexture> loader) {
        if (key == null || key.isBlank()) return null;
        MTexture cached = textures.get(key);
        if (cached != null) return cached;
        if (failed.contains(key)) return null;
        MTexture loaded = loader.apply(key);
        if (loaded == null) {
            failed.add(key);
            return null;
        }
        MTexture raced = textures.putIfAbsent(key, loaded);
        if (raced != null) {
            loaded.close();
            return raced;
        }
        return loaded;
    }

    /**
     * Drops {@code key} so the next lookup decodes again (the asset changed or reappeared) and
     * returns the old texture for the caller to close once no frame uses it.
     */
    public MTexture forget(String key) {
        failed.remove(key);
        return textures.remove(key);
    }

    public int size() {
        return textures.size();
    }

    /** Releases every texture and forgets failures. */
    public void disposeAll() {
        for (MTexture texture : textures.values()) {
            texture.close();
        }
        textures.clear();
        failed.clear();
    }
}

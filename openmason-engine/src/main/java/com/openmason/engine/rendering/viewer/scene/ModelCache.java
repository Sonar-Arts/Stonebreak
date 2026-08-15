package com.openmason.engine.rendering.viewer.scene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Shares one loaded model between every instance that places it, and frees it once
 * nothing does.
 *
 * <p>Keyed on <b>path + last-modified time</b>, so re-saving a model in the editor
 * produces a different key and the scene picks up the new geometry on next load rather
 * than silently showing a stale copy.
 *
 * <p><b>Threading:</b> eviction deletes GL objects, so every method must run on the GL
 * thread.
 */
public final class ModelCache implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ModelCache.class);

    /** Frees a handle's GPU resources. Injectable so tests need no GL context. */
    @FunctionalInterface
    public interface Disposer {
        void dispose(ModelHandle handle);
    }

    private final ModelSource loader;
    private final Disposer disposer;
    private final Map<String, ModelHandle> byKey = new HashMap<>();

    public ModelCache(ModelSource loader, Disposer disposer) {
        this.loader = java.util.Objects.requireNonNull(loader, "loader");
        this.disposer = java.util.Objects.requireNonNull(disposer, "disposer");
    }

    /**
     * The default disposer: cleans up the renderer and deletes the textures it owns.
     *
     * <p>The texture deletion matters — {@code GenericModelRenderer.cleanup()} only frees
     * VAO/VBO/EBO, so material textures would otherwise leak for every model a scene ever
     * opened.
     */
    public static Disposer glDisposer() {
        return handle -> {
            try {
                handle.renderer().cleanup();
            } catch (Exception e) {
                logger.error("Error cleaning up renderer for '{}'", handle.name(), e);
            }
            int[] textures = handle.ownedTextureIds();
            if (textures.length > 0) {
                org.lwjgl.opengl.GL11.glDeleteTextures(textures);
            }
        };
    }

    /**
     * Load (or reuse) the model at {@code path} and take a reference to it.
     *
     * <p>Every {@code acquire} must be matched by a {@link #release}.
     */
    public ModelHandle acquire(Path path) throws IOException {
        String key = keyFor(path);
        ModelHandle existing = byKey.get(key);
        if (existing != null) {
            existing.retain();
            return existing;
        }

        OmoModelLoader.Loaded loaded = loader.load(path);
        ModelHandle handle = new ModelHandle(key, path, loaded.modelName(),
                loaded.renderer(), loaded.textureIds());
        handle.retain();
        byKey.put(key, handle);
        logger.debug("Loaded model into cache: {}", key);
        return handle;
    }

    /**
     * Load (or reuse) a model from bytes — the path a scene takes when its referenced
     * file is missing and it falls back to its embedded copy.
     *
     * @param cacheKey stable identity for these bytes (e.g. a content hash)
     */
    public ModelHandle acquireBytes(String cacheKey, byte[] omoBytes, String displayName) throws IOException {
        ModelHandle existing = byKey.get(cacheKey);
        if (existing != null) {
            existing.retain();
            return existing;
        }

        OmoModelLoader.Loaded loaded = loader.load(omoBytes, displayName);
        ModelHandle handle = new ModelHandle(cacheKey, null, loaded.modelName(),
                loaded.renderer(), loaded.textureIds());
        handle.retain();
        byKey.put(cacheKey, handle);
        return handle;
    }

    /** Drop a reference; the handle is freed when the last one goes. */
    public void release(ModelHandle handle) {
        if (handle == null) {
            return;
        }
        if (handle.release() <= 0) {
            byKey.remove(handle.key());
            disposer.dispose(handle);
            logger.debug("Evicted model from cache: {}", handle.key());
        }
    }

    /** Number of distinct models currently loaded. */
    public int size() {
        return byKey.size();
    }

    public boolean contains(String key) {
        return byKey.containsKey(key);
    }

    @Override
    public void close() {
        for (ModelHandle handle : byKey.values()) {
            disposer.dispose(handle);
        }
        byKey.clear();
    }

    /**
     * Cache key: real path plus last-modified time.
     *
     * <p>Including mtime is what makes an editor save invalidate the scene's copy. If the
     * file cannot be stat'ed we fall back to the path alone rather than failing — a model
     * that loads but never invalidates beats no model at all.
     */
    private static String keyFor(Path path) {
        try {
            Path real = path.toRealPath();
            return real + "@" + Files.getLastModifiedTime(real).toMillis();
        } catch (IOException e) {
            return path.toAbsolutePath().toString();
        }
    }
}

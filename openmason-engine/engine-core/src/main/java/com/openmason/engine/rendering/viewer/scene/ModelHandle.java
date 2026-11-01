package com.openmason.engine.rendering.viewer.scene;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.engine.rendering.model.ModelBounds;

import java.nio.file.Path;

/**
 * One loaded model, shared by every instance that places it.
 *
 * <p>Holds the GPU-resident renderer plus the texture ids that were uploaded for it, so
 * releasing the handle can free both. {@code GenericModelRenderer.cleanup()} only frees
 * VAO/VBO/EBO — material textures were previously never deleted at all, which is why the
 * ids are tracked here.
 */
public final class ModelHandle {

    private final String key;
    private final Path source;
    private final String name;
    private final GenericModelRenderer renderer;
    private final int[] ownedTextureIds;

    private ModelBounds cachedBounds;
    private int refCount;

    ModelHandle(String key, Path source, String name,
                GenericModelRenderer renderer, int[] ownedTextureIds) {
        this(key, source, name, renderer, ownedTextureIds, null);
    }

    /**
     * @param bounds model-space bounds, or null to derive them lazily from the renderer.
     *               Supplying them explicitly is what lets a scene be assembled and
     *               reasoned about (picking, framing) without a GL context.
     */
    public ModelHandle(String key, Path source, String name,
                       GenericModelRenderer renderer, int[] ownedTextureIds, ModelBounds bounds) {
        this.key = key;
        this.source = source;
        this.name = name;
        this.renderer = renderer;
        this.ownedTextureIds = ownedTextureIds != null ? ownedTextureIds.clone() : new int[0];
        this.cachedBounds = bounds;
        this.refCount = 0;
    }

    /** Cache key this handle is stored under. */
    public String key() { return key; }

    /** File this model came from, or null when it was loaded from bytes. */
    public Path source() { return source; }

    public String name() { return name; }

    public GenericModelRenderer renderer() { return renderer; }

    /** Model-space bounds, computed once on first request. */
    public ModelBounds bounds() {
        if (cachedBounds == null && renderer != null) {
            cachedBounds = renderer.getModelBounds();
        }
        return cachedBounds != null ? cachedBounds : ModelBounds.EMPTY;
    }

    /** Texture ids uploaded for this model, which the cache deletes on eviction. */
    public int[] ownedTextureIds() { return ownedTextureIds.clone(); }

    public int refCount() { return refCount; }

    int retain() { return ++refCount; }
    int release() { return --refCount; }

    /** Invalidate the bounds cache after the underlying geometry changes. */
    public void invalidateBounds() { cachedBounds = null; }
}

package com.openmason.engine.ui.masonry.textures;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Shared {@link MTexture}s keyed by resource path or synthetic id. The cache is the single owner
 * of every texture it returns: screens borrow them and never close them, so closing one screen
 * cannot invalidate an image another screen still draws. Decoding happens once per key; a key
 * that fails is remembered and not retried every frame.
 *
 * <p>{@link #disposeAll()} releases the Skija images and belongs on the render thread, at
 * shutdown or context loss. Thread-safe for lookups.
 *
 * <p><b>Releasing a superseded revision</b> (an asset was saved and nothing draws the old bytes):
 * {@link #release} drops the key and queues the texture; it is closed by {@link #drainReleases}
 * once {@link #RELEASE_GRACE_NANOS} has passed, so a frame already being built with the old image
 * never meets a closed one. Lookups drain opportunistically, so a host that never calls
 * {@code drainReleases} still frees memory deterministically instead of waiting for the GC.
 */
public final class MTextureCache {

    /** How long a released texture stays open: comfortably more than one frame. */
    public static final long RELEASE_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private record Retired(MTexture texture, long at) {
    }

    private final Map<String, MTexture> textures = new ConcurrentHashMap<>();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final Queue<Retired> retired = new ConcurrentLinkedQueue<>();

    /**
     * The texture for {@code key}, decoding it with {@code loader} on first use.
     *
     * @return {@code null} when the loader produced nothing (remembered until {@link #forget}
     *         or {@link #disposeAll})
     */
    public MTexture get(String key, Function<String, MTexture> loader) {
        if (key == null || key.isBlank()) return null;
        if (!retired.isEmpty()) drainReleases();
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

    /**
     * Drops {@code key} and closes its texture after {@link #RELEASE_GRACE_NANOS} (at the next
     * {@link #drainReleases} or lookup). Only for revisions no document still draws.
     *
     * @return true when a texture was queued
     */
    public boolean release(String key) {
        MTexture old = forget(key);
        if (old == null) return false;
        retired.add(new Retired(old, System.nanoTime()));
        return true;
    }

    /** Closes released textures whose grace has passed; call on the render thread once per frame. */
    public void drainReleases() {
        drainReleases(System.nanoTime());
    }

    /** {@link #drainReleases()} at a given {@link System#nanoTime} reading (tests). */
    public void drainReleases(long now) {
        Retired r;
        while ((r = retired.peek()) != null && now - r.at() >= RELEASE_GRACE_NANOS) {
            if (retired.remove(r)) {
                r.texture().close();
            }
        }
    }

    /** Textures released but not yet closed. */
    public int pendingReleases() {
        return retired.size();
    }

    public int size() {
        return textures.size();
    }

    /** Releases every texture and forgets failures. */
    public void disposeAll() {
        Retired r;
        while ((r = retired.poll()) != null) {
            r.texture().close();
        }
        for (MTexture texture : textures.values()) {
            texture.close();
        }
        textures.clear();
        failed.clear();
    }
}

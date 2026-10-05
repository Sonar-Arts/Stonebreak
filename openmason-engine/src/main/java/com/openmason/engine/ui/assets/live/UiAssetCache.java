package com.openmason.engine.ui.assets.live;

import com.openmason.engine.ui.assets.ResolvedAsset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Decoded assets (composited images, parsed components, fonts) keyed by dependency id, with
 * per-id revisions so editing a shared asset refreshes exactly what used it.
 *
 * <ul>
 *   <li><b>No per-frame work.</b> A current entry is a map hit; a failed load is remembered
 *       for its revision and not retried until the id is invalidated (relink, save, the file
 *       appearing).</li>
 *   <li><b>Stale loads never win.</b> A load records the revision it started at and installs
 *       only if the id has not been invalidated since; otherwise its result is released.</li>
 *   <li><b>Safe release.</b> Replaced values are queued, not freed in place: GPU-backed values
 *       are released by {@link #drainReleases()} on the thread that owns the GL/Skia context,
 *       after the frame stops using them.</li>
 * </ul>
 *
 * @param <T> decoded value type
 */
public final class UiAssetCache<T> implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(UiAssetCache.class);

    /** Decodes resolved bytes; throwing marks the id failed for its current revision. */
    @FunctionalInterface
    public interface Loader<T> {
        T load(ResolvedAsset asset) throws Exception;
    }

    private record Entry<T>(T value, long revision) {
    }

    private final Function<String, ResolvedAsset> resolver;
    private final Loader<T> loader;
    private final Consumer<T> release;
    private final Map<String, Entry<T>> entries = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private final List<T> pendingRelease = new ArrayList<>();
    private final AtomicLong loads = new AtomicLong();

    /**
     * @param resolver id → resolved asset, {@code null} when unresolved (an
     *                 {@link com.openmason.engine.ui.assets.AssetResolver} lookup)
     * @param release  frees a value (GPU textures, native handles); run by {@link #drainReleases()}
     */
    public UiAssetCache(Function<String, ResolvedAsset> resolver, Loader<T> loader, Consumer<T> release) {
        this.resolver = resolver;
        this.loader = loader;
        this.release = release;
    }

    /** The value for {@code id}, loading synchronously when it is absent or invalidated. */
    public T get(String id) {
        long revision;
        synchronized (this) {
            revision = revision(id);
            Entry<T> e = entries.get(id);
            if (e != null && e.revision() == revision) {
                return e.value();
            }
        }
        return install(id, revision, load(id));
    }

    /**
     * Loads on {@code executor} when needed. Meanwhile {@link #peek} keeps returning the
     * previous value, so a preview never blinks while a texture recomposites.
     */
    public CompletableFuture<T> getAsync(String id, Executor executor) {
        long revision;
        synchronized (this) {
            revision = revision(id);
            Entry<T> e = entries.get(id);
            if (e != null && e.revision() == revision) {
                return CompletableFuture.completedFuture(e.value());
            }
        }
        return CompletableFuture.supplyAsync(() -> install(id, revision, load(id)), executor);
    }

    /** Whatever is cached for {@code id}, current or stale, without loading. */
    public synchronized T peek(String id) {
        Entry<T> e = entries.get(id);
        return e == null ? null : e.value();
    }

    /** Marks {@code id} out of date and forgets a remembered failure. */
    public synchronized void invalidate(String id) {
        revisions.merge(id, 1L, Long::sum);
    }

    public synchronized long revision(String id) {
        return revisions.getOrDefault(id, 0L);
    }

    /** Loads performed so far (diagnostics and tests: steady state must not load). */
    public long loads() {
        return loads.get();
    }

    /** Releases replaced and stale values. Call on the thread that owns their resources. */
    public void drainReleases() {
        List<T> batch;
        synchronized (this) {
            if (pendingRelease.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pendingRelease);
            pendingRelease.clear();
        }
        for (T value : batch) {
            try {
                release.accept(value);
            } catch (RuntimeException e) {
                logger.warn("Releasing a UI asset failed: {}", e.toString());
            }
        }
    }

    /** Queues every value for release and drains the queue on the calling thread. */
    @Override
    public void close() {
        synchronized (this) {
            for (Entry<T> e : entries.values()) {
                if (e.value() != null) {
                    pendingRelease.add(e.value());
                }
            }
            entries.clear();
        }
        drainReleases();
    }

    private T load(String id) {
        loads.incrementAndGet();
        try {
            ResolvedAsset asset = resolver.apply(id);
            return asset == null ? null : loader.load(asset);
        } catch (Exception e) {
            logger.warn("UI asset '{}' failed to load: {}", id, e.toString());
            return null;
        }
    }

    private synchronized T install(String id, long revision, T value) {
        if (revision(id) != revision) {
            if (value != null) {
                pendingRelease.add(value);
            }
            Entry<T> current = entries.get(id);
            return current == null ? null : current.value();
        }
        Entry<T> old = entries.put(id, new Entry<>(value, revision));
        if (old != null && old.value() != null && old.value() != value) {
            pendingRelease.add(old.value());
        }
        return value;
    }
}

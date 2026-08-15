package com.openmason.engine.rendering.viewer.scene;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refcounting and eviction for {@link ModelCache}.
 *
 * <p>Both of the cache's collaborators are seams — {@link ModelSource} for loading and
 * {@link ModelCache.Disposer} for freeing — so this drives the real retain/release/evict
 * logic with no OMO parsing and no GL context.
 */
class ModelCacheTest {

    /** Hands back an empty {@code Loaded} and counts how often it was asked. */
    private static final class StubSource implements ModelSource {
        int loadCount = 0;

        @Override
        public OmoModelLoader.Loaded load(Path path) {
            loadCount++;
            return new OmoModelLoader.Loaded(null, path.toString(), new int[0]);
        }

        @Override
        public OmoModelLoader.Loaded load(byte[] omoBytes, String displayName) {
            loadCount++;
            return new OmoModelLoader.Loaded(null, displayName, new int[0]);
        }
    }

    private static final byte[] ANY_BYTES = new byte[]{1, 2, 3};

    @Test
    @DisplayName("a second acquire of the same key reuses the handle instead of reloading")
    void secondAcquireReuses() throws IOException {
        StubSource source = new StubSource();
        List<ModelHandle> disposed = new ArrayList<>();
        ModelCache cache = new ModelCache(source, disposed::add);

        ModelHandle first = cache.acquireBytes("cube", ANY_BYTES, "cube");
        ModelHandle second = cache.acquireBytes("cube", ANY_BYTES, "cube");

        assertSame(first, second, "one model, one GPU copy");
        assertEquals(1, source.loadCount, "the second acquire must not reload");
        assertEquals(2, first.refCount());
        assertEquals(1, cache.size());
        assertTrue(disposed.isEmpty());
    }

    @Test
    @DisplayName("the handle is disposed exactly once, when the last reference goes")
    void disposedOnceOnLastRelease() throws IOException {
        List<ModelHandle> disposed = new ArrayList<>();
        ModelCache cache = new ModelCache(new StubSource(), disposed::add);

        ModelHandle handle = cache.acquireBytes("cube", ANY_BYTES, "cube");
        cache.acquireBytes("cube", ANY_BYTES, "cube");

        cache.release(handle);
        assertTrue(disposed.isEmpty(), "still referenced by the second acquire");
        assertEquals(1, cache.size());

        cache.release(handle);
        assertEquals(1, disposed.size(), "freed exactly once");
        assertSame(handle, disposed.getFirst());
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("re-acquiring after eviction loads a fresh copy")
    void reacquireAfterEvictionReloads() throws IOException {
        StubSource source = new StubSource();
        ModelCache cache = new ModelCache(source, handle -> { });

        ModelHandle first = cache.acquireBytes("cube", ANY_BYTES, "cube");
        cache.release(first);
        ModelHandle second = cache.acquireBytes("cube", ANY_BYTES, "cube");

        assertEquals(2, source.loadCount);
        assertNotSame(first, second);
        assertEquals(1, second.refCount(), "the fresh handle starts at one reference");
    }

    @Test
    @DisplayName("different keys are independent entries")
    void differentKeysAreIndependent() throws IOException {
        List<ModelHandle> disposed = new ArrayList<>();
        ModelCache cache = new ModelCache(new StubSource(), disposed::add);

        ModelHandle cube = cache.acquireBytes("cube", ANY_BYTES, "cube");
        cache.acquireBytes("sphere", ANY_BYTES, "sphere");

        assertEquals(2, cache.size());

        cache.release(cube);

        assertEquals(1, cache.size());
        assertTrue(cache.contains("sphere"));
        assertEquals(1, disposed.size());
    }

    @Test
    @DisplayName("a changed key loads a fresh copy — this is how an editor save invalidates the scene")
    void changedKeyLoadsFreshCopy() throws IOException {
        // The path-based key embeds the file's last-modified time, so re-saving a model
        // produces a new key rather than the scene silently serving stale geometry.
        StubSource source = new StubSource();
        ModelCache cache = new ModelCache(source, handle -> { });

        ModelHandle before = cache.acquireBytes("model@1000", ANY_BYTES, "model");
        ModelHandle after = cache.acquireBytes("model@2000", ANY_BYTES, "model");

        assertNotSame(before, after);
        assertEquals(2, cache.size());
        assertEquals(2, source.loadCount);
    }

    @Test
    @DisplayName("close disposes every remaining entry")
    void closeDisposesAll() throws IOException {
        List<ModelHandle> disposed = new ArrayList<>();
        ModelCache cache = new ModelCache(new StubSource(), disposed::add);

        cache.acquireBytes("a", ANY_BYTES, "a");
        cache.acquireBytes("b", ANY_BYTES, "b");
        cache.acquireBytes("c", ANY_BYTES, "c");

        cache.close();

        assertEquals(3, disposed.size());
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("releasing null is a no-op")
    void releaseNullIsSafe() {
        ModelCache cache = new ModelCache(new StubSource(), handle -> { });
        cache.release(null);
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("a scene sharing one model across instances keeps a single cache entry")
    void sceneSharingKeepsOneEntry() throws IOException {
        // The end-to-end point of the cache: twenty placements of one model cost one load.
        ModelCache cache = new ModelCache(new StubSource(), handle -> { });
        ModelScene scene = new ModelScene();

        ModelHandle cube = cache.acquireBytes("cube", ANY_BYTES, "cube");
        for (int i = 0; i < 20; i++) {
            scene.add(cube, "instance" + i);
        }

        assertEquals(1, cache.size());
        assertEquals(20, scene.size());
        assertEquals(1, cube.refCount(),
                "one acquire covers every placement — instances hold no cache reference");
    }
}

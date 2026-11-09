package com.stonebreak.fx.bindings;

import com.openmason.engine.format.fx.FxData;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.fx.FxFixtures;
import com.stonebreak.fx.FxRuntime;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import static com.stonebreak.fx.FxFixtures.data;
import static com.stonebreak.fx.FxFixtures.def;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ambient FX follow the light selection (Stonebreak#437): start, move, fade out, restart on a changed block. */
class AmbientFxTest {

    private final FakeSources sources = new FakeSources();
    private final Function<BlockType, FxData> bindings = block -> block == BlockType.TORCH_PLACED
            ? data(def("ambient", "test:glow"), def("break", "test:probe"))
            : block == BlockType.FURNACE ? data(def("ambient", "test:glow", 0, 0.5f, 0, java.util.Map.of(), false))
            : new FxData(List.of());
    private final ToIntFunction<BlockType> tints = b -> 0xFFFFFFFF;
    private FxRuntime runtime;
    private AmbientFx ambient;

    @BeforeEach
    void setUp() {
        runtime = FxFixtures.runtime();
        ambient = new AmbientFx();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    private void frame() {
        ambient.sync(runtime, sources, bindings, tints, 0, 64, 0);
        runtime.update(1f / 60f, 0, 64, 0, 0, 0, 0);
    }

    @Test
    void aSelectedTorchRunsItsAmbientFxUntilItLeavesTheSelection() {
        sources.add(BlockType.TORCH_PLACED, AmbientSources.blockKey(3, 64, 0), 3.5f, 64.7f, 0.5f);
        for (int i = 0; i < 30; i++) frame();
        assertEquals(1, ambient.activeSources());
        assertEquals(1, runtime.instanceCount(), "only the ambient binding started, not break");
        assertTrue(runtime.liveParticles() > 0);

        sources.clear();
        for (int i = 0; i < 90; i++) frame();
        assertEquals(0, ambient.activeSources());
        assertEquals(0, runtime.instanceCount(), "faded, finished and returned to the pool");
    }

    @Test
    void sourcesWithoutAmbientBindingsOrOutOfRangeStartNothing() {
        sources.add(BlockType.STONE, AmbientSources.blockKey(1, 64, 1), 1, 64, 1);
        sources.add(BlockType.TORCH_PLACED, AmbientSources.blockKey(100, 64, 0), 100, 64, 0);
        frame();
        assertEquals(0, ambient.activeSources());
        assertEquals(0, runtime.instanceCount());
    }

    @Test
    void aCarriedTorchsFxFollowsItsHolder() {
        long key = AmbientSources.holderKey(7);
        sources.add(BlockType.TORCH_PLACED, key, 0, 65, 0);
        frame();
        sources.clear();
        sources.add(BlockType.TORCH_PLACED, key, 4, 65, 2);
        frame();
        assertEquals(1, ambient.activeSources());
        assertEquals(1, runtime.instanceCount(), "moved, not restarted");
    }

    @Test
    void aDifferentBlockAtTheSamePlaceRestartsWithItsOwnFx() {
        long key = AmbientSources.blockKey(2, 64, 2);
        sources.add(BlockType.TORCH_PLACED, key, 2.5f, 64.5f, 2.5f);
        frame();
        sources.clear();
        sources.add(BlockType.FURNACE, key, 2.5f, 64.5f, 3.15f);
        frame();
        assertEquals(1, ambient.activeSources());
        assertEquals(2, runtime.instanceCount(), "the torch's fades out while the furnace's starts");
    }

    @Test
    void clearStopsEverythingAtOnce() {
        sources.add(BlockType.TORCH_PLACED, AmbientSources.blockKey(0, 64, 0), 0, 64, 0);
        frame();
        ambient.clear();
        assertEquals(0, ambient.activeSources());
        runtime.update(1f / 60f, 0, 64, 0, 0, 0, 0);
        for (int i = 0; i < 60; i++) runtime.update(1f / 60f, 0, 64, 0, 0, 0, 0);
        assertEquals(0, runtime.instanceCount());
    }

    @Test
    void blockAndHolderKeysNeverCollide() {
        assertTrue(AmbientSources.blockKey(-1, 0, -1) >= 0);
        assertTrue(AmbientSources.holderKey(0) < 0);
        assertTrue(AmbientSources.blockKey(1, 2, 3) != AmbientSources.blockKey(3, 2, 1));
    }

    /** A hand-fed selection. */
    static final class FakeSources implements AmbientSources {
        final List<BlockType> blocks = new ArrayList<>();
        final List<Long> keys = new ArrayList<>();
        final List<Vector3f> positions = new ArrayList<>();

        void add(BlockType block, long key, float x, float y, float z) {
            blocks.add(block);
            keys.add(key);
            positions.add(new Vector3f(x, y, z));
        }

        void clear() {
            blocks.clear();
            keys.clear();
            positions.clear();
        }

        @Override public int count() { return blocks.size(); }
        @Override public BlockType block(int i) { return blocks.get(i); }
        @Override public long key(int i) { return keys.get(i); }
        @Override public Vector3f position(int i, Vector3f out) { return out.set(positions.get(i)); }
    }
}

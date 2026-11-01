package com.openmason.engine.voxel.cco.coordinates;

import com.openmason.engine.voxel.VoxelWorldConfig;
import com.openmason.engine.voxel.VoxelWorldLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #385: world dimensions are configuration, checked once against what the storage and mesh
 * encodings can represent. An over-limit world fails when it is installed, naming every limit it
 * breaks, instead of corrupting or dropping geometry later.
 */
class VoxelWorldLimitsTest {

    private static final VoxelWorldConfig DEFAULT = new VoxelWorldConfig(16, 256, 64);

    @AfterEach
    void restore() {
        CcoBounds.configure(DEFAULT);
    }

    @Test
    void stonebreakAndTallerColumnsAreSupported() {
        assertEquals(List.of(), VoxelWorldLimits.problems(DEFAULT));
        assertEquals(List.of(), VoxelWorldLimits.problems(new VoxelWorldConfig(16, 1024, 320)), "Heracles");
        assertEquals(List.of(), VoxelWorldLimits.problems(new VoxelWorldConfig(32, 128, 40)), "widest chunk");
        assertEquals(List.of(), VoxelWorldLimits.problems(new VoxelWorldConfig(8, 64, 20)));
        assertEquals(1024, VoxelWorldLimits.MAX_WORLD_HEIGHT);
        assertEquals(32, VoxelWorldLimits.MAX_CHUNK_SIZE);
    }

    @Test
    void eachBrokenLimitIsNamed() {
        assertTrue(only(new VoxelWorldConfig(16, 1280, 64)).contains("10 bits"), "QUAD16 absolute Y");
        assertTrue(only(new VoxelWorldConfig(64, 256, 64)).contains("region-local x/z"), "QUAD16 region x/z");
        assertTrue(only(new VoxelWorldConfig(16, 250, 64)).contains("section height"), "whole sections");
        assertTrue(only(new VoxelWorldConfig(16, 256, 300)).contains("seaLevel"), "sea level in the column");
        assertEquals(3, VoxelWorldLimits.problems(new VoxelWorldConfig(64, 1500, 64)).size(),
            "every problem at once: too tall, not whole sections, chunk too wide");
    }

    @Test
    void anOverLimitWorldFailsLoudlyWhenInstalled() {
        CcoBounds.configure(DEFAULT);
        VoxelWorldConfig tooTall = new VoxelWorldConfig(16, 4096, 64);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CcoBounds.configure(tooTall));
        assertTrue(e.getMessage().contains("4096"), e.getMessage());
        assertSame(DEFAULT, CcoBounds.getConfig(), "a refused config is never installed");
    }

    private static String only(VoxelWorldConfig config) {
        List<String> problems = VoxelWorldLimits.problems(config);
        assertEquals(1, problems.size(), problems.toString());
        return problems.get(0);
    }
}

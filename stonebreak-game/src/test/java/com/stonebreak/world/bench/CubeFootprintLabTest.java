package com.stonebreak.world.bench;

import com.stonebreak.world.generation.NativeTerrain;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in driver of the {@link CubeFootprintLab} ({@code -Dstonebreak.bench=true}; {@code Testing/chunk-lab.sh}
 * sets it). Needs the game's worldgen natives.
 */
class CubeFootprintLabTest {

    @Test
    void measuresOneSquareOfCubeColumns() throws Exception {
        assumeTrue(Boolean.getBoolean("stonebreak.bench"), "opt-in: -Dstonebreak.bench=true");
        assumeTrue(NativeTerrain.isAvailable(), "worldgen natives missing");
        Path ledger = CubeFootprintLab.fromSystemProperties().run();
        assertTrue(Files.size(ledger) > 0);
    }
}

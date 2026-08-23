package com.stonebreak.world.bench;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JUnit entry point for the chunk footprint lab. Manual — gated on
 * {@code -Dstonebreak.bench=true}; the normal suite records one skip.
 * Prefer {@code Testing/chunk-lab.sh <tier> <label>} which sets the
 * properties and diffs the ledger against the tier baseline:
 * <pre>
 * mvn -q test -pl stonebreak-game -Dtest=ChunkFootprintLabTest -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dstonebreak.bench=true -Dlab.tier=1 -Dlab.label=baseline [-Dlab.gl=true]
 * </pre>
 */
class ChunkFootprintLabTest {

    @Test
    void lab() throws Exception {
        assumeTrue(Boolean.getBoolean("stonebreak.bench"), "manual benchmark (-Dstonebreak.bench=true)");
        LabConfig config = LabConfig.fromSystemProperties();
        Map<String, Object> report = new ChunkFootprintLab(config).run();
        System.out.println("[chunk-lab] tier " + config.tier() + " '" + config.label() + "': "
            + report.get("generation") + "\n  ram=" + report.get("chunkRam")
            + "\n  mesh=" + ((Map<?, ?>) report.get("mesh")).get("bytesPerChunk") + " B/chunk"
            + "\n  vram=" + ((Map<?, ?>) report.get("vram")).get("totals"));
    }
}

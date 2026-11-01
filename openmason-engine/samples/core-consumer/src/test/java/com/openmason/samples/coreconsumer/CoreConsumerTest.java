package com.openmason.samples.coreconsumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #387 acceptance: a consumer of {@code engine-core} alone builds and runs. */
class CoreConsumerTest {

    @Test
    void runsOnTheEngineCoreAlone() {
        CoreConsumer.Report report = CoreConsumer.run();
        // Around the wall: 7 up, 7 across, 7 down = 21 steps, 22 nodes.
        assertEquals(22, report.pathLength());
        assertEquals(21f, report.pathCost());
        assertTrue(report.payloadRoundTripped());
        CoreConsumer.main(new String[0]);
    }

    @Test
    void neitherTheUiNorTheVoxelModuleNorSkijaIsReachable() {
        // Class lookups, not ModuleLayer queries: they hold on the module path and the class path alike.
        for (String cls : new String[]{"com.openmason.engine.ui.runtime.UiDocumentInstance",
                "com.openmason.engine.voxel.cco.data.CcoBlockStorage", "io.github.humbleui.skija.Canvas"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(cls),
                cls + " leaked into a core-only consumer");
        }
    }
}

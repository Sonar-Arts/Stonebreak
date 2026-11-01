package com.openmason.engine.ui.fidelity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A retired renderer's captures replay exactly as they were recorded (#302). */
class FrozenCapturesTest {

    @TempDir
    Path dir;

    private FrozenCaptures store() {
        return new FrozenCaptures(new GoldenStore(dir.resolve("death"), dir.resolve("out"), false, "x"), "death");
    }

    private static MigrationGate.Capture capture(FidelityCase c) {
        int[] argb = new int[16];
        Arrays.fill(argb, 0xFF000000 | c.viewport().width());
        // Floats that do not survive a decimal round trip through double without care.
        return new MigrationGate.Capture(new FidelityImage(4, 4, argb),
            Map.of("respawn", new float[]{0.1f, 1f / 3f, 543.21875f, Float.MIN_VALUE}),
            Map.of("respawn", new float[]{960.5f, 540.25f, 0, 0}),
            Map.of("respawn", "stonebreak:screen.death.respawn"));
    }

    @Test
    void replayIsBitIdenticalToTheRecordedCapture() {
        List<FidelityCase> cases = FidelityCase.matrix("death", List.of("dead"), FidelityCase.STANDARD);
        store().record(cases, FrozenCapturesTest::capture);

        MigrationGate.Renderer replay = store().replay();
        for (FidelityCase c : cases) {
            MigrationGate.Capture want = capture(c);
            MigrationGate.Capture got = replay.render(c);
            assertArrayEquals(want.image().argb(), got.image().argb(), c.id());
            assertArrayEquals(want.rects().get("respawn"), got.rects().get("respawn"), 0f, c.id());
            assertArrayEquals(want.hits().get("respawn"), got.hits().get("respawn"), 0f, c.id());
            assertEquals(want.actions(), got.actions(), c.id());
        }
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("death", cases, replay, FrozenCapturesTest::capture);
        assertTrue(r.passed(), r.table());
    }

    @Test
    void aCaseThatWasNeverRecordedFailsLoudly() {
        store().record(List.of(new FidelityCase("death", "dead", FidelityCase.STANDARD.get(0))), FrozenCapturesTest::capture);
        FidelityCase other = new FidelityCase("death", "dead", FidelityCase.STANDARD.get(1));
        assertThrows(AssertionError.class, () -> store().replay().render(other));
    }
}

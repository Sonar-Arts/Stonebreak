package com.stonebreak.rendering.gameWorld.water;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Gerstner lattice stays anchored to the world when the render origin steps: the host's per-wave phase
 * of origin O plus the render-space position (W - O) gives the same wave argument for every origin, at any
 * int64 distance from world zero.
 */
class WaterWavePhaseTest {

    private static double argument(long ox, long oz, double wx, double wz, int wave) {
        float[] phase = new float[WaterRenderer.WAVE_K.length];
        WaterRenderer.wavePhases(ox, oz, phase);
        double px = wx - ox, pz = wz - oz; // render space (what the vertex shader sees)
        double a = (px * WaterRenderer.WAVE_DIR_X[wave] + pz * WaterRenderer.WAVE_DIR_Z[wave]) * WaterRenderer.WAVE_K[wave]
                + phase[wave];
        return WaterRenderer.mod2Pi(a);
    }

    private static void assertSameAngle(double expected, double actual, double eps) {
        double d = Math.abs(expected - actual);
        d = Math.min(d, 2 * Math.PI - d);
        assertEquals(0.0, d, eps);
    }

    @Test
    void originStepsDoNotMoveTheWaves() {
        long[] bases = {0, 4096, -70_000, 1L << 30, -(1L << 40)};
        for (long base : bases) {
            for (int wave = 0; wave < WaterRenderer.WAVE_K.length; wave++) {
                double wx = base + 13.25, wz = base - 7.5;
                double before = argument(base, base, wx, wz, wave);
                double after = argument(base + 64, base - 64, wx, wz, wave);
                assertSameAngle(before, after, 2e-3);
            }
        }
    }

    @Test
    void phasesNearZeroMatchTheAbsoluteLattice() {
        // Close to world zero the shader's old absolute form and the phase form agree.
        for (int wave = 0; wave < WaterRenderer.WAVE_K.length; wave++) {
            double wx = 100.5, wz = -42.25;
            double absolute = WaterRenderer.mod2Pi((wx * WaterRenderer.WAVE_DIR_X[wave] + wz * WaterRenderer.WAVE_DIR_Z[wave])
                    * WaterRenderer.WAVE_K[wave]);
            assertSameAngle(absolute, argument(64, -64, wx, wz, wave), 1e-4);
        }
    }
}

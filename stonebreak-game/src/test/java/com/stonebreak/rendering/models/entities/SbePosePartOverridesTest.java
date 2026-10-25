package com.stonebreak.rendering.models.entities;

import com.openmason.engine.entity.sbe.SbeEntityAsset;
import com.openmason.engine.entity.sbe.SbeModelGeometry;
import com.openmason.engine.entity.sbe.SbePart;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Code-driven part rotations ({@link SbePoseSolver.PartOverrides}): how the dinghy's oars row. */
class SbePosePartOverridesTest {

    private static final float EPS = 1e-4f;

    /** An oarlock at (1, 0, 0) with an oar pivoting on it, and a blade riding the oar. */
    private static final SbePart LOCK = new SbePart("lock", "oarlock", null, new Vector3f(),
            new Vector3f(1, 0, 0), new Vector3f(), new Vector3f(1, 1, 1), List.of());
    private static final SbePart OAR = new SbePart("oar", "oar_r", "lock", new Vector3f(),
            new Vector3f(), new Vector3f(), new Vector3f(1, 1, 1), List.of());
    private static final SbePart BLADE = new SbePart("blade", "blade_r", "oar", new Vector3f(),
            new Vector3f(1, 0, 0), new Vector3f(), new Vector3f(1, 1, 1), List.of());

    private static Map<String, Matrix4f> solve(SbePoseSolver.PartOverrides overrides) {
        SbeModelGeometry g = new SbeModelGeometry(new float[0], new float[0], new int[0],
                List.of(LOCK, OAR, BLADE), Map.of(), 0f, List.of());
        SbeEntityAsset a = new SbeEntityAsset("test:boat", Map.of(SbeEntityAsset.DEFAULT_VARIANT, g), Map.of());
        Map<String, Matrix4f> out = new HashMap<>();
        SbePoseSolver.forEachPartMatrix(g, a, null, new Matrix4f(), 0f, 0f, overrides,
                (m, p) -> out.put(p.name(), new Matrix4f(m)));
        return out;
    }

    @Test
    void noOverridesIsTheRestPose() {
        solve(null).values().forEach(m -> assertTrue(m.equals(new Matrix4f(), EPS)));
    }

    @Test
    void anOverriddenPartTurnsAboutItsPivotAndCarriesItsChildren() {
        Map<String, Matrix4f> m = solve(name -> "oar_r".equals(name) ? new Vector3f(0, 90, 0) : null);

        assertTrue(m.get("oarlock").equals(new Matrix4f(), EPS), "parent untouched");
        // The oar pivots at the oarlock (model (1,0,0)): that point stays put...
        Vector3f pivot = m.get("oar_r").transformPosition(new Vector3f(1, 0, 0));
        assertEquals(1f, pivot.x, EPS);
        assertEquals(0f, pivot.z, EPS);
        // ...while the blade one unit outboard swings 90° about it (+Y turns +X toward -Z).
        Vector3f blade = m.get("blade_r").transformPosition(new Vector3f(2, 0, 0));
        assertEquals(1f, blade.x, EPS);
        assertEquals(-1f, blade.z, EPS);
    }
}

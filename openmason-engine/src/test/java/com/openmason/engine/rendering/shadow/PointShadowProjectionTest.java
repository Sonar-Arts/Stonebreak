package com.openmason.engine.rendering.shadow;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PointShadowProjectionTest {
    @Test
    void allSixViewsMatchReceiverFaceCoordinatesAndPerspectiveDepth() {
        Vector3f light = new Vector3f(-17, 23, 9);
        // Off-center samples catch face flips, particularly the unusual +/-Y up vectors.
        Vector3f[] directions = {
                new Vector3f(4, .7f, -1.2f), new Vector3f(-4, .7f, -1.2f),
                new Vector3f(.7f, 4, -1.2f), new Vector3f(.7f, -4, -1.2f),
                new Vector3f(.7f, -1.2f, 4), new Vector3f(.7f, -1.2f, -4)};
        float far = 9.5f;
        for (int face = 0; face < 6; face++) {
            Vector3f d = directions[face];
            Matrix4f vp = PointShadowProjection.projection(far, new Matrix4f())
                    .mul(PointShadowProjection.view(light, face, new Matrix4f()));
            Vector3f projected = vp.transformProject(new Vector3f(light).add(d));
            float u = switch (face) { case 0 -> -d.z; case 1 -> d.z; case 5 -> -d.x; default -> d.x; };
            float v = switch (face) { case 2 -> d.z; case 3 -> -d.z; default -> -d.y; };
            assertEquals(u / 4f, projected.x, 1e-5, "horizontal face " + face);
            assertEquals(v / 4f, projected.y, 1e-5, "vertical face " + face);
            float depth = far / (far - PointShadowProjection.NEAR)
                    - far * PointShadowProjection.NEAR / ((far - PointShadowProjection.NEAR) * 4f);
            assertEquals(depth, projected.z * .5f + .5f, 1e-5, "depth face " + face);
        }
    }
}

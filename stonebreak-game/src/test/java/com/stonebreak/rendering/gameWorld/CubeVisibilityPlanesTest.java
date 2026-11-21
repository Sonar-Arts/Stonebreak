package com.stonebreak.rendering.gameWorld;

import com.openmason.engine.rendering.RenderOrigin;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * cv_visible_cubes takes its frustum in camera-cube-relative block coordinates: the planes rebased from
 * render space must classify every point exactly as the render-space planes do, far from world zero too.
 */
class CubeVisibilityPlanesTest {

    @AfterEach
    void resetOrigin() {
        RenderOrigin.reset();
    }

    @Test
    void cameraCubePlanesMatchRenderSpacePlanes() throws Exception {
        double camX = 1_000_000.3, camY = 71.7, camZ = -2_000_000.6;
        RenderOrigin.update(camX, camY, camZ);
        Matrix4f viewProj = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.1f, 2000f)
                .lookAt((float) (camX - RenderOrigin.blockX()), (float) (camY - RenderOrigin.blockY()),
                        (float) (camZ - RenderOrigin.blockZ()),
                        (float) (camX - RenderOrigin.blockX()) + 1f, (float) (camY - RenderOrigin.blockY()) - 0.2f,
                        (float) (camZ - RenderOrigin.blockZ()) + 0.5f, 0f, 1f, 0f);
        long ccx = Math.floorDiv((long) Math.floor(camX), 32L);
        long ccy = Math.floorDiv((long) Math.floor(camY), 32L);
        long ccz = Math.floorDiv((long) Math.floor(camZ), 32L);
        CubeVisibility visibility = new CubeVisibility();
        visibility.cameraCubePlanes(viewProj, ccx, ccy, ccz);
        Field f = CubeVisibility.class.getDeclaredField("planes");
        f.setAccessible(true);
        float[] cubePlanes = (float[]) f.get(visibility);

        Vector4f plane = new Vector4f();
        for (int i = 0; i < 200; i++) {
            // World points around the camera, as cube-relative q and as render-space r.
            long wx = (long) camX + (i * 37 % 300) - 150, wy = (long) camY + (i * 13 % 120) - 60;
            long wz = (long) camZ + (i * 53 % 300) - 150;
            double qx = wx - (ccx << 5), qy = wy - (ccy << 5), qz = wz - (ccz << 5);
            double rx = wx - RenderOrigin.blockX(), ry = wy - RenderOrigin.blockY(), rz = wz - RenderOrigin.blockZ();
            for (int p = 0; p < 6; p++) {
                viewProj.frustumPlane(p, plane);
                double render = plane.x * rx + plane.y * ry + plane.z * rz + plane.w;
                double cube = cubePlanes[p * 4] * qx + cubePlanes[p * 4 + 1] * qy + cubePlanes[p * 4 + 2] * qz
                        + cubePlanes[p * 4 + 3];
                assertEquals(render, cube, 1e-2 * Math.max(1.0, Math.abs(render)), "plane " + p + " point " + i);
            }
        }
    }
}

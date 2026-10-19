package com.stonebreak.rendering.gameWorld;

import com.openmason.engine.rendering.RenderOrigin;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkFrustumCuller#isBoxVisible} against a known camera: the FastLOD
 * pass relies on it to skip the ~2/3 of ring nodes outside the view. Uses the
 * game's real projection parameters (70° vertical FOV, near 0.1) with the
 * camera at world height 65 looking straight down +Z.
 */
class FastLodFrustumCullTest {

    private final ChunkFrustumCuller culler = new ChunkFrustumCuller();

    @BeforeEach
    void setUp() {
        // The culler rebases the world boxes it is handed, so every case below
        // depends on where the render origin is.
        RenderOrigin.reset();
        Matrix4f projection = new Matrix4f()
                .setPerspective((float) Math.toRadians(70.0), 16f / 9f, 0.1f, 1500f);
        Matrix4f view = new Matrix4f()
                .setLookAt(0f, 65f, 0f,   // eye
                           0f, 65f, 100f, // target: straight down +Z
                           0f, 1f, 0f);
        culler.update(projection, view);
    }

    @Test
    void boxStraightAheadIsVisible() {
        assertTrue(culler.isBoxVisible(-8f, 60f, 192f, 8f, 80f, 208f));
    }

    @Test
    void boxBehindCameraIsCulled() {
        assertFalse(culler.isBoxVisible(-8f, 60f, -208f, 8f, 80f, -192f));
    }

    @Test
    void boxNinetyDegreesToTheSideIsCulled() {
        // Horizontal FOV at 16:9 / 70° vertical is ~100°, so a box due +X
        // (90° off the view axis) sits well outside the frustum.
        assertFalse(culler.isBoxVisible(492f, 60f, -8f, 508f, 80f, 8f));
    }

    @Test
    void hugeBoxStraddlingFrustumPlanesIsVisible() {
        // testAab is conservative: a box containing the whole frustum passes.
        assertTrue(culler.isBoxVisible(-5000f, 0f, -5000f, 5000f, 256f, 5000f));
    }

    @Test
    void boxBeyondFarPlaneIsCulled() {
        assertFalse(culler.isBoxVisible(-8f, 60f, 1992f, 8f, 80f, 2008f));
    }

    @Test
    void tightYBoundsCullBelowTheView() {
        // Camera at y=65 looking horizontally (vertical half-FOV 35°): a low,
        // thin slab close ahead sits ~72° below the view axis — outside the
        // bottom frustum plane — and is culled with tight Y bounds, while the
        // same footprint with full world-height bounds passes. This is why
        // LOD nodes carry real mesh bounds instead of 0..256.
        assertFalse(culler.isBoxVisible(-8f, 0f, 20f, 8f, 2f, 36f),
                "thin slab below the view cone should be culled");
        assertTrue(culler.isBoxVisible(-8f, 0f, 20f, 8f, 256f, 36f),
                "full-height box at the same spot is (conservatively) visible");
    }

    @AfterEach
    void resetOrigin() {
        RenderOrigin.reset();
    }

    /**
     * Culling is expressed in render space (the view matrix is) but fed world
     * boxes, so the same scene must cull identically wherever the origin sits.
     * A mismatch here would be invisible at spawn and then cull the wrong
     * chunks — or draw them in the wrong place — far out.
     */
    @Test
    void cullingIsInvariantUnderAnOriginStep() {
        float camX = 100_000.3f;
        float camZ = 100_000.7f;
        RenderOrigin.update(camX, camZ);

        Matrix4f projection = new Matrix4f()
                .setPerspective((float) Math.toRadians(70.0), 16f / 9f, 0.1f, 1500f);
        // Built exactly as Camera.getViewMatrix() does: at the render-space eye.
        Vector3f eye = RenderOrigin.toRender(new Vector3f(camX, 65f, camZ), new Vector3f());
        Matrix4f view = new Matrix4f()
                .setLookAt(eye.x, eye.y, eye.z,
                           eye.x, eye.y, eye.z + 100f,
                           0f, 1f, 0f);
        ChunkFrustumCuller farCuller = new ChunkFrustumCuller();
        farCuller.update(projection, view);

        // The same three boxes as the spawn cases above, carried out to the far
        // camera in WORLD coordinates — the verdicts must not change.
        assertTrue(farCuller.isBoxVisible(camX - 8f, 60f, camZ + 192f,
                                          camX + 8f, 80f, camZ + 208f),
                "box straight ahead");
        assertFalse(farCuller.isBoxVisible(camX - 8f, 60f, camZ - 208f,
                                           camX + 8f, 80f, camZ - 192f),
                "box behind the camera");
        assertFalse(farCuller.isBoxVisible(camX + 492f, 60f, camZ - 8f,
                                           camX + 508f, 80f, camZ + 8f),
                "box ninety degrees to the side");

        // And the world-consuming form the GPU cull pass uses must agree with
        // the render-space one it is derived from.
        Vector3f worldPoint = new Vector3f(camX, 65f, camZ + 200f);
        Vector3f viaWorld = farCuller.projectionViewWorld()
                .transformPosition(new Vector3f(worldPoint));
        Vector3f viaRender = farCuller.projectionView()
                .transformPosition(RenderOrigin.toRender(worldPoint, new Vector3f()));
        assertTrue(viaWorld.distance(viaRender) < 1e-2f,
                "projectionViewWorld disagreed with projectionView: " + viaWorld + " vs " + viaRender);
    }
}

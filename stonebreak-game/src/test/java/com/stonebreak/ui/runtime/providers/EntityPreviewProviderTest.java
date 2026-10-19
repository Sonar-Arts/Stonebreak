package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.rendering.RenderOrigin;
import com.stonebreak.rendering.models.entities.SbePoseSolver;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The character-creation/character-sheet/glossary preview bug (#world-session): after a world
 * session the {@link RenderOrigin} sits far from zero, the SBE draw path rebases the model into
 * render space, and a preview camera built in model coordinates framed empty air — the character
 * did not load on the second visit. The orbit camera must consume render-space geometry at any
 * origin.
 */
class EntityPreviewProviderTest {

    private static final float[] BOUNDS = {-0.5f, 0f, -0.5f, 0.5f, 2f, 0.5f};

    @AfterEach
    void resetOrigin() {
        RenderOrigin.reset();
    }

    @Test
    void orbitFramesTheModelAtWorldZeroBeforeAnyWorldSession() {
        RenderOrigin.reset();
        Matrix4f view = EntityPreviewProvider.orbit(params(0.3f, 0.2f), false, BOUNDS, 0f, 1f).view();
        Vector3f seen = view.transformPosition(new Vector3f(0f, 1f, 0f));
        Matrix4f reference = EntityPreviewProvider.frame(BOUNDS, 0.3f, 0.2f, 1.25f, 1f).view();
        Vector3f expected = reference.transformPosition(new Vector3f(0f, 1f, 0f));
        assertEquals(expected.x, seen.x, 1e-4f);
        assertEquals(expected.y, seen.y, 1e-4f);
        assertEquals(expected.z, seen.z, 1e-4f);
    }

    @Test
    void orbitFramesTheRenderSpaceModelAfterAWorldSessionSteppedTheOrigin() {
        // Reference: the same pose with the origin at zero (first boot, pre-world).
        RenderOrigin.reset();
        Matrix4f referenceView = EntityPreviewProvider.frame(BOUNDS, 0.3f, 0.2f, 1.25f, 1f).view();
        Vector3f expected = referenceView.transformPosition(new Vector3f(0f, 1f, 0f));

        // A world session snapped the origin to a far grid cell (camera at ±100k).
        RenderOrigin.update(100_000.7f, -100_000.7f);
        try {
            Matrix4f view = EntityPreviewProvider.orbit(params(0.3f, 0.2f), false, BOUNDS, 0f, 1f).view();
            // The model matrix exactly as SbePoseSolver.baseMatrix builds it for position zero.
            Matrix4f model = SbePoseSolver.baseMatrix(new Vector3f(), 0f, new Vector3f(1f, 1f, 1f));
            Vector3f seen = view.transformPosition(model.transformPosition(new Vector3f(0f, 1f, 0f)));
            assertEquals(expected.x, seen.x, 1e-2f, "model centre lands where the origin-free preview framed it");
            assertEquals(expected.y, seen.y, 1e-2f);
            assertEquals(expected.z, seen.z, 1e-2f);
        } finally {
            RenderOrigin.reset();
        }
    }

    /** Params pinning the azimuth/elevation the raw {@code frame} reference uses (degrees on the wire). */
    private static UiValue.Obj params(float azRadians, float elRadians) {
        return new UiValue.Obj(Map.of(
            "yaw", UiValue.of(Math.toDegrees(azRadians)),
            "elevation", UiValue.of(Math.toDegrees(elRadians)),
            "margin", UiValue.of(1.25)));
    }
}

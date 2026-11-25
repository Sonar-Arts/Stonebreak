package com.stonebreak.rendering.models.entities;

import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityPart;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.model.draw.ClipLayers;
import com.openmason.engine.rendering.model.draw.ModelPoseSolver;
import com.openmason.engine.rig.JointPalette;
import com.stonebreak.mobs.sbe.AnimState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Stonebreak's view of the engine's native rig ({@code cenda/rig.h}, engine {@link ModelPoseSolver}):
 * resolves an {@link AnimState} (named states, by {@link EntityAsset#clipFor}) to engine
 * {@link ClipLayers} and poses the model's joints natively. All pose math — clip sampling, overlay
 * layering, the part/bone hierarchy, head tracking, code-driven turns — is the engine's; this class
 * only marshals.
 *
 * <p>Joint {@code i} of a model is its part {@code i} (bones follow the parts). A part's matrix is its
 * joint's SKIN matrix, which moves the part's rest-pose model-space vertices to the posed render-space
 * position:
 * <pre>
 *   base        = T(position) · Ry(yaw) · S(scale)
 *   partMatrix  = base · W_pose(part) · W_rest(part)⁻¹
 *   socketWorld = partMatrix(host joint) · T(localPos) · R_xyz(localRotDeg) · S(localScale)
 * </pre>
 * Renderers pose once with {@link #pose} and skin on the GPU from the {@link JointPalette}; the
 * per-part callbacks ({@link #forEachPartMatrix}) and sockets read the same palette.
 */
public final class SbePoseSolver {

    /** Receives the computed world matrix for one part (the matrix is reused after the call). */
    @FunctionalInterface
    public interface PartConsumer {
        void accept(Matrix4f partMatrix, EntityPart part);
    }

    /**
     * Code-driven rotation for named parts (or bones), layered on top of whatever the clip (or rest
     * pose) gives them — how gameplay drives a part no clip can know about (a dinghy's oars follow
     * the rower's input). Rotates about the part's own pivot, so children follow.
     */
    @FunctionalInterface
    public interface PartOverrides {
        /** Euler degrees added to {@code partName}'s rotation, or null to leave it alone. */
        Vector3f extraRotationDeg(String partName);
    }

    /** Per-thread scratch palette, borrowed and returned so nested calls never share one. */
    private static final ThreadLocal<JointPalette> SCRATCH = new ThreadLocal<>();

    private SbePoseSolver() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The base entity transform: {@code T(position) · Ry(yawDegrees) · S(scale)}.
     *
     * <p>{@code position} is a world position, but the matrix is built in
     * <b>render space</b> ({@link RenderOrigin}) — the single seam where SBE
     * models cross over. Mob geometry has no normal attribute: the fragment
     * stage differentiates the interpolated position to recover a flat normal,
     * and a derivative amplifies coordinate error by the reciprocal of the
     * per-pixel delta, so absolute world coordinates degraded into per-pixel
     * lighting noise from a few thousand blocks out. Rebasing here is what
     * bounds that error.
     *
     * <p>Every matrix downstream of this one ({@link #forEachPartMatrix},
     * {@link #socketWorldMatrix}) is therefore in render space too; anything
     * that needs the world position back wants {@link RenderOrigin#toWorld}.
     */
    public static Matrix4f baseMatrix(Vector3f position, float yawDegrees, Vector3f scale) {
        return ModelPoseSolver.baseMatrix(position, yawDegrees, scale);
    }

    /**
     * {@link #baseMatrix(Vector3f, float, Vector3f)} for a double WORLD position: the translation is made
     * camera-relative in double ({@code RenderOrigin.modelAt}), so models stay precise at any distance.
     */
    public static Matrix4f baseMatrix(org.joml.Vector3dc position, float yawDegrees, Vector3f scale) {
        return RenderOrigin.modelAt(position.x(), position.y(), position.z())
                .rotateY((float) Math.toRadians(yawDegrees))
                .scale(scale);
    }

    /**
     * The engine clip layers of {@code anim} on {@code asset}: the base state's clip (null = rest
     * pose) and every overlay whose state names a clip, with raw elapsed times and caller weights
     * (the engine wraps each time per clip and folds in the clip's own fade envelope).
     */
    public static ClipLayers clipLayers(EntityAsset asset, AnimState anim) {
        if (asset == null || anim == null) return ClipLayers.REST;
        ParsedAnimClip baseClip = asset.clipFor(anim.baseState());
        List<ClipLayers.Overlay> overlays = List.of();
        if (anim.hasOverlays()) {
            overlays = new ArrayList<>(anim.overlays().size());
            for (AnimState.Overlay overlay : anim.overlays()) {
                ParsedAnimClip clip = asset.clipFor(overlay.stateName());
                if (clip == null || overlay.weight() <= 0f) continue;
                overlays.add(new ClipLayers.Overlay(clip, overlay.time(), overlay.weight()));
            }
        }
        if (baseClip == null && overlays.isEmpty()) return ClipLayers.REST;
        return new ClipLayers(baseClip, anim.baseTime(), overlays);
    }

    /**
     * Poses every joint of {@code geometry}'s rig into {@code out} (skin + world matrices) — what a
     * renderer uploads for GPU skinning. {@code headYawDeg}/{@code headPitchDeg} turn the head joint
     * and its descendants about its pivot; {@code overrides} may be null.
     */
    public static JointPalette pose(EntityGeometry geometry, EntityAsset asset, AnimState anim, Matrix4f base,
                                    float headYawDeg, float headPitchDeg, PartOverrides overrides,
                                    JointPalette out) {
        return ModelPoseSolver.pose(geometry, clipLayers(asset, anim), base, headYawDeg, headPitchDeg,
                engineOverrides(overrides), out);
    }

    /**
     * Walks the geometry's parts in order, handing each part's world (skin) matrix to
     * {@code consumer}. {@code headYawDeg}/{@code headPitchDeg} rotate the head and its descendants
     * about its neck pivot.
     */
    public static void forEachPartMatrix(EntityGeometry geometry, EntityAsset asset,
                                         AnimState anim, Matrix4f base,
                                         float headYawDeg, float headPitchDeg,
                                         PartConsumer consumer) {
        forEachPartMatrix(geometry, asset, anim, base, headYawDeg, headPitchDeg, null, consumer);
    }

    /** {@link #forEachPartMatrix} with code-driven part rotations (null for none). */
    public static void forEachPartMatrix(EntityGeometry geometry, EntityAsset asset,
                                         AnimState anim, Matrix4f base,
                                         float headYawDeg, float headPitchDeg,
                                         PartOverrides overrides, PartConsumer consumer) {
        JointPalette palette = borrow();
        try {
            pose(geometry, asset, anim, base, headYawDeg, headPitchDeg, overrides, palette);
            forEachPartMatrix(geometry, palette, consumer);
        } finally {
            giveBack(palette);
        }
    }

    /** Hands each part its skin matrix from an already-posed {@code palette}. */
    public static void forEachPartMatrix(EntityGeometry geometry, JointPalette palette, PartConsumer consumer) {
        Matrix4f partMatrix = new Matrix4f();
        List<EntityPart> parts = geometry.parts();
        int n = Math.min(parts.size(), palette.count());
        for (int i = 0; i < n; i++) {
            consumer.accept(palette.skinMatrix(i, partMatrix), parts.get(i));
        }
    }

    /**
     * World matrix of the named socket (case-insensitive) at the given pose,
     * written into {@code dest} and returned — or {@code null} when the socket
     * is unknown, or when it names a host joint that no longer exists in the
     * model (id and name fallback both miss), so callers skip rendering rather
     * than mount at the model origin.
     */
    public static Matrix4f socketWorldMatrix(EntityAsset asset, String variantName,
                                             AnimState anim, Matrix4f base,
                                             float headYawDeg, float headPitchDeg,
                                             String socketName, Matrix4f dest) {
        if (asset == null || socketName == null) return null;
        EntityGeometry geometry = asset.geometryFor(variantName);
        if (geometry == null) return null;
        return ModelPoseSolver.socketWorldMatrix(geometry, clipLayers(asset, anim), base,
                headYawDeg, headPitchDeg, socketName, dest);
    }

    /**
     * Socket frame from an already-posed {@code palette} (see {@link #pose}) — no second pose. Null
     * when the socket or its host joint is unknown.
     */
    public static Matrix4f socketWorldMatrix(EntityGeometry geometry, JointPalette palette, Matrix4f base,
                                             String socketName, Matrix4f dest) {
        return ModelPoseSolver.socketWorldMatrix(geometry, palette, base, socketName, dest);
    }

    private static ModelPoseSolver.PartOverrides engineOverrides(PartOverrides overrides) {
        return overrides == null ? null : overrides::extraRotationDeg;
    }

    private static JointPalette borrow() {
        JointPalette palette = SCRATCH.get();
        if (palette == null) return new JointPalette();
        SCRATCH.set(null);
        return palette;
    }

    private static void giveBack(JointPalette palette) {
        SCRATCH.set(palette);
    }
}

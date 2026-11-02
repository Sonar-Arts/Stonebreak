package com.stonebreak.rendering.models.entities;

import com.openmason.engine.entity.sbe.SbeEntityAsset;
import com.openmason.engine.entity.sbe.SbeModelGeometry;
import com.openmason.engine.entity.sbe.SbePart;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.rendering.model.draw.ClipLayers;
import com.openmason.engine.rendering.model.draw.ModelPoseSolver;
import com.openmason.engine.rendering.model.draw.PartMatrices;
import com.stonebreak.mobs.sbe.AnimState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Stonebreak's view of the engine pose solver ({@link ModelPoseSolver}, #90):
 * resolves the game's named, layered {@link AnimState} against an
 * {@link SbeEntityAsset}'s clips into the engine's neutral {@link ClipLayers},
 * then delegates. All transform math — base matrix, hierarchy deltas, head
 * tracking, overlays, sockets — lives in the engine; see {@link ModelPoseSolver}
 * for the pipeline.
 */
public final class SbePoseSolver {

    /** Receives the computed world matrix for one animated part. */
    @FunctionalInterface
    public interface PartConsumer {
        void accept(Matrix4f partMatrix, SbePart part);
    }

    /**
     * Code-driven rotation for named parts, layered on top of whatever the clip (or rest pose)
     * gives them — how gameplay drives a part no clip can know about (a dinghy's oars follow
     * the rower's input). Rotates about the part's own origin, so children follow.
     */
    @FunctionalInterface
    public interface PartOverrides extends ModelPoseSolver.PartOverrides {
    }

    private SbePoseSolver() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** {@link ModelPoseSolver#baseMatrix}: render-space {@code T(position) · Ry(yaw) · S(scale)}. */
    public static Matrix4f baseMatrix(Vector3f position, float yawDegrees, Vector3f scale) {
        return ModelPoseSolver.baseMatrix(position, yawDegrees, scale);
    }

    /** The asset's clips for {@code anim}: base state + live overlays (unknown names drop out). */
    public static ClipLayers clipLayers(SbeEntityAsset asset, AnimState anim) {
        if (asset == null || anim == null) return ClipLayers.REST;
        ParsedAnimClip base = asset.clipFor(anim.baseState());
        if (!anim.hasOverlays()) {
            return ClipLayers.single(base, anim.baseTime());
        }
        List<ClipLayers.Overlay> overlays = new ArrayList<>(anim.overlays().size());
        for (AnimState.Overlay overlay : anim.overlays()) {
            ParsedAnimClip clip = asset.clipFor(overlay.stateName());
            if (clip != null) {
                overlays.add(new ClipLayers.Overlay(clip, overlay.time(), overlay.weight()));
            }
        }
        return new ClipLayers(base, anim.baseTime(), overlays);
    }

    /** {@link #forEachPartMatrix(SbeModelGeometry, SbeEntityAsset, AnimState, Matrix4f, float, float, PartOverrides, PartConsumer)} without overrides. */
    public static void forEachPartMatrix(SbeModelGeometry geometry, SbeEntityAsset asset,
                                         AnimState anim, Matrix4f base,
                                         float headYawDeg, float headPitchDeg,
                                         PartConsumer consumer) {
        forEachPartMatrix(geometry, asset, anim, base, headYawDeg, headPitchDeg, null, consumer);
    }

    /** Per-part world matrices for the frame (see {@link ModelPoseSolver#forEachPartMatrix}). */
    public static void forEachPartMatrix(SbeModelGeometry geometry, SbeEntityAsset asset,
                                         AnimState anim, Matrix4f base,
                                         float headYawDeg, float headPitchDeg,
                                         PartOverrides overrides, PartConsumer consumer) {
        ModelPoseSolver.forEachPartMatrix(geometry, clipLayers(asset, anim), base, headYawDeg, headPitchDeg,
                overrides, (index, part, matrix) -> consumer.accept(matrix, part));
    }

    /** The posed part matrices as a renderer input. */
    static PartMatrices partMatrices(SbeModelGeometry geometry, SbeEntityAsset asset, AnimState anim,
                                     Matrix4f base, float headYawDeg, float headPitchDeg,
                                     PartOverrides overrides) {
        return ModelPoseSolver.partMatrices(geometry, clipLayers(asset, anim), base,
                headYawDeg, headPitchDeg, overrides);
    }

    /**
     * World matrix of the named socket (case-insensitive) at the given pose, or
     * {@code null} when unknown or its host part is gone (see
     * {@link ModelPoseSolver#socketWorldMatrix}).
     */
    public static Matrix4f socketWorldMatrix(SbeEntityAsset asset, String variantName,
                                             AnimState anim, Matrix4f base,
                                             float headYawDeg, float headPitchDeg,
                                             String socketName, Matrix4f dest) {
        if (asset == null || socketName == null) return null;
        return ModelPoseSolver.socketWorldMatrix(asset.geometryFor(variantName), clipLayers(asset, anim),
                base, headYawDeg, headPitchDeg, socketName, dest);
    }
}

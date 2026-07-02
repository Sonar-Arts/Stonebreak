package com.openmason.engine.format.oma;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimLayeringTest {

    private static final float EPS = 1e-4f;

    private static ParsedKeyframe kf(float t, float rotY) {
        return new ParsedKeyframe(t, new Vector3f(), new Vector3f(0, rotY, 0),
                new Vector3f(1, 1, 1), "LINEAR");
    }

    private static ParsedAnimClip overlayClip(String trackPart, List<String> mask,
                                              float fadeIn, float fadeOut,
                                              boolean loop, int priority, float rotY) {
        ParsedAnimTrack track = new ParsedAnimTrack(trackPart, trackPart,
                List.of(kf(0f, rotY), kf(1f, rotY)));
        return new ParsedAnimClip("overlay", 30f, 1f, loop, List.of(track),
                new AnimLayerMeta(AnimLayerMeta.LayerType.OVERLAY, mask, fadeIn, fadeOut, priority));
    }

    // ===================== clipWeight =====================

    @Test
    void clipWeightRampsInPlateausAndRampsOut() {
        ParsedAnimClip overlay = overlayClip("arm", List.of(), 0.2f, 0.2f, false, 0, 90f);
        assertEquals(0f, AnimLayering.clipWeight(overlay, 0f), EPS);
        assertEquals(0.5f, AnimLayering.clipWeight(overlay, 0.1f), EPS);   // ramp in
        assertEquals(1f, AnimLayering.clipWeight(overlay, 0.5f), EPS);     // plateau
        assertEquals(0.5f, AnimLayering.clipWeight(overlay, 0.9f), EPS);   // ramp out
        assertEquals(0f, AnimLayering.clipWeight(overlay, 1f), EPS);       // end
        assertEquals(0f, AnimLayering.clipWeight(overlay, 1.5f), EPS);     // past end
    }

    @Test
    void clipWeightZeroFadesSnap() {
        ParsedAnimClip overlay = overlayClip("arm", List.of(), 0f, 0f, false, 0, 90f);
        assertEquals(1f, AnimLayering.clipWeight(overlay, 0f), EPS);
        assertEquals(1f, AnimLayering.clipWeight(overlay, 0.99f), EPS);
    }

    @Test
    void loopingOverlayNeverRampsOut() {
        ParsedAnimClip overlay = overlayClip("arm", List.of(), 0.1f, 0.1f, true, 0, 90f);
        assertEquals(1f, AnimLayering.clipWeight(overlay, 0.95f), EPS);
        assertEquals(1f, AnimLayering.clipWeight(overlay, 37f), EPS);
    }

    // ===================== mask semantics =====================

    @Test
    void emptyMaskCoversAllParts() {
        AnimLayerMeta meta = new AnimLayerMeta(AnimLayerMeta.LayerType.OVERLAY,
                List.of(), 0.1f, 0.1f, 0);
        assertTrue(meta.masksPart("id-1", "anything"));
    }

    @Test
    void maskMatchesByNameCaseInsensitiveWithIdFallback() {
        AnimLayerMeta meta = new AnimLayerMeta(AnimLayerMeta.LayerType.OVERLAY,
                List.of("LeftArm", "uuid-42"), 0.1f, 0.1f, 0);
        assertTrue(meta.masksPart("x", "leftarm"));
        assertTrue(meta.masksPart("uuid-42", "somethingElse"));
        assertFalse(meta.masksPart("x", "rightArm"));
    }

    // ===================== blendPart =====================

    @Test
    void blendAtFullWeightTakesOverlayPose() {
        ParsedAnimClip overlay = overlayClip("arm", List.of("arm"), 0f, 0f, false, 0, 90f);
        AnimSampler.PartPose rest = AnimSampler.PartPose.identity();
        AnimSampler.PartPose pose = AnimLayering.blendPart(rest, null, 0f,
                List.of(new AnimLayering.OverlayFrame(overlay, 0.5f, 1f)), "arm", "arm");
        assertEquals(90f, pose.rotationDeg().y, EPS);
    }

    @Test
    void blendAtZeroWeightKeepsBase() {
        ParsedAnimClip overlay = overlayClip("arm", List.of("arm"), 0f, 0f, false, 0, 90f);
        AnimSampler.PartPose rest = AnimSampler.PartPose.identity();
        AnimSampler.PartPose pose = AnimLayering.blendPart(rest, null, 0f,
                List.of(new AnimLayering.OverlayFrame(overlay, 0.5f, 0f)), "arm", "arm");
        assertEquals(0f, pose.rotationDeg().y, EPS);
    }

    @Test
    void blendAtHalfWeightMixesShortestPath() {
        ParsedAnimClip overlay = overlayClip("arm", List.of("arm"), 0f, 0f, false, 0, 90f);
        AnimSampler.PartPose rest = AnimSampler.PartPose.identity();
        AnimSampler.PartPose pose = AnimLayering.blendPart(rest, null, 0f,
                List.of(new AnimLayering.OverlayFrame(overlay, 0.5f, 0.5f)), "arm", "arm");
        assertEquals(45f, pose.rotationDeg().y, EPS);
    }

    @Test
    void unmaskedPartKeepsBasePose() {
        ParsedAnimClip overlay = overlayClip("leg", List.of("arm"), 0f, 0f, false, 0, 90f);
        ParsedAnimTrack baseTrack = new ParsedAnimTrack("leg", "leg", List.of(kf(0f, 30f)));
        AnimSampler.PartPose pose = AnimLayering.blendPart(AnimSampler.PartPose.identity(),
                baseTrack, 0f,
                List.of(new AnimLayering.OverlayFrame(overlay, 0.5f, 1f)), "leg", "leg");
        assertEquals(30f, pose.rotationDeg().y, EPS);
    }

    @Test
    void noOverlaysAndNoBaseTrackReturnsRestPoseInstance() {
        AnimSampler.PartPose rest = AnimSampler.PartPose.identity();
        assertSame(rest, AnimLayering.blendPart(rest, null, 0f, List.of(), "p", "p"));
    }

    @Test
    void higherPriorityOverlayWinsContestedPart() {
        ParsedAnimClip low = overlayClip("arm", List.of("arm"), 0f, 0f, false, 0, 30f);
        ParsedAnimClip high = overlayClip("arm", List.of("arm"), 0f, 0f, false, 5, 90f);
        // Pass out of order; sorting by priority must put `high` last (it wins at w=1).
        AnimSampler.PartPose pose = AnimLayering.blendPart(AnimSampler.PartPose.identity(),
                null, 0f,
                List.of(new AnimLayering.OverlayFrame(high, 0.5f, 1f),
                        new AnimLayering.OverlayFrame(low, 0.5f, 1f)),
                "arm", "arm");
        assertEquals(90f, pose.rotationDeg().y, EPS);
    }

    @Test
    void overlayTrackResolvesByNameFallback() {
        // Overlay track has a different partId but matching partName.
        ParsedAnimTrack track = new ParsedAnimTrack("other-uuid", "Arm", List.of(kf(0f, 60f)));
        ParsedAnimClip overlay = new ParsedAnimClip("o", 30f, 1f, false, List.of(track),
                new AnimLayerMeta(AnimLayerMeta.LayerType.OVERLAY, List.of("Arm"), 0f, 0f, 0));
        AnimSampler.PartPose pose = AnimLayering.blendPart(AnimSampler.PartPose.identity(),
                null, 0f,
                List.of(new AnimLayering.OverlayFrame(overlay, 0f, 1f)), "model-uuid", "arm");
        assertEquals(60f, pose.rotationDeg().y, EPS);
    }
}

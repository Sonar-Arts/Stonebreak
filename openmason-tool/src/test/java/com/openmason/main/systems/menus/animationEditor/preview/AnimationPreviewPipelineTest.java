package com.openmason.main.systems.menus.animationEditor.preview;

import com.openmason.engine.format.oma.AnimLayerMeta;
import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import com.openmason.main.systems.menus.animationEditor.AnimTestModels;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.openmason.main.systems.menus.animationEditor.AnimTestModels.idOf;
import static com.openmason.main.systems.menus.animationEditor.AnimTestModels.kf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preview must never corrupt the model: rest pose comes back on release
 * and around a save, modelling edits made while the editor is open survive,
 * and auto-key can claim an edit instead.
 */
class AnimationPreviewPipelineTest {

    private static PartTransform at(ModelPartManager pm, String id, float x) {
        PartTransform t = pm.getPartById(id).orElseThrow().transform();
        return new PartTransform(new Vector3f(t.origin()), new Vector3f(x, 0, 0),
                new Vector3f(t.rotation()), new Vector3f(t.scale()));
    }

    private static float posX(ModelPartManager pm, String id) {
        return pm.getPartById(id).orElseThrow().transform().position().x;
    }

    @Test
    void applyThenReleaseRestoresRestPose() {
        ModelPartManager pm = AnimTestModels.model("body", "leg");
        String body = idOf(pm, "body");
        String leg = idOf(pm, "leg");
        pm.setPartTransform(body, at(pm, body, 7f)); // authored rest pose

        AnimationClip clip = AnimationClip.blank();
        clip.ensureTrack(leg).upsert(kf(0f, 3f));

        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm);
        preview.captureRestPose();
        preview.applyPose(clip, 0f);
        assertEquals(3f, posX(pm, leg), 1e-6f);
        assertEquals(7f, posX(pm, body), 1e-6f, "untracked part stays at rest");
        assertTrue(preview.isAnimated(leg));
        assertFalse(preview.isAnimated(body));

        preview.release();
        assertEquals(0f, posX(pm, leg), 1e-6f, "rest pose written back");
        assertEquals(7f, posX(pm, body), 1e-6f);
    }

    @Test
    void restoreRestPoseAroundSaveKeepsSnapshotSoPreviewCanResume() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationClip clip = AnimationClip.blank();
        clip.ensureTrack(leg).upsert(kf(0f, 3f));

        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm);
        preview.captureRestPose();
        preview.applyPose(clip, 0f);

        preview.restoreRestPose();                 // "before save"
        assertEquals(0f, posX(pm, leg), 1e-6f, "the .omo sees the rest pose");
        assertTrue(preview.isAtRest());
        assertTrue(preview.hasCapturedRestPose());

        preview.applyPose(clip, 0f);               // "after save"
        assertEquals(3f, posX(pm, leg), 1e-6f);
    }

    @Test
    void modellingEditOfAnUnanimatedPartBecomesItsNewRestPose() {
        ModelPartManager pm = AnimTestModels.model("body", "leg");
        String body = idOf(pm, "body");
        String leg = idOf(pm, "leg");
        AnimationClip clip = AnimationClip.blank();
        clip.ensureTrack(leg).upsert(kf(0f, 3f));

        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm);
        preview.captureRestPose();
        preview.applyPose(clip, 0f);

        // The user moves the (untracked) body in the property panel while the editor is open.
        pm.setPartTransform(body, at(pm, body, 5f));
        preview.applyPose(clip, 0.5f);
        assertEquals(5f, posX(pm, body), 1e-6f, "scrubbing must not snap the edit back");
        preview.release();
        assertEquals(5f, posX(pm, body), 1e-6f, "closing keeps the modelling edit");
    }

    @Test
    void editOfAnAnimatedPartDoesNotOverwriteRestSnapshot() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationClip clip = AnimationClip.blank();
        clip.ensureTrack(leg).upsert(kf(0f, 3f));

        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm);
        preview.captureRestPose();
        preview.applyPose(clip, 0f);
        pm.setPartTransform(leg, at(pm, leg, 9f));   // posing on top of the animation
        preview.release();
        assertEquals(0f, posX(pm, leg), 1e-6f, "rest pose is still the authored one");
    }

    @Test
    void autoKeyListenerConsumingAnEditKeepsRestSnapshot() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm);
        preview.setExternalEditListener((id, t) -> true);   // "auto-keyed it"
        preview.captureRestPose();
        pm.setPartTransform(leg, at(pm, leg, 4f));
        assertEquals(0f, preview.restPoseOf(leg).position().x, 1e-6f);
    }

    @Test
    void ownWritesAreNotReportedAsExternalEdits() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationClip clip = AnimationClip.blank();
        clip.ensureTrack(leg).upsert(kf(0f, 3f));
        int[] calls = {0};
        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm);
        preview.setExternalEditListener((id, t) -> { calls[0]++; return false; });
        preview.captureRestPose();
        preview.applyPose(clip, 0f);
        preview.release();
        assertEquals(0, calls[0]);
    }

    @Test
    void partsRebuiltRecapturesRestPose() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm);
        preview.captureRestPose();
        // Simulate a model undo that rebuilt every part at a new rest transform.
        pm.setPartTransform(leg, at(pm, leg, 2f));
        preview.onPartsRebuilt();
        assertEquals(2f, preview.restPoseOf(leg).position().x, 1e-6f);
    }

    @Test
    void overlayBlendsOntoBaseByMaskAndFade() {
        ModelPartManager pm = AnimTestModels.model("arm", "leg");
        String arm = idOf(pm, "arm");
        String leg = idOf(pm, "leg");

        AnimationClip base = new AnimationClip("walk", 30f, 1f, true, null);
        base.ensureTrack(arm).upsert(kf(0f, 1f));
        base.ensureTrack(leg).upsert(kf(0f, 2f));

        AnimationClip overlay = new AnimationClip("wave", 30f, 1f, true, null);
        overlay.setLayerType(AnimLayerMeta.LayerType.OVERLAY);
        overlay.setMaskParts(List.of("arm"));
        overlay.setFadeInSeconds(0.5f);
        overlay.ensureTrack(arm).upsert(kf(0f, 11f));
        overlay.ensureTrack(leg).upsert(kf(0f, 22f));   // not masked: must be ignored

        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm);
        preview.captureRestPose();

        preview.applyPose(overlay, 0.25f, base);          // half way through the fade-in
        assertEquals(6f, posX(pm, arm), 1e-4f, "arm: lerp(base 1, overlay 11, 0.5)");
        assertEquals(2f, posX(pm, leg), 1e-4f, "leg follows the base only");

        preview.applyPose(overlay, 1f, base);             // fully faded in
        assertEquals(11f, posX(pm, arm), 1e-4f);

        preview.applyPose(overlay, 1f, null);             // no base: plain preview of the overlay
        assertEquals(22f, posX(pm, leg), 1e-4f);
    }

    @Test
    void loadTrafficOnFreshlyAddedPartsIsNeverReportedAsAnEdit() {
        ModelPartManager pm = AnimTestModels.model("old");
        int[] calls = {0};
        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm);
        preview.setExternalEditListener((id, t) -> { calls[0]++; return true; });
        preview.captureRestPose();

        // A model load in the same frame: clear, add, set saved transforms.
        pm.clear();
        String leg = AnimTestModels.addCube(pm, "leg").id();
        pm.setPartTransform(leg, at(pm, leg, 6f));
        assertEquals(0, calls[0], "load traffic is not a user edit");
        assertEquals(6f, preview.restPoseOf(leg).position().x, 1e-6f, "but it is the rest pose");

        preview.frameTick();                       // next UI frame
        pm.setPartTransform(leg, at(pm, leg, 7f));
        assertEquals(1, calls[0], "now it is a user edit");
    }

    @Test
    void reopeningASessionAfterReleaseListensAgain() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        int[] calls = {0};
        AnimationPreviewPipeline preview = new AnimationPreviewPipeline(pm);
        preview.setExternalEditListener((id, t) -> { calls[0]++; return false; });
        preview.captureRestPose();
        preview.release();                         // editor hidden
        preview.captureRestPose();                 // editor shown again
        pm.setPartTransform(leg, at(pm, leg, 1f));
        assertEquals(1, calls[0], "second session still sees viewport edits");
        preview.release();
        pm.setPartTransform(leg, at(pm, leg, 2f));
        assertEquals(1, calls[0], "detached after release");
    }
}

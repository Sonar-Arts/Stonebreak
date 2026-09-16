package com.openmason.main.systems.menus.animationEditor.controller;

import com.openmason.engine.rendering.model.gmr.parts.ModelPartManager;
import com.openmason.engine.rendering.model.gmr.parts.PartTransform;
import com.openmason.main.systems.menus.animationEditor.AnimTestModels;
import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.menus.animationEditor.state.KeyframeSelection;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.openmason.main.systems.menus.animationEditor.AnimTestModels.idOf;
import static com.openmason.main.systems.menus.animationEditor.AnimTestModels.kf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationEditorControllerTest {

    @TempDir
    Path tempDir;

    private static AnimationEditorController bound(ModelPartManager pm) {
        AnimationEditorController c = new AnimationEditorController();
        c.bindViewport(pm);
        c.beginSession();
        return c;
    }

    private static void select(AnimationEditorController c, String partId, float... times) {
        KeyframeSelection sel = new KeyframeSelection();
        for (float t : times) sel.add(new KeyframeSelection.KeyRef(partId, t));
        c.state().selectKeyframes(sel, partId, 0);
    }

    private static PartTransform moved(ModelPartManager pm, String id, float x) {
        PartTransform t = pm.getPartById(id).orElseThrow().transform();
        return new PartTransform(new Vector3f(t.origin()), new Vector3f(x, 0, 0),
                new Vector3f(t.rotation()), new Vector3f(t.scale()));
    }

    // ---------- dirty tracking ----------

    @Test
    void dirtyFollowsHistoryPositionAcrossUndoRedoAndSave() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationEditorController c = bound(pm);
        assertFalse(c.state().dirty(), "new clip is clean");

        c.insertKeyframe(leg, kf(0f, 1f));
        assertTrue(c.state().dirty());
        c.undo();
        assertFalse(c.state().dirty(), "undo back to the saved position is clean again");
        c.redo();
        assertTrue(c.state().dirty());

        assertTrue(c.saveAs(tempDir.resolve("a").toString()));
        assertFalse(c.state().dirty());
        assertTrue(c.state().filePath().endsWith(".omanim"));

        c.insertKeyframe(leg, kf(0.5f, 2f));
        assertTrue(c.state().dirty());
        c.undo();
        assertFalse(c.state().dirty());
        c.undo();
        assertTrue(c.state().dirty(), "undoing past the save point is dirty");
    }

    @Test
    void saveStampsModelRefFromSupplier() {
        ModelPartManager pm = AnimTestModels.model("leg");
        AnimationEditorController c = bound(pm);
        c.setModelRefSupplier(() -> "models/cow.omo");
        assertNull(c.state().clip().modelRef());
        assertTrue(c.saveAs(tempDir.resolve("b.omanim").toString()));
        assertEquals("models/cow.omo", c.state().clip().modelRef());

        AnimationEditorController other = bound(AnimTestModels.model("leg"));
        assertTrue(other.load(c.state().filePath()));
        assertEquals("models/cow.omo", other.state().clip().modelRef());
    }

    // ---------- bulk edits ----------

    @Test
    void pasteDuplicateAndReverseAreSingleUndoSteps() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationEditorController c = bound(pm);
        c.setClipDuration(10f);
        c.insertKeyframe(leg, kf(0f, 0f));
        c.insertKeyframe(leg, kf(1f, 1f));
        c.insertKeyframe(leg, kf(2f, 2f));

        select(c, leg, 0f, 1f, 2f);
        c.state().setPlayhead(5f);
        c.duplicateSelectionToPlayhead();
        Track track = c.state().clip().trackFor(leg);
        assertEquals(6, track.size());
        assertEquals(5f, track.get(3).time(), 1e-5f);
        assertEquals(3, c.state().selection().size(), "copies are the new selection");
        assertTrue(c.state().clipboard().isEmpty(), "duplicate leaves the clipboard alone");
        c.undo();
        assertEquals(3, track.size(), "one undo removes all copies");

        select(c, leg, 0f, 1f, 2f);
        assertEquals(3, c.copySelection());
        c.state().setPlayhead(4f);
        c.pasteAtPlayhead();
        assertEquals(6, track.size());
        c.undo();
        assertEquals(3, track.size());

        select(c, leg, 0f, 2f);
        c.reverseSelection();
        // t' = 0 + 2 - t: key@0 (x=0) ↔ key@2 (x=2); key@1 untouched
        assertEquals(2f, track.get(0).position().x, 1e-5f);
        assertEquals(0f, track.get(2).position().x, 1e-5f);
        assertEquals(1f, track.get(1).position().x, 1e-5f);
        c.undo();
        assertEquals(0f, track.get(0).position().x, 1e-5f);
    }

    @Test
    void selectAllDeleteSelectedAndEasingComposite() {
        ModelPartManager pm = AnimTestModels.model("a", "b");
        String a = idOf(pm, "a");
        String b = idOf(pm, "b");
        AnimationEditorController c = bound(pm);
        c.insertKeyframe(a, kf(0f, 0f));
        c.insertKeyframe(a, kf(0.5f, 1f));
        c.insertKeyframe(b, kf(0f, 2f));

        c.selectAll();
        assertEquals(3, c.state().selection().size());
        c.setSelectionEasing(Easing.STEP);
        assertEquals(Easing.STEP, c.state().clip().trackFor(b).get(0).easing());
        c.undo();
        assertEquals(Easing.LINEAR, c.state().clip().trackFor(b).get(0).easing());

        c.selectAll();
        c.deleteSelectedKeyframes();
        assertTrue(c.state().clip().tracks().isEmpty());
        c.undo();
        assertEquals(2, c.state().clip().trackFor(a).size());
        assertEquals(1, c.state().clip().trackFor(b).size());
    }

    @Test
    void stepBackFromZeroWrapsOnLoopingClipsOnly() {
        AnimationEditorController c = bound(AnimTestModels.model("a"));
        c.setClipDuration(1f);
        c.setClipFps(10f);
        c.stepFrames(-1);
        assertEquals(0.9f, c.state().playhead(), 1e-5f);
        c.setClipLoop(false);
        c.state().setPlayhead(0f);
        c.stepFrames(-1);
        assertEquals(0f, c.state().playhead(), 1e-5f);
    }

    @Test
    void blankClipNamesAreIgnored() {
        AnimationEditorController c = bound(AnimTestModels.model("a"));
        c.setClipName("   ");
        assertEquals("untitled", c.state().clip().name());
        c.setClipName("  walk ");
        assertEquals("walk", c.state().clip().name());
    }

    // ---------- duration / orphans ----------

    @Test
    void trimRemovesKeysBeyondDuration() {
        ModelPartManager pm = AnimTestModels.model("a");
        String a = idOf(pm, "a");
        AnimationEditorController c = bound(pm);
        c.setClipDuration(5f);
        c.insertKeyframe(a, kf(1f, 0f));
        c.insertKeyframe(a, kf(4f, 0f));
        c.setClipDuration(2f);
        assertEquals(1, c.keyframesBeyondDuration());
        c.trimKeyframesBeyondDuration();
        assertEquals(0, c.keyframesBeyondDuration());
        assertEquals(1, c.state().clip().trackFor(a).size());
        c.undo();
        assertEquals(2, c.state().clip().trackFor(a).size());
    }

    @Test
    void orphanTracksAreListedAndCanBeRebound() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationEditorController c = bound(pm);
        c.insertKeyframe("ghost-id", kf(0f, 3f));
        assertEquals(1, c.orphanTracks().size());
        assertEquals("ghost-id", c.orphanTracks().get(0).partId());

        assertFalse(c.rebindTrack("ghost-id", "not-a-part"));
        assertTrue(c.rebindTrack("ghost-id", leg));
        assertTrue(c.orphanTracks().isEmpty());
        assertEquals("leg", c.state().clip().trackFor(leg).partNameHint());
        assertEquals(3f, pm.getPartById(leg).orElseThrow().transform().position().x, 1e-5f,
                "preview now drives the rebound part");
        c.undo();
        assertEquals(1, c.orphanTracks().size());
    }

    // ---------- viewport edits ----------

    @Test
    void autoKeyRecordsViewportEditsAsOneUndoStepPerDrag() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationEditorController c = bound(pm);
        c.state().setAutoKey(true);
        c.state().setPlayhead(0.5f);

        // A gizmo drag: many transform writes at the same playhead.
        pm.setPartTransform(leg, moved(pm, leg, 1f));
        pm.setPartTransform(leg, moved(pm, leg, 2f));
        pm.setPartTransform(leg, moved(pm, leg, 3f));

        Track track = c.state().clip().trackFor(leg);
        assertNotNull(track);
        assertEquals(1, track.size());
        assertEquals(0.5f, track.get(0).time(), 1e-5f);
        assertEquals(3f, track.get(0).position().x, 1e-5f);
        assertNull(c.state().unkeyedPartId());

        assertTrue(c.undo());
        assertNull(c.state().clip().trackFor(leg), "the whole drag was one history entry");
        assertFalse(c.history().canUndo());
        assertEquals(0f, pm.getPartById(leg).orElseThrow().transform().position().x, 1e-5f,
                "undo put the part back at rest");
    }

    @Test
    void withoutAutoKeyAnEditOfAnAnimatedPartIsFlaggedAsUnkeyed() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationEditorController c = bound(pm);
        c.insertKeyframe(leg, kf(0f, 1f));
        pm.setPartTransform(leg, moved(pm, leg, 9f));
        assertEquals(leg, c.state().unkeyedPartId());
        c.insertKeyframeAtPlayhead(leg);
        assertNull(c.state().unkeyedPartId(), "keying clears the hint");
        assertEquals(9f, c.state().clip().trackFor(leg).get(0).position().x, 1e-5f,
                "K keys the posed transform");
    }

    @Test
    void insertKeyframeAtOtherTimeSamplesTheTrack() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationEditorController c = bound(pm);
        c.setClipDuration(2f);
        c.insertKeyframe(leg, kf(0f, 0f));
        c.insertKeyframe(leg, kf(2f, 10f));
        c.insertKeyframeAt(leg, 1f);
        Track track = c.state().clip().trackFor(leg);
        assertEquals(3, track.size());
        assertEquals(5f, track.get(1).position().x, 1e-5f, "midpoint of the linear segment");
    }

    // ---------- save guard + bytes round trip ----------

    @Test
    void suspendPreviewPutsModelAtRestAndResumeReapplies() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationEditorController c = bound(pm);
        c.insertKeyframe(leg, kf(0f, 4f));
        assertEquals(4f, pm.getPartById(leg).orElseThrow().transform().position().x, 1e-5f);
        c.suspendPreview();
        assertEquals(0f, pm.getPartById(leg).orElseThrow().transform().position().x, 1e-5f);
        c.resumePreview();
        assertEquals(4f, pm.getPartById(leg).orElseThrow().transform().position().x, 1e-5f);
    }

    @Test
    void exportAndImportClipBytesRoundTripAndRebindByName() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationEditorController c = bound(pm);
        c.setClipName("kick");
        c.insertKeyframe(leg, new Keyframe(0.25f, new Vector3f(1, 2, 3), new Vector3f(4, 5, 6),
                new Vector3f(1, 1, 1), Easing.STEP));
        byte[] bytes = c.exportClipBytes();
        assertNotNull(bytes);

        // A different model instance: the part has a new id but the same name.
        ModelPartManager pm2 = AnimTestModels.model("leg");
        AnimationEditorController c2 = bound(pm2);
        assertTrue(c2.importClipBytes(bytes, "sbe state"));
        assertEquals("kick", c2.state().clip().name());
        assertTrue(c2.state().dirty(), "an imported clip is reported unsaved");
        Track track = c2.state().clip().trackFor(idOf(pm2, "leg"));
        assertNotNull(track, "rebound by name hint");
        assertEquals(Easing.STEP, track.get(0).easing());
        assertEquals(5f, track.get(0).rotation().y, 1e-5f);
    }

    @Test
    void newClipAndLoadAreClean() {
        ModelPartManager pm = AnimTestModels.model("leg");
        String leg = idOf(pm, "leg");
        AnimationEditorController c = bound(pm);
        c.insertKeyframe(leg, kf(0f, 1f));
        c.newClip();
        assertFalse(c.state().dirty());
        assertEquals(AnimationClip.blank().name(), c.state().clip().name());
    }
}

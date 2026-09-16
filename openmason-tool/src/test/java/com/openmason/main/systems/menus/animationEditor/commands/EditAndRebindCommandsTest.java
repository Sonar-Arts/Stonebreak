package com.openmason.main.systems.menus.animationEditor.commands;

import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.menus.textureCreator.commands.Command;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The collision-safe edit, orphan rebind, trim and auto-key commands. */
class EditAndRebindCommandsTest {

    private static Keyframe kf(float t, float x) {
        return new Keyframe(t, new Vector3f(x, 0, 0), new Vector3f(), new Vector3f(1, 1, 1), Easing.LINEAR);
    }

    private static AnimationClip clip(String partId, float... times) {
        AnimationClip clip = new AnimationClip("test", 30f, 10f, true, null);
        Track track = clip.ensureTrack(partId);
        for (float t : times) track.upsert(kf(t, t));
        return clip;
    }

    @Test
    void editOntoAnotherKeyframesTimeDisplacesItAndUndoRestoresBoth() {
        AnimationClip clip = clip("p", 0f, 1f, 2f);
        Keyframe moved = kf(2f, 99f);            // retime key@1 onto key@2's time
        Command cmd = KeyframeCommands.edit(clip, "p", 1, moved);

        cmd.execute();
        Track track = clip.trackFor("p");
        assertEquals(2, track.size(), "a track never holds two keys at one time");
        assertEquals(99f, track.get(1).position().x, 1e-6f, "the edited key won");

        cmd.undo();
        assertEquals(3, track.size());
        assertEquals(1f, track.get(1).position().x, 1e-6f);
        assertEquals(2f, track.get(2).position().x, 1e-6f, "displaced key came back");

        cmd.execute();
        assertEquals(2, track.size());
    }

    @Test
    void editUndoLocatesByIdentityNotTime() {
        // Two edits that leave the same time: undo of the first must not grab the second's key.
        AnimationClip clip = clip("p", 0f, 1f);
        Command a = KeyframeCommands.edit(clip, "p", 1, kf(1f, 10f));
        a.execute();
        Command b = KeyframeCommands.edit(clip, "p", 1, kf(1f, 20f));
        b.execute();
        b.undo();
        assertEquals(10f, clip.trackFor("p").get(1).position().x, 1e-6f);
        a.undo();
        assertEquals(1f, clip.trackFor("p").get(1).position().x, 1e-6f);
    }

    @Test
    void rebindTrackMergesIntoExistingTargetAndUndoRestoresBoth() {
        AnimationClip clip = clip("orphan", 0f, 1f);
        clip.trackFor("orphan").setPartNameHint("leg");
        Track target = clip.ensureTrack("leg-id");
        target.upsert(kf(1f, 50f));
        target.upsert(kf(3f, 60f));
        target.setPartNameHint("old");

        Command cmd = KeyframeCommands.rebindTrack(clip, "orphan", "leg-id", "leg");
        cmd.execute();
        assertNull(clip.trackFor("orphan"));
        Track merged = clip.trackFor("leg-id");
        assertEquals(3, merged.size());
        assertEquals(1f, merged.get(1).position().x, 1e-6f, "same-time key from the orphan replaces");
        assertEquals("leg", merged.partNameHint());

        cmd.undo();
        assertEquals(2, clip.trackFor("orphan").size());
        assertEquals("leg", clip.trackFor("orphan").partNameHint());
        Track restored = clip.trackFor("leg-id");
        assertEquals(2, restored.size());
        assertEquals(50f, restored.get(0).position().x, 1e-6f);
        assertEquals("old", restored.partNameHint());
    }

    @Test
    void rebindTrackToFreshPartAndUndoRemovesIt() {
        AnimationClip clip = clip("orphan", 0f);
        Command cmd = KeyframeCommands.rebindTrack(clip, "orphan", "fresh", "Fresh");
        cmd.execute();
        assertNotNull(clip.trackFor("fresh"));
        cmd.undo();
        assertNull(clip.trackFor("fresh"));
        assertNotNull(clip.trackFor("orphan"));
    }

    @Test
    void trimBeyondDropsLateKeysAndEmptiedTracks() {
        AnimationClip clip = clip("a", 0f, 5f, 8f);
        clip.ensureTrack("b").upsert(kf(9f, 1f));
        Command cmd = KeyframeCommands.trimBeyond(clip, 6f);
        assertNotNull(cmd);
        cmd.execute();
        assertEquals(2, clip.trackFor("a").size());
        assertNull(clip.trackFor("b"), "track left empty is removed");
        cmd.undo();
        assertEquals(3, clip.trackFor("a").size());
        assertEquals(1, clip.trackFor("b").size());
        assertNull(KeyframeCommands.trimBeyond(clip, 20f), "nothing beyond → no command");
    }

    @Test
    void autoKeyAmendsInPlaceAndUndoRestoresReplacedKey() {
        AnimationClip clip = clip("p", 0f, 1f);
        AutoKeyCommand cmd = new AutoKeyCommand(clip, "p", kf(1f, 10f));
        cmd.execute();
        assertEquals(10f, clip.trackFor("p").get(1).position().x, 1e-6f);

        cmd.amend(kf(1f, 11f));
        cmd.amend(kf(1f, 12f));
        assertEquals(2, clip.trackFor("p").size(), "amend never duplicates");
        assertEquals(12f, clip.trackFor("p").get(1).position().x, 1e-6f);

        cmd.undo();
        assertEquals(1f, clip.trackFor("p").get(1).position().x, 1e-6f, "original key back");

        cmd.execute();
        assertEquals(12f, clip.trackFor("p").get(1).position().x, 1e-6f, "redo replays the amended pose");
    }

    @Test
    void autoKeyOnFreshTrackUndoRemovesTrack() {
        AnimationClip clip = new AnimationClip("t", 30f, 1f, true, null);
        AutoKeyCommand cmd = new AutoKeyCommand(clip, "p", kf(0.5f, 1f));
        cmd.execute();
        assertNotNull(clip.trackFor("p"));
        cmd.undo();
        assertNull(clip.trackFor("p"));
        assertSame(true, cmd.matches("p", 0.5f));
    }
}

package com.openmason.main.systems.menus.animationEditor.commands;

import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import com.openmason.main.systems.menus.textureCreator.commands.Command;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class KeyframeCommandsTest {

    private static Keyframe kf(float t) {
        return new Keyframe(t, new Vector3f(t, 0, 0), new Vector3f(), new Vector3f(1, 1, 1), Easing.LINEAR);
    }

    private static AnimationClip clipWithTrack(String partId, float... times) {
        AnimationClip clip = new AnimationClip("test", 30f, 10f, true, null);
        Track track = clip.ensureTrack(partId);
        track.setPartNameHint("hint-" + partId);
        for (float t : times) {
            track.upsert(kf(t));
        }
        return clip;
    }

    @Test
    void deleteTrackUndoRestoresKeyframesAndHint() {
        AnimationClip clip = clipWithTrack("p1", 0f, 0.5f, 1f);
        Command cmd = KeyframeCommands.deleteTrack(clip, "p1");

        cmd.execute();
        assertNull(clip.trackFor("p1"));

        cmd.undo();
        Track restored = clip.trackFor("p1");
        assertNotNull(restored);
        assertEquals(3, restored.size());
        assertEquals(0.5f, restored.get(1).time(), 1e-5f);
        assertEquals("hint-p1", restored.partNameHint());

        // redo works too
        cmd.execute();
        assertNull(clip.trackFor("p1"));
    }

    @Test
    void deleteTrackOnMissingTrackIsNoOp() {
        AnimationClip clip = new AnimationClip("test", 30f, 1f, true, null);
        Command cmd = KeyframeCommands.deleteTrack(clip, "nope");
        cmd.execute();
        cmd.undo();
        assertNull(clip.trackFor("nope"));
    }

    @Test
    void replaceTrackKeyframesSwapsAndRestores() {
        AnimationClip clip = clipWithTrack("p1", 0f, 0.5f, 1f);
        List<Keyframe> before = List.copyOf(clip.trackFor("p1").keyframes());
        List<Keyframe> after = List.of(kf(0f), kf(0.7f), kf(1.2f));

        Command cmd = KeyframeCommands.replaceTrackKeyframes(clip, "p1", before, after);
        cmd.execute();
        assertEquals(0.7f, clip.trackFor("p1").get(1).time(), 1e-5f);
        assertEquals(1.2f, clip.trackFor("p1").get(2).time(), 1e-5f);

        cmd.undo();
        assertEquals(0.5f, clip.trackFor("p1").get(1).time(), 1e-5f);
        assertEquals(1f, clip.trackFor("p1").get(2).time(), 1e-5f);
    }

    @Test
    void replaceTrackKeyframesCollapsesCoincidentTimes() {
        AnimationClip clip = clipWithTrack("p1", 0f, 0.5f);
        List<Keyframe> before = List.copyOf(clip.trackFor("p1").keyframes());
        // Two keys landing on the same time collapse via upsert (last wins).
        List<Keyframe> after = List.of(kf(0.5f), kf(0.5f));

        Command cmd = KeyframeCommands.replaceTrackKeyframes(clip, "p1", before, after);
        cmd.execute();
        assertEquals(1, clip.trackFor("p1").size());

        cmd.undo();
        assertEquals(2, clip.trackFor("p1").size());
    }

    @Test
    void replaceTrackKeyframesToEmptyRemovesTrack() {
        AnimationClip clip = clipWithTrack("p1", 0f);
        List<Keyframe> before = List.copyOf(clip.trackFor("p1").keyframes());

        Command cmd = KeyframeCommands.replaceTrackKeyframes(clip, "p1", before, List.of());
        cmd.execute();
        assertNull(clip.trackFor("p1"));

        cmd.undo();
        assertNotNull(clip.trackFor("p1"));
        assertEquals(1, clip.trackFor("p1").size());
    }

    @Test
    void compositeExecutesInOrderAndUndoesInReverse() {
        AnimationClip clip = clipWithTrack("p1", 0f);
        List<Keyframe> initial = List.copyOf(clip.trackFor("p1").keyframes());

        // Second command's "before" is the first command's "after" — order matters.
        List<Keyframe> mid = List.of(kf(0.25f));
        List<Keyframe> end = List.of(kf(0.75f));
        CompositeCommand composite = new CompositeCommand("test", List.of(
                KeyframeCommands.replaceTrackKeyframes(clip, "p1", initial, mid),
                KeyframeCommands.replaceTrackKeyframes(clip, "p1", mid, end)));

        composite.execute();
        assertEquals(0.75f, clip.trackFor("p1").get(0).time(), 1e-5f);

        composite.undo();
        assertEquals(0f, clip.trackFor("p1").get(0).time(), 1e-5f);
    }
}

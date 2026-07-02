package com.openmason.main.systems.menus.animationEditor.state;

import com.openmason.main.systems.menus.animationEditor.data.AnimationClip;
import com.openmason.main.systems.menus.animationEditor.data.Easing;
import com.openmason.main.systems.menus.animationEditor.data.Keyframe;
import com.openmason.main.systems.menus.animationEditor.data.Track;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyframeSelectionTest {

    private static Keyframe kf(float t) {
        return new Keyframe(t, new Vector3f(), new Vector3f(), new Vector3f(1, 1, 1), Easing.LINEAR);
    }

    private static AnimationClip clipWithTrack(String partId, float... times) {
        AnimationClip clip = new AnimationClip("test", 30f, 10f, true, null);
        Track track = clip.ensureTrack(partId);
        for (float t : times) {
            track.upsert(kf(t));
        }
        return clip;
    }

    @Test
    void containsUsesTolerance() {
        KeyframeSelection sel = new KeyframeSelection();
        sel.add(new KeyframeSelection.KeyRef("p1", 0.5f));
        assertTrue(sel.contains("p1", 0.5f + 5e-5f));
        assertFalse(sel.contains("p1", 0.6f));
        assertFalse(sel.contains("p2", 0.5f));
    }

    @Test
    void toggleAddsAndRemoves() {
        KeyframeSelection sel = new KeyframeSelection();
        sel.toggle(new KeyframeSelection.KeyRef("p1", 0.5f));
        assertEquals(1, sel.size());
        sel.toggle(new KeyframeSelection.KeyRef("p1", 0.5f));
        assertTrue(sel.isEmpty());
    }

    @Test
    void addDeduplicates() {
        KeyframeSelection sel = new KeyframeSelection();
        sel.add(new KeyframeSelection.KeyRef("p1", 0.5f));
        sel.add(new KeyframeSelection.KeyRef("p1", 0.5f));
        assertEquals(1, sel.size());
    }

    @Test
    void resolveSkipsMissingKeyframes() {
        AnimationClip clip = clipWithTrack("p1", 0f, 0.5f);
        KeyframeSelection sel = new KeyframeSelection();
        sel.add(new KeyframeSelection.KeyRef("p1", 0.5f));
        sel.add(new KeyframeSelection.KeyRef("p1", 0.9f));   // no such keyframe
        sel.add(new KeyframeSelection.KeyRef("ghost", 0f));  // no such track

        List<KeyframeSelection.ResolvedKey> resolved = sel.resolve(clip);
        assertEquals(1, resolved.size());
        assertEquals("p1", resolved.get(0).partId());
        assertEquals(1, resolved.get(0).index());
    }

    @Test
    void selectionSurvivesIndexShiftingInsert() {
        AnimationClip clip = clipWithTrack("p1", 0.5f);
        KeyframeSelection sel = new KeyframeSelection();
        sel.add(new KeyframeSelection.KeyRef("p1", 0.5f));

        // Insert BEFORE the selected key — its index shifts 0 -> 1.
        clip.trackFor("p1").upsert(kf(0.1f));

        List<KeyframeSelection.ResolvedKey> resolved = sel.resolve(clip);
        assertEquals(1, resolved.size());
        assertEquals(1, resolved.get(0).index());
        assertEquals(0.5f, resolved.get(0).keyframe().time(), 1e-5f);
    }

    @Test
    void retimeRepointsRef() {
        AnimationClip clip = clipWithTrack("p1", 0.8f);
        KeyframeSelection sel = new KeyframeSelection();
        sel.add(new KeyframeSelection.KeyRef("p1", 0.5f));
        sel.retime("p1", 0.5f, 0.8f);
        assertEquals(1, sel.resolve(clip).size());
        assertTrue(sel.contains("p1", 0.8f));
        assertFalse(sel.contains("p1", 0.5f));
    }
}

package com.stonebreak.ui.startupIntro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The intro's timeline and pings (#301): deterministic for a seed, ending exactly once at 4 s. */
class IntroSceneTest {

    @Test
    void theTimelineRunsSubmarineHoldFadeThenEndsOnce() {
        int[] pings = new int[1];
        IntroScene scene = IntroFixtures.scene("t0", 1920, 1080, pings);
        assertEquals(1, pings[0], "the first ring pings at once");
        int ends = 0;
        int frame = 0;
        while (frame < 300) {
            if (scene.update(IntroFixtures.DT)) {
                ends++;
                assertEquals(IntroScene.Phase.COMPLETE, scene.phase());
                // phases end on the first step at or past 1.8, 1.2 and 1.0 s of float sums: frame 242 at 60 Hz
                assertTrue(frame >= 239 && frame <= 243, "ends near 4 s (frame " + frame + ")");
            }
            frame++;
            if (frame == 150) {
                assertEquals(IntroScene.Phase.HOLD, scene.phase());
            }
        }
        assertEquals(1, ends);
        assertEquals(1f, scene.fade(), 0f);
        assertTrue(pings[0] >= 7, "a ping every 0.6 s: " + pings[0]);
    }

    @Test
    void aSeedReplaysTheSameScene() {
        IntroScene a = IntroFixtures.scene("t3-5", 1280, 720);
        IntroScene b = IntroFixtures.scene("t3-5", 1280, 720);
        assertEquals(a.reveal(), b.reveal(), 0f);
        assertEquals(a.fade(), b.fade(), 0f);
        assertEquals(IntroScene.Phase.FADE_TO_BLACK, a.phase());
    }
}

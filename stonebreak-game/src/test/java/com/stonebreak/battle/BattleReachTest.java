package com.stonebreak.battle;

import com.stonebreak.battle.stage.BattleStageLayouts;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityLoader;
import com.stonebreak.rendering.models.entities.SbePoseSolver;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the monk's fists and foot really are on the authored contact frames, measured on the shipped
 * asset through the same GL-free pose solver the renderer uses. This is what the kick stand-off is
 * derived from: a kick reaches farther than a punch by a fixed, authored amount, and the model has to
 * stand the monk exactly that much farther back or the foot goes through the Archon.
 */
class BattleReachTest {

    private static final SbeEntityAsset MONK = SbeEntityLoader.load("/sbe/Mobs/SB_Player.sbe");

    /** Forward (authored −Z) reach of the farthest-forward of the given sockets at a clip time. */
    private static float reach(BattleClip clip, float time, String... sockets) {
        float best = 0f;
        for (String socket : sockets) {
            Matrix4f m = SbePoseSolver.socketWorldMatrix(MONK, SbeEntityAsset.DEFAULT_VARIANT,
                    AnimState.single(clip.state(), time), new Matrix4f(), 0f, 0f, socket, new Matrix4f());
            assertNotNull(m, () -> "the monk asset has no socket '" + socket + "'");
            best = Math.max(best, -m.getTranslation(new Vector3f()).z);
        }
        return best;
    }

    private static float fist(BattleClip clip, float time) {
        return reach(clip, time, "punch_left", "punch_right");
    }

    private static float foot(BattleClip clip, float time) {
        return reach(clip, time, "kick_left", "kick_right");
    }

    @Test
    void everyPunchContactCueIsAFrameWithTheFistFullyOut() {
        BattleClip[] punches = {MonkClips.STRIKE, MonkClips.FLURRY, MonkClips.STUNNING_STRIKE};
        for (BattleClip clip : punches) {
            for (int cue = 0; cue < clip.cueCount(); cue++) {
                float atContact = fist(clip, clip.cue(cue));
                assertTrue(atContact >= 0.43f && atContact <= 0.60f, clip.state() + " cue " + cue + " reach " + atContact);
                // ...and that is as far as this punch ever gets: the cue is the extension, not the wind-up
                // or the recovery. (How long the arm lingers differs: a lone strike holds, a flurry jab snaps back.)
                float farthest = 0f;
                for (float t = clip.cue(cue) - 0.2f; t <= clip.cue(cue) + 0.2f; t += 0.02f) {
                    farthest = Math.max(farthest, fist(clip, t));
                }
                assertEquals(farthest, atContact, 0.03f, clip.state() + " cue " + cue);
            }
        }
        for (int cue = 0; cue < MonkClips.FOCUS_COMBO.cueCount(); cue++) {
            if (cue == MonkClips.FOCUS_COMBO_KICK_CONTACT) continue;
            float atContact = fist(MonkClips.FOCUS_COMBO, MonkClips.FOCUS_COMBO.cue(cue));
            assertTrue(atContact >= 0.43f && atContact <= 0.60f, "combo cue " + cue + " reach " + atContact);
        }
    }

    @Test
    void theKickStandoffIsTheExtraReachOfTheFoot() {
        float punch = fist(MonkClips.STRIKE, MonkClips.STRIKE.cue(0));
        float kick = foot(MonkClips.KICK, MonkClips.KICK.cue(0));
        float comboKick = foot(MonkClips.FOCUS_COMBO, MonkClips.FOCUS_COMBO.cue(MonkClips.FOCUS_COMBO_KICK_CONTACT));
        assertEquals(kick, comboKick, 0.03f, "the combo's front kick is the same kick");
        assertTrue(kick > punch + 0.25f, "a kick reaches farther than a punch: " + kick + " vs " + punch);

        // pose.recoil × the stage's recoil distance is how far back the stage stands the monk.
        float standoff = BattleConfig.Melee.DEFAULTS.kickStandoff() * BattleStageLayouts.RECOIL_DISTANCE;
        assertEquals(kick - punch, standoff, 0.06f,
                "retune Melee.kickStandoff when the stage's RECOIL_DISTANCE or the kick clip changes");
    }
}

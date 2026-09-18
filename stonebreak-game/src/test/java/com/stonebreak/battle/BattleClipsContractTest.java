package com.stonebreak.battle;

import com.stonebreak.battle.api.EnemyAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The animation contract: {@link MonkClips} / {@link ArchonClips} / {@link EnemyAction} must say
 * exactly what the artists' {@code clips.json} files say, and every state must really exist in the
 * shipped {@code .sbe} with that length and loop flag. When a clip is retimed, this is the test that
 * fails until the constants follow, which is what keeps every hit on its authored contact frame.
 */
class BattleClipsContractTest {

    private static final float TOLERANCE = 1.0e-4f;

    private static List<BattleClip> allMonkCombatClips() {
        List<BattleClip> all = new ArrayList<>(MonkClips.USED);
        all.addAll(MonkClips.UNUSED);
        return all;
    }

    private static void assertMatches(ClipCatalog.Authored authored, BattleClip clip, boolean withCues) {
        assertNotNull(authored, () -> "no authored clip named '" + clip.state() + "'");
        assertEquals(authored.duration(), clip.duration(), TOLERANCE, clip.state() + " duration");
        assertEquals(authored.loop(), clip.loop(), clip.state() + " loop flag");
        if (!withCues) return;
        assertEquals(authored.cues().size(), clip.cueCount(), clip.state() + " cue count");
        for (int i = 0; i < clip.cueCount(); i++) {
            assertEquals(authored.cues().get(i), clip.cue(i), TOLERANCE, clip.state() + " cue " + i);
        }
    }

    @Test
    void monkConstantsMatchThePlayerCombatHandoff() {
        Map<String, ClipCatalog.Authored> json = ClipCatalog.monkJson();
        assertEquals(29, json.size(), "the handoff ships 29 combat clips");
        Set<String> covered = new HashSet<>();
        for (BattleClip clip : allMonkCombatClips()) {
            assertMatches(json.get(clip.state()), clip, true);
            assertTrue(covered.add(clip.state()), () -> clip.state() + " is listed twice");
        }
        assertEquals(json.keySet(), covered, "every authored clip is either used or listed as unused");
        for (BattleClip legacy : MonkClips.LEGACY) {
            assertTrue(ClipCatalog.monkLegacyStates().contains(legacy.state()), legacy.state());
            assertFalse(json.containsKey(legacy.state()));
        }
    }

    @Test
    void archonConstantsMatchTheReactionHandoff() {
        Map<String, ClipCatalog.Authored> json = ClipCatalog.archonJson();
        assertEquals(7, json.size());
        Set<String> covered = new HashSet<>();
        for (BattleClip clip : ArchonClips.REACTIONS) {
            assertMatches(json.get(clip.state()), clip, true);
            covered.add(clip.state());
        }
        assertEquals(json.keySet(), covered);
    }

    @Test
    void enemyActionsMatchTheAuthoredAttackClips() {
        Map<String, Float> impacts = ClipCatalog.archonAttackImpacts();
        Map<String, ClipCatalog.Authored> sbe = ClipCatalog.sbeStates(ClipCatalog.ARCHON_SBE);
        assertEquals(EnemyAction.values().length, impacts.size());
        for (EnemyAction action : EnemyAction.values()) {
            assertTrue(impacts.containsKey(action.sbeState()), action.sbeState());
            assertEquals(impacts.get(action.sbeState()), action.impactTime(), TOLERANCE, action + " impact");
            BattleClip clip = ArchonClips.attack(action);
            assertMatches(sbe.get(action.sbeState()), clip, false);
            assertEquals(action.impactTime(), clip.cue(0));
            assertTrue(action.impactTime() < action.clipDuration());
        }
    }

    @Test
    void everyClipTheModelCanPlayExistsInTheShippedSbeWithTheSameLengthAndLoopFlag() {
        Map<String, ClipCatalog.Authored> monk = ClipCatalog.sbeStates(ClipCatalog.MONK_SBE);
        for (BattleClip clip : allMonkCombatClips()) assertMatches(monk.get(clip.state()), clip, false);
        for (BattleClip clip : MonkClips.LEGACY) assertMatches(monk.get(clip.state()), clip, false);

        Map<String, ClipCatalog.Authored> archon = ClipCatalog.sbeStates(ClipCatalog.ARCHON_SBE);
        for (BattleClip clip : ArchonClips.REACTIONS) assertMatches(archon.get(clip.state()), clip, false);
        for (BattleClip clip : ArchonClips.LEGACY) assertMatches(archon.get(clip.state()), clip, false);
    }

    @Test
    void theRulesTimingsSitInsideTheClipsTheyRideOn() {
        BattleConfig config = BattleConfig.defaults(100f, 10);
        assertEquals(MonkClips.PARRY.cue(0), config.support().parryContactOffset(),
                "the parry offset is the parry clip's contact cue");
        assertTrue(config.archon().parryLeadSeconds() >= config.support().parryContactOffset(),
                "a parry pressed at the very start of its window can still line its deflect up");
        assertEquals(config.combo().length(), MonkClips.FOCUS_COMBO.cueCount(), "one prompt per authored contact");
        assertEquals(config.flurry().hits(), MonkClips.FLURRY.cueCount(), "one ring per authored contact");

        // Guard reactions fit inside what is left of every attack clip, so a queued command never cuts them.
        float reaction = Math.max(MonkClips.BLOCK.duration(),
                MonkClips.PARRY.duration() - MonkClips.PARRY.cue(0)) + MonkClips.GUARD_EXIT.duration();
        for (EnemyAction action : EnemyAction.values()) {
            assertTrue(action.clipDuration() - action.impactTime() >= reaction, action + " leaves room to recover");
        }

        // Combo prompts never overlap the previous contact, and the freeze point is after the prompt opens.
        BattleConfig.Combo combo = config.combo();
        assertTrue(combo.freezeOffsetSeconds() < combo.promptLeadSeconds());
        assertTrue(combo.stepSeconds() > combo.promptLeadSeconds() - combo.freezeOffsetSeconds());
        for (int i = 1; i < MonkClips.FOCUS_COMBO.cueCount(); i++) {
            assertTrue(MonkClips.FOCUS_COMBO.cue(i) - combo.promptLeadSeconds() > MonkClips.FOCUS_COMBO.cue(i - 1),
                    "prompt " + i + " opens after contact " + (i - 1));
        }
        // Flurry rings at the shipped playback speed do not overlap either.
        BattleConfig.Flurry flurry = config.flurry();
        for (int i = 1; i < MonkClips.FLURRY.cueCount(); i++) {
            float gap = (MonkClips.FLURRY.cue(i) - MonkClips.FLURRY.cue(i - 1)) / flurry.playbackSpeed();
            assertTrue(gap > flurry.ringLeadSeconds() + flurry.goodWindowSeconds(), "ring " + i + " opens on time");
        }
    }

    @Test
    void clipTimeWrapsLoopsAndHoldsOneShotsLikeTheRenderer() {
        assertEquals(0.2f, MonkClips.COMBAT_IDLE.timeAt(3.4f), 1.0e-5f);
        assertEquals(0f, MonkClips.COMBAT_IDLE.timeAt(-1f));
        assertEquals(0.96f, MonkClips.STRIKE.timeAt(5f));
        assertEquals(0.5f, MonkClips.STRIKE.timeAt(0.5f));
    }
}

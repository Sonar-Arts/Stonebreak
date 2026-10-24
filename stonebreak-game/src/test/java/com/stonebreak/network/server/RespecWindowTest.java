package com.stonebreak.network.server;

import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.network.packet.entity.EntityDamageC2S;
import com.stonebreak.network.packet.player.PlayerPrefsC2S;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.feats.FeatDefinition;
import com.stonebreak.world.save.model.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Issue #421: refunds reach back only to the committed {@link SpendFloor}, and a feat must be
 * available at the server's level. The respec window opens with a provisional report and
 * closes (committing) with a closing report or any combat intent.
 */
class RespecWindowTest {

    /** Level 3 (grants 3 CP / 5 SP / 3 FP): 2 CP, 2 skill levels and one feat spent. */
    private static PlayerData baseline() {
        return PlayerData.builder()
                .level(3)
                .xp(40)
                .remainingCp(1)
                .remainingSp(3)
                .remainingFp(2)
                .remainingAp(5)
                .abilityScores(new int[]{12, 10, 11, 10, 10, 10})
                .classInvestmentOrder(java.util.List.of("berserker"))
                .spentAbilityCp(Map.of("berserker:p0:0", 2))
                .skillLevels(Map.of("mining", 2))
                .acquiredFeatIds(Set.of("double_jump"))
                .build();
    }

    /** A registry with a level-1, a level-3 and a level-5 feat. */
    private static final Function<String, Optional<FeatDefinition>> FEATS = id -> switch (id) {
        case "double_jump" -> Optional.of(new FeatDefinition(id, "Double Jump", "", 1, true));
        case "tough" -> Optional.of(new FeatDefinition(id, "Tough", "", 3, true));
        case "titan" -> Optional.of(new FeatDefinition(id, "Titan", "", 5, true));
        default -> Optional.empty();
    };

    private static ServerPlayer seeded(PlayerData baseline) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), 7);
        sp.acceptBaseline(baseline);
        return sp;
    }

    /** What {@code IntegratedServer.judgePlayerPrefs} does with an accepted report. */
    private static PlayerData report(ServerPlayer sp, boolean provisional, PlayerData candidate) {
        PlayerData[] accepted = new PlayerData[1];
        IntegratedServer.withRespecWindow(sp, provisional, () -> {
            accepted[0] = PlayerPrefsValidator.normalize(sp.serverStats(), sp.progressionBaseline(),
                    sp.spendFloor(), candidate, FEATS);
            if (accepted[0] != null) {
                sp.acceptBaseline(accepted[0]);
            }
        });
        return accepted[0];
    }

    // ── The floor ──────────────────────────────────────────────────────────────

    @Test
    void aCommittedSpendCannotBeRefunded() {
        ServerPlayer sp = seeded(baseline());
        PlayerData respec = new PlayerData.Builder(baseline())
                .classInvestmentOrder(java.util.List.of("ranger"))
                .spentAbilityCp(Map.of("ranger:p0:0", 2))   // move both committed CP elsewhere
                .build();
        assertNull(report(sp, false, respec), "a closing report below the floor");
        assertNull(report(sp, true, respec), "opening a window does not lower the floor");
    }

    @Test
    void everyKindOfCommittedSpendIsHeld() {
        ServerPlayer sp = seeded(baseline());
        assertNull(report(sp, true, new PlayerData.Builder(baseline())
                .skillLevels(Map.of("mining", 1)).build()), "skill level");
        assertNull(report(sp, true, new PlayerData.Builder(baseline())
                .acquiredFeatIds(Set.of()).build()), "feat");
        assertNull(report(sp, true, new PlayerData.Builder(baseline())
                .abilityScores(new int[]{10, 12, 11, 10, 10, 10}).build()), "moved score raise");
    }

    @Test
    void aSessionSpendCanBeUndoneUntilTheWindowCloses() {
        ServerPlayer sp = seeded(baseline());
        PlayerData spent = new PlayerData.Builder(baseline())
                .spentAbilityCp(Map.of("berserker:p0:0", 3))
                .build();
        assertNotNull(report(sp, true, spent), "provisional spend");
        assertTrue(sp.respecWindowOpen());

        // Discard: the closing report carries the pre-session ledger again.
        PlayerData discarded = report(sp, false, baseline());
        assertNotNull(discarded, "an undo down to the floor is accepted");
        assertEquals(1, discarded.getRemainingCp());
        assertFalse(sp.respecWindowOpen());
    }

    @Test
    void aClosingReportCommitsTheSession() {
        ServerPlayer sp = seeded(baseline());
        PlayerData spent = new PlayerData.Builder(baseline())
                .spentAbilityCp(Map.of("berserker:p0:0", 3))
                .build();
        assertNotNull(report(sp, true, spent));
        assertNotNull(report(sp, false, spent), "Save: the closing report keeps the spend");
        assertNull(report(sp, true, baseline()), "the saved spend is committed now");
    }

    @Test
    void combatCommitsAnOpenWindow() {
        // A modified client that never sends a closing report cannot keep its spends
        // refundable: the first attack commits them.
        ServerPlayer sp = seeded(baseline());
        PlayerData spent = new PlayerData.Builder(baseline())
                .spentAbilityCp(Map.of("berserker:p0:0", 3))
                .build();
        assertNotNull(report(sp, true, spent));

        assertTrue(IntegratedServer.isCombatIntent(new EntityDamageC2S(1, 2f, (byte) 0, (byte) 0, (byte) 0)));
        sp.commitSpends(); // what routePlay does for that intent

        assertFalse(sp.respecWindowOpen());
        assertNull(report(sp, true, baseline()), "the fought-with spend is committed");
    }

    @Test
    void aRefusedClosingReportStillClosesTheWindow() {
        ServerPlayer sp = seeded(baseline());
        PlayerData spent = new PlayerData.Builder(baseline())
                .spentAbilityCp(Map.of("berserker:p0:0", 3))
                .build();
        assertNotNull(report(sp, true, spent));
        PlayerData overspent = new PlayerData.Builder(baseline())
                .spentAbilityCp(Map.of("berserker:p0:0", 9))
                .build();
        assertNull(report(sp, false, overspent));
        assertFalse(sp.respecWindowOpen());
        assertNull(report(sp, true, baseline()), "the accepted session spend is committed");
    }

    @Test
    void onlyCombatPacketsAreCombatIntents() {
        assertFalse(IntegratedServer.isCombatIntent(new PlayerPrefsC2S(1, true, "{}")));
        assertTrue(IntegratedServer.isCombatIntent(new com.stonebreak.network.packet.entity.DecoyCastC2S()));
        // A lingering zone re-forwards status effects with no input, even with the sheet open.
        assertFalse(IntegratedServer.isCombatIntent(
                new com.stonebreak.network.packet.entity.EntityStatusEffectC2S(1, (byte) 0, 1f, 1f)));
    }

    // ── Feat level ─────────────────────────────────────────────────────────────

    @Test
    void aFeatAboveTheServersLevelIsRefused() {
        ServerPlayer sp = seeded(baseline()); // server level 3
        PlayerData titan = new PlayerData.Builder(baseline())
                .level(99)                        // the claimed level is never trusted
                .acquiredFeatIds(Set.of("double_jump", "titan"))
                .build();
        assertNull(report(sp, true, titan));
    }

    @Test
    void aFeatAtTheServersLevelIsAccepted() {
        ServerPlayer sp = seeded(baseline());
        PlayerData tough = new PlayerData.Builder(baseline())
                .acquiredFeatIds(Set.of("double_jump", "tough"))
                .build();
        PlayerData accepted = report(sp, true, tough);
        assertNotNull(accepted);
        assertEquals(1, accepted.getRemainingFeatPoints());
    }

    @Test
    void aHeldFeatAboveTheLevelIsKept() {
        // A save from before the level rule: the feat stays and the report still passes.
        PlayerData legacy = new PlayerData.Builder(baseline())
                .acquiredFeatIds(Set.of("double_jump", "titan"))
                .remainingFp(1)
                .build();
        ServerPlayer sp = seeded(legacy);
        assertNotNull(report(sp, false, legacy));
    }

    @Test
    void creationRefusesAFeatAboveLevelOne() {
        PlayerData creation = PlayerData.builder()
                .remainingAp(27)
                .abilityScores(new int[]{10, 10, 10, 10, 10, 10})
                .acquiredFeatIds(Set.of("tough"))
                .build();
        assertNull(PlayerPrefsValidator.normalizeCreation(creation, FEATS));
        PlayerData levelOne = new PlayerData.Builder(creation)
                .acquiredFeatIds(Set.of("double_jump"))
                .build();
        assertNotNull(PlayerPrefsValidator.normalizeCreation(levelOne, FEATS));
    }

    @Test
    void theClientGatesFeatsOnLevelToo() {
        CharacterStats cs = new CharacterStats(null);
        assertTrue(cs.canAcquireFeat("double_jump"));
        assertFalse(cs.canAcquireFeat("not-a-feat"));
        cs.acquireFeat("double_jump");
        assertTrue(cs.hasFeat("double_jump"));
        assertFalse(cs.canAcquireFeat("double_jump"), "already held");
    }
}

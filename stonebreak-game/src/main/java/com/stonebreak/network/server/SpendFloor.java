package com.stonebreak.network.server;

import com.stonebreak.world.save.model.PlayerData;

import java.util.Map;
import java.util.Set;

/**
 * The COMMITTED part of a player's spend ledger (issue #421): what a prefs report may never
 * go below. The validator's conservation rules let a report shrink a spend (the character
 * sheet's save-or-discard undo), but only down to this floor, so only spends made since the
 * last commit can be refunded. Once committed, CP, skill levels, feats and ability-score
 * raises stay put.
 *
 * <p>{@link ServerPlayer} owns the floor and decides when it moves: every accepted report
 * outside an open respec window, the report that closes a window, and any combat intent
 * (a client that never closes its window cannot keep its spends refundable mid-fight).
 *
 * @param abilityCp committed CP per ability key
 * @param skills    committed level per skill id
 * @param feats     committed feat ids
 * @param scores    committed ability scores (six entries), or null for no score floor
 */
public record SpendFloor(Map<String, Integer> abilityCp, Map<String, Integer> skills,
                         Set<String> feats, int[] scores) {

    /** No floor: every refund passes. Character creation and tests only. */
    public static final SpendFloor NONE = new SpendFloor(Map.of(), Map.of(), Set.of(), null);

    public SpendFloor {
        abilityCp = Map.copyOf(abilityCp);
        skills = Map.copyOf(skills);
        feats = Set.copyOf(feats);
        scores = scores == null ? null : scores.clone();
    }

    /** The floor that commits every spend in {@code accepted}. */
    public static SpendFloor of(PlayerData accepted) {
        return new SpendFloor(withoutNulls(accepted.getSpentAbilityCp()),
                withoutNulls(accepted.getSkillLevels()),
                accepted.getAcquiredFeatIds().stream().filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet()),
                accepted.getAbilityScores());
    }

    /** True when {@code candidate} keeps every committed spend (it may add to them). */
    public boolean admits(PlayerData candidate) {
        return covers(candidate.getSpentAbilityCp(), abilityCp)
                && covers(candidate.getSkillLevels(), skills)
                && candidate.getAcquiredFeatIds().containsAll(feats)
                && scoresCover(candidate.getAbilityScores());
    }

    private static boolean covers(Map<String, Integer> ledger, Map<String, Integer> floor) {
        for (Map.Entry<String, Integer> e : floor.entrySet()) {
            Integer v = ledger.get(e.getKey());
            if ((v == null ? 0 : v) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    private boolean scoresCover(int[] candidate) {
        if (scores == null) {
            return true;
        }
        if (candidate == null || candidate.length != scores.length) {
            return false;
        }
        for (int i = 0; i < scores.length; i++) {
            if (candidate[i] < scores[i]) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Integer> withoutNulls(Map<String, Integer> ledger) {
        Map<String, Integer> out = new java.util.HashMap<>();
        ledger.forEach((k, v) -> {
            if (k != null && v != null) out.put(k, v);
        });
        return out;
    }
}

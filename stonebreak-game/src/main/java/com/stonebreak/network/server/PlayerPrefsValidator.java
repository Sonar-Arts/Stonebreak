package com.stonebreak.network.server;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.backgrounds.BackgroundRegistry;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.serialization.JsonPlayerSerializer;

import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes a client-submitted {@code PlayerPrefsC2S} payload (parsed to a
 * {@link PlayerData} candidate) into the server-accepted state (issue #334).
 *
 * <p>The server OWNS the earned progression: level and xp live in the server-side
 * {@link CharacterStats} and advance only through server-granted kill XP, so the
 * candidate's level/xp fields are overwritten, never trusted. The four point pools are
 * SERVER-held too (seeded from the accepted baseline, grown only by server level-up
 * grants — legacy saves keep their larger stored pools). What the client may choose is
 * its SPEND ledger — abilities, skills, feats and ability scores — validated by
 * conservation against the server-held state:
 * <ul>
 *   <li><b>CP/SP/FP</b> — the new pool is the held pool plus the ledger delta; shrinking
 *       a spend (the character sheet's save-or-discard undo) refunds it, a spend beyond
 *       the held pool is refused. Level/xp in the candidate are ignored.</li>
 *   <li><b>AP/scores</b> — a raise is paid out of the server-held remaining AP and an
 *       un-done raise refunds it; {@code sum(scores) + remainingAp} can never exceed its
 *       creation-time ceiling, so refunds can never conjure points.</li>
 *   <li><b>Cosmetics</b> — statistics stay monotone and discoveries append-only
 *       (leaderboard/glossary hygiene only; they cannot mint items).</li>
 * </ul>
 */
public final class PlayerPrefsValidator {

    /**
     * Ceiling of {@code sum(abilityScores) + remainingAp}: the {@link #CREATION_BUDGET} of 87
     * plus headroom for the background bonus the server applies at creation (net at most +1
     * in {@code BackgroundRegistry}, plus the floor-at-1 rounding of a negative bonus).
     * Normalization conserves each player's own total exactly; this only bounds it.
     */
    static final int MAX_SCORE_BUDGET = 90;

    /** A fresh character's point-buy budget before any background: 6 × 10 base + 27 AP. */
    static final int CREATION_BUDGET = 87;

    /** Highest single ability score any state under {@link #MAX_SCORE_BUDGET} can hold
     *  (the other five at their floor of 1). Bounds every score before it is summed. */
    static final int MAX_ABILITY_SCORE = MAX_SCORE_BUDGET - 5;

    /**
     * Size bounds on the client-controlled collections (issue #334). The server
     * re-serializes the accepted state for refusals and rejoin restores, and that payload
     * is capped at {@code PlayerDataS2C.MAX_PLAYER_BYTES} — a client padding a map with
     * thousands of small keys would make the accepted state unsendable.
     */
    static final int MAX_LEDGER_ENTRIES = 64;
    static final int MAX_COSMETIC_ENTRIES = 128;
    static final int MAX_VARIANTS_PER_TYPE = 64;
    static final int MAX_KEY_LENGTH = 64;

    /** Ceiling of the accepted record's serialized size: leaves headroom under the 64 KB
     *  {@code PlayerDataS2C} cap for the server-built inventory and item states. */
    static final int MAX_ACCEPTED_BYTES = 32 * 1024;

    private PlayerPrefsValidator() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Validates and normalizes a progression candidate against the server's own state.
     *
     * @param serverStats the server's CharacterStats, kept in lockstep with the accepted
     *                    baseline ({@link ServerPlayer#acceptBaseline})
     * @param baseline    the last accepted state (cosmetic counters compare against it)
     * @param candidate   the parsed client report
     * @return the candidate with every server-owned field (level, xp, the four remaining
     *         pools) overwritten from server state, or null when the candidate is refused
     */
    public static PlayerData normalize(CharacterStats serverStats, PlayerData baseline,
                                       PlayerData candidate) {
        if (serverStats == null || baseline == null || candidate == null) {
            return null;
        }
        int level = serverStats.getLevel();

        // ── CP/SP/FP: conservation over the SERVER-held pools ───────────────────
        // The candidate may re-arrange its spend ledger (undo included); the pool is the
        // held pool plus the ledger delta, so a spend can never exceed what the server
        // granted and a shrunk ledger simply refunds.
        // Sums run in long and every value is bounded by the pool the player OWNS (held
        // remaining + accepted spends): a plain int sum of client values could wrap and
        // mint points (e.g. two Integer.MAX_VALUE spends sum to -2).
        if (!classIdValid(candidate.getSelectedClassId())) return null;

        long acceptedCp = sum(serverStats.getSpentAbilityCp());
        long ownedCp = serverStats.getRemainingCp() + acceptedCp;
        long spentCp = boundedSum(candidate.getSpentAbilityCp(), ownedCp, MAX_LEDGER_ENTRIES);
        if (spentCp < 0) return null;
        long newCp = ownedCp - spentCp;
        if (newCp < 0) return null;

        long acceptedSkills = sum(serverStats.getSkillLevels());
        long ownedSp = serverStats.getRemainingSkillPoints() + acceptedSkills;
        long skillSum = boundedSum(candidate.getSkillLevels(), ownedSp, MAX_LEDGER_ENTRIES);
        if (skillSum < 0) return null;
        long newSp = ownedSp - skillSum;
        if (newSp < 0) return null;

        Set<String> feats = candidate.getAcquiredFeatIds();
        if (feats.size() > MAX_LEDGER_ENTRIES) return null;
        for (String featId : feats) {
            if (FeatRegistry.ALL.stream().noneMatch(f -> f.id().equals(featId))) return null;
        }
        long newFp = (long) serverStats.getRemainingFeatPoints()
                + serverStats.getAcquiredFeatIds().size() - feats.size();
        if (newFp < 0) return null;

        // ── AP/scores: raises paid from the server-held pool, refunds return it ─
        long sumNew = scoreSum(candidate.getAbilityScores());
        if (sumNew < 0) return null;
        long sumAccepted = 0;
        for (int s : serverStats.getAbilityScores()) {
            sumAccepted += s;
        }
        long newAp = serverStats.getRemainingAp() - (sumNew - sumAccepted);
        if (newAp < 0 || sumNew + newAp > MAX_SCORE_BUDGET) return null;

        // ── Cosmetics: monotone stats, append-only discoveries ──────────────────
        if (candidate.getStatEntitiesKilled() < baseline.getStatEntitiesKilled()
                || candidate.getStatDamageDealt() < baseline.getStatDamageDealt()
                || candidate.getStatTotalDistance() < baseline.getStatTotalDistance()
                || candidate.getStatDistanceWalked() < baseline.getStatDistanceWalked()
                || candidate.getStatDistanceSprinted() < baseline.getStatDistanceSprinted()
                || candidate.getStatDistanceInAir() < baseline.getStatDistanceInAir()
                || candidate.getStatTimeInAir() < baseline.getStatTimeInAir()) {
            return null;
        }
        // Append-only maps MERGE: a key the candidate omits keeps its accepted value, so
        // leaving a whole entity type out of a report can never drop recorded data.
        Map<String, Long> kills = new HashMap<>(baseline.getStatKillsByEntityType());
        if (candidate.getStatKillsByEntityType().size() > MAX_COSMETIC_ENTRIES) return null;
        for (Map.Entry<String, Long> e : candidate.getStatKillsByEntityType().entrySet()) {
            Long old = kills.get(e.getKey());
            if (e.getValue() == null || e.getValue() < 0 || (old != null && e.getValue() < old)
                    || !keyValid(e.getKey())) {
                return null;
            }
            kills.put(e.getKey(), e.getValue());
        }
        Map<String, Set<String>> variants = new HashMap<>(baseline.getDiscoveredVariantsByEntityType());
        if (candidate.getDiscoveredVariantsByEntityType().size() > MAX_COSMETIC_ENTRIES) return null;
        for (Map.Entry<String, Set<String>> e
                : candidate.getDiscoveredVariantsByEntityType().entrySet()) {
            Set<String> old = variants.get(e.getKey());
            if (!keyValid(e.getKey()) || e.getValue().size() > MAX_VARIANTS_PER_TYPE
                    || (old != null && !e.getValue().containsAll(old))) {
                return null;
            }
            for (String v : e.getValue()) {
                if (!keyValid(v)) return null;
            }
            variants.put(e.getKey(), e.getValue());
        }
        if (kills.size() > MAX_COSMETIC_ENTRIES || variants.size() > MAX_COSMETIC_ENTRIES) {
            return null;
        }
        Set<String> weaknesses = candidate.getDiscoveredWeaknessEntityTypes();
        if (weaknesses.size() > MAX_COSMETIC_ENTRIES
                || !weaknesses.containsAll(baseline.getDiscoveredWeaknessEntityTypes())) {
            return null;
        }
        for (String w : weaknesses) {
            if (!keyValid(w)) return null;
        }

        PlayerData accepted = new PlayerData.Builder(candidate)
                .level(level)
                .xp(serverStats.getXp())
                .remainingCp((int) newCp)
                .remainingSp((int) newSp)
                .remainingFp((int) newFp)
                .remainingAp((int) newAp)
                .statKillsByEntityType(kills)
                .discoveredVariantsByEntityType(variants)
                // The server owns the inventory: never carry the client's claimed slots
                // (or their free-form item states) in the accepted record.
                .inventory(baseline.getInventory())
                .selectedHotbarSlot(baseline.getSelectedHotbarSlot())
                .build();
        if (new JsonPlayerSerializer().serialize(accepted).length > MAX_ACCEPTED_BYTES) {
            return null;
        }
        return accepted;
    }

    /**
     * Normalizes a freshly parsed CHARACTER-CREATION blob (issue #334). Level/xp are
     * reset to a level-1 character and the CP/SP/FP pools are recomputed from the
     * level-1 grants minus the submitted spends; the claimed remainingAp is kept (bounded to
     * 0..{@code CREATION_BUDGET - 6} here and by the score budget; {@link #freshCharacter}
     * then holds scores + AP to {@link #CREATION_BUDGET}).
     *
     * @return the normalized creation state, or null when a spend exceeds its grant
     */
    public static PlayerData normalizeCreation(PlayerData candidate) {
        if (candidate == null) {
            return null;
        }
        if (!classIdValid(candidate.getSelectedClassId())) return null;
        long spentCp = boundedSum(candidate.getSpentAbilityCp(), CharacterStats.grantedCp(1),
                MAX_LEDGER_ENTRIES);
        long skillSum = boundedSum(candidate.getSkillLevels(), CharacterStats.grantedSp(1),
                MAX_LEDGER_ENTRIES);
        if (spentCp < 0 || skillSum < 0) return null;
        Set<String> feats = candidate.getAcquiredFeatIds();
        for (String featId : feats) {
            if (FeatRegistry.ALL.stream().noneMatch(f -> f.id().equals(featId))) return null;
        }
        if (spentCp > CharacterStats.grantedCp(1)
                || skillSum > CharacterStats.grantedSp(1)
                || feats.size() > CharacterStats.grantedFp(1)) {
            return null;
        }
        long sum = scoreSum(candidate.getAbilityScores());
        if (sum < 0) return null;
        // A point-buy may trade scores below 10 for AP, so the pool can legitimately exceed 27.
        int ap = Math.max(0, Math.min(CREATION_BUDGET - 6, candidate.getRemainingAp()));
        if (sum + ap > MAX_SCORE_BUDGET) return null;

        return new PlayerData.Builder(candidate)
                .level(1)
                .xp(0)
                .remainingCp(CharacterStats.grantedCp(1) - (int) spentCp)
                .remainingSp(CharacterStats.grantedSp(1) - (int) skillSum)
                .remainingFp(CharacterStats.grantedFp(1) - feats.size())
                .remainingAp(ap)
                .build();
    }

    /**
     * The record a brand-new character legitimately starts with (issue #334): the level-1
     * grants as pools, the validated creation spends and point-buy, the background's ability
     * bonuses applied ONCE here on the server (the client applies the same bonuses to its
     * own mirror) and the player placed at {@code spawn}. A null creation (no or refused
     * creation data) is the untouched default character. Every fresh-player path — late-join
     * creation, a new host world, and every fallback — seeds from this, never from
     * {@code PlayerData.createDefault}'s legacy 100-point pools.
     *
     * @param creation     a {@link #normalizeCreation normalized} creation record, or null
     * @param backgroundId the chosen background id (unknown/null → no bonus)
     * @return the fresh record, or null when the creation's point-buy exceeds the
     *         pre-background budget (a bonus on top would then break the score ceiling)
     */
    public static PlayerData freshCharacter(PlayerData creation, String backgroundId,
                                            String worldName, Vector3f spawn) {
        PlayerData base = creation != null ? creation : PlayerData.builder()
                .remainingCp(CharacterStats.grantedCp(1))
                .remainingSp(CharacterStats.grantedSp(1))
                .remainingFp(CharacterStats.grantedFp(1))
                .build();
        int[] scores = base.getAbilityScores();
        long sum = scoreSum(scores);
        if (sum < 0 || sum + base.getRemainingAp() > CREATION_BUDGET) {
            return null;
        }
        int[] bonuses = BackgroundRegistry.findById(backgroundId)
                .map(b -> b.abilityScoreBonuses())
                .orElse(null);
        PlayerData.Builder b = new PlayerData.Builder(base)
                .level(1)
                .xp(0)
                .abilityScores(CharacterStats.withBackgroundBonuses(scores, bonuses))
                .worldName(worldName)
                .inventory(new com.stonebreak.items.ItemStack[36])
                .selectedHotbarSlot(0)
                .health(20.0f)
                .statKillsByEntityType(Map.of())
                .discoveredVariantsByEntityType(Map.of())
                .discoveredWeaknessEntityTypes(Set.of())
                .stats(new com.stonebreak.player.PlayerStats());
        if (spawn != null) {
            b.position(new Vector3f(spawn)).rotation(new org.joml.Vector2f(0, 0));
        }
        return b.build();
    }

    /** True for "no class" or a class the registry knows. */
    static boolean classIdValid(String classId) {
        return classId == null || ClassRegistry.findById(classId).isPresent();
    }

    private static boolean keyValid(String key) {
        return key != null && !key.isEmpty() && key.length() <= MAX_KEY_LENGTH;
    }

    /** Sum of trusted (server-held) ledger values. */
    private static long sum(Map<String, Integer> ledger) {
        long total = 0;
        for (Integer v : ledger.values()) {
            total += v == null ? 0 : v;
        }
        return total;
    }

    /**
     * Sum of a client-reported ledger in long, or -1 when any entry is null/negative/over
     * {@code maxPerValue}, a key is malformed, or the ledger has too many entries.
     */
    private static long boundedSum(Map<String, Integer> ledger, long maxPerValue, int maxEntries) {
        if (ledger.size() > maxEntries) return -1;
        long total = 0;
        for (Map.Entry<String, Integer> e : ledger.entrySet()) {
            Integer v = e.getValue();
            if (v == null || v < 0 || v > maxPerValue || !keyValid(e.getKey())) return -1;
            total += v;
        }
        return total;
    }

    /** Sum of six scores each within 1..{@link #MAX_ABILITY_SCORE}, else -1. */
    private static long scoreSum(int[] scores) {
        if (scores == null || scores.length != 6) return -1;
        long total = 0;
        for (int s : scores) {
            if (s < 1 || s > MAX_ABILITY_SCORE) return -1;
            total += s;
        }
        return total;
    }

    /**
     * Clamps a client-reported health value to the character's CON-derived max (issue
     * #334). Health is locally simulated, so it stays client-reported, but the raw
     * 0..1024 setter clamp would otherwise let a modified client report god-mode health
     * through the same prefs channel this issue closes.
     */
    public static float acceptedHealth(CharacterStats serverStats, float reportedHealth) {
        float maxHp = serverStats.computeMaxHealth();
        return Math.max(0f, Math.min(reportedHealth, maxHp));
    }
}

package com.stonebreak.network.server;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.world.save.model.PlayerData;

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
     * Ceiling of {@code sum(abilityScores) + remainingAp}: 60 base points + 27 AP + the
     * largest net background bonus ({@code BackgroundRegistry}: mercenary +3). Creation
     * validation enforces it; normalization keeps every later state under it, so an
     * un-spend cycle can redistribute but never grow the total budget.
     */
    static final int MAX_SCORE_BUDGET = 90;

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
        int spentCp = 0;
        for (Integer v : candidate.getSpentAbilityCp().values()) {
            if (v == null || v < 0) return null;
            spentCp += v;
        }
        int acceptedCp = 0;
        for (Integer v : serverStats.getSpentAbilityCp().values()) {
            acceptedCp += v;
        }
        int newCp = serverStats.getRemainingCp() + acceptedCp - spentCp;
        if (newCp < 0) return null;

        int skillSum = 0;
        for (Map.Entry<String, Integer> e : candidate.getSkillLevels().entrySet()) {
            if (e.getValue() == null || e.getValue() < 0 || e.getValue() > 1000) return null;
            skillSum += e.getValue();
        }
        int acceptedSkills = 0;
        for (Integer v : serverStats.getSkillLevels().values()) {
            acceptedSkills += v;
        }
        int newSp = serverStats.getRemainingSkillPoints() + acceptedSkills - skillSum;
        if (newSp < 0) return null;

        Set<String> feats = candidate.getAcquiredFeatIds();
        for (String featId : feats) {
            if (FeatRegistry.ALL.stream().noneMatch(f -> f.id().equals(featId))) return null;
        }
        int newFp = serverStats.getRemainingFeatPoints()
                + serverStats.getAcquiredFeatIds().size() - feats.size();
        if (newFp < 0) return null;

        // ── AP/scores: raises paid from the server-held pool, refunds return it ─
        int[] scores = candidate.getAbilityScores();
        int sumNew = 0;
        for (int s : scores) {
            // Lower bound only: a single score can legitimately reach 10 + 27 AP + a
            // background bonus, and the sum budget check below bounds every total.
            if (s < 1) return null;
            sumNew += s;
        }
        int sumAccepted = 0;
        for (int s : serverStats.getAbilityScores()) {
            sumAccepted += s;
        }
        int newAp = serverStats.getRemainingAp() - (sumNew - sumAccepted);
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
        for (Map.Entry<String, Long> e : candidate.getStatKillsByEntityType().entrySet()) {
            Long old = baseline.getStatKillsByEntityType().get(e.getKey());
            if (e.getValue() < 0 || (old != null && e.getValue() < old)) {
                return null;
            }
        }
        for (Map.Entry<String, Set<String>> e
                : candidate.getDiscoveredVariantsByEntityType().entrySet()) {
            Set<String> old = baseline.getDiscoveredVariantsByEntityType().get(e.getKey());
            if (old != null && !e.getValue().containsAll(old)) {
                return null;
            }
        }
        if (!candidate.getDiscoveredWeaknessEntityTypes()
                .containsAll(baseline.getDiscoveredWeaknessEntityTypes())) {
            return null;
        }

        return new PlayerData.Builder(candidate)
                .level(level)
                .xp(serverStats.getXp())
                .remainingCp(newCp)
                .remainingSp(newSp)
                .remainingFp(newFp)
                .remainingAp(newAp)
                .build();
    }

    /**
     * Normalizes a freshly parsed CHARACTER-CREATION blob (issue #334). Level/xp are
     * reset to a level-1 character and the CP/SP/FP pools are recomputed from the
     * level-1 grants minus the submitted spends; the claimed remainingAp is kept (the
     * creation validator bounds it to 0-27 and the score budget to {@link #MAX_SCORE_BUDGET}).
     *
     * @return the normalized creation state, or null when a spend exceeds its grant
     */
    public static PlayerData normalizeCreation(PlayerData candidate) {
        if (candidate == null) {
            return null;
        }
        int spentCp = 0;
        for (Integer v : candidate.getSpentAbilityCp().values()) {
            if (v == null || v < 0) return null;
            spentCp += v;
        }
        int skillSum = 0;
        for (Map.Entry<String, Integer> e : candidate.getSkillLevels().entrySet()) {
            if (e.getValue() == null || e.getValue() < 0 || e.getValue() > 1000) return null;
            skillSum += e.getValue();
        }
        Set<String> feats = candidate.getAcquiredFeatIds();
        for (String featId : feats) {
            if (FeatRegistry.ALL.stream().noneMatch(f -> f.id().equals(featId))) return null;
        }
        if (spentCp > CharacterStats.grantedCp(1)
                || skillSum > CharacterStats.grantedSp(1)
                || feats.size() > CharacterStats.grantedFp(1)) {
            return null;
        }
        int[] scores = candidate.getAbilityScores();
        int sum = 0;
        for (int s : scores) {
            if (s < 1) return null;
            sum += s;
        }
        int ap = Math.max(0, Math.min(27, candidate.getRemainingAp()));
        if (sum + ap > MAX_SCORE_BUDGET) return null;

        return new PlayerData.Builder(candidate)
                .level(1)
                .xp(0)
                .remainingCp(CharacterStats.grantedCp(1) - spentCp)
                .remainingSp(CharacterStats.grantedSp(1) - skillSum)
                .remainingFp(CharacterStats.grantedFp(1) - feats.size())
                .remainingAp(ap)
                .build();
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

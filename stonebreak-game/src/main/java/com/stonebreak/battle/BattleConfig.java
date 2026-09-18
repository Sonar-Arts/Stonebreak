package com.stonebreak.battle;

/**
 * Tuning values for one encounter. Every number the battle model uses lives here, grouped by topic,
 * so balance passes never touch rule code. The one exception is animation timing: clip lengths and
 * cue times are the artists' and live in {@link MonkClips} / {@link ArchonClips}; the knobs here only
 * say how the rules sit AROUND those cues (leads, windows, playback speed). {@link #defaults(float, int)} is the production entry
 * point (the stage coordinator calls it); tests swap single groups through the {@code with*} copies.
 *
 * @param monkMaxHp     the player's max health (from CharacterStats), used as the monk's battle HP
 * @param monkDexterity the player's DEX score, drives the monk's ATB fill speed
 */
public record BattleConfig(float monkMaxHp, int monkDexterity, Atb atb, Resources resources, Damage damage,
                           Melee melee, Flurry flurry, Combo combo, Support support, Archon archon) {

    public static final String MONK_NAME = "Monk";

    public BattleConfig {
        monkMaxHp = Float.isFinite(monkMaxHp) && monkMaxHp > 0f ? monkMaxHp : 1f;
    }

    /** Default tuning for the given player numbers. */
    public BattleConfig(float monkMaxHp, int monkDexterity) {
        this(monkMaxHp, monkDexterity, Atb.DEFAULTS, Resources.DEFAULTS, Damage.DEFAULTS, Melee.DEFAULTS,
                Flurry.DEFAULTS, Combo.DEFAULTS, Support.DEFAULTS, Archon.DEFAULTS);
    }

    /**
     * @param monkMaxHp     the player's max health (from CharacterStats), used as the monk's battle HP
     * @param monkDexterity the player's DEX score, drives the monk's ATB fill speed
     */
    public static BattleConfig defaults(float monkMaxHp, int monkDexterity) {
        return new BattleConfig(monkMaxHp, monkDexterity);
    }

    /** ATB fill multiplier from DEX alone (before Haste / Chilled). */
    public float dexteritySpeed() {
        float raw = 1f + atb.speedPerDexPoint() * (monkDexterity - atb.baselineDex());
        return Math.max(atb.minDexSpeed(), Math.min(atb.maxDexSpeed(), raw));
    }

    public BattleConfig withAtb(Atb v) {
        return new BattleConfig(monkMaxHp, monkDexterity, v, resources, damage, melee, flurry, combo, support, archon);
    }

    public BattleConfig withResources(Resources v) {
        return new BattleConfig(monkMaxHp, monkDexterity, atb, v, damage, melee, flurry, combo, support, archon);
    }

    public BattleConfig withDamage(Damage v) {
        return new BattleConfig(monkMaxHp, monkDexterity, atb, resources, v, melee, flurry, combo, support, archon);
    }

    public BattleConfig withFlurry(Flurry v) {
        return new BattleConfig(monkMaxHp, monkDexterity, atb, resources, damage, melee, v, combo, support, archon);
    }

    public BattleConfig withCombo(Combo v) {
        return new BattleConfig(monkMaxHp, monkDexterity, atb, resources, damage, melee, flurry, v, support, archon);
    }

    public BattleConfig withArchon(Archon v) {
        return new BattleConfig(monkMaxHp, monkDexterity, atb, resources, damage, melee, flurry, combo, support, v);
    }

    /**
     * ATB gauges.
     *
     * @param monkFillSeconds   empty-to-full time at {@code baselineDex}
     * @param speedPerDexPoint  fill-speed change per DEX point away from the baseline
     */
    public record Atb(float monkFillSeconds, int baselineDex, float speedPerDexPoint, float minDexSpeed,
                      float maxDexSpeed, float hasteMultiplier, float chilledMultiplier, float archonFillSeconds,
                      float monkInitial, float archonInitial) {
        public static final Atb DEFAULTS = // Playtest tuning: 4.0 s / 5.5 s felt frantic; a turn now takes about as long as FF7's at normal
        // battle speed, which leaves time to read the telegraph and weigh holding a full gauge for a Guard.
        new Atb(7.0f, 10, 0.03f, 0.6f, 1.6f, 1.5f, 0.7f, 9.5f, 0.5f, 0.2f);
    }

    /** Qi, Focus and Meditate charges. */
    public record Resources(int maxQi, int startQi, int qiPerTurn, float maxFocus, int meditateCharges,
                            float focusPerStrikeHit, float focusPerFlurryHit, float focusFlurryPerfectBonus,
                            float focusFlurryGoodBonus, float focusOnParry, float focusOnBlock,
                            float focusPerDamageTaken) {
        public static final Resources DEFAULTS = new Resources(5, 2, 1, 100f, 3,
                6f, 4f, 4f, 2f, 18f, 5f, 0.15f);

        public Resources withStartQi(int newStartQi) {
            return new Resources(maxQi, newStartQi, qiPerTurn, maxFocus, meditateCharges, focusPerStrikeHit,
                    focusPerFlurryHit, focusFlurryPerfectBonus, focusFlurryGoodBonus, focusOnParry, focusOnBlock,
                    focusPerDamageTaken);
        }
    }

    /**
     * Damage rolls and reactions.
     *
     * @param variance   ± fraction applied to every monk hit
     * @param guardMultiplier damage the monk takes through an un-parried Guard
     * @param recoilSeconds   hit-reaction recoil 1→0 (either side)
     * @param recoilRiseSeconds hit-reaction recoil 0→1. Not instant on purpose: the stage moves the
     *                        target by the recoil, so a recoil that jumped to 1 ON the contact frame
     *                        would pull the target away from the fist / blade in the very frame that
     *                        has to show them touching. 0 restores the old instant spike.
     */
    public record Damage(float variance, float critChance, float critMultiplier, float guardMultiplier,
                         float recoilSeconds, float recoilRiseSeconds) {
        public static final Damage DEFAULTS = new Damage(0.10f, 0.10f, 1.5f, 0.5f, 0.35f, 0.08f);
        /** No variance, no crits: exact numbers for tests and balance maths. */
        public static final Damage EXACT = new Damage(0f, 0f, 1.5f, 0.5f, 0.35f, 0.08f);
    }

    /**
     * What every dash-in attack shares, plus Strike and Stunning Strike. Hits land on the contact cue
     * of the clip being played, so there are no impact times to tune here.
     *
     * @param dashSeconds          length of the dash out, and of the dash back
     * @param surgeBonusHits       extra Strike hits from Martial Surge (each one a kick)
     * @param strikeAtbRestart     monk gauge value after a Strike (its "fast recovery")
     * @param standoffEaseSeconds  how fast the monk steps out to kicking distance and back in
     * @param kickStandoff         how far out that is, as a fraction of the stage's full recoil distance
     *                             (published through {@code pose.recoil}). Measured on the assets: the
     *                             kick's foot socket reaches 0.91 blocks, the fists 0.45-0.55, so a kick
     *                             wants ~0.36 blocks more room = 0.8 of the stage's 0.45-block recoil
     * @param followThroughSeconds longest the killing blow's clip keeps playing before the monk turns for home
     */
    public record Melee(float dashSeconds, float strikeDamage, int surgeBonusHits, float strikeAtbRestart,
                        float stunningStrikeDamage, float stunSeconds, float stunningStrikeAtbRestart,
                        float standoffEaseSeconds, float kickStandoff, float followThroughSeconds) {
        public static final Melee DEFAULTS = new Melee(0.45f, 42f, 1, 0.25f, 30f, 5f, 0f, 0.12f, 0.8f, 0.8f);
    }

    /**
     * Flurry of Blows: one continuous clip, one timing ring per contact.
     *
     * @param hits                 hits taken from the flurry clip (at most its contact count)
     * @param surgeBonusHits       graded hits appended by Martial Surge (kick, strike, kick, ...)
     * @param playbackSpeed        clip seconds per action second for the flurry clip; below 1 spreads the
     *                             contacts out so the ring rhythm is readable
     * @param ringLeadSeconds      how long before its contact a ring opens
     * @param perfectWindowSeconds PERFECT is the contact ± this
     * @param goodWindowSeconds    GOOD is the contact ± this; the ring closes at contact + this
     */
    public record Flurry(int hits, int surgeBonusHits, float playbackSpeed, float ringLeadSeconds,
                         float perfectWindowSeconds, float goodWindowSeconds, float damage,
                         float perfectMultiplier, float goodMultiplier, float missMultiplier, float atbRestart) {
        public static final Flurry DEFAULTS = new Flurry(3, 2, 0.85f, 0.42f, 0.06f, 0.13f, 22f,
                1.5f, 1.0f, 0.5f, 0f);

        public Flurry withPlaybackSpeed(float speed) {
            return new Flurry(hits, surgeBonusHits, speed, ringLeadSeconds, perfectWindowSeconds, goodWindowSeconds,
                    damage, perfectMultiplier, goodMultiplier, missMultiplier, atbRestart);
        }
    }

    /**
     * Focus Combo: one continuous clip that waits for the player before each contact.
     *
     * @param length              prompts in the string (at most the clip's contact count)
     * @param stepSeconds         seconds allowed per prompt, from the moment it opens
     * @param promptLeadSeconds   a prompt opens this long (in clip time) before its contact
     * @param freezeOffsetSeconds an unanswered prompt freezes the clip this long before the contact
     * @param finisherDamage      bonus added to the last hit of a flawless string
     * @param breakOffSeconds     pause in the ready stance after a miss, before the dash home
     * @param kickStandoffBefore  the monk is at kicking distance this long before the front kick lands
     * @param kickStandoffAfter   and stays there this long after it
     */
    public record Combo(int length, float stepSeconds, float promptLeadSeconds, float freezeOffsetSeconds,
                        float damage, float perfectMultiplier, float finisherDamage, float breakOffSeconds,
                        float kickStandoffBefore, float kickStandoffAfter) {
        public static final Combo DEFAULTS = new Combo(6, 0.9f, 0.45f, 0.08f, 35f, 1.25f, 150f, 0.3f, 0.35f, 0.25f);
    }

    /**
     * Swift Step, Meditate, Guard and the status durations they (and Frost Cast) apply.
     *
     * @param parryContactOffset seconds into the parry clip at which the deflect happens; the model
     *                           starts the clip this long before the Archon's impact
     */
    public record Support(float hasteSeconds, float meditateHealFraction, float chilledSeconds,
                          float parryContactOffset) {
        public static final Support DEFAULTS = new Support(15f, 0.30f, 10f, MonkClips.PARRY.cue(0));
    }

    /**
     * The Ice Archon.
     *
     * @param glideFraction   fraction of the telegraph over which a melee attack glides to the monk
     * @param glideBackDelay  seconds after impact before it glides home
     */
    public record Archon(String displayName, float maxHp, int slashWeight, int overheadWeight, int frostCastWeight,
                         float slashDamage, float overheadDamage, float frostCastDamage, float parryLeadSeconds,
                         float parryLagSeconds, float glideFraction, float glideBackDelay) {
        public static final Archon DEFAULTS = new Archon("Ice Archon", 720f, 45, 30, 25, 38f, 60f, 30f,
                // The blow resolves AT impact, so a post-impact tail could never be hit: the whole
                // parry window sits before the impact and the HUD marker shows exactly what is pressable.
                0.25f, 0.0f, 0.6f, 0.4f);

        public Archon withWeights(int slash, int overhead, int frostCast) {
            return new Archon(displayName, maxHp, slash, overhead, frostCast, slashDamage, overheadDamage,
                    frostCastDamage, parryLeadSeconds, parryLagSeconds, glideFraction, glideBackDelay);
        }

        public Archon withMaxHp(float newMaxHp) {
            return new Archon(displayName, newMaxHp, slashWeight, overheadWeight, frostCastWeight, slashDamage,
                    overheadDamage, frostCastDamage, parryLeadSeconds, parryLagSeconds, glideFraction,
                    glideBackDelay);
        }
    }
}

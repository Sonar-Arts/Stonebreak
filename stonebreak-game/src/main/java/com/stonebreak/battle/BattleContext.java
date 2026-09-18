package com.stonebreak.battle;

import com.stonebreak.battle.api.BattleEvent;
import com.stonebreak.battle.api.BattleEvent.DamageFlavor;
import com.stonebreak.battle.api.BattleOutcome;
import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.CombatantId;
import com.stonebreak.battle.api.EnemyAction;

import java.util.Random;

/**
 * Everything an executing action may touch: the two combatants, the monk's resources, the event
 * buffer, the seeded random, and the shared resolution rules (damage rolls, guard/parry, heals,
 * statuses). {@link BattleState} owns the clock and the turn order; this owns "what a hit does".
 */
final class BattleContext {

    final BattleConfig config;
    final Random random;
    final EventBuffer events = new EventBuffer();
    final StatsTracker stats = new StatsTracker();
    final Combatant monk;
    final Combatant archon;
    final FocusMeter focus;
    final QiPool qi;
    final ArchonScript archonScript;

    private int meditateCharges;
    private boolean surgeQueued;
    private boolean archonInterruptRequested;
    /** Seconds of the Archon's stunned_exit still to play; it cannot fill its gauge or act until then. */
    private float archonRecovery;

    BattleContext(BattleConfig config, Random random) {
        this.config = config;
        this.random = random;
        BattleConfig.Atb atb = config.atb();
        BattleConfig.Resources res = config.resources();
        float home = config.melee().dashSeconds();
        this.monk = new Combatant(CombatantId.MONK, BattleConfig.MONK_NAME, config.monkMaxHp(),
                new AtbGauge(atb.monkFillSeconds(), atb.monkInitial()),
                new Combatant.Stance(MonkClips.IDLE, MonkClips.COMBAT_IDLE, BattleStatus.GUARDING, MonkClips.GUARD,
                        MonkClips.COMBAT_DASH, home, config.melee().followThroughSeconds()));
        // The Archon has no run clip (it glides in whatever it is playing) and always finishes its swing.
        this.archon = new Combatant(CombatantId.ARCHON, config.archon().displayName(), config.archon().maxHp(),
                new AtbGauge(atb.archonFillSeconds(), atb.archonInitial()),
                new Combatant.Stance(ArchonClips.IDLE, ArchonClips.COMBAT_IDLE, BattleStatus.STUNNED,
                        ArchonClips.STUNNED, null, home, Float.POSITIVE_INFINITY));
        this.focus = new FocusMeter(res.maxFocus(), events);
        this.qi = new QiPool(res.maxQi(), res.startQi(), events);
        this.archonScript = new ArchonScript(config.archon());
        this.meditateCharges = Math.max(0, res.meditateCharges());
    }

    void raise(BattleEvent event) {
        events.raise(event);
    }

    Combatant combatant(CombatantId id) {
        return id == CombatantId.MONK ? monk : archon;
    }

    BattleOutcome outcome() {
        if (!archon.alive()) return BattleOutcome.VICTORY;
        if (!monk.alive()) return BattleOutcome.DEFEAT;
        return BattleOutcome.NONE;
    }

    // ---- gauges ---------------------------------------------------------------------------------

    float monkAtbSpeed() {
        float speed = config.dexteritySpeed();
        if (monk.has(BattleStatus.HASTE)) speed *= config.atb().hasteMultiplier();
        if (monk.has(BattleStatus.CHILLED)) speed *= config.atb().chilledMultiplier();
        return speed;
    }

    float archonAtbSpeed() {
        return archonIncapacitated() ? 0f : 1f;
    }

    /** Stunned, or still getting back up from it: no gauge, no attack. */
    boolean archonIncapacitated() {
        return archon.has(BattleStatus.STUNNED) || archonRecovery > 0f;
    }

    float timeToArchonRecovered() {
        return archonRecovery > 0f ? archonRecovery : Float.POSITIVE_INFINITY;
    }

    void advanceArchonRecovery(float dt) {
        if (archonRecovery > 0f) archonRecovery = dt >= archonRecovery ? 0f : archonRecovery - dt;
    }

    // ---- statuses -------------------------------------------------------------------------------

    void applyStatus(Combatant target, BattleStatus status, float seconds) {
        applyStatus(target, status, seconds, 1);
    }

    void applyStatus(Combatant target, BattleStatus status, float seconds, int stacks) {
        target.statusSet().apply(status, seconds, stacks);
        raise(new BattleEvent.StatusApplied(target.id(), status, seconds));
    }

    void expireStatus(Combatant target, BattleStatus status) {
        if (target.statusSet().remove(status)) expired(target, status);
    }

    void advanceStatuses(float dt) {
        for (BattleStatus s : monk.statusSet().advance(dt)) expired(monk, s);
        for (BattleStatus s : archon.statusSet().advance(dt)) expired(archon, s);
    }

    private void expired(Combatant target, BattleStatus status) {
        raise(new BattleEvent.StatusExpired(target.id(), status));
        if (target == archon && status == BattleStatus.STUNNED && archon.alive()) {
            archonRecovery = ArchonClips.STUNNED_EXIT.duration();
            archon.play(ArchonClips.STUNNED_EXIT);
        }
    }

    float timeToNextStatusExpiry() {
        return Math.min(monk.statusSet().timeToNextExpiry(), archon.statusSet().timeToNextExpiry());
    }

    // ---- monk resources -------------------------------------------------------------------------

    int meditateCharges() {
        return meditateCharges;
    }

    void spendMeditateCharge() {
        if (meditateCharges > 0) meditateCharges--;
    }

    boolean surgeQueued() {
        return surgeQueued;
    }

    void queueSurge() {
        surgeQueued = true;
        applyStatus(monk, BattleStatus.SURGE, -1f, config.melee().surgeBonusHits());
    }

    /** Spends a queued surge; returns whether there was one. */
    boolean consumeSurge() {
        if (!surgeQueued) return false;
        surgeQueued = false;
        expireStatus(monk, BattleStatus.SURGE);
        return true;
    }

    // ---- resolution rules -----------------------------------------------------------------------

    /** One monk blow landing on the Archon: the Impact, then its damage. */
    void monkHit(float baseDamage, float multiplier, int hitIndex, int hitCount) {
        monkImpact(hitIndex, hitCount);
        monkDamage(baseDamage, multiplier);
    }

    /** The instant a monk blow reaches the Archon: always the authored contact of the clip being played. */
    void monkImpact(int hitIndex, int hitCount) {
        raise(new BattleEvent.Impact(CombatantId.MONK, CombatantId.ARCHON, hitIndex, hitCount));
    }

    /**
     * What a landed monk blow does: seeded variance + crit roll, damage, the Archon's hit reaction.
     * Both random draws always happen, in this order, so the stream stays aligned whatever the tuning.
     */
    void monkDamage(float baseDamage, float multiplier) {
        BattleConfig.Damage dmg = config.damage();
        float spread = 1f + dmg.variance() * (2f * random.nextFloat() - 1f);
        boolean crit = random.nextFloat() < dmg.critChance();
        float amount = wholeDamage(baseDamage * multiplier * spread * (crit ? dmg.critMultiplier() : 1f));
        // The popup shows the full blow; the running totals count only the HP it actually removed.
        stats.damageDealt(archon.takeDamage(amount));
        raise(new BattleEvent.DamageDealt(CombatantId.ARCHON, amount, crit ? DamageFlavor.CRITICAL : DamageFlavor.NORMAL));
        // A new hit restarts the flinch. A stunned Archon stays in its stun clips; an attacking one is
        // never hit (actions do not overlap), and a dead one gets its death clip from the battle-end check.
        if (archon.alive() && !archon.driven() && !archon.has(BattleStatus.STUNNED)) archon.play(ArchonClips.HURT);
    }

    /** The Archon's telegraphed blow landing on the monk, through Guard / parry if they are up. */
    void archonHit(EnemyAction action, boolean parryLanded) {
        raise(new BattleEvent.Impact(CombatantId.ARCHON, CombatantId.MONK, 0, 1));
        BattleConfig.Resources res = config.resources();
        boolean guarding = monk.has(BattleStatus.GUARDING);
        boolean parried = guarding && parryLanded;
        float base = archonScript.damageOf(action);

        if (parried) {
            stats.parry();
            raise(new BattleEvent.DamageDealt(CombatantId.MONK, 0f, DamageFlavor.PARRIED));
            focus.gain(res.focusOnParry());
        } else {
            float amount = wholeDamage(guarding ? base * config.damage().guardMultiplier() : base);
            stats.damageTaken(monk.takeDamage(amount)); // overkill is not damage taken
            if (guarding) stats.block();
            raise(new BattleEvent.DamageDealt(CombatantId.MONK, amount, guarding ? DamageFlavor.BLOCKED : DamageFlavor.NORMAL));
            if (guarding) focus.gain(res.focusOnBlock());
            focus.gain(res.focusPerDamageTaken() * amount);
            // Parried blows already have their clip running (timed by the attack so the deflect meets the blade).
            if (monk.alive()) {
                if (guarding) monk.play(MonkClips.BLOCK, MonkClips.GUARD_EXIT);
                else monk.play(MonkClips.HURT);
            }
        }
        // The stance is spent on the blow it met, parried or not.
        if (guarding) expireStatus(monk, BattleStatus.GUARDING);
        if (action == EnemyAction.FROST_CAST && !parried) {
            applyStatus(monk, BattleStatus.CHILLED, config.support().chilledSeconds());
        }
    }

    void healMonk(float fractionOfMax) {
        float healed = monk.heal(Math.round(monk.maxHp() * fractionOfMax));
        raise(new BattleEvent.Healed(CombatantId.MONK, healed));
    }

    /** STUNNED on the Archon, plus a request to abort whatever it is doing (see BattleState). */
    void stunArchon() {
        boolean alreadyDown = archon.has(BattleStatus.STUNNED);
        applyStatus(archon, BattleStatus.STUNNED, config.melee().stunSeconds());
        archonRecovery = 0f;
        // A refreshed stun keeps the loop going; a fresh one staggers in (taking over from an attack clip).
        if (!alreadyDown && archon.alive()) archon.play(ArchonClips.STUNNED_ENTER);
        archonInterruptRequested = true;
    }

    /** Guard stance goes up. {@code posed} = play the reaction clip here (a Guard ACTION drives its own). */
    void raiseGuard(boolean posed) {
        applyStatus(monk, BattleStatus.GUARDING, -1f);
        if (posed) monk.play(MonkClips.GUARD_ENTER);
    }

    boolean takeArchonInterruptRequest() {
        boolean requested = archonInterruptRequested;
        archonInterruptRequested = false;
        return requested;
    }

    /** Whole-number damage so popups, HP bars and totals always add up; a landed hit never rounds to 0. */
    private static float wholeDamage(float raw) {
        if (!(raw > 0f)) return 0f;
        return Math.max(1f, Math.round(raw));
    }
}

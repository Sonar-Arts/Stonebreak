package com.stonebreak.player.combat;

import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_DOUBT_DECAY_INTERVAL;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_DOUBT_DECAY_TIMEOUT;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_DOUBT_MAX_STACKS;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_SHAKEN_ATTACK_DELAY;
import static com.stonebreak.player.PlayerConstants.ILLUSIONIST_SHAKEN_DURATION;

import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.entities.status.StatusEffectType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the Illusionist's Doubt resource as discrete stacks held per enemy. Unlike the
 * Ranger's single-target Quarry, Doubt accumulates independently on every enemy that strikes
 * one of the player's illusory decoys, so stacks live in a {@code Map} keyed by entity.
 *
 * <p>Each stack gain refreshes a {@link StatusEffectType#SHAKEN} debuff whose magnitude scales
 * with the current stack count (an attack-hesitation delay). Stacks decay stepwise once an enemy
 * has not had fresh illusory contact for
 * {@link com.stonebreak.player.PlayerConstants#ILLUSIONIST_DOUBT_DECAY_TIMEOUT} seconds; a decay
 * tick at zero stacks removes the entry entirely. Mirrors the decay cadence of
 * {@link QuarryController}.</p>
 */
public class DoubtController {

    /** Per-enemy Doubt state: current stack count and time since the last illusory contact. */
    private static final class DoubtEntry {
        int stacks;
        float timeSinceContact;
    }

    // ConcurrentHashMap: decoy death (game thread) and HUD reads can both touch this map.
    private final Map<LivingEntity, DoubtEntry> tracked = new ConcurrentHashMap<>();

    /**
     * Adds one Doubt stack to {@code target} (clamped to the max), resets its decay clock, and
     * refreshes the SHAKEN debuff so its hesitation magnitude reflects the new stack count.
     */
    public void addStack(LivingEntity target) {
        if (target == null || !target.isAlive()) return;
        DoubtEntry entry = tracked.computeIfAbsent(target, t -> new DoubtEntry());
        entry.stacks = Math.min(ILLUSIONIST_DOUBT_MAX_STACKS, entry.stacks + 1);
        entry.timeSinceContact = 0f;
        target.applyStatusEffect(StatusEffectType.SHAKEN, ILLUSIONIST_SHAKEN_DURATION,
            entry.stacks * ILLUSIONIST_SHAKEN_ATTACK_DELAY);
    }

    /** Current Doubt stacks on {@code target}, or 0 if it isn't tracked. */
    public int getStacks(LivingEntity target) {
        DoubtEntry entry = tracked.get(target);
        return entry == null ? 0 : entry.stacks;
    }

    /** True once {@code target} has reached the maximum Doubt stacks (Bewildered). */
    public boolean isBewildered(LivingEntity target) {
        return getStacks(target) >= ILLUSIONIST_DOUBT_MAX_STACKS;
    }

    /** All alive tracked entities with at least one Doubt stack (Fracture's target pool). */
    public List<LivingEntity> getAllDoubted() {
        List<LivingEntity> result = new ArrayList<>();
        for (Map.Entry<LivingEntity, DoubtEntry> e : tracked.entrySet()) {
            if (e.getKey().isAlive() && e.getValue().stacks > 0) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    /** Removes {@code target}'s Doubt entry entirely (Fracture consumes it). */
    public void consumeAll(LivingEntity target) {
        tracked.remove(target);
        // The SHAKEN debuff lapses on its own timer; nothing further to clear.
    }

    /** Advances every entry's decay clock, stepping stacks down and pruning dead/empty entries. */
    public void update(float deltaTime) {
        Iterator<Map.Entry<LivingEntity, DoubtEntry>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<LivingEntity, DoubtEntry> e = it.next();
            LivingEntity target = e.getKey();
            DoubtEntry entry = e.getValue();
            if (!target.isAlive()) {
                it.remove();
                continue;
            }
            entry.timeSinceContact += deltaTime;
            while (entry.timeSinceContact >= ILLUSIONIST_DOUBT_DECAY_TIMEOUT) {
                entry.timeSinceContact -= ILLUSIONIST_DOUBT_DECAY_INTERVAL;
                if (entry.stacks > 0) {
                    entry.stacks--;
                }
                if (entry.stacks <= 0) {
                    it.remove();
                    break;
                }
            }
        }
    }

    /** Clears all tracked Doubt (world reload — entity references go stale). */
    public void reset() {
        tracked.clear();
    }
}

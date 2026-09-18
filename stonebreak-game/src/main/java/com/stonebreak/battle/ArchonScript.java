package com.stonebreak.battle;

import com.stonebreak.battle.api.EnemyAction;

import java.util.Random;

/**
 * The Ice Archon's attack pick: seeded weights, never Frost Cast twice running and never while the
 * monk is still Chilled (stacking the slow would only waste its turn and feel unfair).
 */
final class ArchonScript {

    private final BattleConfig.Archon tuning;
    private EnemyAction last;

    ArchonScript(BattleConfig.Archon tuning) {
        this.tuning = tuning;
    }

    EnemyAction pick(Random random, boolean monkChilled) {
        boolean frostAllowed = !monkChilled && last != EnemyAction.FROST_CAST;
        int slash = Math.max(0, tuning.slashWeight());
        int overhead = Math.max(0, tuning.overheadWeight());
        int frost = frostAllowed ? Math.max(0, tuning.frostCastWeight()) : 0;
        int total = slash + overhead + frost;

        EnemyAction picked;
        if (total <= 0) {
            // Only Frost Cast is weighted but it is barred this turn: fall back to the basic attack.
            picked = EnemyAction.SLASH;
        } else {
            int roll = random.nextInt(total);
            if (roll < slash) picked = EnemyAction.SLASH;
            else if (roll < slash + overhead) picked = EnemyAction.OVERHEAD;
            else picked = EnemyAction.FROST_CAST;
        }
        last = picked;
        return picked;
    }

    float damageOf(EnemyAction action) {
        return switch (action) {
            case SLASH -> tuning.slashDamage();
            case OVERHEAD -> tuning.overheadDamage();
            case FROST_CAST -> tuning.frostCastDamage();
        };
    }
}

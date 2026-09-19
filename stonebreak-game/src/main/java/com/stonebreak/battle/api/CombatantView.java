package com.stonebreak.battle.api;

import java.util.List;

/** Read-only view of one combatant. */
public interface CombatantView {
    CombatantId id();
    String displayName();
    float hp();
    float maxHp();
    /** ATB gauge fill, 0..1. */
    float atb();
    ActorPose pose();
    List<StatusView> statuses();

    default float hpFraction() {
        return maxHp() <= 0f ? 0f : Math.max(0f, Math.min(1f, hp() / maxHp()));
    }

    default boolean alive() { return hp() > 0f; }

    default boolean has(BattleStatus status) {
        for (StatusView s : statuses()) {
            if (s.status() == status) return true;
        }
        return false;
    }
}

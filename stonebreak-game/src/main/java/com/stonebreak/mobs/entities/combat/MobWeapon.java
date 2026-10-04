package com.stonebreak.mobs.entities.combat;

import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.ai.MobBehaviorState;

/**
 * The weapons a mob can carry: the item it holds, the socket it holds it on, and how it fights
 * with it. All weapon tuning lives in this one table.
 *
 * <p>Constant names are persisted in goblin loadouts — never rename one.
 */
public enum MobWeapon {

    /** Quick and weak: short jabs on a short cooldown. */
    DAGGER("stonebreak:dagger", "weapon_r",
            new WeaponProfile.Melee(MobBehaviorState.STAB, 2.0f, 1.6f, 0.6f, 0.6f, 0.25f, 2.0f)),

    /** Slow and blunt: hits a little harder, and its real threat is the shove. */
    PATTY_SMACKER("stonebreak:patty_smacker", "weapon_r",
            new WeaponProfile.Melee(MobBehaviorState.SMASH, 3.0f, 1.9f, 1.4f, 0.9f, 0.55f, 9.0f)),

    /** Keeps its distance and shoots. */
    BOW("stonebreak:bow", "bow_l",
            new WeaponProfile.Ranged(3.0f, 4.0f, 14.0f, 1.2f, 0.4f, 1.6f, 28.0f, 0.04f));

    private final String itemObjectId;
    private final String socket;
    private final WeaponProfile profile;

    MobWeapon(String itemObjectId, String socket, WeaponProfile profile) {
        this.itemObjectId = itemObjectId;
        this.socket = socket;
        this.profile = profile;
    }

    /** The held item, resolved lazily so the item registry need not exist when this enum loads. */
    public ItemType itemType() {
        return ItemType.getByObjectId(itemObjectId);
    }

    /** Host-model socket the item hangs on. */
    public String socket() {
        return socket;
    }

    public WeaponProfile profile() {
        return profile;
    }
}

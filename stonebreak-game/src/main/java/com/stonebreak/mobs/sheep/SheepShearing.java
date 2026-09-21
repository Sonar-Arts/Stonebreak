package com.stonebreak.mobs.sheep;

import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;

/**
 * Shears interaction: right-clicking a sheep with shears swaps it to the
 * registered {@code "Sheared"} SBE variant.
 *
 * <p>Follows the damage seam: on a network shadow the shear is forwarded as an
 * {@code EntityShearC2S} intent (with a predicted visual swap for feedback —
 * shadows never tick, so the prediction is safe) and the authoritative server
 * shears the real sheep, replicating {@code EntityVariantS2C} to everyone.
 * Entities living in the rendered world shear directly.
 */
public final class SheepShearing {

    private SheepShearing() {}

    /**
     * Attempts to shear the sheep under the crosshair with the held item.
     * Returns true when the click hit a sheep with shears (consumed — the
     * caller skips block placement), false when the target is not a sheep
     * (the caller falls through).
     */
    public static boolean tryShear(Player player, ItemStack held) {
        if (player == null || held == null || held.isEmpty() || held.getItem() != ItemType.SHEARS) {
            return false;
        }
        EntityManager em = Game.getEntityManager();
        if (em == null) {
            return false;
        }
        LivingEntity target = player.getRaycastEngine().raycastEntity(em.getLivingEntities());
        if (!(target instanceof Sheep sheep) || !sheep.isAlive()) {
            return false;
        }
        if (sheep.isSheared()) {
            return true; // already shorn — consume the click, nothing to shear
        }
        if (sheep.isNetworkShadow()) {
            // Predicted visual swap; the authoritative server shears the real
            // sheep and the EntityVariantS2C broadcast is idempotent.
            sheep.setTextureVariant(Sheep.SHEARED_VARIANT);
            MultiplayerSession.onLocalEntityShear(sheep);
            return true;
        }
        sheep.shear();
        return true;
    }
}

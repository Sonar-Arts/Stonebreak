package com.stonebreak.blocks.blueberrybush;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;

/**
 * Picks a ripe blueberry bush on an authoritative world: the growth system sets the bush back
 * to Mature, and the bush drops exactly what breaking it ripe would (its SBO drops table), so
 * picking and breaking always give the same berries. Shared by the server's toggle handler and
 * the no-connection fallback in {@link BlueberryBushInteraction}.
 */
public final class BlueberryBushHarvest {

    /** The state the berries are rolled for: the ripe bush as it was before picking. */
    private static final String RIPE_STATE = new BlueberryBushState(Stage.BERRIES_RIPE, 0).toStateString();

    private BlueberryBushHarvest() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * @param picker where the picker stands; berries spawn on that side (may be null)
     * @return true when the bush was ripe and was picked
     */
    public static boolean harvest(World world, long x, long y, long z, Vector3dc picker) {
        if (world == null || world.getBlueberryBushGrowth() == null
                || !world.getBlueberryBushGrowth().harvest(x, y, z)) {
            return false;
        }
        DropUtil.handleBlockBroken(world, new Vector3d(x + 0.5, y + 0.5, z + 0.5),
                BlockType.BLUEBERRY_BUSH, RIPE_STATE, null, 0, picker);
        return true;
    }
}

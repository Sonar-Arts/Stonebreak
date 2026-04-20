package com.stonebreak.player.interaction;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.Water;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Camera;
import com.stonebreak.player.IBlockPlacementService;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * Places a placeable item as a block one tile in front of the player. Falls back to
 * the tile below when the preferred tile lacks ground support. Not used for
 * death drops (which use {@code DropUtil} directly).
 */
public class ItemDropInteraction {

    private final PhysicsState state;
    private final Camera camera;
    private final IBlockPlacementService placementService;
    private World world;

    public ItemDropInteraction(PhysicsState state, Camera camera,
                               IBlockPlacementService placementService, World world) {
        this.state = state;
        this.camera = camera;
        this.placementService = placementService;
        this.world = world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    public boolean attemptDropItemInFront(ItemStack itemToDrop) {
        if (itemToDrop == null || itemToDrop.isEmpty()) return false;
        if (!itemToDrop.isPlaceable()) return false;
        BlockType blockToPlace = itemToDrop.asBlockType();
        if (blockToPlace == null || blockToPlace == BlockType.AIR) return false;

        Vector3f front = camera.getFront();
        Vector3f position = state.getPosition();
        Vector3i dropPos = new Vector3i(
                (int) Math.floor(position.x + front.x),
                (int) Math.floor(position.y),
                (int) Math.floor(position.z + front.z)
        );

        BlockType blockAtDropPos = world.getBlockAt(dropPos.x, dropPos.y, dropPos.z);
        if (blockAtDropPos != BlockType.AIR) return false;
        if (placementService.wouldIntersectWithPlayer(dropPos, position, blockToPlace, state.isOnGround())) return false;

        BlockType blockBelowDropPos = world.getBlockAt(dropPos.x, dropPos.y - 1, dropPos.z);
        if (!blockBelowDropPos.isSolid()) {
            dropPos.y = dropPos.y - 1;
            blockAtDropPos = world.getBlockAt(dropPos.x, dropPos.y, dropPos.z);
            if (blockAtDropPos != BlockType.AIR) return false;
            if (placementService.wouldIntersectWithPlayer(dropPos, position, blockToPlace, state.isOnGround())) return false;
            blockBelowDropPos = world.getBlockAt(dropPos.x, dropPos.y - 1, dropPos.z);
            if (!blockBelowDropPos.isSolid()) return false;
        }

        if (!world.setBlockAt(dropPos.x, dropPos.y, dropPos.z, blockToPlace, true)) return false;
        if (blockToPlace == BlockType.SNOW) {
            world.getSnowLayerManager().setSnowLayers(dropPos.x, dropPos.y, dropPos.z, 1);
        }
        Water.onBlockPlaced(dropPos.x, dropPos.y, dropPos.z);
        System.out.println("Player dropped item " + blockToPlace.getName() + " at " + dropPos.x + ", " + dropPos.y + ", " + dropPos.z);
        return true;
    }
}

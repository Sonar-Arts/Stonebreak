package com.stonebreak.player.interaction;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.Water;
import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.combat.AttackController;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * Tracks block-breaking progress for the currently targeted block. Per-frame progress
 * advances based on block hardness and tool efficiency (pickaxe on stone, axe on wood).
 * Breaking completes when progress reaches 1.0; instant-break blocks complete on press.
 */
public class BlockBreaker {

    private final RaycastEngine raycastEngine;
    private final Inventory inventory;
    private final AttackController attack;
    private World world;

    private Vector3i breakingBlock;
    private float breakingProgress;
    private float breakingTime;

    public BlockBreaker(RaycastEngine raycastEngine, Inventory inventory, AttackController attack, World world) {
        this.raycastEngine = raycastEngine;
        this.inventory = inventory;
        this.attack = attack;
        this.world = world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    public void update() {
        if (breakingBlock == null) return;

        Vector3i currentTarget = raycastEngine.raycast();
        if (currentTarget == null || !currentTarget.equals(breakingBlock)) {
            reset();
            return;
        }

        BlockType blockType = world.getBlockAt(breakingBlock.x, breakingBlock.y, breakingBlock.z);
        if (!blockType.isBreakable() || blockType == BlockType.AIR) {
            reset();
            return;
        }

        float effectiveHardness = effectiveHardness(blockType);
        if (effectiveHardness <= 0 || effectiveHardness == Float.POSITIVE_INFINITY) return;

        breakingTime += Game.getDeltaTime();
        breakingProgress = Math.min(breakingTime / effectiveHardness, 1.0f);

        if (breakingProgress >= 1.0f) {
            completeBreak(breakingBlock, blockType);
            reset();
        }
    }

    public void startBreaking() {
        Vector3i blockPos = raycastEngine.raycast();
        if (blockPos == null) return;

        BlockType blockType = world.getBlockAt(blockPos.x, blockPos.y, blockPos.z);
        if (!blockType.isBreakable() || blockType == BlockType.AIR) return;

        if (breakingBlock == null || !breakingBlock.equals(blockPos)) {
            breakingBlock = new Vector3i(blockPos);
            breakingProgress = 0.0f;
            breakingTime = 0.0f;
        }

        attack.beginAttackForBreak();

        if (blockType.getHardness() <= 0.0f) {
            completeBreak(blockPos, blockType);
            reset();
        }
    }

    public void stopBreaking() {
        reset();
    }

    public void reset() {
        breakingBlock = null;
        breakingProgress = 0.0f;
        breakingTime = 0.0f;
    }

    public Vector3i getBreakingBlock() { return breakingBlock; }
    public float getBreakingProgress() { return breakingProgress; }

    private float effectiveHardness(BlockType blockType) {
        float hardness = blockType.getHardness();
        ItemStack selectedItem = inventory.getSelectedHotbarSlot();
        if (selectedItem == null || !selectedItem.isTool()) return hardness;

        ItemType itemType = selectedItem.asItemType();
        if (itemType == ItemType.WOODEN_PICKAXE) {
            if (blockType == BlockType.STONE || blockType == BlockType.SANDSTONE ||
                    blockType == BlockType.RED_SANDSTONE) {
                return hardness * 0.25f;
            }
        } else if (itemType == ItemType.WOODEN_AXE && isWoodenBlock(blockType)) {
            return Math.max(0.1f, hardness - 2.0f);
        }
        return hardness;
    }

    private void completeBreak(Vector3i pos, BlockType blockType) {
        if (blockType == BlockType.WATER) {
            Water.removeWaterSource(pos.x, pos.y, pos.z);
        }
        if (blockType == BlockType.SNOW) {
            world.getSnowLayerManager().removeSnowLayers(pos.x, pos.y, pos.z);
        }
        Vector3f dropPosition = new Vector3f(pos.x + 0.5f, pos.y + 0.5f, pos.z + 0.5f);
        // Drops are host-authoritative in multiplayer. A client that creates
        // its own local drop here would end up with two drops (the local one
        // plus the shadow the host broadcasts back) and would double-collect
        // because the host also issues a giveItemTo when the client walks
        // through the host's drop.
        if (!com.stonebreak.network.MultiplayerSession.isClient()) {
            ItemType toolItem = getHeldToolType();
            DropUtil.handleBlockBroken(world, dropPosition, blockType, toolItem);
        }
        world.setBlockAt(pos.x, pos.y, pos.z, BlockType.AIR, true);
        Water.onBlockBroken(pos.x, pos.y, pos.z);
    }

    private ItemType getHeldToolType() {
        ItemStack selectedItem = inventory.getSelectedHotbarSlot();
        if (selectedItem != null && selectedItem.isTool()) {
            return selectedItem.asItemType();
        }
        return null;
    }

    private static boolean isWoodenBlock(BlockType blockType) {
        return blockType == BlockType.WOOD ||
                blockType == BlockType.WORKBENCH ||
                blockType == BlockType.PINE ||
                blockType == BlockType.ELM_WOOD_LOG ||
                blockType == BlockType.WOOD_PLANKS ||
                blockType == BlockType.PINE_WOOD_PLANKS ||
                blockType == BlockType.ELM_WOOD_PLANKS;
    }
}

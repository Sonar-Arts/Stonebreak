package com.stonebreak.player.interaction;

import com.stonebreak.audio.BlockSounds;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.combat.AttackController;
import com.stonebreak.world.World;
import org.joml.Vector3i;

/**
 * Tracks block-breaking progress for the currently targeted block. Per-frame progress
 * advances based on block hardness and tool efficiency (pickaxe on stone, axe on wood).
 * Breaking completes when progress reaches 1.0; instant-break blocks complete on press.
 */
public class BlockBreaker {

    /** Seconds between "hit" sounds while a block is being demolished. */
    private static final float HIT_SOUND_INTERVAL = 0.25f;

    private final RaycastEngine raycastEngine;
    private final Inventory inventory;
    private final AttackController attack;
    private World world;

    private Vector3i breakingBlock;
    private float breakingProgress;
    private float breakingTime;
    private float hitSoundTimer;

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

        hitSoundTimer += Game.getDeltaTime();
        if (hitSoundTimer >= HIT_SOUND_INTERVAL && breakingProgress < 1.0f) {
            BlockSounds.playHit(blockType, breakingBlock.x, breakingBlock.y, breakingBlock.z);
            hitSoundTimer = 0.0f;
        }

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
            hitSoundTimer = 0.0f;
            if (blockType.getHardness() > 0.0f) {
                BlockSounds.playHit(blockType, blockPos.x, blockPos.y, blockPos.z);
            }
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
        hitSoundTimer = 0.0f;
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
        if (blockType == BlockType.SNOW) {
            world.getSnowLayerManager().removeSnowLayers(pos.x, pos.y, pos.z);
        }
        // Drops and per-block-break side effects (furnace cleanup, item drops) are
        // server-authoritative. The setBlockAt(..., true) below forwards this edit to the
        // server via MultiplayerSession.onLocalBlockChange; ServerBlockHandler.handleBlockChange
        // then runs DropUtil + furnace cleanup on the server world, and broadcasts the resulting
        // drops via EntitySpawnS2C. This applies uniformly to host and client — the host is its
        // own server's LocalChannel client. Running DropUtil here too would create a duplicate
        // local drop (in the client EM) alongside the server-spawned shadow.
        world.setBlockAt(pos.x, pos.y, pos.z, BlockType.AIR, true);
        BlockSounds.playBreak(blockType, pos.x, pos.y, pos.z);
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

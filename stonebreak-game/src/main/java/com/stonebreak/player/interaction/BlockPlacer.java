package com.stonebreak.player.interaction;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.Water;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.BlockPlacementValidator;
import com.stonebreak.player.IBlockPlacementService;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.joml.Vector3i;

/**
 * Handles placing the currently-selected hotbar item. Special-cases buckets
 * (pickup/empty into water), snow-layer stacking, water-source vs. flow conversion,
 * and player-intersection checks. Face selection uses {@link RaycastEngine}'s
 * ray/plane intersection against the six faces of the targeted block.
 */
public class BlockPlacer {

    private final PhysicsState state;
    private final RaycastEngine raycastEngine;
    private final Inventory inventory;
    private final IBlockPlacementService placementService;
    private World world;

    public BlockPlacer(PhysicsState state, RaycastEngine raycastEngine, Inventory inventory,
                       IBlockPlacementService placementService, World world) {
        this.state = state;
        this.raycastEngine = raycastEngine;
        this.inventory = inventory;
        this.placementService = placementService;
        this.world = world;
    }

    public void setWorld(World world) {
        this.world = world;
    }

    public void placeBlock() {
        ItemStack selectedItem = inventory.getSelectedHotbarSlot();
        if (selectedItem == null || selectedItem.isEmpty()) return;

        if (selectedItem.isTool()) {
            ItemType itemType = selectedItem.asItemType();
            if (itemType == ItemType.WOODEN_BUCKET) {
                // The wooden bucket carries its full/empty status as an SBO
                // state (1.3+). Water-state buckets pick up water sources;
                // any other state (empty/default) places water.
                if (ItemType.BUCKET_STATE_WATER.equals(selectedItem.getState())) {
                    handleWaterBucket(selectedItem);
                } else {
                    handleEmptyBucket(selectedItem);
                }
                return;
            }
        }

        if (!selectedItem.isPlaceable()) return;

        BlockType selectedBlockType = selectedItem.asBlockType();
        if (selectedBlockType == null) return;

        Vector3i hitBlockPos = raycastEngine.raycastForPlacement();
        Vector3i placePos;
        if (hitBlockPos != null) {
            BlockType hitBlockType = world.getBlockAt(hitBlockPos.x, hitBlockPos.y, hitBlockPos.z);
            if (selectedBlockType == BlockType.SNOW && hitBlockType == BlockType.SNOW) {
                placePos = new Vector3i(hitBlockPos);
            } else {
                placePos = findPlacePosition(hitBlockPos);
            }
        } else {
            placePos = null;
        }

        if (placePos == null) return;

        BlockType blockAtPos = world.getBlockAt(placePos.x, placePos.y, placePos.z);

        if (selectedBlockType == BlockType.SNOW && blockAtPos == BlockType.SNOW) {
            handleSnowStacking(selectedItem, placePos);
            return;
        }

        if (blockAtPos != BlockType.AIR && blockAtPos != BlockType.WATER) return;

        BlockPlacementValidator.PlacementValidationResult validationResult =
                placementService.validatePlacement(placePos, state.getPosition(), selectedBlockType, state.isOnGround());
        if (!validationResult.canPlace()) return;

        if (blockAtPos == BlockType.WATER && selectedBlockType == BlockType.WATER) {
            if (!Water.isWaterSource(placePos.x, placePos.y, placePos.z)) {
                world.setBlockAt(placePos.x, placePos.y, placePos.z, BlockType.AIR, true);
                world.setBlockAt(placePos.x, placePos.y, placePos.z, BlockType.WATER, true);
                inventory.removeItem(selectedItem.getItem(), 1);
            }
            return;
        }

        if (blockAtPos == BlockType.WATER) {
            Water.removeWaterSource(placePos.x, placePos.y, placePos.z);
        }

        if (world.setBlockAt(placePos.x, placePos.y, placePos.z, selectedBlockType, true)) {
            inventory.removeItem(selectedItem.getItem(), 1);
            if (selectedBlockType == BlockType.WATER) {
                Water.addWaterSource(placePos.x, placePos.y, placePos.z);
            }
            Water.onBlockPlaced(placePos.x, placePos.y, placePos.z);
            if (selectedBlockType == BlockType.SNOW) {
                world.getSnowLayerManager().setSnowLayers(placePos.x, placePos.y, placePos.z, 1);
            }
        }
    }

    private void handleEmptyBucket(ItemStack selectedItem) {
        Vector3i targetBlock = raycastEngine.raycastIncludingWater();
        if (targetBlock == null) return;
        BlockType blockType = world.getBlockAt(targetBlock.x, targetBlock.y, targetBlock.z);
        if (blockType != BlockType.WATER) return;
        if (!Water.isWaterSource(targetBlock.x, targetBlock.y, targetBlock.z)) return;

        world.setBlockAt(targetBlock.x, targetBlock.y, targetBlock.z, BlockType.AIR, true);
        Water.onBlockPlaced(targetBlock.x, targetBlock.y, targetBlock.z);

        int currentSlot = inventory.getSelectedHotbarSlotIndex();
        int currentCount = selectedItem.getCount();
        if (currentCount == 1) {
            inventory.setHotbarSlot(currentSlot,
                    new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER));
        } else {
            inventory.setHotbarSlot(currentSlot,
                    new ItemStack(ItemType.WOODEN_BUCKET, currentCount - 1, ItemType.BUCKET_STATE_EMPTY));
            // addItem doesn't carry state; place a water bucket directly into
            // the inventory by stacking via the explicit-state ItemStack path.
            inventory.addItem(new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_WATER));
        }
    }

    private void handleWaterBucket(ItemStack selectedItem) {
        Vector3i targetBlock = raycastEngine.raycastIncludingWater();
        Vector3i placePos = null;
        if (targetBlock != null) {
            BlockType targetBlockType = world.getBlockAt(targetBlock.x, targetBlock.y, targetBlock.z);
            placePos = (targetBlockType == BlockType.WATER) ? targetBlock : findPlacePosition(targetBlock);
        }
        if (placePos == null) return;

        BlockType blockAtPos = world.getBlockAt(placePos.x, placePos.y, placePos.z);
        if (blockAtPos != BlockType.AIR && blockAtPos != BlockType.WATER) return;

        if (blockAtPos == BlockType.WATER) {
            if (Water.isWaterSource(placePos.x, placePos.y, placePos.z)) return;
            world.setBlockAt(placePos.x, placePos.y, placePos.z, BlockType.AIR, true);
            world.setBlockAt(placePos.x, placePos.y, placePos.z, BlockType.WATER, true);
        } else {
            if (!world.setBlockAt(placePos.x, placePos.y, placePos.z, BlockType.WATER, true)) return;
            Water.onBlockPlaced(placePos.x, placePos.y, placePos.z);
        }

        int currentSlot = inventory.getSelectedHotbarSlotIndex();
        int currentCount = selectedItem.getCount();
        if (currentCount == 1) {
            inventory.setHotbarSlot(currentSlot,
                    new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_EMPTY));
        } else {
            inventory.setHotbarSlot(currentSlot,
                    new ItemStack(ItemType.WOODEN_BUCKET, currentCount - 1, ItemType.BUCKET_STATE_WATER));
            inventory.addItem(new ItemStack(ItemType.WOODEN_BUCKET, 1, ItemType.BUCKET_STATE_EMPTY));
        }
    }

    private void handleSnowStacking(ItemStack selectedItem, Vector3i placePos) {
        int currentLayers = world.getSnowLayers(placePos.x, placePos.y, placePos.z);
        if (currentLayers < 8) {
            world.getSnowLayerManager().addSnowLayer(placePos.x, placePos.y, placePos.z);
            inventory.removeItem(selectedItem.getItem(), 1);
            world.triggerChunkRebuild(placePos.x, placePos.y, placePos.z);
            return;
        }

        Vector3i abovePos = new Vector3i(placePos.x, placePos.y + 1, placePos.z);
        BlockType blockAbove = world.getBlockAt(abovePos.x, abovePos.y, abovePos.z);
        if (blockAbove != BlockType.AIR) return;
        if (placementService.wouldIntersectWithPlayer(abovePos, state.getPosition(), BlockType.SNOW, state.isOnGround())) return;
        if (!world.setBlockAt(abovePos.x, abovePos.y, abovePos.z, BlockType.SNOW, true)) return;
        world.getSnowLayerManager().setSnowLayers(abovePos.x, abovePos.y, abovePos.z, 1);
        inventory.removeItem(selectedItem.getItem(), 1);
        Water.onBlockPlaced(abovePos.x, abovePos.y, abovePos.z);
    }

    private Vector3i findPlacePosition(Vector3i hitBlock) {
        Vector3f rayOrigin = raycastEngine.eyeOrigin();
        Vector3f rayDirection = raycastEngine.eyeDirection();

        float minDist = Float.MAX_VALUE;
        Vector3i placePos = null;

        // Top
        float d = RaycastEngine.rayIntersectsPlane(rayOrigin, rayDirection,
                new Vector3f(hitBlock.x + 0.5f, hitBlock.y + 1, hitBlock.z + 0.5f),
                new Vector3f(0, 1, 0));
        if (d > 0 && d < minDist && faceHitInBoundsXZ(rayOrigin, rayDirection, d, hitBlock)) {
            placePos = new Vector3i(hitBlock.x, hitBlock.y + 1, hitBlock.z);
            minDist = d;
        }
        // Bottom
        d = RaycastEngine.rayIntersectsPlane(rayOrigin, rayDirection,
                new Vector3f(hitBlock.x + 0.5f, hitBlock.y, hitBlock.z + 0.5f),
                new Vector3f(0, -1, 0));
        if (d > 0 && d < minDist && faceHitInBoundsXZ(rayOrigin, rayDirection, d, hitBlock)) {
            placePos = new Vector3i(hitBlock.x, hitBlock.y - 1, hitBlock.z);
            minDist = d;
        }
        // Front (+z)
        d = RaycastEngine.rayIntersectsPlane(rayOrigin, rayDirection,
                new Vector3f(hitBlock.x + 0.5f, hitBlock.y + 0.5f, hitBlock.z + 1),
                new Vector3f(0, 0, 1));
        if (d > 0 && d < minDist && faceHitInBoundsXY(rayOrigin, rayDirection, d, hitBlock)) {
            placePos = new Vector3i(hitBlock.x, hitBlock.y, hitBlock.z + 1);
            minDist = d;
        }
        // Back (-z)
        d = RaycastEngine.rayIntersectsPlane(rayOrigin, rayDirection,
                new Vector3f(hitBlock.x + 0.5f, hitBlock.y + 0.5f, hitBlock.z),
                new Vector3f(0, 0, -1));
        if (d > 0 && d < minDist && faceHitInBoundsXY(rayOrigin, rayDirection, d, hitBlock)) {
            placePos = new Vector3i(hitBlock.x, hitBlock.y, hitBlock.z - 1);
            minDist = d;
        }
        // Right (+x)
        d = RaycastEngine.rayIntersectsPlane(rayOrigin, rayDirection,
                new Vector3f(hitBlock.x + 1, hitBlock.y + 0.5f, hitBlock.z + 0.5f),
                new Vector3f(1, 0, 0));
        if (d > 0 && d < minDist && faceHitInBoundsYZ(rayOrigin, rayDirection, d, hitBlock)) {
            placePos = new Vector3i(hitBlock.x + 1, hitBlock.y, hitBlock.z);
            minDist = d;
        }
        // Left (-x)
        d = RaycastEngine.rayIntersectsPlane(rayOrigin, rayDirection,
                new Vector3f(hitBlock.x, hitBlock.y + 0.5f, hitBlock.z + 0.5f),
                new Vector3f(-1, 0, 0));
        if (d > 0 && d < minDist && faceHitInBoundsYZ(rayOrigin, rayDirection, d, hitBlock)) {
            placePos = new Vector3i(hitBlock.x - 1, hitBlock.y, hitBlock.z);
        }

        if (placePos == null) return null;

        BlockType blockAtPos = world.getBlockAt(placePos.x, placePos.y, placePos.z);
        if (blockAtPos != BlockType.AIR && blockAtPos != BlockType.WATER) return null;
        if (placementService.wouldIntersectWithPlayer(placePos, state.getPosition(), null, state.isOnGround())) return null;
        return placePos;
    }

    private static boolean faceHitInBoundsXZ(Vector3f o, Vector3f dir, float dist, Vector3i b) {
        Vector3f h = new Vector3f(dir).mul(dist).add(o);
        return h.x >= b.x && h.x <= b.x + 1.0f && h.z >= b.z && h.z <= b.z + 1.0f;
    }

    private static boolean faceHitInBoundsXY(Vector3f o, Vector3f dir, float dist, Vector3i b) {
        Vector3f h = new Vector3f(dir).mul(dist).add(o);
        return h.x >= b.x && h.x <= b.x + 1.0f && h.y >= b.y && h.y <= b.y + 1.0f;
    }

    private static boolean faceHitInBoundsYZ(Vector3f o, Vector3f dir, float dist, Vector3i b) {
        Vector3f h = new Vector3f(dir).mul(dist).add(o);
        return h.y >= b.y && h.y <= b.y + 1.0f && h.z >= b.z && h.z <= b.z + 1.0f;
    }
}

package com.stonebreak.items.held;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemModels;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import com.stonebreak.rendering.sbo.SBOHandMeshRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a held object is drawn, and so which default grip places it (#458). The value is also the player graph's
 * {@code heldKind} parameter, so keep the order.
 */
public enum HeldKind {
    /** Nothing in hand (or nothing drawable). */
    NONE,
    /** A placeable block: its hand mesh (SBO display mesh or a textured cube), a unit cube on the origin. */
    BLOCK,
    /**
     * A tool or weapon (its SBO carries {@code tool} or {@code weapon} data) drawn as its voxelized sprite: the
     * handle-to-tip diagonal of the image (bottom-left to top-right) along +Y, gripped like a handle.
     */
    TOOL,
    /** Any other sprite item (food, materials, a bucket): the image held upright, its front facing out. */
    ITEM,
    /** An item drawn as a 3D model ({@link ItemModels}): the model at its origin. */
    MODEL;

    private static final Map<Item, HeldKind> CACHE = new ConcurrentHashMap<>();

    /** The graph parameter value. */
    public int graphValue() {
        return ordinal();
    }

    /** How {@code item} is drawn in a hand; {@link #NONE} when it is not drawable there. Cached per object. */
    public static HeldKind of(Item item) {
        return item == null ? NONE : CACHE.computeIfAbsent(item, HeldKind::resolve);
    }

    private static HeldKind resolve(Item item) {
        if (item instanceof BlockType block) {
            return SBOHandMeshRegistry.isDisplayable(block) ? BLOCK : NONE;
        }
        if (ItemModels.of(item) != null) return MODEL;
        if (item instanceof ItemType type
                && ((type.getAtlasX() >= 0 && type.getAtlasY() >= 0) || SpriteVoxelizer.isVoxelizable(type))) {
            return HeldGrips.isToolOrWeapon(type) ? TOOL : ITEM;
        }
        return NONE;
    }
}

package com.stonebreak.blocks.campfire;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.crafting.SmeltingRecipe;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One campfire: what is cooking on it, and — when a goblin camp has made it its hearth — that
 * camp's saved data, kept here so the camp is saved, loaded and frozen with the fire's chunk.
 *
 * <p>Anything the furnace can smelt cooks here too ({@link SmeltingManager}), one item per spot,
 * {@link #COOK_SECONDS} each, no fuel. A finished item comes off the fire as a drop.
 *
 * <p>State string: {@code campfire:state=Lit[;cook=<id>@<seconds>,...][;camp=<data>]}. The
 * {@code state=} key is the mesh/clip variant ({@code BlockRenderState.meshVariantKey}), so the
 * payload after it never remeshes or restarts the flicker.
 */
public final class CampfireState {

    public static final String PREFIX = "campfire:";
    public static final String LIT = "Lit";
    /** Spots on the fire. */
    public static final int SLOTS = 4;
    public static final float COOK_SECONDS = 8.0f;

    private final BlockPos pos;
    private final int[] cooking = new int[SLOTS];      // item id, 0 = empty spot
    private final float[] progress = new float[SLOTS]; // seconds cooked
    private String campData;

    public CampfireState(BlockPos pos) {
        this.pos = pos;
    }

    public BlockPos pos() {
        return pos;
    }

    public static boolean isCampfireState(String raw) {
        return raw != null && raw.startsWith(PREFIX);
    }

    /** The goblin camp's saved data, or {@code null} for a plain fire. */
    public String campData() {
        return campData;
    }

    public void setCampData(String campData) {
        this.campData = campData == null || campData.isBlank() ? null : campData;
    }

    public boolean hasFreeSpot() {
        for (int id : cooking) {
            if (id == 0) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code item} cooks into something. */
    public static boolean isCookable(SmeltingManager smelting, Item item) {
        return smelting != null && item != null && smelting.getRecipe(new ItemStack(item, 1)) != null;
    }

    /** Puts one {@code item} on a free spot; false when it does not cook or the fire is full. */
    public boolean put(SmeltingManager smelting, Item item) {
        if (!isCookable(smelting, item)) {
            return false;
        }
        for (int i = 0; i < SLOTS; i++) {
            if (cooking[i] == 0) {
                cooking[i] = item.getId();
                progress[i] = 0.0f;
                return true;
            }
        }
        return false;
    }

    /** Cooks for {@code dt}; whatever finished, already turned into its cooked result. */
    public List<ItemStack> tick(SmeltingManager smelting, float dt) {
        List<ItemStack> done = null;
        for (int i = 0; i < SLOTS; i++) {
            if (cooking[i] == 0) {
                continue;
            }
            progress[i] += dt;
            if (progress[i] < COOK_SECONDS) {
                continue;
            }
            SmeltingRecipe recipe = smelting == null ? null : smelting.getRecipe(new ItemStack(cooking[i], 1));
            if (done == null) {
                done = new ArrayList<>();
            }
            done.add(recipe != null ? recipe.getOutput().copy() : new ItemStack(cooking[i], 1));
            cooking[i] = 0;
            progress[i] = 0.0f;
        }
        return done == null ? List.of() : done;
    }

    /** What is still on the fire, uncooked — spilled when the fire is broken. */
    public List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>();
        for (int id : cooking) {
            if (id != 0) {
                out.add(new ItemStack(id, 1));
            }
        }
        return out;
    }

    public String toStateString() {
        StringBuilder sb = new StringBuilder(PREFIX).append("state=").append(LIT);
        StringBuilder cook = new StringBuilder();
        for (int i = 0; i < SLOTS; i++) {
            if (cooking[i] != 0) {
                if (!cook.isEmpty()) {
                    cook.append(',');
                }
                cook.append(cooking[i]).append('@').append(Math.round(progress[i] * 10) / 10f);
            }
        }
        if (!cook.isEmpty()) {
            sb.append(";cook=").append(cook);
        }
        if (campData != null) {
            sb.append(";camp=").append(campData);
        }
        return sb.toString();
    }

    /** Reads a state string; a missing or malformed one is an unlit-looking empty fire. */
    public static CampfireState parse(BlockPos pos, String raw) {
        CampfireState s = new CampfireState(pos);
        if (!isCampfireState(raw)) {
            return s;
        }
        for (String field : raw.substring(PREFIX.length()).split(";")) {
            int eq = field.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = field.substring(0, eq);
            String value = field.substring(eq + 1);
            if (key.equals("camp")) {
                s.setCampData(value);
            } else if (key.equals("cook")) {
                int slot = 0;
                for (String entry : value.split(",")) {
                    int at = entry.indexOf('@');
                    if (at < 0 || slot >= SLOTS) {
                        continue;
                    }
                    try {
                        s.cooking[slot] = Integer.parseInt(entry.substring(0, at));
                        s.progress[slot] = Float.parseFloat(entry.substring(at + 1));
                        slot++;
                    } catch (NumberFormatException ignored) {
                        // skip a damaged entry
                    }
                }
            }
        }
        return s;
    }
}

package com.stonebreak.blocks.campfire;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The authoritative world's campfires: cooking and the camp data they carry. Server-side only —
 * a client draws a fire from its block and state string and needs nothing more.
 *
 * <p>Like the furnace and crafting table, a fire's state lives in its cube's block states, so the
 * cube save captures it. The string is rewritten (and echoed to clients) only when what is on
 * the fire or the camp changes; cooking progress in between is flushed at save points and when
 * the cube unloads.
 */
public class CampfireRegistry {

    /** Fired when a fire's state string changes, to echo it to clients. */
    @FunctionalInterface
    public interface StateChangeListener {
        void onCampfireStateChanged(BlockPos pos, String stateString);
    }

    private final Map<BlockPos, CampfireState> states = new ConcurrentHashMap<>();
    private final SmeltingManager smelting;
    private volatile StateChangeListener stateChangeListener;
    private volatile CampAnchor anchor = CampAnchor.NONE;

    public CampfireRegistry(SmeltingManager smelting) {
        this.smelting = smelting;
    }

    public void setStateChangeListener(StateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    public void setAnchor(CampAnchor anchor) {
        this.anchor = anchor != null ? anchor : CampAnchor.NONE;
    }

    public CampfireState get(BlockPos pos) {
        return states.get(pos);
    }

    public Collection<BlockPos> positions() {
        return Collections.unmodifiableSet(states.keySet());
    }

    public SmeltingManager smelting() {
        return smelting;
    }

    /** Whether {@code item} would cook on a fire. */
    public boolean isCookable(Item item) {
        return CampfireState.isCookable(smelting, item);
    }

    /** Puts one {@code item} on the fire at {@code pos}; false when it is not a fire, full, or uncookable. */
    public boolean cook(World world, BlockPos pos, Item item) {
        CampfireState s = states.get(pos);
        if (s == null || !s.put(smelting, item)) {
            return false;
        }
        write(world, s);
        return true;
    }

    /** Saves a camp's data in its fire (and tells clients). */
    public void setCampData(World world, BlockPos pos, String campData) {
        CampfireState s = states.get(pos);
        if (s != null) {
            s.setCampData(campData);
            write(world, s);
        }
    }

    /* ── Lifecycle ───────────────────────────────────────────── */

    public void onBlockPlaced(World world, int x, int y, int z) {
        CampfireState s = new CampfireState(new BlockPos(x, y, z));
        CampfireState previous = states.put(s.pos(), s);
        if (previous != null) {
            anchor.extinguished(s.pos());
        }
        write(world, s);
    }

    /** The fire went out — broken or replaced. Spills what was cooking; any camp there disbands. */
    public void onBlockBroken(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        CampfireState s = states.remove(pos);
        if (s == null) {
            return;
        }
        Vector3d at = new Vector3d(x + 0.5, y + 0.5, z + 0.5);
        for (ItemStack item : s.contents()) {
            DropUtil.createItemDrop(world, at, item);
        }
        anchor.extinguished(pos);
    }

    /** Discovers the fires of a cube that entered the world, and wakes the camps they anchor. */
    public void onCubeInstalled(Cube cube) {
        List<CampfireState> withCamps = new ArrayList<>();
        cube.forEachState((x, y, z, value) -> {
            if (!CampfireState.isCampfireState(value)) {
                return;
            }
            BlockPos pos = new BlockPos(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z));
            CampfireState s = CampfireState.parse(pos, value);
            states.put(pos, s);
            if (s.campData() != null) {
                withCamps.add(s);
            }
        });
        for (CampfireState s : withCamps) {
            anchor.loaded(s.pos(), s.campData());
        }
    }

    /**
     * The cube is about to unload: every camp anchored in it freezes into its fire, and the fire's
     * state — cooking included — is written to the still-resident cube, ahead of its save.
     */
    public void freezeCamps(World world, Cube cube) {
        for (CampfireState s : statesIn(cube)) {
            if (s.campData() != null) {
                s.setCampData(anchor.unloading(s.pos()));
            }
            write(world, s);
        }
    }

    /** A save point: writes every fire's current state (cooking progress) into the cube. */
    public void flushToWorld(World world, Cube cube) {
        for (CampfireState s : statesIn(cube)) {
            BlockPos p = s.pos();
            if (CampfireState.isCampfireState(world.getBlockStateAt(p.x(), p.y(), p.z()))) {
                write(world, s);
            }
        }
    }

    /** Drops the cube's fires (their camps froze and their state was written in {@link #freezeCamps}). */
    public void onCubeUnloaded(Cube cube) {
        states.keySet().removeIf(p -> cube.contains(p.x(), p.y(), p.z()));
    }

    private List<CampfireState> statesIn(Cube cube) {
        List<CampfireState> in = new ArrayList<>();
        for (CampfireState s : states.values()) {
            BlockPos p = s.pos();
            if (cube.contains(p.x(), p.y(), p.z())) {
                in.add(s);
            }
        }
        return in;
    }

    /** Cooks, and lets go of any fire whose block is no longer a campfire. */
    public void tick(World world, float dt) {
        for (CampfireState s : states.values()) {
            BlockPos p = s.pos();
            if (!world.isLoaded(p.x(), p.y(), p.z())) {
                continue; // unloading — the cube hook saves it
            }
            if (world.getBlockAt(p.x(), p.y(), p.z()) != BlockType.CAMPFIRE) {
                onBlockBroken(world, p.x(), p.y(), p.z());
                continue;
            }
            List<ItemStack> done = s.tick(smelting, dt);
            if (!done.isEmpty()) {
                // Cooked food hops off the fire, for a player — or a goblin — to pick up.
                Vector3d at = new Vector3d(p.x() + 0.5, p.y() + 0.6, p.z() + 0.5);
                for (ItemStack item : done) {
                    DropUtil.createItemDrop(world, at, item);
                }
                write(world, s);
            }
        }
    }

    private void write(World world, CampfireState s) {
        if (world == null) {
            return;
        }
        BlockPos p = s.pos();
        String next = s.toStateString();
        String previous = world.getBlockStateAt(p.x(), p.y(), p.z());
        world.setBlockStateAt(p.x(), p.y(), p.z(), next);
        StateChangeListener l = stateChangeListener;
        if (l != null && !next.equals(previous)) {
            l.onCampfireStateChanged(p, next);
        }
    }
}

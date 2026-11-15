package com.stonebreak.blocks.campfire;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.util.DropUtil;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.utils.LocalBlockKey;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The authoritative world's campfires: cooking and the camp data they carry. Server-side only —
 * a client draws a fire from its block and state string and needs nothing more.
 *
 * <p>Like the furnace and crafting table, a fire's state lives in its chunk's block states, so the
 * chunk save captures it. The string is rewritten (and echoed to clients) only when what is on
 * the fire or the camp changes; cooking progress in between is flushed when the chunk unloads.
 */
public class CampfireRegistry {

    private static final int CHUNK_SIZE = 16;

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
        Vector3f at = new Vector3f(x + 0.5f, y + 0.5f, z + 0.5f);
        for (ItemStack item : s.contents()) {
            DropUtil.createItemDrop(world, at, item);
        }
        anchor.extinguished(pos);
    }

    public void onChunkLoaded(Chunk chunk) {
        int cx = chunk.getX();
        int cz = chunk.getZ();
        List<CampfireState> withCamps = new ArrayList<>();
        for (Map.Entry<Integer, String> e : chunk.getBlockStates().entrySet()) {
            if (!CampfireState.isCampfireState(e.getValue())) {
                continue;
            }
            int key = e.getKey();
            BlockPos pos = new BlockPos(cx * CHUNK_SIZE + LocalBlockKey.x(key), LocalBlockKey.y(key),
                    cz * CHUNK_SIZE + LocalBlockKey.z(key));
            CampfireState s = CampfireState.parse(pos, e.getValue());
            states.put(pos, s);
            if (s.campData() != null) {
                withCamps.add(s);
            }
        }
        for (CampfireState s : withCamps) {
            anchor.loaded(s.pos(), s.campData());
        }
    }

    /**
     * The chunk is about to unload: every camp anchored in it freezes into its fire, and the
     * fire's state — cooking included — is written to the still-resident chunk, ahead of its save.
     */
    public void freezeCamps(World world, Chunk chunk) {
        for (CampfireState s : statesIn(chunk)) {
            if (s.campData() != null) {
                s.setCampData(anchor.unloading(s.pos()));
            }
            write(world, s);
        }
    }

    /** Drops the chunk's fires, flushing each one's state (the camps froze in freezeCamps). */
    public void onChunkUnloaded(Chunk chunk) {
        int xMin = chunk.getX() * CHUNK_SIZE;
        int zMin = chunk.getZ() * CHUNK_SIZE;
        Iterator<Map.Entry<BlockPos, CampfireState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, CampfireState> entry = it.next();
            BlockPos p = entry.getKey();
            if (p.x() < xMin || p.x() >= xMin + CHUNK_SIZE || p.z() < zMin || p.z() >= zMin + CHUNK_SIZE) {
                continue;
            }
            CampfireState s = entry.getValue();
            int lx = Math.floorMod(p.x(), CHUNK_SIZE);
            int lz = Math.floorMod(p.z(), CHUNK_SIZE);
            if (CampfireState.isCampfireState(chunk.getBlockState(lx, p.y(), lz))) {
                chunk.setBlockState(lx, p.y(), lz, s.toStateString());
            }
            it.remove();
        }
    }

    private List<CampfireState> statesIn(Chunk chunk) {
        int xMin = chunk.getX() * CHUNK_SIZE;
        int zMin = chunk.getZ() * CHUNK_SIZE;
        List<CampfireState> in = new ArrayList<>();
        for (CampfireState s : states.values()) {
            BlockPos p = s.pos();
            if (p.x() >= xMin && p.x() < xMin + CHUNK_SIZE && p.z() >= zMin && p.z() < zMin + CHUNK_SIZE) {
                in.add(s);
            }
        }
        return in;
    }

    /** Cooks, and lets go of any fire whose block is no longer a campfire. */
    public void tick(World world, float dt) {
        for (CampfireState s : states.values()) {
            BlockPos p = s.pos();
            if (world.getChunkIfLoaded(Math.floorDiv(p.x(), CHUNK_SIZE), Math.floorDiv(p.z(), CHUNK_SIZE)) == null) {
                continue; // unloading — the chunk hook saves it
            }
            if (world.getBlockAt(p.x(), p.y(), p.z()) != BlockType.CAMPFIRE) {
                onBlockBroken(world, p.x(), p.y(), p.z());
                continue;
            }
            List<ItemStack> done = s.tick(smelting, dt);
            if (!done.isEmpty()) {
                // Cooked food hops off the fire, for a player — or a goblin — to pick up.
                Vector3f at = new Vector3f(p.x() + 0.5f, p.y() + 0.6f, p.z() + 0.5f);
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

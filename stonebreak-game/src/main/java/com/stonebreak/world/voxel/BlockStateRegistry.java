package com.stonebreak.world.voxel;

import com.stonebreak.blocks.stairs.StairState;
import com.stonebreak.blocks.stalagmite.Stalagmite;
import com.stonebreak.blocks.stalagmite.StalagmiteState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

/**
 * Block-state names &lt;-&gt; the uint16 ids the {@link VoxelLayers#STATE} layer stores, per world.
 *
 * <p>What is registered is a state's MESH VARIANT KEY ({@code BlockRenderState.meshVariantKey}): stair
 * facings, a lit furnace, stalagmite sizes. Volatile payload (furnace inventory, crafting grid) never
 * becomes an id - it would grow the table forever - and lives in the cube's game payload instead.
 *
 * <p><b>Fixed ids.</b> Id 0 is "no state". Ids 1..26 are the stalagmite states the native generator
 * writes (anchor {@code ((size - 1) * 2 + hanging) * 4 + facing} at {@code 1 + that}, then the
 * {@link Stalagmite#UPPER} / {@link Stalagmite#LOWER} parts), registered by the constructor in a fixed
 * order so every world agrees on them and the generator config ({@link #stalagmiteAnchorIds},
 * {@link #stalagmitePartIds}) needs no lookup. Everything else gets the next free id on first use.
 *
 * <p><b>Persistence and network.</b> The mapping is part of the world: {@link #save}/{@link #load} write it
 * as UTF-8 lines (line i = name of id i + 1) next to the world's cube store
 * ({@code <world>/cubes/block_states.txt}, see {@code World}). A client's registry must mirror its
 * server's ids (cube bytes carry raw ids): the network sends {@link #names()} on join and every new name
 * as it is registered ({@link #addListener}) - 3B-N.
 *
 * <p>Thread-safe: ids are assigned under the instance lock, lookups are lock-free.
 */
public final class BlockStateRegistry {

    public static final int NONE = 0;
    public static final int MAX_ID = 0xFFFF;
    /** Number of fixed (stalagmite) ids after {@link #NONE}. */
    public static final int FIXED_IDS = 26;

    private final Map<String, Integer> ids = new ConcurrentHashMap<>();
    private volatile String[] names = new String[64];
    private int next = 1;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Told about every id assigned after construction (on the registering thread). */
    @FunctionalInterface
    public interface Listener {
        void onRegistered(int id, String name);
    }

    public BlockStateRegistry() {
        for (String fixed : fixedNames()) {
            register(fixed);
        }
    }

    /** The fixed names in id order (id 1 first). */
    public static List<String> fixedNames() {
        List<String> out = new ArrayList<>(FIXED_IDS);
        StairState.Facing[] facings = StairState.Facing.values();
        for (int size = 1; size <= 3; size++) {
            for (int hanging = 0; hanging < 2; hanging++) {
                for (int facing = 0; facing < 4; facing++) {
                    out.add(new StalagmiteState(size, hanging == 1, facings[facing]).toStateString());
                }
            }
        }
        out.add(Stalagmite.UPPER);
        out.add(Stalagmite.LOWER);
        return out;
    }

    /** {@code stalagmiteStates[((size - 1) * 2 + hanging) * 4 + facing]} for the native generator config. */
    public short[] stalagmiteAnchorIds() {
        short[] out = new short[24];
        for (int i = 0; i < 24; i++) {
            out[i] = (short) (1 + i);
        }
        return out;
    }

    /**
     * The state ids of a wild (grown) blueberry bush's stages for the native generator config (SBW ABI 6):
     * Mature, Berries_Unripe, Berries_Ripening, Berries_Ripe — the stage-only strings the world stores
     * ({@code BlueberryBushState.toStageString}). Registered on first use, so a world's table only gains
     * them once it generates with them (fixed ids would collide with ids an existing world already holds).
     */
    public short[] blueberryWildStateIds() {
        com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage[] wild = {
            com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage.MATURE,
            com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage.BERRIES_UNRIPE,
            com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage.BERRIES_RIPENING,
            com.stonebreak.blocks.blueberrybush.BlueberryBushState.Stage.BERRIES_RIPE
        };
        short[] out = new short[wild.length];
        for (int i = 0; i < wild.length; i++) {
            out[i] = (short) idOf(new com.stonebreak.blocks.blueberrybush.BlueberryBushState(wild[i], 0).toStageString());
        }
        return out;
    }

    /** {upright part, hanging part} for the native generator config. */
    public short[] stalagmitePartIds() {
        return new short[]{(short) 25, (short) 26};
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** The id of {@code name}, registering it when new; {@link #NONE} for null/blank. */
    public int idOf(String name) {
        if (name == null || name.isBlank()) {
            return NONE;
        }
        Integer id = ids.get(name);
        return id != null ? id : register(name);
    }

    /** The id of {@code name} without registering; {@link #NONE} when unknown. */
    public int find(String name) {
        if (name == null) {
            return NONE;
        }
        Integer id = ids.get(name);
        return id != null ? id : NONE;
    }

    /** The name of {@code id}, null for {@link #NONE} or an unknown id. */
    public String nameOf(int id) {
        String[] n = names;
        return id > 0 && id < n.length ? n[id] : null;
    }

    /** Number of registered names (ids 1..size). */
    public int size() {
        synchronized (this) {
            return next - 1;
        }
    }

    /** Every name in id order (index i = id i + 1). */
    public List<String> names() {
        synchronized (this) {
            List<String> out = new ArrayList<>(next - 1);
            for (int i = 1; i < next; i++) {
                out.add(names[i]);
            }
            return out;
        }
    }

    private int register(String name) {
        int id;
        synchronized (this) {
            Integer existing = ids.get(name);
            if (existing != null) {
                return existing;
            }
            if (next > MAX_ID) {
                throw new IllegalStateException("block state table full (" + MAX_ID + " names)");
            }
            id = next++;
            String[] n = names;
            if (id >= n.length) {
                n = java.util.Arrays.copyOf(n, Math.min(MAX_ID + 1, n.length * 2));
            }
            n[id] = name;
            names = n;
            ids.put(name, id);
        }
        for (Listener l : listeners) {
            l.onRegistered(id, name);
        }
        return id;
    }

    /**
     * Replaces the table with {@code orderedNames} (index i = id i + 1), e.g. a saved world or the server's
     * table. The fixed names must come first and unchanged (a table that disagrees is rejected).
     */
    public void replaceAll(List<String> orderedNames) {
        List<String> fixed = fixedNames();
        if (orderedNames.size() < fixed.size() || !orderedNames.subList(0, fixed.size()).equals(fixed)) {
            throw new IllegalArgumentException("block state table does not start with the fixed stalagmite states");
        }
        List<int[]> added = new ArrayList<>();
        synchronized (this) {
            ids.clear();
            String[] n = new String[Math.max(64, Integer.highestOneBit(orderedNames.size() + 1) * 2)];
            for (int i = 0; i < orderedNames.size(); i++) {
                n[i + 1] = orderedNames.get(i);
                ids.put(orderedNames.get(i), i + 1);
                added.add(new int[]{i + 1});
            }
            names = n;
            next = orderedNames.size() + 1;
        }
        for (int[] id : added) {
            for (Listener l : listeners) {
                l.onRegistered(id[0], nameOf(id[0]));
            }
        }
    }

    /** Visits every registered id. */
    public void forEachId(IntConsumer action) {
        int n = size();
        for (int id = 1; id <= n; id++) {
            action.accept(id);
        }
    }

    // ---- persistence ------------------------------------------------------------------------------------------

    /** Writes the table (atomic replace). Names never contain a line break (they are state strings). */
    public void save(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, names(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Loads a table written by {@link #save}; false when the file does not exist. */
    public boolean load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return false;
        }
        replaceAll(Files.readAllLines(file, StandardCharsets.UTF_8));
        return true;
    }
}

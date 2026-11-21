package com.stonebreak.world.growth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.handlers.FlowBlockInteraction;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.generation.trees.CypressTree;
import com.stonebreak.world.generation.trees.RegularTree;
import com.stonebreak.world.generation.trees.TreeBlockSink;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Grows placed saplings into trees ({@link Species}) after a random delay, the way a
 * Minecraft sapling matures. The delay only counts down while the sapling's cube is loaded.
 *
 * <p><b>Persistence (#447).</b> Each sapling's remaining time is kept in its cube's blob section
 * {@value #BLOB_SECTION} ({@link #encodeSection}: {@code u32 n | n x (u16 cell, i32 ticks)}), written
 * every {@value #PERSIST_INTERVAL_TICKS} ticks and when the cube is about to unload, and read back when
 * the cube installs. (It used to be a {@code grow:<ticks>} block-state string; in the cubic world block
 * states become ids in a per-world table, and a countdown would have registered a new id every second.)
 *
 * <p>Runs on authoritative worlds only, at 20 TPS like {@code LeafDecaySystem}. Positions are full
 * {@code long} triples ({@link Pos}), cubes {@link CubePos}.
 */
public final class SaplingGrowthSystem {

    /** Blob section holding a cube's sapling progress. */
    public static final String BLOB_SECTION = "saplings";
    /** 5–10 minutes at 20 TPS. */
    static final int MIN_GROW_TICKS = 5 * 60 * 20;
    static final int GROW_TICK_RANGE = 5 * 60 * 20;
    /** Wait before retrying a sapling that was due but couldn't grow. */
    static final int RETRY_TICKS = 10 * 20;
    /** How often remaining time is written back to the cube for saving. */
    static final int PERSIST_INTERVAL_TICKS = 20;
    private static final int MAX_TICKS_PER_FRAME = 2;
    private static final float TICK_INTERVAL = 1.0f / 20.0f;

    /** What each sapling grows into, with the space that tree needs. */
    private enum Species {
        // headroom: the shortest cypress trunk
        CYPRESS(BlockType.CYPRESS_SAPLING, CypressTree.MAX_HEIGHT, CypressTree.LEAF_RADIUS, 10,
            CypressTree::place),
        // headroom: the tallest oak trunk, so no variant is cut short by a ceiling
        DRY_OAK(BlockType.DRY_OAK_SAPLING, RegularTree.MAX_HEIGHT, RegularTree.LEAF_RADIUS, 7,
            (out, x, y, z) -> RegularTree.place(out, x, y, z, BlockType.DRY_OAK_LOG, BlockType.DRY_OAK_LEAVES));

        private final BlockType sapling;
        private final int maxHeight;
        private final int leafRadius;
        private final int headroom;
        private final TreeShape shape;

        Species(BlockType sapling, int maxHeight, int leafRadius, int headroom, TreeShape shape) {
            this.sapling = sapling;
            this.maxHeight = maxHeight;
            this.leafRadius = leafRadius;
            this.headroom = headroom;
            this.shape = shape;
        }

        static Species of(BlockType block) {
            for (Species s : values()) {
                if (s.sapling == block) {
                    return s;
                }
            }
            return null;
        }
    }

    @FunctionalInterface
    private interface TreeShape {
        void place(TreeBlockSink out, int x, int y, int z);
    }

    private final SaplingWorld world;
    private final Random random;
    private final Map<Pos, Integer> remaining = new LinkedHashMap<>();
    private float tickAccumulator;
    private long logicalTick;

    public SaplingGrowthSystem(SaplingWorld world, Random random) {
        this.world = Objects.requireNonNull(world, "world");
        this.random = Objects.requireNonNull(random, "random");
        world.attach(this);
    }

    /** Soil a sapling may be planted on and grow from. */
    public static boolean isSoil(BlockType block) {
        return block == BlockType.DIRT || block == BlockType.GRASS || block == BlockType.SWAMPY_GRASS
            || block == BlockType.DRY_GRASS;
    }

    public static boolean isSapling(BlockType block) {
        return Species.of(block) != null;
    }

    // ===== Tick driving =====

    /** Frame-time entry point: accumulates delta and runs logical ticks at 20 TPS. */
    public void tick(float deltaTimeSeconds) {
        float delta = Float.isFinite(deltaTimeSeconds) ? Math.max(0.0f, deltaTimeSeconds) : 0.0f;
        tickAccumulator += delta;
        int ticksToRun = 0;
        while (tickAccumulator >= TICK_INTERVAL && ticksToRun < MAX_TICKS_PER_FRAME) {
            tickAccumulator -= TICK_INTERVAL;
            ticksToRun++;
        }
        advanceTicks(ticksToRun);
    }

    /** Advances whole logical ticks directly — deterministic driver for tests. */
    public void advanceTicks(int ticks) {
        for (int i = 0; i < ticks; i++) {
            logicalTick++;
            step();
        }
    }

    public int getTrackedCount() {
        return remaining.size();
    }

    /** Remaining ticks of the sapling at a position, or -1 when none is tracked there. */
    public int remainingAt(long x, long y, long z) {
        Integer left = remaining.get(new Pos(x, y, z));
        return left == null ? -1 : left;
    }

    private void step() {
        if (remaining.isEmpty()) {
            return;
        }
        List<Pos> due = new ArrayList<>();
        for (Map.Entry<Pos, Integer> e : remaining.entrySet()) {
            int left = e.getValue() - 1;
            e.setValue(left);
            if (left <= 0) {
                due.add(e.getKey());
            }
        }
        // Growth writes blocks, which re-enter onBlockChanged and edit the map, so it runs
        // after the countdown pass rather than inside it.
        Set<CubePos> touched = new HashSet<>();
        for (Pos pos : due) {
            touched.add(pos.cube());
            if (tryGrow(pos)) {
                remaining.remove(pos);
            } else if (remaining.containsKey(pos)) {
                remaining.put(pos, RETRY_TICKS);
            }
        }
        if (logicalTick % PERSIST_INTERVAL_TICKS == 0) {
            for (Pos pos : remaining.keySet()) {
                touched.add(pos.cube());
            }
        } else if (touched.isEmpty()) {
            return;
        }
        for (CubePos cube : touched) {
            persist(cube);
        }
    }

    /** Writes the cube's current sapling progress (or clears it when none is left). */
    private void persist(CubePos cube) {
        world.writeSaplings(cube, encodeSection(saplingsIn(cube)));
    }

    private Map<Integer, Integer> saplingsIn(CubePos cube) {
        Map<Integer, Integer> cells = new LinkedHashMap<>();
        for (Map.Entry<Pos, Integer> e : remaining.entrySet()) {
            Pos p = e.getKey();
            if (p.inCube(cube)) {
                cells.put(Cube.cellOf(p.x(), p.y(), p.z()), e.getValue());
            }
        }
        return cells;
    }

    // ===== External triggers =====

    /** Block-change funnel (World.setBlockAt, authoritative worlds only). */
    public void onBlockChanged(long x, long y, long z, BlockType previous, BlockType next) {
        Pos pos = new Pos(x, y, z);
        if (isSapling(next)) {
            int ticks = MIN_GROW_TICKS + random.nextInt(GROW_TICK_RANGE);
            remaining.put(pos, ticks);
            persist(pos.cube());
        } else if (isSapling(previous)) {
            if (remaining.remove(pos) != null) {
                persist(pos.cube());
            }
        }
    }

    /** Resumes the saplings saved with a cube that became resident. */
    public void onCubeLoaded(CubePos cube) {
        Map<Integer, Integer> cells = decodeSection(world.readSaplings(cube));
        for (Map.Entry<Integer, Integer> e : cells.entrySet()) {
            int cell = e.getKey();
            Pos pos = new Pos(cube.minBlockX() + Cube.localX(cell), cube.minBlockY() + Cube.localY(cell),
                cube.minBlockZ() + Cube.localZ(cell));
            if (isSapling(world.getBlock(pos.x(), pos.y(), pos.z()))) {
                remaining.put(pos, e.getValue());
            }
        }
    }

    public void onCubeInstalled(Cube cube) {
        onCubeLoaded(cube.pos());
    }

    /** The cube leaves next tick: write its latest progress into it (World unload-pending hook). */
    public void onCubeUnloadPending(CubePos cube) {
        Map<Integer, Integer> cells = saplingsIn(cube);
        if (!cells.isEmpty()) {
            world.writeSaplings(cube, encodeSection(cells));
        }
    }

    /** Stops tracking the saplings of a cube that left (its progress was written while pending). */
    public void onCubeUnloaded(CubePos cube) {
        remaining.keySet().removeIf(p -> p.inCube(cube));
    }

    public void onCubeUnloaded(Cube cube) {
        onCubeUnloaded(cube.pos());
    }

    // ===== Section codec =====

    /** {@code u32 n | n x (u16 cell, i32 ticks)}; null for no saplings (clears the section). */
    static byte[] encodeSection(Map<Integer, Integer> cells) {
        if (cells.isEmpty()) {
            return null;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4 + cells.size() * 6);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(cells.size());
            for (Map.Entry<Integer, Integer> e : cells.entrySet()) {
                out.writeShort(e.getKey());
                out.writeInt(e.getValue());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Inverse of {@link #encodeSection}; empty for null/corrupt bytes. */
    static Map<Integer, Integer> decodeSection(byte[] section) {
        Map<Integer, Integer> out = new HashMap<>();
        if (section == null || section.length < 4) {
            return out;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(section))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int cell = in.readUnsignedShort();
                int ticks = in.readInt();
                if (cell < CubeWorld.CUBE_EDGE * CubeWorld.CUBE_EDGE * CubeWorld.CUBE_EDGE) {
                    out.put(cell, Math.max(0, ticks));
                }
            }
        } catch (IOException e) {
            out.clear();
        }
        return out;
    }

    // ===== Growth =====

    private boolean tryGrow(Pos pos) {
        long x = pos.x();
        long y = pos.y();
        long z = pos.z();
        Species species = Species.of(world.getBlock(x, y, z));
        if (species == null) {
            return true; // gone without a funnel notice — just stop tracking it
        }
        if (!isSoil(world.getBlock(x, y - 1, z))
                || y + species.maxHeight >= com.stonebreak.world.operations.WorldConfiguration.MAX_Y
                || !world.isAreaLoaded(x, y, z, species.leafRadius, species.maxHeight)) {
            return false;
        }
        for (int dy = 1; dy <= species.headroom; dy++) {
            if (!isReplaceable(world.getBlock(x, y + dy, z))) {
                return false;
            }
        }
        species.shape.place((bx, by, bz, type) -> {
            if (isReplaceable(world.getBlock(bx, by, bz))) {
                world.setBlock(bx, by, bz, type);
            }
        }, Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z));
        return true;
    }

    /** A growing tree may only fill space that nothing else holds — never a player's build. */
    static boolean isReplaceable(BlockType block) {
        return block == BlockType.AIR || block == BlockType.WATER || block.isLeaves()
            || isSapling(block) || FlowBlockInteraction.isFragile(block);
    }

    /** A sapling position: a full {@code long} triple (never packed), hashed like {@link CubePos}. */
    record Pos(long x, long y, long z) {

        CubePos cube() {
            return CubePos.ofBlock(x, y, z);
        }

        boolean inCube(CubePos c) {
            return (x >> 5) == c.x() && (y >> 5) == c.y() && (z >> 5) == c.z();
        }

        @Override
        public int hashCode() {
            long h = CubePos.hash(x, y, z);
            return (int) (h ^ (h >>> 32));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pos p && p.x == x && p.y == y && p.z == z;
        }
    }
}

package com.stonebreak.world.physics;

import com.openmason.engine.physics.FluidSample;
import com.openmason.engine.physics.PhysicsBody;
import com.openmason.engine.physics.PhysicsIntegrator;
import com.openmason.engine.physics.PhysicsWorld;
import com.openmason.engine.physics.RayHit;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.anim.AnimatedBlockRegistry;
import com.stonebreak.blocks.anim.AnimatedBlockShapes;
import com.stonebreak.blocks.stairs.StairShape;
import com.stonebreak.blocks.stairs.StairState;
import com.stonebreak.blocks.stalagmite.Stalagmite;
import com.stonebreak.blocks.stalagmite.StalagmiteShape;
import com.stonebreak.blocks.torch.TorchBlock;
import com.stonebreak.blocks.waterSystem.WaterFlowPhysics;
import com.stonebreak.blocks.waterSystem.WaterHeightUtil;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.ChunkWaterLayer;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stonebreak's side of the engine physics (Open Mason {@code cp_*}, OpenMason #117): what each
 * block IS to physics, fed to one native {@link PhysicsWorld} per {@link World}.
 *
 * <p><b>Shapes.</b> A cell's shape follows the same rules {@link com.stonebreak.blocks.BlockShape}
 * states: snow answers its layer height, stairs their treads (as boxes, so a body meets the tread it
 * is really on), stalagmites the tiers of their model, every other block its collision height — all
 * {@link #SOLID} and {@link #PICK}. Blocks without collision but with something to aim at (flowers,
 * grass) are {@link #PICK} only. Animated cells (doors, torches) collide only as {@link #DOOR_CELL}
 * (mobs and drops) and are aimed at and walked into through their posed model boxes —
 * {@link #PANEL} for doors, {@link #MODEL_PICK} for torches — kept as dynamic boxes. Shapes below a
 * full block (snow below 8 layers, slabs) are {@code SHAPE_PARTIAL}: the player climbs them up to a
 * full block. Stairs are not: their treads are each a normal half-block step.
 *
 * <p><b>Fluids.</b> Water cells carry their surface height ({@link WaterHeightUtil}) and the force
 * of their current ({@link WaterFlowPhysics#cellForce}).
 *
 * <p><b>Cache.</b> The engine pulls cells through {@link #fill} one 16³ brick (a chunk section) at a
 * time and keeps them; the world calls the {@code invalidate*} methods wherever a block, state, snow
 * layer, water level or chunk changes. Invalidation is lazy and cheap.
 *
 * <p><b>Threads.</b> Owned by the thread that simulates the world (the client's main thread, the
 * server's tick thread); invalidation is safe from anywhere.
 */
public final class WorldPhysics implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldPhysics.class);

    // ---- categories (cell shapes and dynamic boxes) -----------------------------------------
    /** Collides with every body. */
    public static final int SOLID = 1;
    /** Something to aim at: picking rays and line of sight stop here. */
    public static final int PICK = 2;
    /** An animated block's cell: mobs and drops treat it as a block, the player and rays use its model. */
    public static final int DOOR_CELL = 4;
    /** A door's posed model box: the player walks into it, rays stop at it. */
    public static final int PANEL = 8;
    /** A torch's model box: aimed at, never walked into. */
    public static final int MODEL_PICK = 16;
    /** A static scene collider (the battle-test arena): walls and sight blockers, not blocks to target. */
    public static final int SCENE = 32;

    /** Fluid category of water. */
    public static final int WATER = 1;

    // ---- masks ---------------------------------------------------------------------------
    public static final int PLAYER_MASK = SOLID | PANEL;
    public static final int MOB_MASK = SOLID | DOOR_CELL;
    public static final int DROP_MASK = SOLID | DOOR_CELL;
    /** Block picking and placement: anything with a surface to aim at. */
    public static final int PICK_MASK = PICK | PANEL | MODEL_PICK;
    /** Line of sight (cameras, hitscan): what can be aimed at, and scene colliders. */
    public static final int SIGHT_MASK = PICK_MASK | SCENE;
    /** What a projectile stops at: any block, door cell or model. */
    public static final int PROJECTILE_MASK = PICK | DOOR_CELL | PANEL | MODEL_PICK;

    /** The player climbs anything up to half a block, partial shapes (snow, treads) up to a full block. */
    public static final float PLAYER_STEP = 0.5f;
    public static final float PLAYER_PARTIAL_STEP = 1.0f;

    private static final int BRICK_SHIFT = brickShift();
    /** 2048 bricks = 2048 chunk sections per world, a few MiB with uniform bricks folded. */
    private static final int CACHE_BRICKS = 2048;
    /** A safety resync of the model boxes every this many syncs, whatever the version says. */
    private static final int FULL_SYNC_EVERY = 32;

    private final World world;
    private final boolean throughAccessors;
    private final PhysicsWorld engine;
    private final Stalagmite.Cells stalagmiteCells;

    // Shape table (owner thread).
    private final Map<ShapeKey, Integer> shapeIds = new HashMap<>();
    private final Map<BlockType, Integer> simpleShape = new java.util.IdentityHashMap<>();
    private final Map<FluidKey, Integer> fluidIds = new HashMap<>();
    private int nextShape = 1;
    private int nextFluid = 1;
    private final Vector3f force = new Vector3f();

    // Model boxes of animated blocks (owner thread).
    private final Map<BlockPos, ModelBox> modelBoxes = new HashMap<>();
    private int seenAnimatedVersion = -1;
    private int syncsSinceFull;

    // Scratch.
    private final FluidSample sample = new FluidSample();

    private record ShapeKey(float[] boxes, int category, int flags) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ShapeKey k && k.category == category && k.flags == flags && Arrays.equals(k.boxes, boxes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(boxes), category, flags);
        }
    }

    private record FluidKey(float surface, float fx, float fy, float fz) {
    }

    private static final class ModelBox {
        int id;
        BlockType type;
        String state;
    }

    private WorldPhysics(World world, boolean throughAccessors) {
        this.world = world;
        this.throughAccessors = throughAccessors;
        this.stalagmiteCells = Stalagmite.of(world);
        this.engine = PhysicsWorld.create(this::fill, BRICK_SHIFT, CACHE_BRICKS, 0);
        // Static scene colliders (the battle-test arena): never change, registered once.
        for (float[] b : world.getStaticCollisionBoxes(new Vector3f(), Float.MAX_VALUE)) {
            engine.addBox(b[0], b[1], b[2], b[3], b[4], b[5], SOLID | SCENE, 0, 0L);
        }
    }

    /** A physics world for {@code world}. Throws when Cenda physics is unavailable (no fallback). */
    public static WorldPhysics create(World world) {
        return new WorldPhysics(world, false);
    }

    /**
     * A physics world that reads cells through the world's public accessors ({@code getBlockAt},
     * {@code getBlockStateAt}, {@code getSnowHeight}, {@code getWaterLevelAt}) and treats every
     * region as loaded — for worlds without chunk storage behind them (unit-test stubs). Slower
     * per fill than {@link #create}, identical answers.
     */
    public static WorldPhysics overAccessors(World world) {
        return new WorldPhysics(world, true);
    }

    /** Accessor-backed physics for worlds that have none of their own (stubs), one per world. */
    private static final Map<World, WorldPhysics> DETACHED = new java.util.WeakHashMap<>();

    /**
     * The physics of {@code world}: its own ({@link World#physics()}), or — for a world without one
     * (a unit-test stub) — an {@link #overAccessors} instance kept for that world.
     */
    public static WorldPhysics of(World world) {
        WorldPhysics own = world.physics();
        if (own != null) {
            return own;
        }
        synchronized (DETACHED) {
            return DETACHED.computeIfAbsent(world, WorldPhysics::overAccessors);
        }
    }

    private static int brickShift() {
        int size = WorldConfiguration.CHUNK_SIZE;
        for (int shift = 5; shift >= 3; shift--) {
            if (size % (1 << shift) == 0) {
                return shift;
            }
        }
        return 3;
    }

    /** The engine world, for callers that drive bodies or rays directly. */
    public PhysicsWorld engine() {
        return engine;
    }

    @Override
    public void close() {
        engine.close();
    }

    // ---- the source -----------------------------------------------------------------------

    private boolean fill(int x0, int y0, int z0, int sx, int sy, int sz, short[] shapes, short[] fluids) {
        if (throughAccessors) {
            fillThroughAccessors(x0, y0, z0, sx, sy, sz, shapes, fluids);
            return true;
        }
        final int cs = WorldConfiguration.CHUNK_SIZE;
        final int height = WorldConfiguration.WORLD_HEIGHT;
        // A brick never spans two chunks (its edge divides the chunk size).
        Chunk chunk = world.getChunkIfLoaded(Math.floorDiv(x0, cs), Math.floorDiv(z0, cs));
        if (chunk == null) {
            return false;
        }
        int lx0 = Math.floorMod(x0, cs);
        int lz0 = Math.floorMod(z0, cs);
        for (int dy = 0; dy < sy; dy++) {
            int y = y0 + dy;
            if (y < 0 || y >= height) {
                continue; // outside the column: air (arrays arrive zeroed)
            }
            for (int dz = 0; dz < sz; dz++) {
                int base = (dy * sz + dz) * sx;
                for (int dx = 0; dx < sx; dx++) {
                    BlockType block = chunk.getBlock(lx0 + dx, y, lz0 + dz);
                    if (block == null || block == BlockType.AIR) {
                        continue;
                    }
                    int x = x0 + dx;
                    int z = z0 + dz;
                    if (block == BlockType.WATER) {
                        int level = ChunkWaterLayer.level(chunk.getWaterLayer().get(lx0 + dx, y, lz0 + dz));
                        fluids[base + dx] = (short) waterFluid(level, x, y, z);
                        continue;
                    }
                    String state = block.isStairs() ? chunk.getBlockState(lx0 + dx, y, lz0 + dz) : null;
                    shapes[base + dx] = (short) shapeOf(block, state, x, y, z);
                }
            }
        }
        return true;
    }

    private void fillThroughAccessors(int x0, int y0, int z0, int sx, int sy, int sz, short[] shapes, short[] fluids) {
        for (int dy = 0; dy < sy; dy++) {
            for (int dz = 0; dz < sz; dz++) {
                int base = (dy * sz + dz) * sx;
                for (int dx = 0; dx < sx; dx++) {
                    int x = x0 + dx, y = y0 + dy, z = z0 + dz;
                    BlockType block = world.getBlockAt(x, y, z);
                    if (block == null || block == BlockType.AIR) {
                        continue;
                    }
                    if (block == BlockType.WATER) {
                        int level = world.getWaterLevelAt(x, y, z);
                        fluids[base + dx] = (short) waterFluid(Math.max(level, 0), x, y, z);
                        continue;
                    }
                    String state = block.isStairs() ? world.getBlockStateAt(x, y, z) : null;
                    shapes[base + dx] = (short) shapeOf(block, state, x, y, z);
                }
            }
        }
    }

    private int shapeOf(BlockType block, String state, int x, int y, int z) {
        if (AnimatedBlockRegistry.isAnimatedType(block)) {
            float h = block.getCollisionHeight();
            return h > 0f ? shapeId(new float[]{0, 0, 0, 1, h, 1}, DOOR_CELL, 0) : 0;
        }
        if (block == BlockType.SNOW) {
            float h = world.getSnowHeight(x, y, z);
            // Partial only below a full block: a full 8-layer drift is a wall, as any full block.
            return h > 0f ? shapeId(new float[]{0, 0, 0, 1, Math.min(h, 1f), 1}, SOLID | PICK,
                    h < 1f ? PhysicsWorld.SHAPE_PARTIAL : 0)
                : shapeId(unitCell(), PICK, 0);
        }
        if (block.isStairs()) {
            // Not partial: each tread is within the ordinary half-block step from the one before it,
            // and the full-height back of a stair is a wall (the old resolver's rule: a footprint
            // reaching the full tread was a full block).
            List<float[]> boxes = StairShape.of(block).cellBoxes(StairState.parse(state).facing());
            return shapeId(flatten(boxes), SOLID | PICK, 0);
        }
        if (block == BlockType.LIMESTONE_STALAGMITE) {
            return stalagmiteShape(x, y, z);
        }
        Integer cached = simpleShape.get(block);
        if (cached != null) {
            return cached;
        }
        float h = block.getCollisionHeight();
        int id;
        if (h > 0f) {
            id = shapeId(new float[]{0, 0, 0, 1, Math.min(h, 1f), 1}, SOLID | PICK,
                h < 1f ? PhysicsWorld.SHAPE_PARTIAL : 0);
        } else {
            id = shapeId(unitCell(), PICK, 0);
        }
        simpleShape.put(block, id);
        return id;
    }

    /** The stalagmite's model tiers clipped to this cell, so a body walks around the spire it sees. */
    private int stalagmiteShape(int x, int y, int z) {
        List<float[]> world = StalagmiteShape.worldBoxes(stalagmiteCells, x, y, z);
        List<float[]> local = new ArrayList<>(world.size());
        for (float[] b : world) {
            float minX = Math.max(b[0] - x, 0f), maxX = Math.min(b[3] - x, 1f);
            float minY = Math.max(b[1] - y, 0f), maxY = Math.min(b[4] - y, 1f);
            float minZ = Math.max(b[2] - z, 0f), maxZ = Math.min(b[5] - z, 1f);
            if (maxX > minX && maxY > minY && maxZ > minZ) {
                local.add(new float[]{minX, minY, minZ, maxX, maxY, maxZ});
            }
        }
        if (local.isEmpty()) {
            // An orphaned part (no anchor) still has a surface to aim at; it collides as nothing.
            return shapeId(unitCell(), PICK, 0);
        }
        if (local.size() > PhysicsWorld.SHAPE_MAX_BOXES) {
            local = local.subList(0, PhysicsWorld.SHAPE_MAX_BOXES);
        }
        return shapeId(flatten(local), SOLID | PICK, 0);
    }

    private int waterFluid(int level, int x, int y, int z) {
        float surface = WaterHeightUtil.surfaceFraction(level);
        WaterFlowPhysics.cellForce(world, x, y, z, force);
        FluidKey key = new FluidKey(surface, force.x, force.y, force.z);
        Integer id = fluidIds.get(key);
        if (id != null) {
            return id;
        }
        if (nextFluid >= PhysicsWorld.MAX_FLUIDS) {
            LOGGER.warn("Physics fluid table full; reusing fluid 1");
            return 1;
        }
        int fresh = nextFluid++;
        engine.defineFluid(fresh, surface, force.x, force.y, force.z, WATER);
        fluidIds.put(key, fresh);
        return fresh;
    }

    private int shapeId(float[] boxes, int category, int flags) {
        ShapeKey key = new ShapeKey(boxes, category, flags);
        Integer id = shapeIds.get(key);
        if (id != null) {
            return id;
        }
        if (nextShape >= PhysicsWorld.MAX_SHAPES) {
            LOGGER.warn("Physics shape table full; treating a new shape as empty");
            return 0;
        }
        int fresh = nextShape++;
        engine.defineShape(fresh, boxes, category, flags);
        shapeIds.put(key, fresh);
        return fresh;
    }

    private static float[] unitCell() {
        return new float[]{0, 0, 0, 1, 1, 1};
    }

    private static float[] flatten(List<float[]> boxes) {
        float[] out = new float[boxes.size() * 6];
        for (int i = 0; i < boxes.size(); i++) {
            System.arraycopy(boxes.get(i), 0, out, i * 6, 6);
        }
        return out;
    }

    // ---- invalidation (any thread) ----------------------------------------------------------

    /**
     * A block (or its state) changed: the cell, the neighbours whose water flow reads it, and the
     * column a stalagmite of up to {@link Stalagmite#MAX_SIZE} cells can share with it.
     */
    public void invalidateBlock(int x, int y, int z) {
        int reach = Stalagmite.MAX_SIZE;
        engine.invalidateBox(x - 1, y - reach, z - 1, x + 2, y + reach + 1, z + 2);
    }

    /** A water level changed: the cell and the horizontal neighbours whose flow reads it. */
    public void invalidateWater(int x, int y, int z) {
        engine.invalidateBox(x - 1, y, z - 1, x + 2, y + 1, z + 2);
    }

    /** A cell's own shape changed (a snow layer). */
    public void invalidateCell(int x, int y, int z) {
        engine.invalidateCell(x, y, z);
    }

    /** A whole chunk column arrived, left or was replaced. */
    public void invalidateChunk(int chunkX, int chunkZ) {
        int cs = WorldConfiguration.CHUNK_SIZE;
        // One cell of margin: water flow at the seam reads across it.
        engine.invalidateBox(chunkX * cs - 1, Integer.MIN_VALUE / 2, chunkZ * cs - 1,
            chunkX * cs + cs + 1, Integer.MAX_VALUE / 2, chunkZ * cs + cs + 1);
    }

    // ---- model boxes of animated blocks ------------------------------------------------------

    /**
     * Brings the door panels and torch models up to date with the world's animated blocks. Cheap
     * when nothing changed (the registry's version says so); every query wrapper below calls it.
     */
    public void syncModelBoxes() {
        AnimatedBlockRegistry registry = world.getAnimatedBlockRegistry();
        if (registry == null) {
            return;
        }
        int version = registry.version();
        boolean full = ++syncsSinceFull >= FULL_SYNC_EVERY;
        if (version == seenAnimatedVersion && !full) {
            return;
        }
        seenAnimatedVersion = version;
        syncsSinceFull = 0;
        for (BlockPos pos : registry.positions()) {
            BlockType type = world.getBlockAt(pos.x(), pos.y(), pos.z());
            ModelBox current = modelBoxes.get(pos);
            if (!AnimatedBlockRegistry.isAnimatedType(type)) {
                continue;
            }
            String state = world.getBlockStateAt(pos.x(), pos.y(), pos.z());
            if (current != null && current.type == type && Objects.equals(current.state, state)) {
                continue;
            }
            float[] b = AnimatedBlockShapes.worldAabb(world, pos.x(), pos.y(), pos.z(), type);
            if (b == null) {
                continue;
            }
            int category = TorchBlock.isTorch(type) ? MODEL_PICK : PANEL;
            if (current == null) {
                current = new ModelBox();
                current.id = engine.addBox(b[0], b[1], b[2], b[3], b[4], b[5], category, 0, BlockPosKey.pack(pos));
                modelBoxes.put(pos, current);
            } else {
                engine.setBox(current.id, b[0], b[1], b[2], b[3], b[4], b[5], category, 0);
            }
            current.type = type;
            current.state = state;
        }
        for (Iterator<Map.Entry<BlockPos, ModelBox>> it = modelBoxes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, ModelBox> e = it.next();
            BlockPos pos = e.getKey();
            if (!registry.positions().contains(pos)
                    || !AnimatedBlockRegistry.isAnimatedType(world.getBlockAt(pos.x(), pos.y(), pos.z()))) {
                engine.removeBox(e.getValue().id);
                it.remove();
            }
        }
    }

    // ---- queries ------------------------------------------------------------------------------

    /** Moves a body through the world (see {@link PhysicsWorld#move}). */
    public void move(PhysicsBody body, float dt) {
        syncModelBoxes();
        engine.move(body, dt);
    }

    /** Integrates and moves a body (see {@link PhysicsWorld#step}). */
    public void step(PhysicsBody body, float dt, PhysicsIntegrator params, FluidSample out) {
        syncModelBoxes();
        engine.step(body, dt, params, out);
    }

    public boolean raycast(float ox, float oy, float oz, float dx, float dy, float dz, float maxDistance,
                           int mask, int fluidMask, RayHit out) {
        syncModelBoxes();
        return engine.raycast(ox, oy, oz, dx, dy, dz, maxDistance, mask, fluidMask, out);
    }

    public boolean sweep(float fromX, float fromY, float fromZ, float toX, float toY, float toZ,
                         float hx, float hy, float hz, int mask, RayHit out) {
        syncModelBoxes();
        return engine.sweep(fromX, fromY, fromZ, toX, toY, toZ, hx, hy, hz, mask, out);
    }

    public boolean overlaps(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int mask) {
        syncModelBoxes();
        return engine.overlaps(minX, minY, minZ, maxX, maxY, maxZ, mask);
    }

    /** Height of the tallest box of the cell's shape (in mask) over the XZ footprint; see {@link PhysicsWorld#cellTop}. */
    public float cellTop(int x, int y, int z, float minX, float minZ, float maxX, float maxZ, int mask) {
        return engine.cellTop(x, y, z, minX, minZ, maxX, maxZ, mask);
    }

    /** {@link #cellTop} over the whole cell. */
    public float cellTop(int x, int y, int z, int mask) {
        return engine.cellTop(x, y, z, mask);
    }

    /** Whether the whole cell holds nothing in {@code mask}. */
    public boolean isOpen(int x, int y, int z, int mask) {
        return engine.cellTop(x, y, z, mask) <= 0f;
    }

    /**
     * Water around a body: submersion of {@code height} standing at {@code feetY} and the mean
     * current over the flow samples taken from {@code flowBaseY} up {@code flowHeight}. The returned
     * sample is reused by the next call.
     */
    public FluidSample measureFluid(float x, float feetY, float z, float height, float halfWidth,
                                    float flowBaseY, float flowHeight) {
        return engine.measureFluid(x, feetY, z, height, halfWidth, flowBaseY, flowHeight, WATER, sample);
    }

    /** Packs a block position into a dynamic box's 64-bit host value and back. */
    public static final class BlockPosKey {
        private BlockPosKey() {
        }

        /** Never 0 (the top bit marks it), so a scene box's 0 can never pass for a block. */
        public static long pack(BlockPos p) {
            return Long.MIN_VALUE | ((long) (p.x() & 0x1FFFFFF) << 38) | ((long) (p.y() & 0xFFF) << 26)
                | (p.z() & 0x3FFFFFFL);
        }

        public static BlockPos unpack(long v) {
            int x = (int) ((v >>> 38) & 0x1FFFFFF);
            x = (x << 7) >> 7;
            int y = (int) ((v >>> 26) & 0xFFF);
            y = (y << 20) >> 20;
            int z = (int) (v & 0x3FFFFFF);
            z = (z << 6) >> 6;
            return new BlockPos(x, y, z);
        }
    }
}

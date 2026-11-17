package com.stonebreak.world.physics;

import com.openmason.engine.physics.FluidSample;
import com.openmason.engine.physics.PhysicsBody;
import com.openmason.engine.physics.RayHit;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.stairs.StairShape;
import com.stonebreak.blocks.stairs.StairState;
import com.stonebreak.mobs.entities.EntityCollision;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.player.physics.CollisionHandler;
import com.stonebreak.player.state.PhysicsState;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in VISUAL physics tests ({@code -Dstonebreak.physics.visual=true}): each scenario runs the
 * real game physics paths (the player's {@link CollisionHandler}, the mob integrator, picking
 * rays, projectile sweeps, drop masks) over a small world, asserts the outcome, and writes a side
 * view of what happened to {@code -Dstonebreak.physics.visual.dir} (default
 * {@code stonebreak-game/target/physics-visual/}): the shapes physics collides with (grey solid,
 * light grey partial, green pick-only, blue water), the body at intervals, its path, rays and hits.
 *
 * <p>Run: {@code Testing/run-tests.sh physics -Dstonebreak.physics.visual=true} (or
 * {@code mvn -pl stonebreak-game -am test -Dtest=PhysicsVisualTest -Dstonebreak.physics.visual=true}).
 */
class PhysicsVisualTest {

    private static final float DT = 1f / 60f;
    private static final int FLOOR = 10;

    private TestWorld world;
    private Chunk chunk;
    private Path out;

    @BeforeEach
    void setUp() {
        assumeTrue(Boolean.getBoolean("stonebreak.physics.visual"),
            "visual physics tests (-Dstonebreak.physics.visual=true)");
        out = Path.of(System.getProperty("stonebreak.physics.visual.dir", "target/physics-visual"));
        world = new TestWorld(new WorldConfiguration(8, 4), 1L, true);
        chunk = new Chunk(0, 0);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                chunk.setBlock(x, FLOOR, z, BlockType.STONE);
            }
        }
    }

    @AfterEach
    void tearDown() {
        if (world != null) {
            world.cleanup();
        }
    }

    private void install() {
        world.setChunk(0, 0, chunk);
    }

    private void fillX(int y, int z, BlockType type) {
        for (int x = 0; x < 16; x++) {
            chunk.setBlock(x, y, z, type);
        }
    }

    /** The stair facing whose full-height tread is on the +Z half: walking +Z climbs it. */
    private static StairState.Facing climbingPlusZ() {
        for (StairState.Facing f : StairState.Facing.values()) {
            for (float[] b : StairShape.of(BlockType.OAK_STAIRS).cellBoxes(f)) {
                if (b[4] > 0.99f && b[2] >= 0.49f && b[0] <= 0.01f && b[3] >= 0.99f) {
                    return f;
                }
            }
        }
        throw new AssertionError("no stair facing climbs +Z");
    }

    private void stairX(int y, int z, StairState.Facing facing) {
        for (int x = 0; x < 16; x++) {
            chunk.setBlock(x, y, z, BlockType.OAK_STAIRS);
            chunk.setBlockState(x, y, z, new StairState(facing).toStateString());
        }
    }

    /** Walks the player +Z at a steady speed for {@code seconds}, with gravity while airborne. */
    private List<float[]> walkPlayer(PhysicsState state, CollisionHandler collision, float speed, float seconds,
                                     PhysicsSketch sketch) {
        List<float[]> path = new ArrayList<>();
        int frames = Math.round(seconds / DT);
        for (int i = 0; i < frames; i++) {
            state.getVelocity().z = speed;
            if (!state.isOnGround()) {
                state.getVelocity().y -= PlayerConstants.GRAVITY * DT;
            }
            collision.move(DT);
            var p = state.getPosition();
            path.add(new float[] {p.x, p.y, p.z});
            if (i % 10 == 0) {
                sketch.body(p.x, p.y, p.z, PlayerConstants.PLAYER_WIDTH / 2, 0f, PlayerConstants.PLAYER_HEIGHT,
                    PhysicsSketch.BODY, 0.35f + 0.65f * i / frames);
            }
        }
        return path;
    }

    private static PhysicsState standingAt(float x, float y, float z) {
        PhysicsState state = new PhysicsState();
        state.getPosition().set(x, y, z);
        state.setOnGround(true);
        return state;
    }

    @Test
    void playerClimbsAStaircaseOneTreadAtATime() throws Exception {
        StairState.Facing up = climbingPlusZ();
        stairX(11, 5, up);
        fillX(11, 6, BlockType.STONE);
        stairX(12, 6, up);
        fillX(11, 7, BlockType.STONE);
        fillX(12, 7, BlockType.STONE);
        stairX(13, 7, up);
        for (int z = 8; z < 14; z++) {
            for (int y = 11; y <= 13; y++) {
                fillX(y, z, BlockType.STONE);
            }
        }
        install();
        var sketch = new PhysicsSketch(world, PhysicsSketch.Axis.X, 8.5f, 0, 9, 14, 17, 40);
        PhysicsState state = standingAt(8.5f, 11f, 2.5f);
        // 2.2 s at 4.3 blocks/s ends on the platform (it runs to z = 14).
        var path = walkPlayer(state, new CollisionHandler(state, world), 4.3f, 2.2f, sketch);
        sketch.path(path, PhysicsSketch.PATH);
        sketch.title("Player climbs oak stairs (+Z, facing " + up + ") - treads are boxes, partial step 1.0");
        sketch.note(String.format("end y=%.3f z=%.2f onGround=%s", state.getPosition().y, state.getPosition().z,
            state.isOnGround()), 0);
        System.out.println("[physics-visual] " + sketch.save(out, "01-player-stairs").toAbsolutePath());
        assertEquals(14f, state.getPosition().y, 1e-3f);
        assertTrue(state.getPosition().z > 9f);
    }

    @Test
    void playerWadesUpASnowDriftButNotAFullBlockWall() throws Exception {
        int[] layers = {2, 4, 6, 8};
        for (int i = 0; i < layers.length; i++) {
            fillX(11, 4 + i, BlockType.SNOW);
        }
        fillX(11, 8, BlockType.STONE);
        fillX(11, 9, BlockType.STONE);
        fillX(12, 9, BlockType.STONE);
        install();
        for (int i = 0; i < layers.length; i++) {
            for (int x = 0; x < 16; x++) {
                world.getSnowLayerManager().setSnowLayers(x, 11, 4 + i, layers[i]);
            }
        }
        var sketch = new PhysicsSketch(world, PhysicsSketch.Axis.X, 8.5f, 0, 9, 12, 15, 48);
        PhysicsState state = standingAt(8.5f, 11f, 1.5f);
        var path = walkPlayer(state, new CollisionHandler(state, world), 4.3f, 3f, sketch);
        sketch.path(path, PhysicsSketch.PATH);
        sketch.title("Snow layers 2/4/6/8 are partial (climbed); the stone wall after them is not");
        sketch.note(String.format("end y=%.3f z=%.3f (wall face at z=9, body half 0.3)", state.getPosition().y,
            state.getPosition().z), 0);
        System.out.println("[physics-visual] " + sketch.save(out, "02-player-snow").toAbsolutePath());
        assertEquals(12f, state.getPosition().y, 1e-3f);
        assertEquals(9f - 0.3f, state.getPosition().z, 1e-3f);
    }

    @Test
    void aRunningJumpMantlesOntoAOneBlockLedge() throws Exception {
        for (int z = 6; z < 14; z++) {
            fillX(11, z, BlockType.STONE);
        }
        install();
        var sketch = new PhysicsSketch(world, PhysicsSketch.Axis.X, 8.5f, 0, 9, 10, 15, 48);
        PhysicsState state = standingAt(8.5f, 11f, 2.5f);
        CollisionHandler collision = new CollisionHandler(state, world);
        List<float[]> path = new ArrayList<>();
        float peak = 0f;
        boolean jumped = false;
        for (int i = 0; i < 80; i++) {
            state.getVelocity().z = 4.3f;
            if (!jumped && state.getPosition().z > 5.0f && state.isOnGround()) {
                state.getVelocity().y = PlayerConstants.JUMP_FORCE;
                state.setOnGround(false);
                jumped = true;
            }
            if (!state.isOnGround()) {
                state.getVelocity().y -= PlayerConstants.GRAVITY * DT;
            }
            collision.move(DT);
            var p = state.getPosition();
            path.add(new float[] {p.x, p.y, p.z});
            if (!state.isOnGround()) peak = Math.max(peak, p.y);
            if (i % 6 == 0) {
                sketch.body(p.x, p.y, p.z, 0.3f, 0f, 1.8f, PhysicsSketch.BODY, 0.3f + 0.7f * i / 80f);
            }
        }
        sketch.path(path, PhysicsSketch.PATH);
        sketch.title("Running jump (8.5 up, gravity 35) peaks under 1 block: mantles onto the ledge (AIR_STEP)");
        sketch.note(String.format("highest airborne feet y=%.3f (ledge top 12), end y=%.3f z=%.2f", peak,
            state.getPosition().y, state.getPosition().z), 0);
        System.out.println("[physics-visual] " + sketch.save(out, "08-player-jump-ledge").toAbsolutePath());
        assertEquals(12f, state.getPosition().y, 1e-3f);
        assertTrue(state.getPosition().z > 6.5f);
    }

    @Test
    void aFastFallLandsOnAThinFloorAtAnyFrameTime() throws Exception {
        install();
        var sketch = new PhysicsSketch(world, PhysicsSketch.Axis.X, 8.5f, 0, 8, 16, 42, 14);
        PhysicsState state = new PhysicsState();
        state.getPosition().set(8.5f, 40f, 4.5f);
        state.getVelocity().set(0, -80f, 6f);
        CollisionHandler collision = new CollisionHandler(state, world);
        List<float[]> path = new ArrayList<>();
        float dt = 0.25f; // a 4 fps hitch: 20 blocks per step
        for (int i = 0; i < 6; i++) {
            collision.move(dt);
            var p = state.getPosition();
            path.add(new float[] {p.x, p.y, p.z});
            sketch.body(p.x, p.y, p.z, 0.3f, 0f, 1.8f, PhysicsSketch.BODY, 0.4f + 0.1f * i);
        }
        sketch.path(path, PhysicsSketch.PATH);
        sketch.title("Swept fall at 80 blocks/s, dt 0.25: lands on the 1-block floor, no tunnelling");
        System.out.println("[physics-visual] " + sketch.save(out, "03-fast-fall").toAbsolutePath());
        assertEquals(FLOOR + 1f, state.getPosition().y, 1e-4f);
        assertTrue(state.isOnGround());
    }

    @Test
    void aMobDroppedInAPondFloatsAtItsWaterLine() throws Exception {
        for (int x = 3; x <= 12; x++) {
            for (int z = 3; z <= 12; z++) {
                for (int y = 11; y <= 13; y++) {
                    chunk.setBlock(x, y, z, BlockType.WATER);
                }
            }
        }
        install();
        var sketch = new PhysicsSketch(world, PhysicsSketch.Axis.X, 8.5f, 2, 9, 14, 18, 40);
        PhysicsBody mob = new PhysicsBody().box(0.45f, 0.65f, 0.6f, 0.8f).steps(0.5f, 0.5f).position(8.5f, 17f, 8.5f);
        mob.mask = WorldPhysics.MOB_MASK;
        mob.flags = PhysicsBody.KEEP_VELOCITY;
        FluidSample sample = new FluidSample();
        List<float[]> path = new ArrayList<>();
        float t = 0;
        for (int i = 0; i < 200; i++) {
            mob.vz = 0.4f; // drifting forward so the history spreads out
            WorldPhysics.of(world).step(mob, 0.05f, EntityCollision.INTEGRATOR, sample);
            t += 0.05f;
            path.add(new float[] {mob.x, mob.y, mob.z});
            if (i % 12 == 0) {
                sketch.body(mob.x, mob.y, mob.z, 0.65f, 0.6f, 0.8f, PhysicsSketch.BODY, 0.3f + 0.7f * i / 200f);
            }
        }
        sketch.path(path, PhysicsSketch.PATH);
        sketch.title("Cow-sized body dropped into a 3-deep pond: buoyancy settles it 55% under");
        sketch.note(String.format("after %.0f s: submersion %.3f (target 0.55), vy %.3f", t, sample.submersion, mob.vy), 0);
        System.out.println("[physics-visual] " + sketch.save(out, "04-mob-float").toAbsolutePath());
        assertEquals(0.55f, sample.submersion, 0.08f);
    }

    @Test
    void pickingRaysStopAtFlowersAndTreadsButOnlyBucketRaysAtWater() throws Exception {
        StairState.Facing up = climbingPlusZ();
        chunk.setBlock(8, 11, 4, BlockType.ROSE);
        stairX(11, 7, up);
        for (int z = 9; z <= 10; z++) {
            chunk.setBlock(8, 11, z, BlockType.WATER);
        }
        for (int y = 11; y <= 15; y++) {
            fillX(y, 13, BlockType.STONE);
        }
        install();
        WorldPhysics physics = WorldPhysics.of(world);
        float[] eye = {8.5f, 12.62f, 1.2f};
        RayHit hit = new RayHit();
        for (String mode : new String[] {"pick", "bucket"}) {
            var sketch = new PhysicsSketch(world, PhysicsSketch.Axis.X, 8.5f, 0, 9, 15, 16, 40);
            int fluid = mode.equals("bucket") ? WorldPhysics.WATER : 0;
            int waterHits = 0, roseHits = 0;
            for (int i = 0; i <= 24; i++) {
                double pitch = Math.toRadians(-45 + i * 2.5);
                float dy = (float) Math.sin(pitch), dz = (float) Math.cos(pitch);
                boolean any = physics.raycast(eye[0], eye[1], eye[2], 0, dy, dz, 14f, WorldPhysics.PICK_MASK, fluid, hit);
                float d = any ? hit.distance : 14f;
                sketch.ray(eye, new float[] {eye[0], eye[1] + dy * d, eye[2] + dz * d}, any);
                if (any && hit.kind == RayHit.KIND_FLUID) waterHits++;
                if (any && hit.x == 8 && hit.y == 11 && hit.z == 4) roseHits++;
            }
            sketch.dot(eye, PhysicsSketch.BODY);
            sketch.title("Picking rays (" + mode + "): the rose and stair treads stop them; water "
                + (fluid != 0 ? "stops them" : "does not"));
            sketch.note("rays on the rose " + roseHits + "  on water " + waterHits, 0);
            System.out.println("[physics-visual] " + sketch.save(out, "05-rays-" + mode).toAbsolutePath());
            assertTrue(roseHits > 0, "the rose is a pick target");
            if (fluid != 0) {
                assertTrue(waterHits > 0, "the bucket ray stops at water");
            } else {
                assertEquals(0, waterHits, "the picking ray sees through water");
            }
        }
    }

    @Test
    void anArrowArcStopsAtThePillarItWouldOtherwiseSkipPast() throws Exception {
        for (int y = 11; y <= 14; y++) {
            fillX(y, 10, BlockType.STONE);
        }
        install();
        WorldPhysics physics = WorldPhysics.of(world);
        var sketch = new PhysicsSketch(world, PhysicsSketch.Axis.X, 8.5f, 0, 9, 14, 18, 40);
        float[] p = {8.5f, 12f, 1f};
        float[] v = {0f, 7f, 30f}; // 1.5 blocks per 20 Hz tick
        List<float[]> path = new ArrayList<>();
        path.add(p.clone());
        RayHit hit = new RayHit();
        boolean stopped = false;
        for (int i = 0; i < 40 && !stopped; i++) {
            float[] next = {p[0] + v[0] * 0.05f, p[1] + v[1] * 0.05f, p[2] + v[2] * 0.05f};
            if (physics.sweep(p[0], p[1], p[2], next[0], next[1], next[2], 0.05f, 0.05f, 0.05f,
                    WorldPhysics.PROJECTILE_MASK, hit)) {
                next = new float[] {hit.px, hit.py, hit.pz};
                stopped = true;
            }
            p = next;
            v[1] -= 10f * 0.05f;
            path.add(p.clone());
            sketch.dot(p, PhysicsSketch.HIT);
        }
        sketch.path(path, PhysicsSketch.PATH);
        sketch.title("Projectile sweep: 1.5 blocks/tick arc meets the pillar face, not the cell behind it");
        sketch.note(String.format("impact z=%.3f (pillar face 10 - half 0.05)", p[2]), 0);
        System.out.println("[physics-visual] " + sketch.save(out, "06-arrow-sweep").toAbsolutePath());
        assertTrue(stopped);
        assertEquals(10f - 0.05f, p[2], 1e-3f);
    }

    @Test
    void aDropFallsThroughAFlowerToTheGroundBelow() throws Exception {
        fillX(11, 6, BlockType.ROSE);
        install();
        var sketch = new PhysicsSketch(world, PhysicsSketch.Axis.X, 8.5f, 3, 9, 10, 16, 48);
        PhysicsBody drop = new PhysicsBody().box(0.125f, 0.125f, 0.125f, 0.125f).position(8.5f, 15f, 6.5f);
        drop.mask = WorldPhysics.DROP_MASK;
        List<float[]> path = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            if (!drop.onGround()) drop.vy -= 20f * 0.05f;
            WorldPhysics.of(world).move(drop, 0.05f);
            path.add(new float[] {drop.x, drop.y, drop.z});
            if (i % 3 == 0) sketch.body(drop.x, drop.y, drop.z, 0.125f, 0.125f, 0.125f, PhysicsSketch.BODY, 0.8f);
        }
        sketch.path(path, PhysicsSketch.PATH);
        sketch.title("Drop mask: the rose is pick-only, so the drop falls through it to the stone");
        System.out.println("[physics-visual] " + sketch.save(out, "07-drop-flower").toAbsolutePath());
        assertEquals(11.125f, drop.y, 1e-4f);
    }
}

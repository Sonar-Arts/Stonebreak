package com.stonebreak.world.spawn;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.TestWorld;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for issue #250 — "Player spawn can land above a ravine or sinkhole and
 * drop into it on every respawn".
 *
 * <p>{@code SpawnLocator.findSafeSurfaceSpawn} (and {@code ServerLevel.resolveWorldSpawn}) pick
 * the standing Y from {@code getFinalTerrainHeightAt}, which samples the pre-carve noise field
 * and so reports <em>rim</em> height for a column a ravine or sinkhole has cut open down to its
 * floor. The player would then spawn in mid-air and fall into the pit — with fall damage
 * possible on a fresh world — every respawn, because the bad spawn is frozen into the world data.
 *
 * <p>The fix adds {@link SpawnLocator#resolveStandingY}, which reads the real (carved) blocks of
 * a column once its chunk is resident and returns the topmost standable Y. This test pins two
 * properties headlessly (no GL): it resolves the real column surface on natural terrain, and it
 * descends to the carved floor after a carve — even though the noise height (what the old code
 * used) never moves.
 */
@Tag("regression")
public class SpawnLocatorResolveStandingYTest {

    private static final long SEED = 12345L;
    private static final int TOP = WorldConfiguration.WORLD_HEIGHT - 1;

    @Test
    public void resolvesRealTopOfNaturalColumn() throws Exception {
        World world = new TestWorld(new WorldConfiguration(), SEED, true);
        loadChunk(world);

        int[] col = findStandableColumn(world);

        // The resolved position must sit on the actual top solid block: a solid block below
        // with air at and above the player's feet.
        int standY = SpawnLocator.resolveStandingY(world, col[0], col[2], TOP);
        assertTrue(standY >= 1, "expected a standable position in the column");
        assertEquals(col[1], standY, "resolveStandingY must return the real top solid + 1");
    }

    @Test
    public void descendsToCarvedFloorWhileNoiseHeightStays() throws Exception {
        World world = new TestWorld(new WorldConfiguration(), SEED, true);
        loadChunk(world);

        int[] col = findStandableColumn(world);
        int x = col[0];
        int z = col[2];
        int originalNoiseHeight = world.terrain().getFinalTerrainHeightAt(x, z);

        // The real top solid block before carving.
        int topSolid = topSolidY(world, x, z);
        assertTrue(topSolid >= 1, "expected a solid surface to carve");

        // Carve a 6-block shaft from the surface down, as a ravine/sinkhole would.
        for (int i = 0; i < 6; i++) {
            world.setBlockAt(x, topSolid - i, z, BlockType.AIR, false);
        }

        int newTopSolid = topSolidY(world, x, z);
        assertEquals(topSolid - 6, newTopSolid, "carve should remove exactly 6 blocks");

        // The fix reads the carved blocks: the resolved standing Y descends to the new floor.
        int standY = SpawnLocator.resolveStandingY(world, x, z, TOP);
        assertEquals(newTopSolid + 1, standY, "must land on the real carved floor + 1");

        // The noise height the old spawn logic used is untouched by carving — this is exactly
        // why it placed the player at rim height and caused the fall. Confirm the fix disagrees
        // with it (i.e. it did NOT land at rim).
        assertEquals(originalNoiseHeight, world.terrain().getFinalTerrainHeightAt(x, z),
            "pre-carve noise height must be unaffected by a carve");
        assertTrue(standY < originalNoiseHeight + 1,
            "resolved stand Y must be below the uncarved rim, not at it");
    }

    @Test
    public void rejectsCarvedPitAndAcceptsSurface() throws Exception {
        World world = new TestWorld(new WorldConfiguration(), SEED, true);
        loadChunk(world);

        int[] col = findAcceptableColumn(world);
        int x = col[0];
        int z = col[2];
        int noiseHeight = world.terrain().getFinalTerrainHeightAt(x, z);
        Vector3f candidate = new Vector3f(x, noiseHeight + 1, z);

        // An un-carved column is accepted and snapped to its real surface (top solid + 1).
        Vector3f accepted = SpawnLocator.acceptIfSafeSurface(world, candidate);
        assertNotNull(accepted, "an intact surface column should be accepted");
        assertEquals(topSolidY(world, x, z) + 1, (int) Math.floor(accepted.y),
            "accepts lands on the real top + 1");

        // Carve a deep pit well past the rim (ravines are 25-60 deep).
        int topSolid = topSolidY(world, x, z);
        for (int i = 0; i < 40; i++) {
            world.setBlockAt(x, topSolid - i, z, BlockType.AIR);
        }

        // Now the same column is a pit: its real surface is far below the pre-carve rim, so the
        // candidate must be rejected (return null) rather than accepted on the pit floor.
        assertNull(SpawnLocator.acceptIfSafeSurface(world, candidate),
            "a column carved into a deep pit must be rejected, not spawned on the floor");
    }

    @Test
    public void rejectsUnderwaterColumn() throws Exception {
        World world = new TestWorld(new WorldConfiguration(), SEED, true);
        loadChunk(world);

        int[] col = findAcceptableColumn(world);
        int x = col[0];
        int z = col[2];
        int noiseHeight = world.terrain().getFinalTerrainHeightAt(x, z);
        Vector3f candidate = new Vector3f(x, noiseHeight + 1, z);
        assertNotNull(SpawnLocator.acceptIfSafeSurface(world, candidate), "precondition: column is acceptable");

        // Flood the column: every AIR layer from the surface up to the pre-carve rim becomes
        // water, so the standing block and everything below it ends up underwater.
        for (int y = noiseHeight; y >= 1; y--) {
            if (world.getBlockAt(x, y, z) == BlockType.AIR) {
                world.setBlockAt(x, y, z, BlockType.WATER);
            }
        }

        assertNull(SpawnLocator.acceptIfSafeSurface(world, candidate),
            "a column whose standing block is underwater must be rejected");
    }

    /**
     * Finds a column in chunk 0,0 that {@link SpawnLocator#acceptIfSafeSurface} accepts,
     * returning {@code {x, 0, z}}.
     */
    private static int[] findAcceptableColumn(World world) {
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = lx;
                int z = lz;
                int noiseHeight = world.terrain().getFinalTerrainHeightAt(x, z);
                if (SpawnLocator.acceptIfSafeSurface(world, new Vector3f(x, noiseHeight + 1, z)) != null) {
                    return new int[]{x, 0, z};
                }
            }
        }
        throw new AssertionError("No acceptable spawn column found in chunk 0,0 for seed " + SEED);
    }

    /**
     * Validates the accept/reject discriminator against REAL generator output (not hand-carved
     * blocks): a seed/region chosen to contain a genuine ravine. Every inland column must be
     * handled consistently — a genuine carved pit (real surface far below the pre-carve rim)
     * or a flooded column is rejected, while a usable land column is accepted and lands on
     * exactly the real top standable block (never floating). Confirmed via a terrain profile
     * that seed 12345 has a real chasm near origin (surface drops ~99 -> ~47).
     */
    @Test
    public void discriminatesRealRavinesOnGeneratedTerrain() throws Exception {
        World world = new TestWorld(new WorldConfiguration(), SEED, true);
        int r = 4; // chunk radius around origin covering the known ravine
        for (int cx = -r; cx <= r; cx++) {
            for (int cz = -r; cz <= r; cz++) {
                world.getChunkAt(cx, cz);
            }
        }
        world.awaitPendingChunkLoads().get(30, TimeUnit.SECONDS);

        int accepted = 0;
        int ravinePits = 0;
        int flatLand = 0;
        for (int x = -r * 16; x < r * 16; x++) {
            for (int z = -r * 16; z < r * 16; z++) {
                int noiseH = world.terrain().getFinalTerrainHeightAt(x, z);
                if (noiseH < WorldConfiguration.SEA_LEVEL) continue; // skip open ocean

                int standY = SpawnLocator.resolveStandingY(world, x, z, TOP);
                if (standY < 1) continue; // no standable column (e.g. under a tree/shelf)

                int dropToGround = noiseH - (standY - 1); // rim surface -> standing ground
                boolean underwater = (standY - 1) < WorldConfiguration.SEA_LEVEL;
                Vector3f candidate = new Vector3f(x + 0.5f, noiseH + 1, z + 0.5f);
                Vector3f acceptedPos = SpawnLocator.acceptIfSafeSurface(world, candidate);

                if (dropToGround > 20) {
                    // Unambiguous ravine/sinkhole pit (ravine depth is 25-60): must be rejected.
                    ravinePits++;
                    assertNull(acceptedPos, "real ravine/sinkhole pit at (" + x + "," + z + ")"
                        + " noiseH=" + noiseH + " standY=" + standY + " must be rejected");
                } else if (dropToGround <= 2 && !underwater) {
                    // Unambiguous flat land (surface matches the rim): must be accepted.
                    flatLand++;
                    assertNotNull(acceptedPos, "flat land at (" + x + "," + z + ")"
                        + " noiseH=" + noiseH + " standY=" + standY + " must be accepted");
                }

                // Core invariant regardless of classification: any accepted spawn lands on
                // exactly the real top standable block — never floating in mid-air.
                if (acceptedPos != null) {
                    accepted++;
                    assertEquals(standY, (int) Math.floor(acceptedPos.y),
                        "accepted spawn must land on the real top standable block (no floating)"
                            + " at (" + x + "," + z + ")");
                }
            }
        }
        assertTrue(accepted > 0, "expected usable spawn columns in the generated region");
        assertTrue(ravinePits > 0, "expected the generated region to contain a genuine ravine/sinkhole");
        assertTrue(flatLand > 0, "expected flat land columns to be accepted");
    }

    /** Loads chunk 0,0 and blocks until its terrain is generated. */
    private static void loadChunk(World world) throws Exception {
        world.getChunkAt(0, 0);
        world.awaitPendingChunkLoads().get(15, TimeUnit.SECONDS);
    }

    /**
     * Finds a land column (solid ground with air above, not underwater) inside chunk 0,0 and
     * returns {@code {x, standY, z}}. Picks whatever column the seed happens to produce.
     */
    private static int[] findStandableColumn(World world) {
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = lx;
                int z = lz;
                int standY = SpawnLocator.resolveStandingY(world, x, z, TOP);
                if (standY >= 1) {
                    BlockType ground = world.getBlockAt(x, standY - 1, z);
                    if (ground != null && ground != BlockType.WATER) {
                        return new int[]{x, standY, z};
                    }
                }
            }
        }
        throw new AssertionError("No standable land column found in chunk 0,0 for seed " + SEED);
    }

    /** Highest non-air, non-water block in the column; -1 if the column is empty. */
    private static int topSolidY(World world, int x, int z) {
        for (int y = TOP; y >= 0; y--) {
            BlockType b = world.getBlockAt(x, y, z);
            if (b != null && b != BlockType.AIR && b != BlockType.WATER) {
                return y;
            }
        }
        return -1;
    }
}

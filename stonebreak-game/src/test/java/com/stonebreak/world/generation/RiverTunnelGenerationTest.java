package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.diffusion.TunnelledRiverTileSource;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A river that meets a hill goes UNDER it.
 *
 * <p>The defect these pin: {@code ck_carve_water} wrote {@code carved = surf - cut}
 * with no reference to the ground already there, so a route crossing high terrain
 * deleted every block above the water line — a trench through a mountain. Terrain
 * is now kept and the river is carried through a void described by two per-column
 * planes ({@code riverFloor}/{@code riverRoof}).
 *
 * <p>Why an offline fake rather than the real kernel: {@link com.stonebreak.world.generation.water.NativeWaterTiles}
 * needs a basin solve over a real DEM, and the kernel's own invariants (the ground
 * survives, the lid holds, nothing leaks) are already pinned in
 * {@code kernels_water_test.cpp}. What is NOT covered there is everything after the
 * ABI: whether the planes survive {@code TerrainTile} -> {@code HeightMapGenerator}
 * -> the block loop, and whether the block loop turns them into the right blocks.
 * That journey is what fails silently, so it is what these test.
 *
 * <p>Chunk choice: (0,0) straddles the tunnel MOUTH (open reach at x 0-7, hill from
 * x 8), (2,0) is deep under the hill, and (-1,0) is open reach. So the transition is
 * covered and not just the two steady states.
 */
public class RiverTunnelGenerationTest {

    private static final long SEED = 12345L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;

    private static final int[][] CHUNKS = {{0, 0}, {2, 0}, {-1, 0}};

    private static TerrainGenerationSystem terrain() {
        return new TerrainGenerationSystem(SEED, new TunnelledRiverTileSource());
    }

    /**
     * The core contract, and the one that admits no exceptions: inside the void the
     * block is WATER below the river surface and AIR above it, whatever any carver
     * thought. The tunnel branch sits at the TOP of the block loop's chain precisely
     * so a cavern or worm cannot win one of these cells — worldgen water is a source
     * block, so a carver that opened the passage would drain the river forever.
     */
    @Test
    public void theVoidHoldsWaterBelowTheSurfaceAndAirAbove() {
        TerrainGenerationSystem terrain = terrain();
        List<String> problems = new ArrayList<>();
        int water = 0;
        int air = 0;

        for (int[] c : CHUNKS) {
            Chunk chunk = terrain.generateTerrainOnly(c[0], c[1]).chunk();
            for (int lx = 0; lx < CHUNK; lx++) {
                for (int lz = 0; lz < CHUNK; lz++) {
                    int wx = c[0] * CHUNK + lx;
                    int wz = c[1] * CHUNK + lz;
                    if (!TunnelledRiverTileSource.isTunnel(wx, wz)) {
                        continue;
                    }
                    int floor = TunnelledRiverTileSource.CHANNEL_FLOOR;
                    int roof = TunnelledRiverTileSource.TUNNEL_ROOF;
                    for (int y = floor + 1; y < roof; y++) {
                        BlockType got = chunk.getBlock(lx, y, lz);
                        BlockType want = y < TunnelledRiverTileSource.SURFACE
                                ? BlockType.WATER : BlockType.AIR;
                        if (got != want) {
                            problems.add(String.format("(%d,%d,%d): want %s, got %s", wx, y, wz, want, got));
                        } else if (want == BlockType.WATER) {
                            water++;
                        } else {
                            air++;
                        }
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.size() + " wrong blocks inside river tunnels, first few: "
                + problems.subList(0, Math.min(8, problems.size())));
        assertTrue(water > 0, "the fixture should put water in the tunnel");
        assertTrue(air > 0, "and leave headroom above it");
    }

    /**
     * The reported bug, stated directly: the hill is still there.
     *
     * <p>Before the fix a tunnelled column's terrain height WAS the water line, so
     * there was no solid ground above the river at all. Both halves matter — the
     * tile still reports the hill, and the chunk actually contains rock up there.
     */
    @Test
    public void theGroundAboveATunnelIsNotRemoved() {
        TerrainGenerationSystem terrain = terrain();
        int checked = 0;

        for (int[] c : CHUNKS) {
            Chunk chunk = terrain.generateTerrainOnly(c[0], c[1]).chunk();
            for (int lx = 0; lx < CHUNK; lx++) {
                for (int lz = 0; lz < CHUNK; lz++) {
                    int wx = c[0] * CHUNK + lx;
                    int wz = c[1] * CHUNK + lz;
                    if (!TunnelledRiverTileSource.isTunnel(wx, wz)) {
                        continue;
                    }
                    checked++;
                    assertEquals(TunnelledRiverTileSource.HILL, terrain.getFinalTerrainHeightAt(wx, wz),
                            "a tunnelled column keeps its full terrain height at (" + wx + "," + wz + ")");

                    int solidAboveRoof = 0;
                    for (int y = TunnelledRiverTileSource.TUNNEL_ROOF; y < TunnelledRiverTileSource.HILL; y++) {
                        if (chunk.getBlock(lx, y, lz) != BlockType.AIR) {
                            solidAboveRoof++;
                        }
                    }
                    assertTrue(solidAboveRoof > 0,
                            "no rock at all above the tunnel at (" + wx + "," + wz + ") — the hill was deleted");
                }
            }
        }
        assertTrue(checked > 0, "the swept chunks must actually contain tunnelled columns");
    }

    /**
     * The tunnel is roofed: every tunnelled column has solid ground somewhere between
     * its roof and the surface, so the passage is not open to the sky.
     *
     * <p>This is the assertion most exposed to the rest of worldgen. The carvers are
     * held off by {@code WaterGuard}, which now measures from the tunnel FLOOR rather
     * than the column height — from the height it would guard the hilltop and leave
     * the passage wide open. {@code Density3D} has no such guard and carves on noise
     * alone, so if this ever goes red the lid is where to look, not the stamp.
     */
    @Test
    public void everyTunnelKeepsARoofOverIt() {
        TerrainGenerationSystem terrain = terrain();
        List<String> breached = new ArrayList<>();
        int checked = 0;

        for (int[] c : CHUNKS) {
            Chunk chunk = terrain.generateTerrainOnly(c[0], c[1]).chunk();
            for (int lx = 0; lx < CHUNK; lx++) {
                for (int lz = 0; lz < CHUNK; lz++) {
                    int wx = c[0] * CHUNK + lx;
                    int wz = c[1] * CHUNK + lz;
                    if (!TunnelledRiverTileSource.isTunnel(wx, wz)) {
                        continue;
                    }
                    checked++;
                    boolean capped = false;
                    for (int y = TunnelledRiverTileSource.TUNNEL_ROOF;
                         y < TunnelledRiverTileSource.HILL && !capped; y++) {
                        capped = chunk.getBlock(lx, y, lz) != BlockType.AIR;
                    }
                    if (!capped) {
                        breached.add("(" + wx + "," + wz + ")");
                    }
                }
            }
        }
        assertTrue(checked > 0, "the swept chunks must contain tunnelled columns");
        assertTrue(breached.isEmpty(), breached.size() + "/" + checked
                + " tunnel columns are open to the sky: " + breached.subList(0, Math.min(8, breached.size())));
    }

    /**
     * The floor is ground, not a hole. A void whose floor had been carved away would
     * drain the river downward just as surely as a breached roof.
     */
    @Test
    public void theTunnelFloorIsSolid() {
        TerrainGenerationSystem terrain = terrain();
        int checked = 0;

        for (int[] c : CHUNKS) {
            Chunk chunk = terrain.generateTerrainOnly(c[0], c[1]).chunk();
            for (int lx = 0; lx < CHUNK; lx++) {
                for (int lz = 0; lz < CHUNK; lz++) {
                    int wx = c[0] * CHUNK + lx;
                    int wz = c[1] * CHUNK + lz;
                    if (!TunnelledRiverTileSource.isTunnel(wx, wz)) {
                        continue;
                    }
                    checked++;
                    BlockType bed = chunk.getBlock(lx, TunnelledRiverTileSource.CHANNEL_FLOOR, lz);
                    assertTrue(bed != BlockType.AIR && bed != BlockType.WATER,
                            "tunnel bed at (" + wx + "," + wz + ") is " + bed + ", not ground");
                }
            }
        }
        assertTrue(checked > 0, "the swept chunks must contain tunnelled columns");
    }

    /**
     * A reach running at grade is untouched by any of this: the bed is cut and the
     * water sits on top of it, exactly as before. Tunnelling must be the exception
     * the ground asks for, not a new default.
     */
    @Test
    public void anOpenReachStillPlacesItsWaterOnTheGround() {
        TerrainGenerationSystem terrain = terrain();
        List<String> problems = new ArrayList<>();
        int wet = 0;

        for (int[] c : CHUNKS) {
            Chunk chunk = terrain.generateTerrainOnly(c[0], c[1]).chunk();
            for (int lx = 0; lx < CHUNK; lx++) {
                for (int lz = 0; lz < CHUNK; lz++) {
                    int wx = c[0] * CHUNK + lx;
                    int wz = c[1] * CHUNK + lz;
                    if (!TunnelledRiverTileSource.isOpenChannel(wx, wz)) {
                        continue;
                    }
                    for (int y = TunnelledRiverTileSource.CHANNEL_FLOOR;
                         y < TunnelledRiverTileSource.SURFACE; y++) {
                        BlockType got = chunk.getBlock(lx, y, lz);
                        if (got != BlockType.WATER) {
                            problems.add(String.format("(%d,%d,%d): open channel holds %s", wx, y, wz, got));
                        } else {
                            wet++;
                        }
                    }
                    assertEquals(BlockType.AIR, chunk.getBlock(lx, TunnelledRiverTileSource.SURFACE, lz),
                            "the open channel's water stops at its surface at (" + wx + "," + wz + ")");
                }
            }
        }
        assertTrue(problems.isEmpty(), "open reach mis-placed water: "
                + problems.subList(0, Math.min(8, problems.size())));
        assertTrue(wet > 0, "the swept chunks must contain an open reach");
    }

    /**
     * No water anywhere the channel is not — in particular none perched on top of the
     * hill. A tunnelled column reports a water level BELOW its own terrain height,
     * which is a shape the old per-column rule ({@code y >= height && y < waterLevel})
     * had never seen; read carelessly it paints a sheet of water over the hilltop.
     */
    @Test
    public void noWaterIsPlacedOutsideTheChannel() {
        TerrainGenerationSystem terrain = terrain();
        List<String> stray = new ArrayList<>();

        for (int[] c : CHUNKS) {
            Chunk chunk = terrain.generateTerrainOnly(c[0], c[1]).chunk();
            for (int lx = 0; lx < CHUNK; lx++) {
                for (int lz = 0; lz < CHUNK; lz++) {
                    int wx = c[0] * CHUNK + lx;
                    int wz = c[1] * CHUNK + lz;
                    if (TunnelledRiverTileSource.inChannel(wz)) {
                        continue;
                    }
                    for (int y = 0; y < WORLD_HEIGHT; y++) {
                        if (chunk.getBlock(lx, y, lz) == BlockType.WATER) {
                            stray.add("(" + wx + "," + y + "," + wz + ")");
                        }
                    }
                }
            }
        }
        assertTrue(stray.isEmpty(), stray.size() + " water blocks outside the channel: "
                + stray.subList(0, Math.min(8, stray.size())));
    }

    /**
     * The rock beside a tunnel is solid over the passage's own height band.
     *
     * <p>A hole here drains the river sideways exactly as a breached bed drains it
     * downward — the bank case {@code WaterGuard}'s per-chunk plane was written for.
     * That plane takes the lowest wet bed in each column's 4-neighbourhood, so a
     * column beside a tunnel is guarded from the tunnel FLOOR and every carver is
     * held off it. {@code Density3D} is the exception: it is the one carver the guard
     * was never wired to, and it reads noise and depth only.
     */
    @Test
    public void theRockBesideATunnelIsSolid() {
        TerrainGenerationSystem terrain = terrain();
        List<String> holes = new ArrayList<>();
        int checked = 0;

        for (int[] c : CHUNKS) {
            Chunk chunk = terrain.generateTerrainOnly(c[0], c[1]).chunk();
            for (int lx = 0; lx < CHUNK; lx++) {
                for (int lz = 0; lz < CHUNK; lz++) {
                    int wx = c[0] * CHUNK + lx;
                    int wz = c[1] * CHUNK + lz;
                    // The first dry column on either side of the channel, under the hill.
                    boolean besideChannel = Math.abs(wz - TunnelledRiverTileSource.RIVER_Z)
                            == TunnelledRiverTileSource.HALF_WIDTH + 1;
                    if (!besideChannel || !TunnelledRiverTileSource.underHill(wx)) {
                        continue;
                    }
                    checked++;
                    for (int y = TunnelledRiverTileSource.CHANNEL_FLOOR;
                         y <= TunnelledRiverTileSource.TUNNEL_ROOF; y++) {
                        if (chunk.getBlock(lx, y, lz) == BlockType.AIR) {
                            holes.add("(" + wx + "," + y + "," + wz + ")");
                        }
                    }
                }
            }
        }
        assertTrue(checked > 0, "the swept chunks must contain columns beside a tunnel");
        assertTrue(holes.isEmpty(), holes.size() + " open cells in the wall beside a tunnel: "
                + holes.subList(0, Math.min(8, holes.size())));
    }

    /**
     * The surface FastLOD draws is the hill, not the river under it.
     *
     * <p>{@code carvedSurfaceHeight} is what a heightfield view of the world renders,
     * and it has to agree with the block loop or distant terrain stands in the wrong
     * place and pops when the real chunk replaces it. A tunnel is invisible at LOD by
     * construction — the ground over it is untouched — and that is exactly the property
     * worth pinning, because it is what makes the tunnel free for the renderer.
     */
    @Test
    public void theSurfaceFastLodDrawsIsTheHillAndMatchesTheBlockLoop() {
        TerrainGenerationSystem terrain = terrain();
        int checked = 0;

        for (int[] c : CHUNKS) {
            Chunk chunk = terrain.generateTerrainOnly(c[0], c[1]).chunk();
            for (int lx = 0; lx < CHUNK; lx++) {
                for (int lz = 0; lz < CHUNK; lz++) {
                    int wx = c[0] * CHUNK + lx;
                    int wz = c[1] * CHUNK + lz;
                    if (!TunnelledRiverTileSource.isTunnel(wx, wz)) {
                        continue;
                    }
                    checked++;
                    int lod = terrain.carvedSurfaceHeight(wx, wz);
                    assertTrue(lod > TunnelledRiverTileSource.TUNNEL_ROOF,
                            "LOD surface at (" + wx + "," + wz + ") is " + lod
                                    + ", at or below the tunnel roof — the hill is missing at distance");

                    int topSolid = 0;
                    for (int y = WORLD_HEIGHT - 1; y >= 0; y--) {
                        if (chunk.getBlock(lx, y, lz) != BlockType.AIR
                                && chunk.getBlock(lx, y, lz) != BlockType.WATER) {
                            topSolid = y + 1;
                            break;
                        }
                    }
                    assertEquals(topSolid, lod,
                            "LOD surface and block-loop top disagree at (" + wx + "," + wz + ")");
                }
            }
        }
        assertTrue(checked > 0, "the swept chunks must contain tunnelled columns");
    }
}

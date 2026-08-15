package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.noise.NoiseChannel2D;
import com.stonebreak.world.generation.noise.TerrainNoise;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * The cave system's organising datum: a synthetic water table used to decide what
 * <em>shape</em> a cave takes at a given depth.
 *
 * <p>Real caves are shaped by where the water was. Rather than simulate that — which is
 * what made the old Python hydrology a 20-minute world load — this exploits the fact that
 * a water table is a <b>subdued replica of surface topography, damped toward the regional
 * base level</b>. That reduces to arithmetic over the heights the carvers already hold:
 *
 * <pre>
 *   table = SEA_LEVEL + (surface - SEA_LEVEL) * DAMP + lowFreqNoise * WOBBLE
 *   table = min(table, surface - MIN_ROOF)
 * </pre>
 *
 * <p>Per-column, stateless, no scan, no new tile channel — so it satisfies the same purity
 * rule {@code ck_carve_water} imposes: every emitted value is a pure function of
 * {@code (seed, column)}. Where a column is genuinely wet the table is pinned to the real
 * river/lake surface, so galleries agree with the water the player can see.
 *
 * <p>Past water-table stands are just offsets ({@code table - k * LEVEL_SPACING}), which is
 * what gives multi-storey cave systems: each stillstand of a dropping table leaves a level
 * gallery abandoned above the next one.
 *
 * <p>The three zones and the shapes they drive:
 * <table>
 *   <tr><th>Zone</th><th>Where</th><th>Shape</th></tr>
 *   <tr><td>VADOSE</td><td>above the table</td><td>gravity-driven — vertical shafts, canyons</td></tr>
 *   <tr><td>EPIPHREATIC</td><td>at any storey</td><td>horizontal galleries</td></tr>
 *   <tr><td>PHREATIC</td><td>below them all</td><td>rounded, looping tubes</td></tr>
 * </table>
 *
 * <p><b>This is a shape datum, not a water datum.</b> PHREATIC means <em>shaped like</em> a
 * water-filled tube; nothing here may place water. Worldgen water is source blocks, so a
 * carved wet cell that breaks the containment invariant is a permanent, spreading spring.
 * {@link WaterGuard} remains the hard safety layer and is entirely independent of this class
 * — do not conflate the two.
 */
public final class CaveWaterTable {

    /** Hydrological zone, which selects a cave's cross-section shape. */
    public enum Zone {
        /** Above the table: gravity-driven, vertical. */
        VADOSE,
        /** At a (present or former) table: horizontal galleries. */
        EPIPHREATIC,
        /** Below every storey: rounded looping tubes. */
        PHREATIC
    }

    /** How strongly the table follows surface relief. 0 = flat at sea level, 1 = parallel to terrain. */
    private static final float DAMP = 0.40f;
    /** Amplitude of the low-frequency wobble, in blocks. */
    private static final float WOBBLE = 10f;
    /** Minimum rock roof over the topmost gallery, so galleries never breach the surface. */
    private static final int MIN_ROOF = 12;
    /** Half-height of a gallery band, in blocks. */
    public static final int BAND = 10;
    /** Vertical gap between successive former water tables. */
    private static final int LEVEL_SPACING = 55;
    /** Number of stacked storeys (present table plus former stands). */
    private static final int STOREYS = 3;
    /** Lowest permitted table — keeps the deepest gallery band clear of the bedrock floor. */
    private static final int FLOOR = 9;
    /** Wobble wavelength: long enough that the table reads as regional, not per-chunk. */
    private static final float SCALE = 1f / 220f;

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private final NoiseChannel2D wobble;
    private final HeightMapGenerator heightMap;

    public CaveWaterTable(long seed, HeightMapGenerator heightMap) {
        // Seed offset distinct from Density3D (+17) and the worm channels (+41, +113).
        this.wobble = TerrainNoise.channel2D(seed + 8191, 2, 0.5, 2.0, SCALE, 0f, 0f, 0, 0);
        this.heightMap = heightMap;
    }

    /**
     * Per-column table for a chunk, indexed {@code [x*CHUNK_SIZE+z]} to match the
     * heights/waterLevels grids.
     *
     * @param heights     final surface height per column
     * @param waterLevels per-column water level, or {@link TerrainTile#NO_WATER}
     */
    public int[] tableForChunk(int chunkX, int chunkZ, int[] heights, int[] waterLevels) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;

        // One batched fill rather than 256 point samples. The NoiseChannel2D contract
        // guarantees fill and sample agree bit-for-bit at the same block coordinate, so
        // this matches tableAt() for the same column — the worm walk relies on that.
        float[] noise = new float[CHUNK_SIZE * CHUNK_SIZE];
        wobble.fill(noise, baseX, baseZ, CHUNK_SIZE, CHUNK_SIZE, 1);

        int[] table = new int[CHUNK_SIZE * CHUNK_SIZE];
        for (int i = 0; i < table.length; i++) {
            int water = waterLevels == null ? TerrainTile.NO_WATER : waterLevels[i];
            table[i] = resolve(heights[i], water, noise[i]);
        }
        return table;
    }

    /**
     * Table at an arbitrary world column. Used by carvers whose walk leaves the chunk they
     * are building a mask for, where the batched grid is not available.
     */
    public int tableAt(int worldX, int worldZ) {
        return tableFrom(worldX, worldZ,
            heightMap.generateHeight(worldX, worldZ),
            heightMap.waterLevel(worldX, worldZ));
    }

    /**
     * As {@link #tableAt}, for a caller that has already resolved the column's surface and
     * water level.
     *
     * <p>Worth having separately: the worm walk fetches both once per step already, and a
     * carver scan touches thousands of steps per chunk, so re-resolving them here would
     * triple the walk's tile lookups for a value it is holding in a local.
     */
    public int tableFrom(int worldX, int worldZ, int surface, int waterLevel) {
        return resolve(surface, waterLevel, wobble.sample(worldX, worldZ));
    }

    /**
     * The table for one column.
     *
     * <p>A wet column pins the table to its own water surface: a gallery near a river should
     * sit at the river's level, not at a value interpolated from terrain that the river has
     * already incised through.
     */
    private static int resolve(int surface, int waterLevel, float wobbleNoise) {
        int table;
        if (waterLevel != TerrainTile.NO_WATER && waterLevel > 0) {
            table = waterLevel;
        } else {
            table = Math.round(SEA_LEVEL + (surface - SEA_LEVEL) * DAMP + wobbleNoise * WOBBLE);
        }
        table = Math.max(table, FLOOR);
        // Roof clamp last so it always wins: a gallery that reaches the surface would carve
        // an unintended open trench across the landscape.
        return Math.min(table, surface - MIN_ROOF);
    }

    /**
     * Zone of a block, given its column's table.
     *
     * <p>Checked top-down: anything clear above the present table is vadose; otherwise the
     * storey bands claim it; anything below them all is phreatic.
     */
    public static Zone zoneAt(int table, int y) {
        if (y > table + BAND) {
            return Zone.VADOSE;
        }
        for (int k = 0; k < STOREYS; k++) {
            int storey = table - k * LEVEL_SPACING;
            if (Math.abs(y - storey) <= BAND) {
                return Zone.EPIPHREATIC;
            }
        }
        return Zone.PHREATIC;
    }

    /**
     * How strongly a block sits in a gallery band: 1 at a storey's exact level, falling
     * linearly to 0 at {@link #BAND} away, taking the nearest storey.
     *
     * <p>This is what produces horizontal galleries in the noise field without sampling the
     * noise a second time at a different vertical squash. Feeding it into a carve threshold
     * concentrates carving into a slab around each former water table — and a slab is a
     * horizontal gallery. Anisotropy from the threshold rather than from the sampling grid,
     * which is the difference between three noise fills per chunk and nine.
     */
    public static float galleryWeight(int table, int y) {
        float best = 0f;
        for (int k = 0; k < STOREYS; k++) {
            int storey = table - k * LEVEL_SPACING;
            float w = 1f - Math.abs(y - storey) / (float) BAND;
            if (w > best) {
                best = w;
            }
        }
        return best;
    }

    /** Y of storey {@code k} (0 = present table), for carvers that want to track a gallery. */
    public static int storeyY(int table, int k) {
        return table - k * LEVEL_SPACING;
    }

    /** Lowest storey centre — below this every column is phreatic. */
    public static int deepestStoreyY(int table) {
        return table - (STOREYS - 1) * LEVEL_SPACING;
    }
}

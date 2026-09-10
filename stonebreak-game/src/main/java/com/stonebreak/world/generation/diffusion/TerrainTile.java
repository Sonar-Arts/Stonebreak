package com.stonebreak.world.generation.diffusion;

/**
 * One decoded tile from the terrain bridge's {@code POST /generate_heightmap}
 * response: a {@code height x width} grid of already-mapped block heights,
 * the co-located (still vanilla-Minecraft, unmapped — see plan.md Phase 4)
 * biome ids, and the per-column water level, row-major with
 * row = worldX - worldI1, col = worldZ - worldJ1.
 *
 * <p>Upstream crops as {@code elev_up[crop_i1:crop_i2, crop_j1:crop_j2]} and
 * reports {@code h, w = elev.shape} (see minecraft_api.py {@code _get_upsampled}),
 * so <em>rows are the i axis</em> — which the bridge maps to world X
 * ({@code i1 = tile_x * tile_size}, see terrain-bridge/bridge/tiling.py) — and
 * columns are the j axis, i.e. world Z. Getting this backwards transposes each
 * tile about its own diagonal; because tiles are square no length check ever
 * catches it, and the only symptom is tile-sized terrain patches that don't
 * line up at their seams.
 *
 * <p>Careful with the canonical constructor: it takes {@code width} <em>before</em>
 * {@code height}, while the wire headers are read {@code X-Height} first.
 */
public record TerrainTile(
        int tileX,
        int tileZ,
        int worldI1,
        int worldJ1,
        int worldI2,
        int worldJ2,
        int width,
        int height,
        short[] blockHeights,
        short[] biomeIds,
        short[] waterLevels,
        short[] riverFloors,
        short[] riverRoofs
) {

    /**
     * A tile with no river tunnels in it. Every source of tiles other than the
     * water carve — the bridge client, the test fixtures — produces one of
     * these, so the two planes are nullable rather than allocated and filled
     * with a sentinel per tile.
     */
    public TerrainTile(int tileX, int tileZ, int worldI1, int worldJ1, int worldI2, int worldJ2,
                       int width, int height,
                       short[] blockHeights, short[] biomeIds, short[] waterLevels) {
        this(tileX, tileZ, worldI1, worldJ1, worldI2, worldJ2, width, height,
                blockHeights, biomeIds, waterLevels, null, null);
    }

    /**
     * Water level for a column: the first y that is <em>not</em> water, so a column
     * holds water for {@code height <= y < waterLevel}. {@link #NO_WATER} means the
     * column holds none at all.
     *
     * <p>Ocean is one case of this and not a separate rule — a submerged column
     * reports sea level, which is exactly what the old {@code y < SEA_LEVEL} test
     * placed. Inland lakes and rivers report their own surface, which is higher.
     */
    public static final short NO_WATER = -1;

    /**
     * Sentinel for "this column carries no river tunnel", in both
     * {@link #riverFloorAt} and {@link #riverRoofAt}.
     */
    public static final short NO_TUNNEL = -1;

    public short heightAt(int worldX, int worldZ) {
        return blockHeights[indexOf(worldX, worldZ)];
    }

    public short biomeIdAt(int worldX, int worldZ) {
        return biomeIds[indexOf(worldX, worldZ)];
    }

    /** @see #NO_WATER */
    public short waterLevelAt(int worldX, int worldZ) {
        return waterLevels[indexOf(worldX, worldZ)];
    }

    /**
     * The floor of the river tunnel through this column, or {@link #NO_TUNNEL}.
     *
     * <p>A river that meets ground standing above its own surface runs under it
     * rather than removing it, so the column keeps its full {@link #heightAt}
     * and the passage lives in this pair: the void is
     * {@code riverFloorAt < y < riverRoofAt}, holding water below
     * {@link #waterLevelAt} and air above it. The roof is always well below the
     * terrain surface, so the ground over a tunnel is never breached.
     *
     * <p>This is also the <em>bed</em> of a tunnelled column, and the one a cave
     * guard must measure from — {@link #heightAt} is the hilltop there, and
     * guarding from it leaves the tunnel open to be drained.
     */
    public short riverFloorAt(int worldX, int worldZ) {
        return riverFloors == null ? NO_TUNNEL : riverFloors[indexOf(worldX, worldZ)];
    }

    /** @see #riverFloorAt */
    public short riverRoofAt(int worldX, int worldZ) {
        return riverRoofs == null ? NO_TUNNEL : riverRoofs[indexOf(worldX, worldZ)];
    }

    private int indexOf(int worldX, int worldZ) {
        int row = worldX - worldI1;   // i axis — rows, extent = height
        int col = worldZ - worldJ1;   // j axis — cols, extent = width
        if (row < 0 || row >= height || col < 0 || col >= width) {
            throw new IllegalStateException(
                    "world (" + worldX + "," + worldZ + ") is outside tile (" + tileX + "," + tileZ +
                    ") bounds i[" + worldI1 + "," + worldI2 + ") j[" + worldJ1 + "," + worldJ2 + ") — " +
                    "likely a Java/bridge tile-size mismatch (DiffusionBridgeConfig.tileSizeBlocks " +
                    "must equal the bridge's TERRAIN_BRIDGE_TILE_SIZE)");
        }
        return row * width + col;
    }
}

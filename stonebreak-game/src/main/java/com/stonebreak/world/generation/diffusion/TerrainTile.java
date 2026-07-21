package com.stonebreak.world.generation.diffusion;

/**
 * One decoded tile from the terrain bridge's {@code POST /generate_heightmap}
 * response: a {@code height x width} grid of already-mapped block heights
 * plus the co-located (still vanilla-Minecraft, unmapped — see plan.md Phase 4)
 * biome ids, row-major with row = worldX - worldI1, col = worldZ - worldJ1.
 *
 * <p>Upstream crops as {@code elev_up[crop_i1:crop_i2, crop_j1:crop_j2]} and
 * reports {@code h, w = elev.shape} (see minecraft_api.py {@code _get_upsampled}),
 * so <em>rows are the i axis</em> — which the bridge maps to world X
 * ({@code i1 = tile_x * tile_size}, see terrain-bridge/bridge/tiling.py) — and
 * columns are the j axis, i.e. world Z. Getting this backwards transposes each
 * tile about its own diagonal; because tiles are square no length check ever
 * catches it, and the only symptom is tile-sized terrain patches that don't
 * line up at their seams.
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
        short[] biomeIds
) {

    public short heightAt(int worldX, int worldZ) {
        return blockHeights[indexOf(worldX, worldZ)];
    }

    public short biomeIdAt(int worldX, int worldZ) {
        return biomeIds[indexOf(worldX, worldZ)];
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

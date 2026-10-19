package com.stonebreak.ui.terrainMapper.visualization.impl.diffusion;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.PreviewChannel;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.generation.diffusion.heightmap.HeightMapGenerator;

/**
 * Rivers overlay for DaedalusTGM-Exp's 3D rivers (tile protocol v3): which way every river
 * column flows, and where the banks are shaped in 3D.
 *
 * <ul>
 *   <li>Flowing water is coloured by its flow octant on a colour wheel, so a drainage network
 *       reads as its direction: tributaries change hue where they turn into the main stem.</li>
 *   <li>Still water (the sea, anything the tile gives no flow) is dark blue.</li>
 *   <li>An <b>undercut</b> bank (a dry column with a river tunnel: a stone lip at the waterline
 *       with an air pocket above it) is red; an <b>overhang</b> (a wet column with a tunnel:
 *       water running under the bank's lip) is orange.</li>
 *   <li>Dry land is the near-black background, as in {@link WaterVisualizer}.</li>
 * </ul>
 *
 * <p>Everything is folded into one cached value per column, {@link #code}, so the preview
 * store keeps a single channel for it.
 */
public final class RiverVisualizer implements NoiseVisualizer {

    /** Dry land with no river feature. */
    public static final int DRY = -1;
    /** Water that does not run (sea, or a wet column the tile gives no flow). Octants are 0..7. */
    public static final int STILL = 8;
    /** A dry bank column carrying a tunnel: an undercut at the waterline. */
    public static final int UNDERCUT = 9;
    /** A wet column carrying a tunnel: water under an overhanging lip. */
    public static final int OVERHANG = 10;

    private static final int COLOR_DRY = 0xFF141414;
    private static final int COLOR_STILL = 0xFF0D3B66;
    private static final int COLOR_UNDERCUT = 0xFFE0412F;
    private static final int COLOR_OVERHANG = 0xFFFF9F1C;
    /** Octant names in the tile's own terms: 0 = +X, turning toward +Z. */
    private static final String[] DIRECTIONS = {"+X", "+X +Z", "+Z", "-X +Z", "-X", "-X -Z", "-Z", "+X -Z"};

    private final HeightMapGenerator heightMap;

    public RiverVisualizer(HeightMapGenerator heightMap) {
        this.heightMap = heightMap;
    }

    /**
     * The one value this view caches per column, from the tile's water, tunnel and flow planes.
     * A tunnel wins over flow: an overhang column is flowing water too, but the 3D bank is the
     * thing this view exists to show.
     */
    public static int code(int waterLevel, int riverFloor, int riverRoof, int riverFlow) {
        boolean wet = waterLevel != TerrainTile.NO_WATER;
        boolean tunnel = riverFloor != TerrainTile.NO_TUNNEL && riverRoof > riverFloor;
        if (tunnel) {
            return wet ? OVERHANG : UNDERCUT;
        }
        if (!wet) {
            return DRY;
        }
        return (riverFlow >= 0 && riverFlow <= 7) ? riverFlow : STILL;
    }

    @Override public String displayName() { return "Rivers"; }

    @Override
    public float sample(int worldX, int worldZ) {
        return code(heightMap.waterLevel(worldX, worldZ), heightMap.riverFloor(worldX, worldZ),
                heightMap.riverRoof(worldX, worldZ), heightMap.riverFlow(worldX, worldZ));
    }

    @Override public PreviewChannel channel() { return PreviewChannel.RIVER; }

    @Override
    public float normalize(float raw) {
        return raw; // categorical: colorFor reads the code itself
    }

    @Override
    public int colorFor(float raw) {
        int c = Math.round(raw);
        if (c >= 0 && c <= 7) {
            return flowColor(c);
        }
        return switch (c) {
            case STILL -> COLOR_STILL;
            case UNDERCUT -> COLOR_UNDERCUT;
            case OVERHANG -> COLOR_OVERHANG;
            default -> COLOR_DRY;
        };
    }

    @Override
    public String formatValue(float raw) {
        int c = Math.round(raw);
        if (c >= 0 && c <= 7) {
            return "river flowing " + DIRECTIONS[c];
        }
        return switch (c) {
            case STILL -> "still water";
            case UNDERCUT -> "undercut bank (air pocket at the waterline)";
            case OVERHANG -> "overhang (water under the bank's lip)";
            default -> "dry";
        };
    }

    /** A colour wheel over the eight octants (hue = direction), bright enough to read on black. */
    static int flowColor(int octant) {
        float hue = octant / 8f;
        float s = 0.55f;
        float v = 0.95f;
        float h6 = hue * 6f;
        int sector = (int) Math.floor(h6) % 6;
        float f = h6 - (float) Math.floor(h6);
        float p = v * (1 - s);
        float q = v * (1 - s * f);
        float t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (sector) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }
}

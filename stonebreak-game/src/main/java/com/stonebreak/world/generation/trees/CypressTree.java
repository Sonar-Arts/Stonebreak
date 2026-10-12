package com.stonebreak.world.generation.trees;

import java.util.Random;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Bald cypress shape generator: a tall straight trunk with a flared base, crowned by
 * layered, flat leaf pads that step out from the trunk on short branches, each tier
 * turned away from the one below.
 *
 * <p>Size comes from a {@link Variant} rolled per world position, so the same column always
 * grows the same tree whether it generates now, later from the feature queue, or from a
 * sapling.
 *
 * <p><b>Leaf-decay contract.</b> Every pad is centred on a branch-tip log and is at most
 * {@link #MAX_PAD_RADIUS} wide, which keeps its rim inside {@code LeafDecaySystem.DECAY_RADIUS}.
 * Leaves that rim thinning leaves isolated are pruned by {@link TreeShapeBuffer#flushAnchored}.
 * Logs are written after leaves so foliage never replaces wood.
 */
public final class CypressTree {

    /** Longest branch (3) plus the widest pad (4). */
    public static final int LEAF_RADIUS = 7;
    /** Tallest trunk (24) plus the crown above it, with slack. */
    public static final int MAX_HEIGHT = 28;

    private static final int MAX_PAD_RADIUS = 4;
    /** Vertical gap between tiers: pads are two thick, so three keeps a clear layer between them. */
    private static final int TIER_SPACING = 3;
    /** Share of the trunk (from the top) that carries canopy tiers. */
    private static final double CANOPY_SHARE = 0.65;
    private static final long SEED_TAG = 0xC1A55C1A55C1A55CL;

    private enum Variant {
        //         trunk  +rand  tiers +rand  flare  maxBranch
        SMALL (10, 4, 2, 2, 1, 2),
        MEDIUM(14, 5, 3, 2, 2, 3),
        LARGE (19, 6, 4, 2, 3, 3);

        final int minTrunk, trunkRange, minTiers, tierRange, flareHeight, maxBranch;

        Variant(int minTrunk, int trunkRange, int minTiers, int tierRange,
                int flareHeight, int maxBranch) {
            this.minTrunk = minTrunk;
            this.trunkRange = trunkRange;
            this.minTiers = minTiers;
            this.tierRange = tierRange;
            this.flareHeight = flareHeight;
            this.maxBranch = maxBranch;
        }

        static Variant roll(Random rng) {
            int r = rng.nextInt(100);
            return r < 35 ? SMALL : r < 80 ? MEDIUM : LARGE;
        }
    }

    private CypressTree() {}

    public static void place(World world, int worldX, int worldY, int worldZ) {
        TreeBlockPlacer placer = new TreeBlockPlacer(world);
        place(placer, worldX, worldY, worldZ);
        placer.complete();
    }

    /**
     * Shape generation against any sink — used by sapling growth and by tests. The shape is
     * buffered, and leaves the decay system could never anchor are pruned before anything is
     * written.
     */
    public static void place(TreeBlockSink out, int worldX, int worldY, int worldZ) {
        if (worldY + MAX_HEIGHT >= WorldConfiguration.WORLD_HEIGHT) return;
        TreeShapeBuffer buffer = new TreeShapeBuffer();
        generate(buffer, worldX, worldY, worldZ);
        buffer.flushAnchored(out);
    }

    private static void generate(TreeBlockSink out, int wx, int wy, int wz) {
        Random rng = TreeRandom.forPosition(wx, wy, wz, SEED_TAG);
        Variant v = Variant.roll(rng);
        int trunk = v.minTrunk + rng.nextInt(v.trunkRange);
        int canopyBottom = wy + trunk - (int) Math.round(trunk * CANOPY_SHARE);
        int canopyTop = wy + trunk - 3;
        // Fit the tier count to the band so tiers stay distinct layers, not one merged blob.
        int tiers = Math.min(v.minTiers + rng.nextInt(v.tierRange),
            (canopyTop - canopyBottom) / TIER_SPACING + 1);

        // Foliage first, wood last: a pad never overwrites a trunk or branch log.
        int top = wy + trunk;
        double angle = rng.nextDouble() * Math.PI * 2;
        int[][] tips = new int[tiers][];
        for (int i = 0; i < tiers; i++) {
            float t = tiers == 1 ? 0f : i / (float) (tiers - 1); // 0 = lowest tier
            int y = canopyBottom + Math.round(t * (canopyTop - canopyBottom));
            int length = Math.max(1, Math.round(v.maxBranch * (1f - 0.5f * t)));
            int radius = Math.max(2, Math.round(MAX_PAD_RADIUS - t * 2)) - (v == Variant.SMALL ? 1 : 0);
            tips[i] = new int[]{
                wx + (int) Math.round(Math.cos(angle) * length), y,
                wz + (int) Math.round(Math.sin(angle) * length)};
            placePad(out, rng, tips[i][0], tips[i][1], tips[i][2], Math.max(2, radius));
            // Successive tiers swing 100-160 degrees round the trunk, so the layers step about.
            angle += Math.PI * (0.55 + rng.nextDouble() * 0.35);
        }
        placeCrown(out, rng, wx, top, wz);

        placeFlare(out, wx, wy, wz, v.flareHeight);
        for (int y = wy; y < top; y++) {
            out.placeBlock(wx, y, wz, BlockType.CYPRESS_LOG);
        }
        for (int i = 0; i < tiers; i++) {
            placeBranch(out, wx, wz, tips[i]);
        }
    }

    /** Orthogonally connected log path from the trunk to the tip, so pads stay anchored. */
    private static void placeBranch(TreeBlockSink out, int wx, int wz, int[] tip) {
        int x = wx;
        int z = wz;
        while (x != tip[0] || z != tip[2]) {
            if (Math.abs(tip[0] - x) >= Math.abs(tip[2] - z)) {
                x += Integer.signum(tip[0] - x);
            } else {
                z += Integer.signum(tip[2] - z);
            }
            out.placeBlock(x, tip[1], z, BlockType.CYPRESS_LOG);
        }
    }

    /** A flat, two-layer pad: a wide thinned-rim disc with a narrower layer on top. */
    private static void placePad(TreeBlockSink out, Random rng, int cx, int cy, int cz, int radius) {
        placeDisc(out, rng, cx, cy, cz, radius);
        placeDisc(out, rng, cx, cy + 1, cz, radius - 1);
    }

    private static void placeCrown(TreeBlockSink out, Random rng, int wx, int top, int wz) {
        placeDisc(out, rng, wx, top - 1, wz, 2);
        placeDisc(out, rng, wx, top, wz, 1);
        out.placeBlock(wx, top + 1, wz, BlockType.CYPRESS_LEAVES);
    }

    private static void placeDisc(TreeBlockSink out, Random rng, int cx, int y, int cz, int radius) {
        double limit = (radius + 0.3) * (radius + 0.3);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double d2 = dx * dx + dz * dz;
                if (d2 > limit) continue;
                boolean rim = d2 > (radius - 0.7) * (radius - 0.7);
                if (rim && rng.nextFloat() < 0.35f) continue; // ragged edge
                out.placeBlock(cx + dx, y, cz + dz, BlockType.CYPRESS_LEAVES);
            }
        }
    }

    /** Buttressed base: a plus of logs around the trunk, as tall as the variant's flare. */
    private static void placeFlare(TreeBlockSink out, int wx, int wy, int wz, int height) {
        for (int dy = 0; dy < height; dy++) {
            out.placeBlock(wx + 1, wy + dy, wz, BlockType.CYPRESS_LOG);
            out.placeBlock(wx - 1, wy + dy, wz, BlockType.CYPRESS_LOG);
            out.placeBlock(wx, wy + dy, wz + 1, BlockType.CYPRESS_LOG);
            out.placeBlock(wx, wy + dy, wz - 1, BlockType.CYPRESS_LOG);
        }
    }
}

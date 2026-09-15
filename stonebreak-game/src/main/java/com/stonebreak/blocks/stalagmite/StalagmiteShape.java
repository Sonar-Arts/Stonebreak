package com.stonebreak.blocks.stalagmite;

import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.mobs.sbe.SbeEntityLoader;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.stonebreak.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collision and targeting boxes for stalagmites, measured from the SBO models.
 *
 * <p>Each size's model is cut into horizontal slices {@link #SLICE} tall; a slice's box is the
 * XZ extent of every triangle crossing it, and equal neighbouring slices merge. The stepped
 * models come out as one box per tier (plus the rubble at the foot), so a body collides with
 * the spire it sees rather than a full block, and a ray passes through the open air beside it.
 *
 * <p>Boxes are then placed the way the renderer places the stamp — mirrored top to bottom for a
 * hanging stalagmite, turned by the stair facing's quarter turns — so what collides always
 * matches what is drawn. Re-export a model and the shape follows.
 */
public final class StalagmiteShape {

    private static final Logger logger = LoggerFactory.getLogger(StalagmiteShape.class);

    /** Slice height in blocks: fine enough to follow the tiers, coarse enough to stay a few boxes. */
    private static final float SLICE = 0.0625f;
    private static final float EPS = 1e-4f;

    /** Box layout everywhere here: {@code {minX, minY, minZ, maxX, maxY, maxZ}}. */
    private static final Map<Integer, List<float[]>> MODEL_BOXES = new ConcurrentHashMap<>();
    private static final Map<String, List<float[]>> PLACED_BOXES = new ConcurrentHashMap<>();

    private StalagmiteShape() {}

    /**
     * World-space boxes of the stalagmite occupying {@code (x, y, z)}, or an empty list when the
     * cell is not part of one.
     */
    public static List<float[]> worldBoxes(World world, int x, int y, int z) {
        return worldBoxes(Stalagmite.of(world), x, y, z);
    }

    public static List<float[]> worldBoxes(Stalagmite.Cells cells, int x, int y, int z) {
        int ay = Stalagmite.anchorY(cells, x, y, z);
        if (ay == Integer.MIN_VALUE) {
            return List.of();
        }
        List<float[]> local = placedBoxes(StalagmiteState.parse(cells.state(x, ay, z)));
        List<float[]> world = new ArrayList<>(local.size());
        for (float[] b : local) {
            world.add(new float[]{x + 0.5f + b[0], ay + 0.5f + b[1], z + 0.5f + b[2],
                    x + 0.5f + b[3], ay + 0.5f + b[4], z + 0.5f + b[5]});
        }
        return world;
    }

    /** Whether a world point lies inside the stalagmite at that cell. */
    public static boolean contains(World world, int x, int y, int z, float px, float py, float pz) {
        for (float[] b : worldBoxes(world, x, y, z)) {
            if (px >= b[0] && px <= b[3] && py >= b[1] && py <= b[4] && pz >= b[2] && pz <= b[5]) {
                return true;
            }
        }
        return false;
    }

    /** Boxes for a state, relative to the anchor cell's centre. */
    static List<float[]> placedBoxes(StalagmiteState state) {
        return PLACED_BOXES.computeIfAbsent(state.toStateString(), k -> {
            List<float[]> out = new ArrayList<>();
            for (float[] m : modelBoxes(state.size())) {
                out.add(place(m, state));
            }
            return List.copyOf(out);
        });
    }

    /** Same transform the renderer bakes: flip about the cell centre, then quarter-turn about +Y. */
    static float[] place(float[] b, StalagmiteState state) {
        float minY = b[1], maxY = b[4];
        if (state.hanging()) {
            minY = -b[4];
            maxY = -b[1];
        }
        float[] c1 = turn(b[0], b[2], state.facing().quarterTurns());
        float[] c2 = turn(b[3], b[5], state.facing().quarterTurns());
        return new float[]{Math.min(c1[0], c2[0]), minY, Math.min(c1[1], c2[1]),
                Math.max(c1[0], c2[0]), maxY, Math.max(c1[1], c2[1])};
    }

    /** {@code SBOStampRotator}'s quarter turn: one turn maps +Z onto +X. */
    private static float[] turn(float x, float z, int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> new float[]{z, -x};
            case 2 -> new float[]{-x, -z};
            case 3 -> new float[]{-z, x};
            default -> new float[]{x, z};
        };
    }

    private static List<float[]> modelBoxes(int size) {
        return MODEL_BOXES.computeIfAbsent(size, s -> {
            try {
                float[][] triangles = modelTriangles(s);
                if (triangles != null && triangles.length > 0) {
                    return List.copyOf(slice(triangles));
                }
                logger.warn("No stalagmite geometry for size {} — colliding as full cells", s);
            } catch (Exception e) {
                logger.error("Failed to measure stalagmite size {} — colliding as full cells", s, e);
            }
            return List.of(new float[]{-0.5f, -0.5f, -0.5f, 0.5f, s - 0.5f, 0.5f});
        });
    }

    /** Triangles of a size's model as {@code {minX, minY, minZ, maxX, maxY, maxZ}} bounds. */
    private static float[][] modelTriangles(int size) throws Exception {
        BlockType type = BlockType.LIMESTONE_STALAGMITE;
        BlockRegistry.BlockEntry entry = type == null ? null
                : BlockRegistry.getInstance().getById(type.getId()).orElse(null);
        SBOParseResult sbo = entry != null ? entry.sboData() : null;
        if (sbo == null) {
            return null;
        }
        String stateName = new StalagmiteState(size, false, null).sboStateName();
        OMOReader.ReadResult omo = sbo.stateOmoData().get(stateName);
        if (omo == null && size == 1) {
            omo = sbo.stateOmoData().get(sbo.defaultStateName());
        }
        if (omo == null) {
            return null;
        }
        SbeModelGeometry geometry = SbeEntityLoader.buildGeometry(omo);
        return triangleBounds(geometry.vertices(), geometry.indices());
    }

    static float[][] triangleBounds(float[] vertices, int[] indices) {
        float[][] tris = new float[indices.length / 3][];
        for (int t = 0; t < tris.length; t++) {
            float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                    -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int v = 0; v < 3; v++) {
                int vi = indices[t * 3 + v] * 3;
                for (int a = 0; a < 3; a++) {
                    b[a] = Math.min(b[a], vertices[vi + a]);
                    b[a + 3] = Math.max(b[a + 3], vertices[vi + a]);
                }
            }
            tris[t] = b;
        }
        return tris;
    }

    /** Slices triangle bounds into merged horizontal boxes. */
    static List<float[]> slice(float[][] triangles) {
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] t : triangles) {
            minY = Math.min(minY, t[1]);
            maxY = Math.max(maxY, t[4]);
        }
        List<float[]> boxes = new ArrayList<>();
        float[] open = null;
        for (float lo = minY; lo < maxY - EPS; lo += SLICE) {
            float hi = Math.min(lo + SLICE, maxY);
            float[] xz = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (float[] t : triangles) {
                // A side face spanning the slice, or a cap lying strictly inside it. A cap exactly
                // on a slice boundary belongs to neither: the sides above and below it already
                // give each slice its true width.
                boolean crosses = t[4] > lo + EPS && t[1] < hi - EPS;
                if (!crosses) continue;
                xz[0] = Math.min(xz[0], t[0]);
                xz[1] = Math.min(xz[1], t[2]);
                xz[2] = Math.max(xz[2], t[3]);
                xz[3] = Math.max(xz[3], t[5]);
            }
            if (xz[0] > xz[2]) {
                open = null;
                continue;
            }
            if (open != null && Math.abs(open[0] - xz[0]) < EPS && Math.abs(open[2] - xz[1]) < EPS
                    && Math.abs(open[3] - xz[2]) < EPS && Math.abs(open[5] - xz[3]) < EPS
                    && Math.abs(open[4] - lo) < EPS) {
                open[4] = hi;
            } else {
                open = new float[]{xz[0], lo, xz[1], xz[2], hi, xz[3]};
                boxes.add(open);
            }
        }
        return boxes;
    }
}

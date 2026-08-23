package com.openmason.engine.voxel.lighting;

/**
 * Stateless per-vertex lighting sampler. Produces the unified brightness factor
 * that the mesh's per-vertex light attribute carries into the shader.
 *
 * <p>Current factors:
 * <ul>
 *   <li><b>Sky occlusion</b> — fraction (0..1) of the 4 columns touching the
 *       vertex on the face's air side whose heightmap is at or below the
 *       vertex's Y.</li>
 *   <li><b>Ambient occlusion</b> — classic 3-neighbor solid count on the air
 *       side of the face; 0..3 neighbors maps to 1.0 / 0.87 / 0.74 / 0.61.</li>
 * </ul>
 *
 * <p>Both are pure functions of current world state exposed via
 * {@link LightingContext}; no propagation, no seeding, no global queues. Safe
 * to call from mesh-builder threads. Future emissive-block light folds in here
 * as an additional factor without changing the external contract.
 *
 * <p>MMS face convention: 0 top (+Y), 1 bottom (-Y), 2 north (-Z), 3 south
 * (+Z), 4 east (+X), 5 west (-X).
 *
 * @since 1.0
 */
public final class VertexLightSampler {

    /** How much each solid AO neighbor dims the vertex. 3 neighbors → 0.61. */
    private static final float AO_PER_NEIGHBOR = 0.13f;
    /** Minimum sky factor for a fully-shaded vertex before AO multiplies. */
    private static final float SKY_FLOOR = 0.0f;

    /**
     * Smooth-lighting quality switch. When on (default), vertices blend a 2x2
     * sky-column neighborhood and apply 3-tap ambient occlusion — soft gradients
     * and darkened creases. When off, each vertex takes a single sky sample and
     * skips AO entirely: flat, blocky lighting at roughly 1/7th the context
     * queries per vertex. Read by mesh-builder threads; hosts must remesh loaded
     * chunks after flipping it for the change to become visible.
     */
    private static volatile boolean smoothLightingEnabled = true;

    private VertexLightSampler() {}

    public static void setSmoothLightingEnabled(boolean enabled) {
        smoothLightingEnabled = enabled;
    }

    public static boolean isSmoothLightingEnabled() {
        return smoothLightingEnabled;
    }

    /** Combined per-vertex brightness factor: {@code skyFactor * aoFactor} ∈ [0,1]. */
    public static float sampleCombined(LightingContext ctx, float vx, float vy, float vz, int face) {
        if (ctx == null) return 1.0f;
        int ivx = Math.round(vx);
        int ivy = Math.round(vy);
        int ivz = Math.round(vz);
        boolean smooth = smoothLightingEnabled;
        float sky = sampleSkyFactor(ctx, ivx, ivy, ivz, face, smooth);
        if (!smooth) return sky;
        float ao = sampleAoFactor(ctx, ivx, ivy, ivz, face);
        return sky * ao;
    }

    /**
     * Geometry-aware variant for shaped (SBO stamp) blocks whose vertices may sit
     * at fractional cell coordinates — a stair's upper riser at half depth, its
     * lower tread at half height. {@link #sampleCombined(LightingContext, float,
     * float, float, int)} rounds every coordinate to the nearest cell corner,
     * which snaps such a face onto the emitting block's own cell: the riser then
     * "sees" the stair beneath it as an occluder and its own column as overhead
     * cover, and renders in shadow even in open daylight (issue #224).
     *
     * <p>Here the cells touching a vertex are resolved per axis: along the face
     * normal the air side is {@code floor(v + n·ε)}; along the tangents an
     * integral coordinate touches two cells ({@code v-1}, {@code v}) while a
     * fractional one lies inside a single cell. The emitting block's own cell
     * ({@code ownX, ownY, ownZ}) is treated as open — interior geometry is by
     * definition inside it — both for occlusion and for its column's sky height.
     * Integral vertices of a boundary face resolve to exactly the same cells as
     * the rounding variant, so cube-shaped stamps are lit identically.
     */
    public static float sampleCombined(LightingContext ctx, float vx, float vy, float vz, int face,
                                       int ownX, int ownY, int ownZ) {
        if (ctx == null) return 1.0f;
        // Normal direction per MMS face: +Y, -Y, -Z, +Z, +X, -X.
        int nx = face == 4 ? 1 : face == 5 ? -1 : 0;
        int ny = face == 0 ? 1 : face == 1 ? -1 : 0;
        int nz = face == 3 ? 1 : face == 2 ? -1 : 0;
        int xlo = nx != 0 ? floorAlongNormal(vx, nx) : floor(vx - CELL_EPS);
        int xhi = nx != 0 ? xlo : floor(vx + CELL_EPS);
        int ylo = ny != 0 ? floorAlongNormal(vy, ny) : floor(vy - CELL_EPS);
        int yhi = ny != 0 ? ylo : floor(vy + CELL_EPS);
        int zlo = nz != 0 ? floorAlongNormal(vz, nz) : floor(vz - CELL_EPS);
        int zhi = nz != 0 ? zlo : floor(vz + CELL_EPS);

        boolean smooth = smoothLightingEnabled;

        // Sky: the (up to four) air-side columns touching the vertex. (a, b) walk
        // the two tangent axes; flat lighting samples only the (hi, hi) cell.
        int litCount = 0;
        int sampled = 0;
        for (int a = smooth ? 0 : 1; a < 2; a++) {
            for (int b = smooth ? 0 : 1; b < 2; b++) {
                int cx, cy, cz;
                if (ny != 0) {            // top/bottom: tangents x, z
                    cx = a == 0 ? xlo : xhi; cy = ylo; cz = b == 0 ? zlo : zhi;
                } else if (nz != 0) {     // north/south: tangents x, y
                    cx = a == 0 ? xlo : xhi; cy = b == 0 ? ylo : yhi; cz = zlo;
                } else {                  // east/west: tangents y, z
                    cx = xlo; cy = a == 0 ? ylo : yhi; cz = b == 0 ? zlo : zhi;
                }
                int h = columnHeight(ctx, cx, cz, ownX, ownY, ownZ);
                if (h < 0) continue;
                sampled++;
                if (cy >= h) litCount++;
            }
        }
        float sky = sampled == 0 ? 1.0f : SKY_FLOOR + (1.0f - SKY_FLOOR) * ((float) litCount / sampled);
        if (!smooth) return sky;

        // AO: classic 3-neighbour count over the air-side cells. The "air" cell is
        // the (hi, hi) corner; sides are one step down each tangent; corner both.
        // A fractional tangent coordinate collapses that axis (lo == hi), so a
        // side or the corner can coincide with another cell — count each distinct
        // cell once.
        int s1x, s1y, s1z, s2x, s2y, s2z, cx, cy, cz;
        if (ny != 0) {            // top/bottom: tangents x, z
            s1x = xlo; s1y = ylo; s1z = zhi;
            s2x = xhi; s2y = ylo; s2z = zlo;
            cx = xlo;  cy = ylo;  cz = zlo;
        } else if (nz != 0) {     // north/south: tangents x, y
            s1x = xlo; s1y = yhi; s1z = zlo;
            s2x = xhi; s2y = ylo; s2z = zlo;
            cx = xlo;  cy = ylo;  cz = zlo;
        } else {                  // east/west: tangents y, z
            s1x = xlo; s1y = ylo; s1z = zhi;
            s2x = xlo; s2y = yhi; s2z = zlo;
            cx = xlo;  cy = ylo;  cz = zlo;
        }
        boolean side1 = solid(ctx, s1x, s1y, s1z, ownX, ownY, ownZ);
        boolean side2 = !same(s2x, s2y, s2z, s1x, s1y, s1z) && solid(ctx, s2x, s2y, s2z, ownX, ownY, ownZ);
        boolean corner = !same(cx, cy, cz, s1x, s1y, s1z) && !same(cx, cy, cz, s2x, s2y, s2z)
                && solid(ctx, cx, cy, cz, ownX, ownY, ownZ);
        int count = (side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0);
        if (side1 && side2) count = 3;
        return sky * (1.0f - AO_PER_NEIGHBOR * count);
    }

    private static boolean same(int ax, int ay, int az, int bx, int by, int bz) {
        return ax == bx && ay == by && az == bz;
    }

    /** Sub-cell tolerance separating "on a cell boundary" from "inside a cell". */
    private static final float CELL_EPS = 1e-3f;

    private static int floor(float v) {
        return (int) Math.floor(v);
    }

    /** Cell on the air side of a face plane at {@code v} whose normal points along {@code n}. */
    private static int floorAlongNormal(float v, int n) {
        return floor(v + n * CELL_EPS);
    }

    private static boolean solid(LightingContext ctx, int x, int y, int z, int ownX, int ownY, int ownZ) {
        if (x == ownX && y == ownY && z == ownZ) return false;
        return ctx.isSolidAt(x, y, z);
    }

    /**
     * Column height with the emitting block's own cell treated as open: when the
     * block itself is the column's topmost occluder, the sky starts at its floor.
     */
    private static int columnHeight(LightingContext ctx, int x, int z, int ownX, int ownY, int ownZ) {
        int h = ctx.getColumnHeight(x, z);
        if (h == ownY + 1 && x == ownX && z == ownZ) return ownY;
        return h;
    }

    /** Point sky probe for shading first-person geometry at the player's eye. */
    public static float samplePointSky(LightingContext ctx, float wx, float wy, float wz) {
        if (ctx == null) return 1.0f;
        int ix = (int) Math.floor(wx);
        int iy = (int) Math.floor(wy);
        int iz = (int) Math.floor(wz);
        int h = ctx.getColumnHeight(ix, iz);
        if (h < 0) return 1.0f; // unloaded
        return iy >= h ? 1.0f : Math.max(SKY_FLOOR, 0.5f);
    }

    // ─── Sky factor ────────────────────────────────────────────────────────

    private static float sampleSkyFactor(LightingContext ctx, int ivx, int ivy, int ivz, int face,
                                         boolean smooth) {
        int litCount = 0;
        int sampled = 0;
        // Flat lighting samples only the (0, 0) column — one lookup instead of four.
        int lo = smooth ? -1 : 0;
        for (int a = lo; a <= 0; a++) {
            for (int b = lo; b <= 0; b++) {
                int cx, cy, cz;
                switch (face) {
                    case 0 -> { cx = ivx + a; cy = ivy;     cz = ivz + b; } // top
                    case 1 -> { cx = ivx + a; cy = ivy - 1; cz = ivz + b; } // bottom
                    case 2 -> { cx = ivx + a; cy = ivy + b; cz = ivz - 1; } // north
                    case 3 -> { cx = ivx + a; cy = ivy + b; cz = ivz;     } // south
                    case 4 -> { cx = ivx;     cy = ivy + a; cz = ivz + b; } // east
                    case 5 -> { cx = ivx - 1; cy = ivy + a; cz = ivz + b; } // west
                    default -> { continue; }
                }
                int h = ctx.getColumnHeight(cx, cz);
                if (h < 0) continue; // unloaded neighbor — don't count
                sampled++;
                if (cy >= h) litCount++;
            }
        }
        if (sampled == 0) return 1.0f;
        float skyFraction = (float) litCount / (float) sampled;
        return SKY_FLOOR + (1.0f - SKY_FLOOR) * skyFraction;
    }

    // ─── Ambient occlusion ────────────────────────────────────────────────

    private static float sampleAoFactor(LightingContext ctx, int ivx, int ivy, int ivz, int face) {
        int nx, ny, nz;      // offset from vertex to air-side cell
        int t1x, t1y, t1z;   // tangent 1
        int t2x, t2y, t2z;   // tangent 2
        switch (face) {
            case 0 -> { nx=0;  ny=0;  nz=0;  t1x=-1; t1y=0;  t1z=0;  t2x=0; t2y=0;  t2z=-1; } // top
            case 1 -> { nx=0;  ny=-1; nz=0;  t1x=-1; t1y=0;  t1z=0;  t2x=0; t2y=0;  t2z=-1; } // bottom
            case 2 -> { nx=0;  ny=0;  nz=-1; t1x=-1; t1y=0;  t1z=0;  t2x=0; t2y=-1; t2z=0;  } // north
            case 3 -> { nx=0;  ny=0;  nz=0;  t1x=-1; t1y=0;  t1z=0;  t2x=0; t2y=-1; t2z=0;  } // south
            case 4 -> { nx=0;  ny=0;  nz=0;  t1x=0;  t1y=-1; t1z=0;  t2x=0; t2y=0;  t2z=-1; } // east
            case 5 -> { nx=-1; ny=0;  nz=0;  t1x=0;  t1y=-1; t1z=0;  t2x=0; t2y=0;  t2z=-1; } // west
            default -> { return 1.0f; }
        }
        boolean side1  = ctx.isSolidAt(ivx + nx + t1x,       ivy + ny + t1y,       ivz + nz + t1z);
        boolean side2  = ctx.isSolidAt(ivx + nx + t2x,       ivy + ny + t2y,       ivz + nz + t2z);
        boolean corner = ctx.isSolidAt(ivx + nx + t1x + t2x, ivy + ny + t1y + t2y, ivz + nz + t1z + t2z);
        int count = (side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0);
        // Minecraft-style: if both sides occlude, force corner-full regardless.
        if (side1 && side2) count = 3;
        return 1.0f - AO_PER_NEIGHBOR * count;
    }
}

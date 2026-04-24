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
 *       side of the face; 0..3 neighbors maps to 1.0 / 0.8 / 0.6 / 0.4.</li>
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

    /** How much each solid AO neighbor dims the vertex. 3 neighbors → 0.4. */
    private static final float AO_PER_NEIGHBOR = 0.2f;
    /** Minimum sky factor for a fully-shaded vertex before AO multiplies. */
    private static final float SKY_FLOOR = 0.0f;

    private VertexLightSampler() {}

    /** Combined per-vertex brightness factor: {@code skyFactor * aoFactor} ∈ [0,1]. */
    public static float sampleCombined(LightingContext ctx, float vx, float vy, float vz, int face) {
        if (ctx == null) return 1.0f;
        int ivx = Math.round(vx);
        int ivy = Math.round(vy);
        int ivz = Math.round(vz);
        float sky = sampleSkyFactor(ctx, ivx, ivy, ivz, face);
        float ao = sampleAoFactor(ctx, ivx, ivy, ivz, face);
        return sky * ao;
    }

    /** Point sky probe for shading first-person geometry at the player's eye. */
    public static float samplePointSky(LightingContext ctx, float wx, float wy, float wz) {
        if (ctx == null) return 1.0f;
        int ix = (int) Math.floor(wx);
        int iy = (int) Math.floor(wy);
        int iz = (int) Math.floor(wz);
        int h = ctx.getColumnHeight(ix, iz);
        if (h < 0) return 1.0f; // unloaded
        return iy >= h ? 1.0f : Math.max(SKY_FLOOR, 0.35f);
    }

    // ─── Sky factor ────────────────────────────────────────────────────────

    private static float sampleSkyFactor(LightingContext ctx, int ivx, int ivy, int ivz, int face) {
        int litCount = 0;
        int sampled = 0;
        for (int a = -1; a <= 0; a++) {
            for (int b = -1; b <= 0; b++) {
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

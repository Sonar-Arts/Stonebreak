package com.openmason.engine.voxel.lighting;

import java.util.Arrays;

/**
 * Bounded diffuse-bounce approximation. Directly illuminated solid faces seed the
 * adjacent air; four attenuating propagation steps carry that light around corners.
 * Solids block both seed visibility and propagation. No GL/world dependencies.
 * Neutral reflectance and strongest-path propagation deliberately bound energy.
 */
public final class IndirectLightVolume {
    public static final float REFLECTANCE = .22f;
    public static final float STEP_TRANSMISSION = .55f;
    public static final int SPREAD_STEPS = 4;
    private static final int[][] NORMALS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private final int size;
    private final boolean[] solid;
    private final boolean[] reflective;
    private float[] light;
    private float[] scratch;

    public IndirectLightVolume(int size) {
        if (size < 3) throw new IllegalArgumentException("Volume must have an interior");
        this.size = size;
        solid = new boolean[size * size * size];
        reflective = new boolean[solid.length];
        light = new float[solid.length];
        scratch = new float[solid.length];
    }

    /** Returns whether this cell's light-blocking or reflection properties changed. */
    public boolean setCell(int x, int y, int z, boolean blocked, boolean reflects) {
        int i = index(x, y, z);
        boolean changed = solid[i] != blocked || reflective[i] != (blocked && reflects);
        solid[i] = blocked;
        reflective[i] = blocked && reflects;
        return changed;
    }

    public float get(int x, int y, int z) { return light[index(x, y, z)]; }
    /** x-fastest texels suitable for GL_RED volume upload. Owned by this object. */
    public float[] data() { return light; }

    public void rebuild(float sourceX, float sourceY, float sourceZ, float radius) {
        Arrays.fill(light, 0);
        int sx = (int) Math.floor(sourceX), sy = (int) Math.floor(sourceY), sz = (int) Math.floor(sourceZ);
        if (!inside(sx, sy, sz) || solid[index(sx, sy, sz)]) return;
        float radiusSq = radius * radius;
        for (int z = 1; z < size - 1; z++) for (int y = 1; y < size - 1; y++) for (int x = 1; x < size - 1; x++) {
            int cell = index(x, y, z);
            if (solid[cell]) continue;
            for (int[] n : NORMALS) {
                // n points from the reflecting solid into this air cell.
                if (!reflective[index(x - n[0], y - n[1], z - n[2])]) continue;
                float px = x + .5f - n[0] * .48f;
                float py = y + .5f - n[1] * .48f;
                float pz = z + .5f - n[2] * .48f;
                float dx = sourceX - px, dy = sourceY - py, dz = sourceZ - pz;
                float distSq = dx * dx + dy * dy + dz * dz;
                float cosine = dx * n[0] + dy * n[1] + dz * n[2];
                if (cosine <= 0 || distSq >= radiusSq) continue;
                if (!visible(sourceX, sourceY, sourceZ, px, py, pz)) continue;
                float dist = (float) Math.sqrt(distSq);
                float t = 1 - dist / radius;
                float falloff = t * t * (3 - 2 * t);
                float seed = REFLECTANCE * falloff * falloff * cosine / Math.max(dist, .001f);
                light[cell] = Math.max(light[cell], seed);
            }
        }
        for (int step = 0; step < SPREAD_STEPS; step++) {
            Arrays.fill(scratch, 0);
            for (int z = 1; z < size - 1; z++) for (int y = 1; y < size - 1; y++) for (int x = 1; x < size - 1; x++) {
                int cell = index(x, y, z);
                if (solid[cell]) continue;
                float value = light[cell];
                for (int[] n : NORMALS) value = Math.max(value,
                        light[index(x + n[0], y + n[1], z + n[2])] * STEP_TRANSMISSION);
                scratch[cell] = value;
            }
            float[] swap = light; light = scratch; scratch = swap;
        }
    }

    /** Supercover voxel traversal: a ray cannot slip through the edge of two touching solids. */
    private boolean visible(float sx, float sy, float sz, float tx, float ty, float tz) {
        int x = (int) Math.floor(sx), y = (int) Math.floor(sy), z = (int) Math.floor(sz);
        float dx = tx - sx, dy = ty - sy, dz = tz - sz;
        int ix = dx >= 0 ? 1 : -1, iy = dy >= 0 ? 1 : -1, iz = dz >= 0 ? 1 : -1;
        float ax = dx == 0 ? Float.POSITIVE_INFINITY : Math.abs(1 / dx);
        float ay = dy == 0 ? Float.POSITIVE_INFINITY : Math.abs(1 / dy);
        float az = dz == 0 ? Float.POSITIVE_INFINITY : Math.abs(1 / dz);
        float nx = dx == 0 ? ax : (dx > 0 ? x + 1 - sx : sx - x) * ax;
        float ny = dy == 0 ? ay : (dy > 0 ? y + 1 - sy : sy - y) * ay;
        float nz = dz == 0 ? az : (dz > 0 ? z + 1 - sz : sz - z) * az;
        for (int steps = 0; steps < size * 3; steps++) {
            if (!inside(x, y, z) || solid[index(x, y, z)]) return false;
            float next = Math.min(nx, Math.min(ny, nz));
            if (next >= 1) return true;
            boolean crossX = nx <= next + 1e-6f, crossY = ny <= next + 1e-6f, crossZ = nz <= next + 1e-6f;
            // Check each side cell on ties before crossing the corner.
            if (crossX && blocked(x + ix, y, z) || crossY && blocked(x, y + iy, z)
                    || crossZ && blocked(x, y, z + iz)) return false;
            if (crossX && crossY && blocked(x + ix, y + iy, z)
                    || crossX && crossZ && blocked(x + ix, y, z + iz)
                    || crossY && crossZ && blocked(x, y + iy, z + iz)) return false;
            if (crossX) { x += ix; nx += ax; }
            if (crossY) { y += iy; ny += ay; }
            if (crossZ) { z += iz; nz += az; }
        }
        return false;
    }

    private boolean blocked(int x, int y, int z) { return !inside(x, y, z) || solid[index(x, y, z)]; }
    private boolean inside(int x, int y, int z) { return x >= 0 && x < size && y >= 0 && y < size && z >= 0 && z < size; }
    private int index(int x, int y, int z) { return (z * size + y) * size + x; }
}

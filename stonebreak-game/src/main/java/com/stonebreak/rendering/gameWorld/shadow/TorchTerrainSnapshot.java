package com.stonebreak.rendering.gameWorld.shadow;

import com.stonebreak.rendering.gameWorld.regions.CubeDraw;

import java.util.List;

/**
 * Exact mesh identities, not a lossy hash: edits/uploads/unloads invalidate cached terrain depth. A cube's
 * {@link CubeDraw} is replaced on every remesh, so comparing the instances in order catches uploads,
 * unloads and enable changes of the set a torch view draws.
 */
final class TorchTerrainSnapshot {
    private Object[] references = new Object[0];
    private boolean valid;

    boolean matches(List<?> cubes) {
        if (!valid || references.length != cubes.size()) return false;
        for (int i = 0; i < cubes.size(); i++) {
            if (references[i] != cubes.get(i)) return false;
        }
        return true;
    }

    void capture(List<?> cubes) {
        if (references.length != cubes.size()) references = new Object[cubes.size()];
        for (int i = 0; i < cubes.size(); i++) {
            references[i] = cubes.get(i);
        }
        valid = true;
    }

    void clear() {
        java.util.Arrays.fill(references, null);
        valid = false;
    }
}

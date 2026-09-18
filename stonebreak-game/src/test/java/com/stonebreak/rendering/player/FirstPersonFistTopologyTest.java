package com.stonebreak.rendering.player;

import com.openmason.engine.format.omo.OMOReader;
import com.stonebreak.mobs.sbe.SbeEntityLoader;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FirstPersonFistTopologyTest {
    @Test
    void exportedFistIsOneClosedConsistentlyWoundShell() {
        var geometry = SbeEntityLoader.load(FirstPersonArmPose.RESOURCE).geometryFor("Default");
        var fist = geometry.parts().stream().filter(p -> p.name().equals("fist")).findFirst().orElseThrow();
        // Render vertices split at face/UV seams; compare the shared geometric positions.
        Map<Vector3f, Integer> vertices = new HashMap<>();
        Map<Long, Integer> edgeUses = new HashMap<>(), winding = new HashMap<>();
        Map<Integer, Set<Integer>> neighbors = new HashMap<>();
        int triangles = 0;
        for (var face : fist.faces()) {
            for (int i = face.indexStart(); i < face.indexStart() + face.indexCount(); i += 3) {
                var points = new Vector3f[3];
                var ids = new int[3];
                for (int c = 0; c < 3; c++) {
                    int v = geometry.indices()[i + c] * 3;
                    points[c] = new Vector3f(geometry.vertices()[v], geometry.vertices()[v + 1], geometry.vertices()[v + 2]);
                    ids[c] = vertices.computeIfAbsent(points[c], k -> vertices.size());
                }
                assertTrue(new Vector3f(points[1]).sub(points[0])
                        .cross(new Vector3f(points[2]).sub(points[0])).lengthSquared() > 0, "degenerate triangle");
                for (int c = 0; c < 3; c++) {
                    int a = ids[c], b = ids[(c + 1) % 3];
                    assertNotEquals(a, b);
                    long edge = ((long) Math.min(a, b) << 32) | Math.max(a, b);
                    edgeUses.merge(edge, 1, Integer::sum);
                    winding.merge(edge, a < b ? 1 : -1, Integer::sum);
                    neighbors.computeIfAbsent(a, k -> new HashSet<>()).add(b);
                    neighbors.computeIfAbsent(b, k -> new HashSet<>()).add(a);
                }
                triangles++;
            }
        }
        assertFalse(edgeUses.isEmpty());
        edgeUses.values().forEach(n -> assertEquals(2, n, "open or non-manifold edge"));
        winding.values().forEach(n -> assertEquals(0, n, "inconsistent face winding"));
        var visited = new HashSet<Integer>();
        var pending = new ArrayDeque<Integer>();
        pending.add(0);
        while (!pending.isEmpty()) {
            int v = pending.removeFirst();
            if (visited.add(v)) pending.addAll(neighbors.get(v));
        }
        assertEquals(vertices.size(), visited.size(), "disconnected finger or thumb");
        assertEquals(2, vertices.size() - edgeUses.size() + triangles, "fist must have no through holes");
    }

    @Test
    void sourceFistRetainsHandBoneAndAnimatedForearmParent() throws Exception {
        try (var stream = getClass().getResourceAsStream("/models/player/SB_FPArm.omo")) {
            var source = new OMOReader().read(stream);
            var fist = source.parts().stream().filter(p -> p.name().equals("fist")).findFirst().orElseThrow();
            var forearm = source.parts().stream().filter(p -> p.name().equals("forearm")).findFirst().orElseThrow();
            assertEquals("5a0c1b9a-e44a-59de-a3b4-3d5edf1c708f", fist.boneId());
            assertEquals(forearm.id(), fist.parentId());
        }
    }
}

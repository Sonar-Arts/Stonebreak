package com.stonebreak.world.chunk;

import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.format.sbo.SBOParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game textures SBO geometry from the baked per-corner texCoords, while
 * Open Mason draws a face with a sub-region mapping by projecting it into that
 * region. An export that skipped the re-projection keeps the old 0..1 corners,
 * so a face mapped to one pixel (an ore nugget's side) shows the WHOLE texture
 * in game while looking right in the editor. Every corner of a face with a
 * sub-region mapping must lie inside that region.
 */
public class SboFaceUvConsistencyTest {

    private static final Path SBO_ROOT = Path.of("src/main/resources/sbo");
    private static final float EPS = 1e-4f;

    @Test
    void subRegionMappedFacesHaveTheirCornersInsideTheRegion() throws Exception {
        List<String> stale = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(SBO_ROOT)) {
            for (Path sbo : files.filter(p -> p.toString().endsWith(".sbo")).sorted().toList()) {
                int bad = staleFaces(sbo);
                if (bad > 0) stale.add(SBO_ROOT.relativize(sbo) + " (" + bad + " faces)");
                checked++;
            }
        }
        assertTrue(checked > 0, "fixture: no SBOs found under " + SBO_ROOT);
        assertTrue(stale.isEmpty(), "faces whose baked UVs ignore their mapping region — "
                + "re-export from Open Mason or rebake: " + stale);
    }

    private static int staleFaces(Path sbo) throws IOException {
        SBOParseResult parsed = new SBOParser().parse(sbo);
        ParsedMeshData mesh = parsed.meshData();
        if (mesh == null || !mesh.hasGeometry() || parsed.faceMappings() == null
                || mesh.triangleToFaceId() == null) {
            return 0;
        }
        Map<Integer, ParsedFaceMapping> regions = new HashMap<>();
        for (ParsedFaceMapping m : parsed.faceMappings()) {
            boolean full = m.u0() <= EPS && m.v0() <= EPS && m.u1() >= 1 - EPS && m.v1() >= 1 - EPS;
            if (!full) regions.put(m.faceId(), m);
        }
        float[] uv = mesh.texCoords();
        int[] idx = mesh.indices();
        int[] triFace = mesh.triangleToFaceId();
        java.util.Set<Integer> badFaces = new java.util.HashSet<>();
        for (int t = 0; t < triFace.length; t++) {
            ParsedFaceMapping m = regions.get(triFace[t]);
            if (m == null) continue;
            for (int k = 0; k < 3; k++) {
                int v = idx[t * 3 + k];
                float u = uv[v * 2], w = uv[v * 2 + 1];
                if (u < m.u0() - EPS || u > m.u1() + EPS || w < m.v0() - EPS || w > m.v1() + EPS) {
                    badFaces.add(triFace[t]);
                }
            }
        }
        return badFaces.size();
    }
}

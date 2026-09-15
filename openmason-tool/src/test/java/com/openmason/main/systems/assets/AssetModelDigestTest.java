package com.openmason.main.systems.assets;

import com.openmason.engine.rendering.model.gmr.analysis.WindingAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Digests real shipped assets — SBO block, SBE mob — end to end. */
class AssetModelDigestTest {

    private final AssetCatalog catalog = new AssetCatalog();

    private AssetEntry require(String id) {
        AssetEntry e = catalog.find(id);
        assertNotNull(e, "asset " + id + " not found in resources");
        return e;
    }

    @Test
    void sboBlockDigests() throws IOException {
        AssetModelDigest digest = AssetModelDigest.build(require("SB_Oak_Door"), null, null);
        assertTrue(digest.modelBearing());
        assertFalse(digest.parts().isEmpty());
        assertTrue(digest.totalFaces() > 0);
        assertTrue(digest.totalTriangles() > 0);
        for (AssetModelDigest.PartDigest p : digest.parts()) {
            assertNotNull(p.winding());
            assertEquals(p.mesh().faceCount()
                            + p.winding().degenerateFaceIds().length
                            - (int) p.winding().faces().stream()
                                    .filter(f -> f.degenerate() && p.mesh().face(f.faceId()) != null).count(),
                    p.winding().faceCount(),
                    "winding report face count mismatch for part " + p.name());
        }
        // Every mapped face id must belong to some part.
        for (int faceId : digest.faceMappings().keySet()) {
            assertNotNull(digest.partOfFace(faceId), "face " + faceId + " attributed to no part");
        }
    }

    @Test
    void sbeMobDigestsWithParts() throws IOException {
        AssetModelDigest digest = AssetModelDigest.build(require("stonebreak:cow"), null, null);
        assertTrue(digest.modelBearing());
        assertTrue(digest.parts().size() > 1, "cow should be multi-part");
        assertTrue(digest.materials().size() >= 1);
        AssetModelDigest.Pixels px = digest.materialPixels(digest.materials().get(0).materialId());
        assertTrue(px.width() > 0 && px.height() > 0);
        assertEquals(px.width() * px.height(), px.packed().length);
    }

    @Test
    void unknownStateHasTeachingError() {
        AssetEntry entry = require("stonebreak:cow");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AssetModelDigest.build(entry, "not_a_state", null));
        assertTrue(e.getMessage().contains("state"));
    }

    @Test
    void windingOnShippedAssetsNeverCrashes() throws IOException {
        // Sweep everything — the analyzer must classify every shipped asset
        // without throwing (indeterminate is fine, crashes are not).
        int checked = 0;
        for (AssetEntry entry : catalog.listAll(true)) {
            AssetModelDigest digest;
            try {
                digest = AssetModelDigest.build(entry, null, null);
            } catch (IOException | RuntimeException e) {
                throw new AssertionError("digest failed for " + entry.id() + ": " + e, e);
            }
            for (AssetModelDigest.PartDigest p : digest.parts()) {
                WindingAnalyzer.PartReport r = p.winding();
                assertNotNull(r.faces());
                checked++;
            }
        }
        assertTrue(checked > 0);
    }
}

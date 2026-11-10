package com.stonebreak.openmason.assets.mcp;

import com.openmason.main.systems.mcp.PixelTextCodec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stonebreak.openmason.StonebreakSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the eyeglass end-to-end against the real shipped game assets. */
@Tag("integration")
class AssetLensServiceTest {

    /** The lens reads the active game target's tree (#380): the Stonebreak checkout. */
    @BeforeEach
    void stonebreakSession() {
        StonebreakSession.install();
    }

    @AfterEach
    void noTarget() {
        StonebreakSession.clear();
    }

    private static AssetLensService service() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
        return new AssetLensService(null, mapper); // null main interface — snapshot-test discipline
    }

    @Test
    void listFindsShippedAssets() {
        AssetLensService.AssetListResult result =
                service().list(null, null, null, 200, 0, true);
        assertTrue(result.total() >= 70, "expected >=70 assets, saw " + result.total());
        assertTrue(result.assets().stream().anyMatch(r -> r.kind().equals("sbe")));
    }

    @Test
    void manifestAndMeshSummary() throws IOException {
        AssetLensService service = service();
        ObjectNode manifest = service.manifest("stonebreak:cow");
        assertEquals("stonebreak:cow", manifest.get("id").asText());
        assertTrue(manifest.get("inventory").get("modelBearing").asBoolean());

        // An SBO's Stonebreak data is decoded under "gameplay" whatever its layout (#383); the
        // shipped files are legacy-layout, so it comes from their top-level fields.
        ObjectNode stone = service.manifest("stonebreak:cobblestone");
        assertEquals(29, stone.path("gameplay").path("gameProperties").path("numericId").asInt(), stone.toString());
        assertEquals("stone", stone.path("gameplay").path("gameProperties").path("material").asText());
        assertEquals("OPAQUE", stone.path("manifest").path("rendering").path("renderLayer").asText());
        assertTrue(stone.path("manifest").path("extensions").path("legacyFields").has("gameProperties"),
                stone.toString());

        Map<String, Object> summary = service.meshSummary("stonebreak:cow", null, null);
        assertTrue((Boolean) summary.get("modelBearing"));
        assertNotNull(summary.get("totals"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) summary.get("parts");
        assertTrue(parts.size() > 1, "cow should be multi-part");
    }

    @Test
    void faceDataPaginates() throws IOException {
        Map<String, Object> page = service().faceData("stonebreak:cow", null, null,
                null, null, 0, 5);
        @SuppressWarnings("unchecked")
        List<AssetLensService.FaceRow> faces = (List<AssetLensService.FaceRow>) page.get("faces");
        assertEquals(5, faces.size());
        assertTrue((Boolean) page.get("truncated"));
        for (AssetLensService.FaceRow row : faces) {
            assertNotNull(row.loop(), "face " + row.faceId() + " has no loop");
            assertTrue(row.loop().length >= 3);
        }
    }

    @Test
    void textureDescribeWorksFileOnly() throws IOException {
        AssetLensService service = service();
        // Multi-material asset without a material id must teach, not guess.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.textureDescribe("stonebreak:cow", null, null,
                        null, null, PixelTextCodec.Options.defaults()));
        assertTrue(e.getMessage().contains("material"));
        Map<String, Object> described = service.textureDescribe("stonebreak:cow", null, null,
                1, null, PixelTextCodec.Options.defaults());
        assertNotNull(described.get("describe"), "no describe payload");
        assertNotNull(described.get("textureSize"));
    }

    @Test
    void checkWindingRunsOnAssets() throws IOException {
        Map<String, Object> report = service().checkWinding("stonebreak:cow", null, null, null);
        assertNotNull(report.get("status"));
        assertNotNull(report.get("parts"));
        assertNotNull(report.get("totals"));
    }

    @Test
    void unknownAssetTeaches() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().manifest("not_a_real_asset"));
        assertTrue(e.getMessage().contains("asset_list"));
    }

    @Test
    void openWithoutUiDeclinesStructurally() throws IOException {
        Map<String, Object> result = service().open("stonebreak:cow", null, null, 1);
        assertEquals(Boolean.FALSE, result.get("opened"));
        assertEquals("approval_unavailable", result.get("reason"));
        assertFalse(String.valueOf(result.get("message")).isBlank());
    }
}

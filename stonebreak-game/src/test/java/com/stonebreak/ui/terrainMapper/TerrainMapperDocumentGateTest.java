package com.stonebreak.ui.terrainMapper;

import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.UiWallClock;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The terrain mapper migration gate (#301): {@code ui/documents/terrain_mapper.sbui} against the legacy
 * renderer's frozen captures (panels, map, fields, view modes, spawn and footer buttons
 * {@code FLOAT_EXACT}, pixels {@code EXACT}, the live map included).
 */
@Tag("regression")
class TerrainMapperDocumentGateTest {

    @Test
    void theShippedTerrainMapperPassesTheMigrationGate() throws Exception {
        ChatDocumentGateTest.requireLua();
        try {
            MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
                .run("terrain-mapper", TerrainMapperFixtures.cases(), FrozenCaptures.forTests("terrain-mapper").replay(),
                    DocumentTerrainMapperCapture.shipped());
            GateAssert.passed(r);
        } finally {
            UiWallClock.release();
        }
    }

    /**
     * Opt-in ({@code -Dstonebreak.ui.terrainmapper.worldoptions=true}): the reviewed edit of the frozen captures for
     * the EXP-2 world-options row (scale + climate buttons under the spawn section, shown once a generator is
     * picked). It is NOT a re-record: for every case the shipped document must match the frozen capture exactly
     * outside the row's band (caption top to button bottom, sidebar inner width), else it fails and writes nothing;
     * only that band is copied into the frozen PNG, and the row's two rects (from {@link TerrainMapperLayout}, the
     * independent oracle) are added to the case's geometry entry. Cases with no generator must not change at all.
     */
    @Test
    void addTheWorldOptionsRowToTheFrozenCaptures() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("stonebreak.ui.terrainmapper.worldoptions"),
            "opt-in reviewed edit of the frozen captures");
        ChatDocumentGateTest.requireLua();
        try {
            FrozenCaptures frozen = FrozenCaptures.forTests("terrain-mapper");
            java.nio.file.Path geometry = frozen.geometryFile();
            var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(geometry.toFile());
            var all = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("cases");
            DocumentTerrainMapperCapture live = DocumentTerrainMapperCapture.shipped();
            java.util.Map<java.nio.file.Path, com.openmason.engine.ui.fidelity.FidelityImage> edits =
                new java.util.LinkedHashMap<>();
            StringBuilder report = new StringBuilder();
            for (com.openmason.engine.ui.fidelity.FidelityCase c : TerrainMapperFixtures.cases()) {
                java.nio.file.Path png = geometry.resolveSibling(c.id() + ".png");
                var old = com.openmason.engine.ui.fidelity.FidelityImage.read(png);
                MigrationGate.Capture cap = live.render(c);
                var now = cap.image();
                org.junit.jupiter.api.Assertions.assertEquals(old.width(), now.width(), c.id());
                org.junit.jupiter.api.Assertions.assertEquals(old.height(), now.height(), c.id());
                float[] scale = cap.rects().get("scale");
                float[] climate = cap.rects().get("climate");
                // the band the row may paint: caption top (24 px above the buttons) to the buttons' bottom, plus the
                // button surface's edge (1 px each side) and drop shadow (3 px below)
                int x0 = 0, x1 = 0, y0 = 0, y1 = 0;
                if (scale != null && climate != null) {
                    x0 = (int) Math.floor(scale[0]) - 2;
                    x1 = (int) Math.ceil(climate[0] + climate[2]) + 2;
                    y0 = (int) Math.floor(scale[1] - 24f);
                    y1 = (int) Math.ceil(scale[1] + scale[3]) + 4;
                }
                int outside = 0, inside = 0;
                int ox0 = Integer.MAX_VALUE, oy0 = Integer.MAX_VALUE, ox1 = -1, oy1 = -1;
                int[] merged = old.argb().clone();
                for (int y = 0; y < old.height(); y++) {
                    for (int x = 0; x < old.width(); x++) {
                        boolean band = x >= x0 && x < x1 && y >= y0 && y < y1;
                        int i = y * old.width() + x;
                        if (old.argb()[i] != now.argb()[i]) {
                            if (band) {
                                inside++;
                            } else {
                                outside++;
                                ox0 = Math.min(ox0, x);
                                oy0 = Math.min(oy0, y);
                                ox1 = Math.max(ox1, x);
                                oy1 = Math.max(oy1, y);
                            }
                        }
                        if (band) merged[i] = now.argb()[i];
                    }
                }
                report.append(c.id()).append(": ").append(inside).append(" px in the row band, ").append(outside)
                    .append(" outside").append(outside == 0 ? "" : " in x " + ox0 + ".." + ox1 + ", y " + oy0 + ".." + oy1)
                    .append(" (band x ").append(x0).append("..").append(x1).append(", y ").append(y0).append("..")
                    .append(y1).append(")\n");
                org.junit.jupiter.api.Assertions.assertEquals(0, outside,
                    c.id() + ": the document differs from the frozen capture outside the world-options row\n" + report);
                if (scale == null) {
                    org.junit.jupiter.api.Assertions.assertEquals(0, inside, c.id());
                    continue;
                }
                org.junit.jupiter.api.Assertions.assertTrue(inside > 0, c.id() + ": the row painted nothing");
                java.util.Map<String, float[]> oracle = oracleRects(c);
                edits.put(png, new com.openmason.engine.ui.fidelity.FidelityImage(old.width(), old.height(), merged));
                var rects = (com.fasterxml.jackson.databind.node.ObjectNode) all.get(c.id()).get("rects");
                for (String n : java.util.List.of("scale", "climate")) {
                    var a = rects.putArray(n);
                    for (float v : oracle.get(n)) {
                        a.add(v);
                    }
                }
                // keep the entry's names sorted as the recorder writes them
                var sorted = new java.util.TreeMap<String, com.fasterxml.jackson.databind.JsonNode>();
                rects.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
                rects.removeAll();
                sorted.forEach(rects::set);
            }
            edits.forEach((p, img) -> img.write(p));
            json.writeValue(geometry.toFile(), root);
            System.out.println("[terrain-mapper world options]\n" + report);
        } finally {
            UiWallClock.release();
        }
    }

    /** The legacy-formula rects of a case's state (the geometry oracle). */
    private static java.util.Map<String, float[]> oracleRects(com.openmason.engine.ui.fidelity.FidelityCase c) {
        TerrainMapperScreen screen = new TerrainMapperScreen();
        try {
            TerrainMapperFixtures.prepare(screen.getStateManager(), c.variant());
            return TerrainMapperFixtures.rects(c.viewport().width(), c.viewport().height(), screen.getStateManager());
        } finally {
            screen.dispose();
        }
    }
}

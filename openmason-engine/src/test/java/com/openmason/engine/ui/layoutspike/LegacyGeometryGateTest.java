package com.openmason.engine.ui.layoutspike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.ui.fidelity.GeometryComparator;
import com.openmason.engine.ui.fidelity.GeometryRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The geometry half of the migration gate (#296) run on the #283 flexbox trees: every case of the
 * committed legacy oracle ({@code ui/fixtures/legacy-geometry.json}) laid out by Yoga must pass
 * {@link GeometryComparator} under the screen's rule — pause {@link GeometryRule#FLOAT_EXACT}
 * (pixel grid off), furnace {@link GeometryRule#INTEGER_CENTRED} (1 px grid). These are the rules
 * #297 and #298 are held to; the trees are the starting point of their documents.
 */
class LegacyGeometryGateTest {

    @Test
    void flexTreesPassTheGeometryGateAtEveryCommittedCase() throws IOException {
        assumeTrue(YogaFlex.isAvailable(), "Cenda library with Yoga not built");
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/ui/fixtures/legacy-geometry.json")) {
            assertNotNull(in, "legacy-geometry.json missing");
            root = new ObjectMapper().readTree(in);
        }
        StringBuilder failures = new StringBuilder();
        int cases = 0;
        for (JsonNode c : root.get("cases")) {
            String screen = c.get("screen").asText();
            int w = c.get("framebuffer").get(0).asInt();
            int h = c.get("framebuffer").get(1).asInt();
            float s = (float) c.get("uiScale").asDouble();
            boolean pause = screen.equals("pause");
            ScreenFixtures.Screen sc = pause ? ScreenFixtures.pause(w, h, s, c.get("online").asBoolean())
                : ScreenFixtures.furnace(w, h, s);
            float[] out = YogaFlex.layout(sc.tree(), w, h, pause ? 0f : 1f, null);

            Map<String, float[]> legacy = new LinkedHashMap<>();
            Map<String, float[]> laidOut = new LinkedHashMap<>();
            c.get("rects").properties().forEach(e -> {
                JsonNode r = e.getValue();
                legacy.put(e.getKey(), new float[]{(float) r.get(0).asDouble(), (float) r.get(1).asDouble(),
                    (float) r.get(2).asDouble(), (float) r.get(3).asDouble()});
                int n = sc.nodes().get(e.getKey());
                laidOut.put(e.getKey(), new float[]{out[n * 4], out[n * 4 + 1], out[n * 4 + 2], out[n * 4 + 3]});
            });
            GeometryComparator.GeometryReport report = GeometryComparator.compare(legacy, laidOut, w, h,
                pause ? GeometryRule.FLOAT_EXACT : GeometryRule.INTEGER_CENTRED);
            if (!report.passed()) {
                failures.append(String.format(Locale.ROOT, "%s %dx%d@%s: %s%n", screen, w, h, s, report.summary()));
            }
            cases++;
        }
        assertTrue(cases >= 11, "fixture lost cases: " + cases);
        assertTrue(failures.isEmpty(), failures.toString());
    }
}

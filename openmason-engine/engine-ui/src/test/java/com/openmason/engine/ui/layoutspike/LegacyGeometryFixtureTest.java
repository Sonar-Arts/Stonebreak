package com.openmason.engine.ui.layoutspike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the first pause/furnace fixtures (#283) as language-neutral data:
 * {@code src/test/resources/ui/fixtures/legacy-geometry.json} holds today's
 * rects at representative framebuffer sizes and UI scales, so #287's layout
 * conformance tests (and a future C++ runtime) can check against plain data.
 * Regenerate after an intentional legacy change with
 * {@code -Dui.fixtures.write=true}.
 */
@Tag("regression")
class LegacyGeometryFixtureTest {

    private static final String RESOURCE = "/ui/fixtures/legacy-geometry.json";
    private static final Object[][] CASES = {
        {"pause", 1920, 1080, 1f, false}, {"pause", 1920, 1080, 1f, true},
        {"pause", 1280, 720, 0.75f, false}, {"pause", 1280, 720, 0.75f, true},
        {"pause", 3840, 2160, 2f, true}, {"pause", 1921, 1081, 1.25f, true},
        {"furnace", 1920, 1080, 1f, false}, {"furnace", 1280, 720, 0.75f, false},
        {"furnace", 3840, 2160, 2f, false}, {"furnace", 1921, 1081, 1.25f, false},
        {"furnace", 800, 600, 2f, false},
    };

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void legacyOracleMatchesTheCommittedFixture() throws IOException {
        ObjectNode generated = generate();
        if (Boolean.getBoolean("ui.fixtures.write")) {
            Path out = Path.of("src/test/resources" + RESOURCE);
            Files.createDirectories(out.getParent());
            json.writeValue(out.toFile(), generated);
            return; // the classpath copy is stale until the next build
        }
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, RESOURCE + " missing — run once with -Dui.fixtures.write=true");
            JsonNode committed = json.readTree(in);
            assertEquals(committed, generated, "legacy layout math drifted from " + RESOURCE);
        }
    }

    private ObjectNode generate() {
        ObjectNode root = json.createObjectNode();
        root.put("description", "Legacy Stonebreak UI geometry (#283): today's hand-written layout math, "
            + "transcribed in ScreenFixtures with file:line references, pinned to commit 2b4bcc91.");
        root.put("units", "framebuffer pixels; rect = [x, y, width, height], origin top-left");
        root.put("notes", "pause: SkijaPauseMenuRenderer + PauseMenu hit-test (same rects). "
            + "furnace: InventoryLayoutCalculator.calculateWorkbenchLayout + FurnaceLayout; int tokens "
            + "round(K*uiScale), integer centring truncates.");
        ArrayNode cases = root.putArray("cases");
        for (Object[] c : CASES) {
            String screen = (String) c[0];
            int w = (int) c[1], h = (int) c[2];
            float s = (float) c[3];
            boolean online = (boolean) c[4];
            ScreenFixtures.Screen sc = screen.equals("pause")
                ? ScreenFixtures.pause(w, h, s, online) : ScreenFixtures.furnace(w, h, s);
            ObjectNode node = cases.addObject();
            node.put("screen", screen);
            node.putArray("framebuffer").add(w).add(h);
            node.put("uiScale", (double) s);
            if (screen.equals("pause")) {
                node.put("online", online);
            }
            ObjectNode rects = node.putObject("rects");
            for (Map.Entry<String, float[]> e : sc.legacy().entrySet()) {
                ArrayNode r = rects.putArray(e.getKey());
                for (float v : e.getValue()) {
                    r.add((double) v); // doubles: a re-read file parses as DoubleNode
                }
            }
        }
        return root;
    }
}

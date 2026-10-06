package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.ui.runtime.UiDocs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;

/**
 * The #292 sample documents, built from the readable sources in
 * {@code src/test/resources/ui/script/samples/}. {@code ScriptSampleFixtureTest} pins the packed
 * archives next to them ({@code ui/script/*.omui}), which the game's dev overlay
 * ({@code -Dstonebreak.uidoc}) and the editor preview ({@code -Dopenmason.uidoc.preview}) open.
 * Not a test class.
 */
final class ScriptSamples {

    static final String PAUSE_ID = "stonebreak:ui/samples/scripted_pause";
    static final String MINIGAME_ID = "stonebreak:ui/samples/minigame";

    /** Preview fixture: an online session and deterministic action results. */
    static final String PAUSE_FIXTURE = """
        {
          "data": {
            "session": {
              "hosting": false,
              "mode": "SINGLEPLAYER",
              "online": true
            }
          },
          "contracts": {
            "session": "stonebreak:session"
          },
          "actions": {
            "stonebreak:network.resync": {
              "result": {
                "audited": 12
              }
            },
            "stonebreak:screen.pause.resume": {
              "result": null
            }
          }
        }
        """;

    private ScriptSamples() {
    }

    static OmuiArchive scriptedPause() {
        UiDocs.N panel = box("panel").name("panel").style("width", 360).style("height", 260)
            .style("background-color", "#202020E6").style("border-radius", 6).style("align-items", "center")
            .style("justify-content", "center").style("row-gap", 12).kids(
                label("title", "Scripted Pause").name("title").style("font-size", 24).style("color", "#FFFFFF"),
                label("online", "Online").name("online").style("color", "#7CFC00")
                    .bind("style:display", "session.online", UiNode.BindingMode.TO_TARGET, "display_if"),
                label("status", "Ready").name("status").style("color", "#FFFFFF"),
                button("resync", "Resync"),
                button("resume", "Resume"));
        UiDocs.N root = box("root").style("width", "100%").style("height", "100%").style("align-items", "center")
            .style("justify-content", "center").style("picking-mode", "ignore").kids(panel);
        OmuiArchive doc = UiDocs.declare(screen(PAUSE_ID, root), List.of(), "stonebreak:network.resync@1",
            "stonebreak:screen.pause@1", "stonebreak:session@1");
        return ScriptRig.withCode(doc, "pause", source("scripted_pause.lua"))
            .withEditorEntry("editor/fixtures.json", UiBytes.utf8(PAUSE_FIXTURE));
    }

    static OmuiArchive minigame() {
        UiDocs.N frame = box("frame").name("frame").style("align-items", "center").style("row-gap", 8).kids(
            label("title", "Minigame: 1,000 sprites").name("title").style("color", "#FFFFFF"),
            node("game", "Canvas").name("game").style("width", 640).style("height", 360).prop("capacity", 16384),
            label("hint", "Move the pointer to bat the sprites; click to reset").name("hint")
                .style("color", "#B0B0B0"));
        UiDocs.N root = box("root").style("width", "100%").style("height", "100%").style("align-items", "center")
            .style("justify-content", "center").style("picking-mode", "ignore").kids(frame);
        OmuiArchive doc = UiDocs.declare(screen(MINIGAME_ID, root), List.of(UiFeatures.CANVAS));
        return ScriptRig.withCode(doc, "minigame", source("minigame.lua"));
    }

    private static UiDocs.N button(String id, String text) {
        return node(id, "Button").name(id).style("width", 160).style("height", 36).style("align-items", "center")
            .style("justify-content", "center").kids(label(id + "_label", text).style("color", "#FFFFFF"));
    }

    static String source(String file) {
        try (InputStream in = ScriptSamples.class.getResourceAsStream("/ui/script/samples/" + file)) {
            if (in == null) {
                throw new IllegalStateException("missing sample source " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

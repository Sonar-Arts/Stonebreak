package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Headless input replay (#288 acceptance: pointer, focus and keyboard behaviour match between
 * the game runtime and preview fixtures). The committed script drives a menu, a text field, a
 * modal dialog, a scroll list and a controller through both host paths at UI scale 1.25; the
 * traces must be identical to each other and to the committed golden trace. Regenerate the
 * golden with {@code -Dui.replay.write=true} after an intentional behaviour change.
 */
class InputReplayTest {

    private static final String DIR = "/ui/input/replay/";

    private static UiDocs.N button(String id, String text) {
        return node(id, "Button").style("width", 100).style("height", 24).kids(label(id + "_text", text));
    }

    static OmuiArchive settingsScreen() {
        UiDocs.N list = node("list", "ScrollView").style("width", 120).style("height", 60);
        for (int i = 0; i < 6; i++) {
            list.kids(button("row" + i, "Row " + i).style("flex-shrink", 0));
        }
        return InputRig.withFeatures(screen("t:ui/replay", box("root").style("width", "100%").style("height", "100%")
            .style("flex-direction", "row").style("column-gap", 12).style("picking-mode", "ignore").kids(
                box("menu").style("row-gap", 4).kids(button("play", "Play"), button("options", "Options"),
                    button("quit", "Quit")),
                box("form").style("row-gap", 4).kids(
                    node("name", "TextField").prop("pattern", "[A-Za-z ]+").prop("placeholder", "Name")
                        .style("width", 140).style("height", 24),
                    button("apply", "Apply"), list),
                box("dialog").prop("focusScope", "modal").style("display", "none").style("position", "absolute")
                    .style("left", 150).style("top", 150).style("width", 200).style("height", 60)
                    .style("flex-direction", "row").kids(button("yes", "Yes"), button("no", "No")))),
            UiFeatures.INPUT, UiFeatures.SCROLL);
    }

    /** Quit opens the dialog; any answer or Cancel closes it. */
    static void wire(UiDocumentInstance ui) {
        ui.find("quit").on(UiEventType.CLICK, e -> ui.find("dialog").setStyle("display", UiValue.of("flex")));
        UiEventHandler close = e -> {
            ui.find("dialog").setStyle("display", UiValue.of("none"));
            e.stopPropagation();
        };
        ui.find("yes").on(UiEventType.CLICK, close);
        ui.find("no").on(UiEventType.CLICK, close);
        ui.find("dialog").on(UiEventType.CANCEL, close);
    }

    @Test
    void gameAndPreviewHostsProduceTheGoldenTrace() throws IOException {
        String script = resource("settings.replay");
        List<String> game = InputReplay.run(settingsScreen(), script, InputReplay.Host.GAME, 640, 360, 1.25f,
            InputReplayTest::wire);
        List<String> preview = InputReplay.run(settingsScreen(), script, InputReplay.Host.PREVIEW, 640, 360, 1.25f,
            InputReplayTest::wire);
        assertEquals(String.join("\n", game), String.join("\n", preview), "game window and editor preview diverged");
        String actual = String.join("\n", game) + "\n";
        if (Boolean.getBoolean("ui.replay.write")) {
            Path out = Path.of("src/test/resources" + DIR + "settings.trace");
            Files.writeString(out, actual, StandardCharsets.UTF_8);
            return;
        }
        assertEquals(resource("settings.trace"), actual, "behaviour changed; inspect and regenerate if intended");
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = InputReplayTest.class.getResourceAsStream(DIR + name)) {
            assertNotNull(in, "missing fixture " + name + " (regenerate the trace with -Dui.replay.write=true)");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

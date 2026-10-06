package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.ui.data.FixtureHost;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The #292 samples: their packed archives are pinned (regenerate with
 * {@code -Dui.script.write=true}), and the scripted pause runs on the editor's fixture host
 * exactly as the game test runs it on {@code GameUiHost}.
 */
class ScriptSampleTest {

    static final Path DIR = Path.of("src/test/resources/ui/script");
    static final boolean WRITE = Boolean.getBoolean("ui.script.write");

    @Test
    void packedSamplesMatchTheirSources() throws Exception {
        Map<String, byte[]> golden = new LinkedHashMap<>();
        golden.put("scripted_pause.omui", OmuiWriter.write(ScriptSamples.scriptedPause()));
        golden.put("minigame.omui", OmuiWriter.write(ScriptSamples.minigame()));
        for (Map.Entry<String, byte[]> e : golden.entrySet()) {
            Path file = DIR.resolve(e.getKey());
            if (WRITE) {
                Files.createDirectories(DIR);
                Files.write(file, e.getValue());
            }
            assertTrue(Files.exists(file), file + " missing; generate it with -Dui.script.write=true");
            assertArrayEquals(e.getValue(), Files.readAllBytes(file),
                e.getKey() + " drifted from its source; if intentional rerun with -Dui.script.write=true");
            OmuiArchive back = OmuiReader.read(e.getValue()).archive();
            assertTrue(back.document().codeBehind() != null && !back.scripts().isEmpty());
        }
    }

    @Test
    void scriptedPauseRunsOnTheEditorFixtureHost() {
        OmuiArchive doc = ScriptSamples.scriptedPause();
        FixtureHost fixture = FixtureHost.forArchive(doc);
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, fixture.host(), UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
            // Open animation: the panel fades and slides in through the host sampler.
            assertEquals(0, rig.el("panel").computedStyle().number("opacity", 1), 1e-6);
            rig.frame(0.175);
            double mid = rig.el("panel").computedStyle().number("opacity", 1);
            assertTrue(mid > 0.3 && mid < 1, "fading in: " + mid);
            rig.frame(0.25);
            assertEquals(1, rig.el("panel").computedStyle().number("opacity", 0), 1e-6);
            // Binding through the Lua converter.
            assertEquals("flex", rig.el("online").computedStyle().keyword("display", "?"));
            // Code-behind event -> awaited host action -> label + colour tween.
            rig.click("resync");
            assertEquals("Resyncing...", rig.text("status"));
            fixture.host().drain();
            rig.frame(0.016);
            assertEquals("Audited 12 chunks (1)", rig.text("status"), rig.rt.diagnostics().toString());
            assertEquals("stonebreak:network.resync", fixture.calls().getFirst().actionId());
            // Resume: fade out, then the host action.
            rig.click("resume");
            rig.frame(0.25);
            rig.frame(0.016);
            fixture.host().drain();
            assertEquals("stonebreak:screen.pause.resume", fixture.calls().getLast().actionId());
        }
    }
}

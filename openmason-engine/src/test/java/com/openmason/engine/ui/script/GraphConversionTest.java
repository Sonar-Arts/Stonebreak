package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiGraph;
import com.openmason.engine.ui.data.FixtureHost;
import com.openmason.engine.ui.graph.edit.GraphEditor;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Convert to script" (#291): the module copied from a graph's Lua, wired from code-behind,
 * behaves like the graph it came from.
 */
class GraphConversionTest {

    static final String WIRING = """
        local pause = require("pause_script")
        local M = {}
        function M.on_open(ui) pause.bind(M) pause.on_open() end
        return M
        """;

    @Test
    void theConvertedModuleRunsLikeTheGraph() {
        OmuiArchive graphDoc = ScriptSamples.graphPause();
        GraphEditor editor = GraphEditor.open(graphDoc, "pause", UiDocumentSource.EMPTY);
        assertTrue(editor.convertToScript("pause_script").isEmpty());
        OmuiArchive converted = withoutGraphs(ScriptRig.withCode(editor.document(), WIRING));

        List<String> graph = run(graphDoc);
        List<String> script = run(converted);
        assertEquals(graph, script);
        assertEquals("Audited 12 chunks (1)", script.getLast().split("\\|")[0], script.toString());
    }

    private static OmuiArchive withoutGraphs(OmuiArchive d) {
        return new OmuiArchive(d.manifest(), d.document(), d.styles(), Map.<String, UiGraph>of(), d.animations(),
            d.scripts(), d.dependencies(), d.assets(), d.editor(), d.extraEntries());
    }

    /** Status text and panel opacity after each step of the same interaction. */
    private static List<String> run(OmuiArchive doc) {
        FixtureHost fixture = FixtureHost.forArchive(doc);
        Map<String, String> out = new LinkedHashMap<>();
        try (ScriptRig rig = new ScriptRig(doc, UiDocumentSource.EMPTY, fixture.host(), UiScriptOptions.DEFAULTS,
            UiScriptServices.NONE)) {
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
            rig.frame(0.5);
            out.put("open", snapshot(rig));
            rig.click("resync");
            fixture.host().drain();
            rig.frame(0.016);
            out.put("resync", snapshot(rig));
        }
        return List.copyOf(out.values());
    }

    private static String snapshot(ScriptRig rig) {
        return rig.text("status") + "|" + rig.el("panel").computedStyle().number("opacity", -1) + "|"
            + rig.el("resync").computedStyle().keyword("display", "?");
    }
}

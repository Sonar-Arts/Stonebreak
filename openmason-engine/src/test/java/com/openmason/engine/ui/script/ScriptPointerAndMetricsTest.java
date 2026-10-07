package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.input.PointerEvent;
import org.junit.jupiter.api.Test;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What container screens' code-behind needs (#298): pointer events carry the held buttons and the
 * modifiers (a right-drag sweep enters slots with the right button held; shift-click), and
 * {@code ui.metrics()} gives the viewport and scale a legacy screen's integer pixel maths needs.
 */
class ScriptPointerAndMetricsTest {

    private static OmuiArchive doc() {
        return ScriptRig.withCode(screen("t:ui/pointer", box("root").style("width", 400).style("height", 300)
            .style("flex-direction", "row").kids(
                box("a").name("a").style("width", 100).style("height", 100),
                box("b").name("b").style("width", 100).style("height", 100),
                label("out", "-").name("out"),
                label("m", "-").name("m"))), """
            function on_open(ui)
              local out = ui.q("#out")
              ui.q("#a"):on("pointer-down", function(e)
                out:setText(string.format("down %d held %d shift %s", e.button, e.buttons, tostring((e.mods & 1) ~= 0)))
              end)
              ui.q("#b"):on("pointer-enter", function(e)
                out:setText((e.buttons & 2) ~= 0 and "sweep" or "hover")
              end)
            end
            function update()
              local m = ui.metrics()
              ui.q("#m"):setText(string.format("%d %d %.2f %.2f", m.width, m.height, m.uiScale, m.scale))
            end
            """);
    }

    @Test
    void pointerEventsCarryHeldButtonsAndModifiers() {
        try (ScriptRig rig = new ScriptRig(doc())) {
            float[] a = centre(rig, "a");
            float[] b = centre(rig, "b");
            rig.view.input().pointerMove(a[0], a[1]);
            rig.view.input().pointerDown(a[0], a[1], PointerEvent.SECONDARY, 1);
            rig.frame(0);
            assertEquals("down 1 held 2 shift true", rig.text("out"));

            rig.view.input().pointerMove(b[0], b[1]);
            rig.frame(0);
            assertEquals("sweep", rig.text("out"), "entered with the right button held");

            rig.view.input().pointerUp(b[0], b[1], PointerEvent.SECONDARY, 0);
            rig.view.input().pointerMove(a[0], a[1]);
            rig.view.input().pointerMove(b[0], b[1]);
            rig.frame(0);
            assertEquals("hover", rig.text("out"));
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
        }
    }

    @Test
    void metricsReportTheLastLayout() {
        try (ScriptRig rig = new ScriptRig(doc())) {
            rig.ui.setMetrics(new UiMetrics(1280, 720, 0.75f, 2f));
            rig.frame(0);
            rig.frame(0);
            assertEquals("1280 720 0.75 1.50", rig.text("m"));
        }
    }

    private static float[] centre(ScriptRig rig, String key) {
        UiRect r = rig.el(key).rect();
        return new float[]{r.x() + r.width() / 2f, r.y() + r.height() / 2f};
    }
}

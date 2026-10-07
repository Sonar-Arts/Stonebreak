package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.input.PointerEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.openmason.engine.ui.runtime.UiDocs.inst;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.button;
import static com.openmason.engine.ui.script.ScriptBehaviourTest.root;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The #292 cost record besides the frame budget ({@link MinigameBenchmarkTest}): one UI event
 * through a Lua handler, one {@code ui} host op (Lua → Java → Lua with typed values), and the Lua
 * heap one component instance's environment costs. The numbers are printed; the bounds are loose
 * guards against order-of-magnitude regressions.
 */
@Tag("regression")
class ScriptCostBenchmarkTest {

    @Test
    void eventDispatchHostOpsAndPerInstanceMemory() {
        OmuiArchive doc = ScriptRig.withCode(screen("t:ui/cost", root(button("b"), label("out", "0").name("out"))), """
            local n = 0
            local out
            function on_open(ui)
              out = ui.q("#out")
              ui.q("#b"):on("click", function() n = n + 1 end)
            end
            function update(dt)
              if dt > 0 then
                for i = 1, 2000 do local _ = out:prop("text") end
              end
            end
            """);
        try (ScriptRig rig = new ScriptRig(doc)) {
            UiRect r = rig.el("b").rect();
            float x = r.x() + r.width() / 2;
            float y = r.y() + r.height() / 2;
            for (int i = 0; i < 3000; i++) {
                rig.view.input().pointerDown(x, y, PointerEvent.PRIMARY, 0);
                rig.view.input().pointerUp(x, y, PointerEvent.PRIMARY, 0);
            }
            int clicks = 5000;
            long t0 = System.nanoTime();
            for (int i = 0; i < clicks; i++) {
                rig.view.input().pointerDown(x, y, PointerEvent.PRIMARY, 0);
                rig.view.input().pointerUp(x, y, PointerEvent.PRIMARY, 0);
            }
            double perClick = (System.nanoTime() - t0) / 1e3 / clicks;

            // Host ops: 2000 ui calls inside one update(dt > 0); the empty update(0) is subtracted.
            long base = median(rig, 0);
            long busy = median(rig, 0.016);
            double perOp = (busy - base) / 1e3 / 2000;

            System.out.printf(Locale.ROOT, "[ui-script cost] click through a Lua handler (router + event table + task): "
                + "%.2f us; ui host op round trip: %.2f us%n", perClick, perOp);
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
            assertTrue(perClick < 200, "click " + perClick + " us");
            assertTrue(perOp < 50, "host op " + perOp + " us");
        }
    }

    private static long median(ScriptRig rig, double dt) {
        List<Long> t = new ArrayList<>();
        for (int i = 0; i < 41; i++) {
            long t0 = System.nanoTime();
            rig.rt.update(dt);
            t.add(System.nanoTime() - t0);
        }
        t.sort(null);
        return t.get(t.size() / 2);
    }

    @Test
    void typedValueFfmCrossings() {
        ScriptRig.assumeLua();
        try (com.openmason.engine.cenda.LuaState lua = com.openmason.engine.cenda.CendaLua.newState(0)) {
            lua.registerValues(0, "host", (in, out) -> {
                out.number(in.number() + 1);
                return 1;
            });
            lua.run("function noop(x) return x end\n"
                + "function loop(n) local s = 0 for i = 1, n do s = s + host(i) end return s end", "bench", 0);
            int noop = lua.refFunction(0, "noop");
            int loop = lua.refFunction(0, "loop");
            int n = 200_000;
            for (int warm = 0; warm < 3; warm++) {
                for (int i = 0; i < n; i++) {
                    lua.args().number(i);
                    lua.callValues(noop, 1);
                }
                lua.args().number(n);
                lua.callValues(loop, 1);
            }
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                lua.args().number(i);
                lua.callValues(noop, 1);
            }
            double javaToLua = (System.nanoTime() - t0) / (double) n;
            lua.args().number(n);
            long t1 = System.nanoTime();
            lua.callValues(loop, 1);
            double luaToJava = (System.nanoTime() - t1) / (double) n;
            System.out.printf(Locale.ROOT, "[ui-script cost] typed-value FFM crossing (ABI 2): Java->Lua %.0f ns, "
                + "Lua->Java %.0f ns (numbers; #283 measured 22 / 26 ns on the number-only ABI)%n",
                javaToLua, luaToJava);
            assertTrue(javaToLua < 5000 && luaToJava < 5000, javaToLua + " / " + luaToJava);
        }
    }

    @Test
    void perInstanceEnvironmentMemory() {
        int instances = 20;
        List<UiDocs.N> kids = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            kids.add(inst("c" + i, "t:ui/counter", Map.of()));
        }
        UiDocumentSource source = UiDocumentSource.of(Map.of("t:ui/counter", ScriptBehaviourTest.counterComponent()),
            Map.of());
        long one;
        try (ScriptRig rig = new ScriptRig(ScriptRig.withCode(screen("t:ui/m1", root(inst("c0", "t:ui/counter",
            Map.of()))), "-- screen"), source, null, UiScriptOptions.DEFAULTS, UiScriptServices.NONE)) {
            one = rig.rt.memoryUsed();
        }
        try (ScriptRig rig = new ScriptRig(ScriptRig.withCode(screen("t:ui/m20", root(kids.toArray(UiDocs.N[]::new))),
            "-- screen"), source, null, UiScriptOptions.DEFAULTS, UiScriptServices.NONE)) {
            assertEquals(instances + 1, rig.rt.modules().size());
            double perInstance = (rig.rt.memoryUsed() - one) / (double) (instances - 1);
            System.out.printf(Locale.ROOT, "[ui-script cost] state with 1 counter: %d KiB; each further instance "
                + "environment: %.1f KiB%n", one / 1024, perInstance / 1024);
            assertTrue(perInstance < 64 * 1024, "per instance " + perInstance + " B");
        }
    }
}

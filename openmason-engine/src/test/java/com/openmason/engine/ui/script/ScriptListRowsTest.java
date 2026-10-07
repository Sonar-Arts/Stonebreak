package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.input.PointerEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.component;
import static com.openmason.engine.ui.runtime.UiDocs.inst;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scripted components inside ListView rows (#325): their code-behind starts with the row, stops
 * when the row goes, and starts over when a virtualized row is recycled for another item, so the
 * next item never sees the last one's module state, handlers or tasks.
 */
class ScriptListRowsTest {

    private static final HostContract INV = HostContract.of("stonebreak:inventory", 1);
    private static final DataType.ListOf SLOTS = DataType.list(
        DataType.object("id", DataType.string(), "name", DataType.string()), "id");
    private static final String CARD = "t:ui/card";

    /**
     * A card that remembers being picked in a module global, and whose {@code on_open} waits on a
     * long sleep: a recycled card must neither remember the pick nor ever wake.
     */
    private static final String CARD_CODE = """
        function on_open(ui)
          ui.log("open " .. ui.root.key .. " picked=" .. tostring(picked))
          ui.root:on("click", function()
            picked = true
            ui.log("picked " .. ui.root.key)
          end)
          ui.await(ui.sleep(5))
          ui.log("woke " .. ui.root.key)
        end

        function on_close(ui)
          ui.log("close " .. ui.root.key .. " picked=" .. tostring(picked))
        end
        """;

    private static OmuiArchive card() {
        return ScriptRig.withCode(component(CARD, box("frame").style("width", 100).style("height", 20)
                .kids(label("title", "")), new UiDocument.ComponentDef(List.of(), List.of(), List.of(), Map.of())),
            "card", CARD_CODE);
    }

    /** {@code itemHeight} 0 = every item gets a row; else a 100 px virtualized window. */
    private static OmuiArchive list(int itemHeight, String screenCode) {
        UiDocs.N view = node("list", "ListView").style("height", 100).prop("itemHeight", itemHeight)
            .bind("prop:items", "inventory")
            .kids(box("row").kids(inst("card", CARD, Map.of()), label("name", "?").bind("prop:text", ".name")));
        OmuiArchive doc = UiDocs.declare(screen("t:ui/list", box("root").style("width", 400).style("height", 300)
                .kids(node("trim", "Button").name("trim").style("width", 100).style("height", 30), view)),
            List.of(UiFeatures.DATA, UiFeatures.INPUT, UiFeatures.L10N), "stonebreak:inventory@1");
        return screenCode == null ? doc : ScriptRig.withCode(doc, screenCode);
    }

    private static final class Host {
        final UiHost host = new UiHost();
        final DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), INV);

        Host(int items) {
            fill(items);
            // Trims the list at once, from inside the script's call (a host that answers synchronously).
            host.actions().register(ActionSpec.of("stonebreak:inventory.trim", INV, DataType.object(),
                DataType.ANY), (args, ctx) -> {
                    fill(1);
                    return CompletableFuture.completedFuture(UiValue.NULL);
                });
        }

        void fill(int n) {
            List<UiValue> items = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                items.add(new UiValue.Obj(Map.of("id", UiValue.of("s" + i), "name", UiValue.of("item" + i))));
            }
            inv.setAll(items);
        }
    }

    private static ScriptRig rig(OmuiArchive doc, Host h) {
        return new ScriptRig(doc, UiDocumentSource.of(Map.of(CARD, card()), Map.of()), h.host,
            UiScriptOptions.DEFAULTS, UiScriptServices.NONE);
    }

    private static UiElement row(ScriptRig rig, int i) {
        return rig.el("list").children().stream().filter(c -> !c.children().isEmpty()).toList().get(i);
    }

    private static String cardKey(ScriptRig rig, int i) {
        return row(rig, i).children().getFirst().key();
    }

    private static long count(ScriptRig rig, String prefix) {
        return rig.log().stream().filter(l -> l.startsWith(prefix)).count();
    }

    @Test
    void componentScriptsInRowsStartWithTheirRowsAndStopWhenTheyGo() {
        Host h = new Host(3);
        try (ScriptRig rig = rig(list(0, null), h)) {
            assertEquals(3, rig.rt.modules().size(), rig.rt.modules().toString());
            assertEquals(3, count(rig, "open "), rig.log().toString());
            assertTrue(rig.log().contains("open " + cardKey(rig, 0) + " picked=nil"));
            String last = cardKey(rig, 2);

            h.fill(5); // two more items: two more rows
            rig.frame(0.016);
            assertEquals(5, rig.rt.modules().size());
            assertEquals(5, count(rig, "open "));

            h.fill(2); // three rows go
            rig.frame(0.016);
            assertEquals(2, rig.rt.modules().size(), rig.rt.modules().toString());
            assertEquals(3, count(rig, "close "), rig.log().toString());
            assertTrue(rig.log().contains("close " + last + " picked=nil"));
            assertEquals(2, rig.rt.pendingCount(), "only the two remaining cards' sleeps are pending");
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
        }
    }

    @Test
    void aRecycledRowsScriptStartsOverForItsNewItem() {
        Host h = new Host(50);
        try (ScriptRig rig = rig(list(20, null), h)) {
            rig.frame(0.016);
            int realized = rig.rt.modules().size();
            assertTrue(realized > 1 && realized < 50, "only the window's rows run scripts: " + realized);
            // The second row: after a one-line scroll the first row sits in the overscan line above
            // the view (#326), where no click reaches it.
            String first = cardKey(rig, 1);
            assertEquals("item1", rig.text(row(rig, 1).children().get(1).key()));
            rig.click(first + "/frame");
            assertTrue(rig.log().contains("picked " + first), rig.log().toString());

            rig.el("list").scrollTo(0, 40); // past the overscan line: the second row now shows item2
            rig.frame(0.016);
            assertEquals("item2", rig.text(row(rig, 1).children().get(1).key()));
            assertEquals(cardKey(rig, 1), first, "the row and its card element were recycled, not rebuilt");
            assertTrue(rig.log().contains("close " + first + " picked=true"), "item1's script closed: " + rig.log());
            assertEquals(2, count(rig, "open " + first + " "), rig.log().toString());
            // Scrolling one row recycles every row of the window; the card's new context
            // opened after its old one closed, with fresh module state.
            assertTrue(rig.log().indexOf("close " + first + " picked=true")
                    < rig.log().lastIndexOf("open " + first + " picked=nil"),
                "item2's script starts with fresh module state: " + rig.log());
            assertEquals(realized, rig.rt.modules().size(), "one context per realized row, still");

            rig.click(first + "/frame"); // the old click handler is gone: one pick, from the new context
            assertEquals(2, count(rig, "picked " + first), rig.log().toString());

            rig.frame(6); // every sleep that is still pending wakes; item1's was cancelled with its context
            assertEquals(realized, count(rig, "woke "), rig.log().toString());
            assertTrue(rig.rt.diagnostics().isEmpty(), rig.rt.diagnostics().toString());
        }
    }

    @Test
    void rowsAScriptsCallRemovesCloseWhenThatCallReturns() {
        Host h = new Host(3);
        String screen = """
            function on_open(ui)
              ui.get("trim"):on("click", function()
                ui.await(ui.action("stonebreak:inventory.trim"))
              end)
            end
            """;
        try (ScriptRig rig = rig(list(0, screen), h)) {
            assertEquals(4, rig.rt.modules().size(), "the screen + three cards");
            UiElement trim = rig.el("trim");
            float x = trim.rect().x() + 5;
            float y = trim.rect().y() + 5;
            rig.view.input().pointerDown(x, y, PointerEvent.PRIMARY, 0);
            rig.view.input().pointerUp(x, y, PointerEvent.PRIMARY, 0); // no layout: no other settle point
            assertEquals(2, count(rig, "close "), "closed as the handler's call returned: " + rig.log());
            assertEquals(2, rig.rt.modules().size(), rig.rt.modules().toString());
            assertFalse(rig.rt.diagnostics().stream().anyMatch(d -> d.severity() == UiScriptDiagnostic.Severity.ERROR),
                rig.rt.diagnostics().toString());
        }
    }
}

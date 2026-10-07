package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiStateMachine;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.EditPolicy;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiDocumentSource;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.anim.UiAnimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.inst;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.UiDocs.sheet;
import static com.openmason.engine.ui.runtime.binding.BindingRig.obj;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review findings of #289 in the binder: to-source rollback, failing back-converters, rows a
 * script removed, recycled rows that keep another item's state (local values, focus, animation
 * #325), and grid lists (#300).
 */
class BindingHardeningTest {

    private static final HostContract SETTINGS = HostContract.of("stonebreak:settings", 1);
    private static final HostContract INV = HostContract.of("stonebreak:inventory", 1);
    private static final DataType.ListOf SLOTS = DataType.list(
        DataType.object("id", DataType.string(), "name", DataType.string()), "id");

    private static UiValue.Obj slot(int i) {
        return obj("id", "s" + i, "name", "item" + i);
    }

    private static UiHost settingsHost(DataCell[] out) {
        UiHost host = new UiHost();
        DataType.Obj type = DataType.object("name", DataType.string());
        out[0] = host.data().registerEditable("settings", new DataCell(type, obj("name", "Steve")), SETTINGS,
            new EditPolicy("stonebreak:settings.apply", null));
        host.actions().register(ActionSpec.of("stonebreak:settings.apply", SETTINGS, DataType.object("value", type),
            DataType.ANY), (args, ctx) -> CompletableFuture.completedFuture(UiValue.NULL));
        return host;
    }

    private static OmuiArchive nameEditor(UiNode.BindingMode mode, String converter) {
        return BindingRig.withData(screen("t:ui/settings", box("root").data("settings").kids(
            label("name", "authored").bind("prop:text", ".name", mode, converter))), "stonebreak:settings@1");
    }

    @Test
    void toSourceEditsAreRolledBackOnCancel() {
        DataCell[] cell = new DataCell[1];
        UiHost host = settingsHost(cell);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(nameEditor(UiNode.BindingMode.TO_SOURCE, null),
            UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            UiElement name = ui.find("name");
            name.setProp("text", UiValue.of("Alex"));
            assertTrue(b.scope().edits().isDirty());
            b.revertEdits();
            assertFalse(b.scope().edits().isDirty());
            assertEquals("authored", name.text("text"), "the local edit went with the draft");
            assertFalse(name.hasState(UiElement.INVALID));
        }
    }

    @Test
    void aBackConverterThatThrowsIsReportedAndMarksTheFieldInvalid() {
        DataCell[] cell = new DataCell[1];
        UiHost host = settingsHost(cell);
        UiConverters converters = UiConverters.of(Map.of("upper", new UiConverter(DataType.string(),
            v -> v, v -> {
                throw new IllegalStateException("cannot parse");
            })));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(nameEditor(UiNode.BindingMode.TWO_WAY, "upper"),
            UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, converters)) {
            UiElement name = ui.find("name");
            assertDoesNotThrow(() -> name.setProp("text", UiValue.of("Alex")));
            assertTrue(name.hasState(UiElement.INVALID));
            assertTrue(UiDocs.has(ui, UiRuntimeDiagnostic.Code.CONVERTER_FAILED));
            assertFalse(b.scope().edits().isDirty());
        }
    }

    private static OmuiArchive list(int height, int itemHeight, int columns) {
        UiDocs.N view = node("list", "ListView").style("height", height).prop("itemHeight", itemHeight)
            .bind("prop:items", "inventory").kids(
                box("row").kids(label("name", "?").bind("prop:text", ".name")));
        if (columns > 1) {
            view = view.prop("columns", columns);
        }
        return BindingRig.withData(screen("t:ui/x", box("root").style("width", 200).style("height", 400).kids(view)),
            "stonebreak:inventory@1");
    }

    private static UiDocumentInstance layout(OmuiArchive doc) {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT));
        ui.setMetrics(UiMetrics.of(200, 400, 1));
        return ui;
    }

    private static void frame(UiDocumentInstance ui, UiBinder b) {
        ui.update();
        b.sync();
        ui.update();
    }

    @Test
    void aRowAScriptRemovedIsRebuiltInsteadOfBreakingTheList() {
        UiHost host = new UiHost();
        DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), INV);
        inv.setAll(List.of(slot(0), slot(1), slot(2)));
        UiDocumentInstance ui = layout(list(100, 0, 1));
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            ListBinding l = b.list("list");
            l.rows().get(1).remove(); // a script's el:remove()
            assertDoesNotThrow(() -> inv.move(2, 0));
            assertEquals(3, l.rows().size());
            assertTrue(l.rows().stream().noneMatch(UiElement::isRemoved));
            assertEquals(List.of("item2", "item0", "item1"),
                l.rows().stream().map(r -> BindingRig.label(r).text("text")).toList());
        }
    }

    @Test
    void aVirtualRowAScriptRemovedIsRebuiltOnTheNextWindow() {
        UiHost host = new UiHost();
        DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), INV);
        List<UiValue> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            items.add(slot(i));
        }
        inv.setAll(items);
        UiDocumentInstance ui = layout(list(100, 20, 1));
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            frame(ui, b);
            ListBinding l = b.list("list");
            l.rows().getFirst().remove();
            ui.find("list").scrollTo(0, 200);
            assertDoesNotThrow(() -> frame(ui, b));
            assertEquals(10, l.firstVisibleIndex());
            assertTrue(l.rows().stream().noneMatch(UiElement::isRemoved));
            assertEquals("item10", BindingRig.label(l.rows().getFirst()).text("text"));
        }
    }

    @Test
    void aRecycledRowDropsTheLastItemsLocalEditsAndFocusFollowsTheItem() {
        UiHost host = new UiHost();
        DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), INV);
        List<UiValue> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            items.add(slot(i));
        }
        inv.setAll(items);
        UiDocumentInstance ui = layout(list(100, 20, 1));
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            List<UiElement[]> recycled = new ArrayList<>();
            b.onRecycled((row, replacement) -> recycled.add(new UiElement[] {row, replacement}));
            frame(ui, b);
            ListBinding l = b.list("list");
            UiElement first = l.rows().getFirst();
            UiElement second = l.rows().get(1);
            first.setStyle("background-color", UiValue.of("#ff0000")); // a script highlighted item0
            first.addClass("picked");

            ui.find("list").scrollTo(0, 20); // one row down: item1 is now shown by the first row
            frame(ui, b);
            assertEquals("item1", BindingRig.label(first).text("text"));
            assertNull(first.localStyle("background-color"), "the highlight belonged to item0");
            assertNull(first.localClass("picked"));
            assertEquals(UiValue.of(20.0), first.localStyle("height"), "the list's own row sizing stays");

            UiElement[] firstEvent = recycled.stream().filter(e -> e[0] == first).findFirst().orElseThrow();
            assertNull(firstEvent[1], "item0 scrolled out of the window: focus on it moves on");
            UiElement[] secondEvent = recycled.stream().filter(e -> e[0] == second).findFirst().orElseThrow();
            assertSame(first, secondEvent[1], "item1 is now shown by the first row: focus follows it there");
        }
    }

    // ── #325: recycled rows drop the previous item's animation state ────────

    private static final DataType.ListOf CARDS = DataType.list(
        DataType.object("id", DataType.string(), "name", DataType.string(), "rare", DataType.bool()), "id");
    private static final String CARD = "t:ui/card";

    private static DataCollection cards(UiHost host) {
        DataCollection inv = host.data().register("inventory", new DataCollection(CARDS), INV);
        List<UiValue> items = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            items.add(obj("id", "s" + i, "name", "item" + i, "rare", i % 2 == 1));
        }
        inv.setAll(items);
        return inv;
    }

    private static UiStyleSheet.StyleRule animated(String selector, Object... kv) {
        Map<String, UiValue> style = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            style.put((String) kv[i], UiDocs.value(kv[i + 1]));
        }
        List<UiStyleSheet.StyleTransition> transitions = List.of(
            new UiStyleSheet.StyleTransition("scale", 1, UiEasing.LINEAR, 0, Map.of()),
            new UiStyleSheet.StyleTransition("opacity", 1, UiEasing.LINEAR, 0, Map.of()));
        return new UiStyleSheet.StyleRule(selector, style, transitions, Map.of());
    }

    /** Rows grow on hover and fade when their item is rare, both through 1 s transitions. */
    private static OmuiArchive animatedList() {
        UiDocs.N view = node("list", "ListView").style("height", 100).prop("itemHeight", 20)
            .bind("prop:items", "inventory").kids(
                box("row").cls("row").bind("class:rare", ".rare").kids(
                    inst("card", CARD, Map.of()), label("name", "?").bind("prop:text", ".name")));
        return BindingRig.withData(screen("t:ui/x", box("root").style("width", 200).style("height", 400).kids(view),
                sheet("s", animated(".row", "scale", 1, "opacity", 1), animated(".row:hover", "scale", 2),
                    animated(".row.rare", "opacity", 0.5))),
            "stonebreak:inventory@1");
    }

    /** A card whose manual machine dims it while "picked". */
    private static OmuiArchive card() {
        UiStateMachine look = new UiStateMachine("look", UiStateMachine.Driver.MANUAL, null, "idle", List.of(
            new UiStateMachine.MachineState("idle", null, Map.of()),
            new UiStateMachine.MachineState("picked", "dim", Map.of())), List.of(), Map.of());
        UiAnimationClip dim = new UiAnimationClip("dim", 0.1, UiAnimationClip.LoopMode.ONCE, List.of(
            new UiAnimationClip.AnimTrack("frame", "style:opacity", List.of(
                new UiAnimationClip.AnimKey(0, UiValue.of(0.3), UiEasing.LINEAR, Map.of())), Map.of())),
            List.of(), Map.of());
        return UiDocs.component(CARD, box("frame"), UiDocs.contract(List.of(), List.of()))
            .withAnimation(dim).withStateMachine(look);
    }

    private static UiDocumentInstance animatedLayout() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(animatedList(), UiRuntimeContext.basic()
            .withMeasurer(UiDocs.FIXED_TEXT).withSource(UiDocumentSource.of(Map.of(CARD, card()), Map.of())));
        ui.setMetrics(UiMetrics.of(200, 400, 1));
        return ui;
    }

    private static double num(UiElement el, String property) {
        return el.computedStyle().get(property) instanceof UiValue.Num n ? n.value() : Double.NaN;
    }

    @Test
    void aRecycledRowDropsTheLastItemsTransitionsTweensAndHeldValues() {
        UiHost host = new UiHost();
        cards(host);
        UiDocumentInstance ui = animatedLayout();
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            frame(ui, b);
            ListBinding l = b.list("list");
            UiElement first = l.rows().getFirst();
            UiElement name = BindingRig.label(first);
            assertEquals(1, num(first, "opacity"), 1e-9, "item0 is not rare");

            first.setState(UiElement.HOVER, true); // the pointer rests on the first row
            ui.resolveStyles();
            ui.advanceClock(0.5);
            ui.resolveStyles();
            assertEquals(1.5, num(first, "scale"), 1e-6, "item0's hover transition is under way");
            List<String> tween = new ArrayList<>();
            UiAnimator.Listener log = (token, stopped) -> tween.add(stopped ? "stopped" : "done");
            ui.animator().tween(name.key(), Map.of("opacity", UiValue.of(0.0)), 10, UiEasing.LINEAR, 0, log);
            ui.animator().tween(first.key(), Map.of("translate-x", UiValue.of(10.0)), 0, UiEasing.LINEAR, 0, null);
            ui.resolveStyles();
            assertEquals(UiValue.of(10.0), first.animatedStyle("translate-x"), "a finished tween holds its value");

            ui.find("list").scrollTo(0, 20); // the first row now shows item1 (rare)
            frame(ui, b);
            assertEquals("item1", name.text("text"));
            assertEquals(List.of("stopped"), tween, "the tween on item0's label was interrupted");
            assertNull(name.animatedStyle("opacity"));
            assertNull(first.animatedStyle("translate-x"), "item0's held value is gone");
            assertEquals(2, num(first, "scale"), 1e-9, "still hovered: the cascade, not item0's mid-transition");
            assertEquals(0.5, num(first, "opacity"), 1e-9, "item1's look settles at once: no fade from item0's");
            assertEquals(0, ui.animator().active());
            assertEquals(0, ui.animator().activeTransitions());
            assertEquals(0, ui.animator().channelCount());

            first.setState(UiElement.HOVER, false); // later changes still transition
            ui.resolveStyles();
            ui.advanceClock(0.5);
            ui.resolveStyles();
            assertEquals(1.5, num(first, "scale"), 1e-6);
        }
    }

    @Test
    void aRecycledRowsComponentStateMachinesStartOver() {
        UiHost host = new UiHost();
        cards(host);
        UiDocumentInstance ui = animatedLayout();
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            frame(ui, b);
            UiElement cardEl = b.list("list").rows().getFirst().children().getFirst();
            UiElement frameEl = cardEl.children().getFirst();
            assertEquals("idle", ui.stateMachines().state(cardEl.key(), "look"));
            ui.stateMachines().set(cardEl.key(), "look", "picked", null); // a script picked item0
            ui.advanceClock(0.2);
            ui.resolveStyles();
            assertEquals(0.3, num(frameEl, "opacity"), 1e-6);

            ui.find("list").scrollTo(0, 20);
            frame(ui, b);
            assertEquals("idle", ui.stateMachines().state(cardEl.key(), "look"), "item1 was never picked");
            assertNull(frameEl.animatedStyle("opacity"));
            assertTrue(Double.isNaN(num(frameEl, "opacity")) || num(frameEl, "opacity") == 1);
        }
    }

    @Test
    void aGridVirtualizesByLinesOfColumns() {
        UiHost host = new UiHost();
        DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), INV);
        List<UiValue> items = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            items.add(slot(i));
        }
        inv.setAll(items);
        UiDocumentInstance ui = layout(list(100, 20, 9));
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            frame(ui, b);
            ListBinding l = b.list("list");
            assertEquals(9, l.columns());
            assertEquals(6 * 9, l.rows().size(), "(100 px / 20 px + 1) lines of 9");
            assertEquals(10 * 20 - 100, ui.find("list").maxScrollY(), 0.5, "10 lines of 20 px");
            UiElement a = l.rows().get(0);
            UiElement b1 = l.rows().get(1);
            UiElement nextLine = l.rows().get(9);
            assertEquals(a.rect().y(), b1.rect().y(), 0.5, "items of one line sit side by side");
            assertTrue(b1.rect().x() > a.rect().x());
            assertEquals(a.rect().y() + 20, nextLine.rect().y(), 0.5, "the tenth item starts the next line");
            assertEquals(200 / 9f, a.rect().width(), 0.5);

            ui.find("list").scrollTo(0, 60);
            frame(ui, b);
            assertEquals(27, l.firstVisibleIndex(), "three lines down");
            assertEquals("item27", BindingRig.label(l.rows().getFirst()).text("text"));
            assertEquals(6 * 9, l.rows().size());

            ui.find("list").scrollTo(0, 100); // the end
            frame(ui, b);
            assertEquals(36, l.firstVisibleIndex(), "the window is clamped so its 6 lines end at the last line");
            assertEquals("item89", BindingRig.label(l.rows().getLast()).text("text"));
        }
    }

    @Test
    void aSmallGridIsNotVirtualizedButStillWraps() {
        UiHost host = new UiHost();
        DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), INV);
        inv.setAll(List.of(slot(0), slot(1), slot(2), slot(3)));
        UiDocumentInstance ui = layout(list(100, 0, 2));
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            frame(ui, b);
            List<UiElement> rows = b.list("list").rows();
            assertEquals(rows.get(0).rect().y(), rows.get(1).rect().y(), 0.5);
            assertTrue(rows.get(2).rect().y() > rows.get(0).rect().y());
            assertEquals(100, rows.get(0).rect().width(), 0.5);
        }
    }
}

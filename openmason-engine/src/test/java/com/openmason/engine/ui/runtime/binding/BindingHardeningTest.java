package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiNode;
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
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.binding.BindingRig.obj;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review findings of #289 in the binder: to-source rollback, failing back-converters, rows a
 * script removed, recycled rows that keep another item's state, and grid lists (#300).
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

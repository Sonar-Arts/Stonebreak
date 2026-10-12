package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.data.DataType;
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

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.inst;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.binding.BindingRig.label;
import static com.openmason.engine.ui.runtime.binding.BindingRig.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ListView: slot templates over inherited item sources, identity, selection and recycling (#289). */
class ListBindingTest {

    private static final HostContract INV = HostContract.of("stonebreak:inventory", 1);
    private static final DataType.ListOf SLOTS = DataType.list(
        DataType.object("id", DataType.string(), "name", DataType.string(), "count", DataType.integer()), "id");

    private static UiValue.Obj slot(String id, String name, int count) {
        return obj("id", id, "name", name, "count", count);
    }

    private static List<String> names(ListBinding list) {
        List<String> out = new ArrayList<>();
        for (UiElement row : list.rows()) {
            out.add(label(row).text("text"));
        }
        return out;
    }

    /** Rows are stone buttons (a component) whose label param binds the item's name: a slot template. */
    private static OmuiArchive inventory() {
        return BindingRig.withData(screen("t:ui/inv", box("root").kids(
            node("list", "ListView").bind("prop:items", "inventory").kids(
                inst("slot", UiSamples.BUTTON_ID, Map.of()).bind("prop:label", ".name")))), "stonebreak:inventory@1");
    }

    @Test
    void slotTemplatesBindTheirItemAndFollowIncrementalChanges() {
        UiHost host = new UiHost();
        DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), INV);
        inv.setAll(List.of(slot("a", "Stone", 1), slot("b", "Dirt", 2), slot("c", "Log", 3)));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(inventory(), BindingRig.pauseContext());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            ListBinding list = b.list("list");
            assertEquals(List.of("Stone", "Dirt", "Log"), names(list));
            UiElement dirtRow = list.rows().get(1);
            assertEquals("list/r1", dirtRow.key());

            list.select(1);
            assertTrue(dirtRow.hasState("checked"));
            inv.move(1, 2);
            inv.insert(0, slot("d", "Sand", 4));
            inv.update(slot("b", "Coarse Dirt", 2));
            assertEquals(List.of("Sand", "Stone", "Log", "Coarse Dirt"), names(list));
            assertSame(dirtRow, list.rows().get(3), "the row followed its item through move, insert and update");
            assertTrue(dirtRow.hasState("checked"), "selection is kept by identity");
            assertEquals("Coarse Dirt", ((UiValue.Str) ((UiValue.Obj) list.selectedItem()).get("name")).value());

            inv.remove("b");
            assertNull(list.selectedItem(), "removing the selected item clears the selection");
            assertTrue(dirtRow.isRemoved());
            assertEquals(3, list.rows().size());

            inv.setAll(List.of(slot("c", "Log", 9), slot("a", "Stone", 1)));
            assertEquals(List.of("Log", "Stone"), names(list));
            assertTrue(ui.diagnostics().isEmpty(), ui.diagnostics().toString());
        }
    }

    @Test
    void plainListValuesAreDiffedByItemKey() {
        OmuiArchive doc = BindingRig.withData(screen("t:ui/x", box("root").kids(
            node("list", "ListView").prop("itemKey", "id").bind("prop:items", "lobby.players").kids(
                label("row", "?").bind("prop:text", ".name")))), "t:lobby@1");
        UiHost host = new UiHost();
        DataCell lobby = host.data().register("lobby", new DataCell(DataType.ANY,
            obj("players", new UiValue.Arr(List.of(obj("id", 1, "name", "A"), obj("id", 2, "name", "B"))))),
            HostContract.of("t:lobby", 1));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            ListBinding list = b.list("list");
            UiElement rowB = list.rows().get(1);
            lobby.set(obj("players", new UiValue.Arr(List.of(obj("id", 2, "name", "B"), obj("id", 3, "name", "C")))));
            assertEquals(List.of("B", "C"), list.rows().stream().map(r -> r.text("text")).toList());
            assertSame(rowB, list.rows().getFirst());
        }
    }

    @Test
    void clickingARowSelectsIt() {
        UiHost host = new UiHost();
        DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), INV);
        inv.setAll(List.of(slot("a", "Stone", 1), slot("b", "Dirt", 2)));
        OmuiArchive doc = BindingRig.withData(screen("t:ui/x", box("root").style("width", 200).style("height", 200).kids(
            node("list", "ListView").style("height", 100).bind("prop:items", "inventory").kids(
                label("row", "?").style("height", 20).bind("prop:text", ".name")))), "stonebreak:inventory@1");
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT));
        ui.setMetrics(UiMetrics.of(200, 200, 1));
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            var view = new com.openmason.engine.ui.runtime.input.UiInputRouter(ui);
            ui.update();
            view.sync();
            UiElement second = b.list("list").rows().get(1);
            float x = second.rect().x() + 5;
            float y = second.rect().y() + 5;
            view.pointerDown(x, y, com.openmason.engine.ui.runtime.input.PointerEvent.PRIMARY, 0);
            view.pointerUp(x, y, com.openmason.engine.ui.runtime.input.PointerEvent.PRIMARY, 0);
            assertTrue(second.hasState("checked"));
        }
    }

    @Test
    void virtualizedRowsAreRecycledAndRebound() {
        UiHost host = new UiHost();
        DataCollection inv = host.data().register("inventory", new DataCollection(SLOTS), INV);
        List<UiValue> items = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            items.add(slot("s" + i, "item" + i, i));
        }
        inv.setAll(items);
        OmuiArchive doc = BindingRig.withData(screen("t:ui/x", box("root").style("width", 200).style("height", 300).kids(
            node("list", "ListView").style("height", 100).prop("itemHeight", 20).bind("prop:items", "inventory").kids(
                box("row").kids(label("name", "?").bind("prop:text", ".name"), label("count", "?").bind("prop:text", ".name"))))), "stonebreak:inventory@1");
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic().withMeasurer(UiDocs.FIXED_TEXT));
        ui.setMetrics(UiMetrics.of(200, 300, 1));
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            ListBinding list = b.list("list");
            ui.update();
            b.sync();
            ui.update();
            assertEquals(7, list.rows().size(), "100 px view / 20 px rows + a line of overscan each side");
            assertEquals(1900, ui.find("list").maxScrollY(), 0.5, "spacers keep the full extent");
            List<UiElement> before = list.rows();
            int listeners = b.localListenerCount();
            int elements = ui.elements().size();

            ui.find("list").scrollTo(0, 420); // line 21 at the top: the window starts a line above
            ui.update();
            assertEquals(20, list.firstVisibleIndex());
            assertEquals(before, list.rows(), "the same row elements, recycled");
            assertEquals("item20", label(list.rows().getFirst()).text("text"));
            assertEquals("item26", label(list.rows().getLast()).text("text"));
            assertEquals(listeners, b.localListenerCount(), "recycling rebinds without leaking listeners");
            assertEquals(elements, ui.elements().size());

            list.select(21);
            ui.find("list").scrollTo(0, 0);
            ui.update();
            assertTrue(list.rows().stream().noneMatch(r -> r.hasState("checked")), "item 21 is off screen");
            ui.find("list").scrollTo(0, 420); // line 21 at the top: the window starts a line above
            assertTrue(list.rows().get(1).hasState("checked"), "selection follows the item, not the recycled row");

            inv.update(slot("s20", "renamed", 20));
            assertEquals("renamed", label(list.rows().getFirst()).text("text"));
        }
    }

    @Test
    void aListViewWithoutTemplateIsReported() {
        OmuiArchive doc = BindingRig.withData(screen("t:ui/x", box("root").kids(
            node("list", "ListView").bind("prop:items", "inventory"))));
        UiHost host = new UiHost();
        host.data().register("inventory", new DataCollection(SLOTS), INV);
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        try (UiBinder b = UiBinder.open(ui, host, UiConverters.NONE)) {
            assertTrue(UiDocs.has(ui, UiRuntimeDiagnostic.Code.LIST_TEMPLATE));
        }
    }
}

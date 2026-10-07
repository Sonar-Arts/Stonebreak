package com.stonebreak.ui.furnace.core;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.ui.runtime.input.PointerEvent;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.inventoryScreen.handlers.PointerFrame;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.support.UiTestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #298 acceptance 2: every slot click and drag through the shipped furnace document yields the same
 * validated outcome as the legacy mouse path. One script of presses (left, shift-left, right,
 * middle, right-drag sweeps, bare panel, outside the panel) runs against two identical furnaces:
 * once as the legacy screen's polled pointer frames at the legacy slot centres, once as pointer
 * events on the document (its Lua routing them to {@code stonebreak:inventory.slot-*}). After every
 * step the inventory, the three furnace slots and the cursor stack must be the same, and nothing is
 * created or lost along the way.
 */
@Tag("regression")
class FurnaceDocumentInteractionTest {

    private static final int W = 1920;
    private static final int H = 1080;
    private static final int LEFT = PointerEvent.PRIMARY;
    private static final int RIGHT = PointerEvent.SECONDARY;
    private static final int MIDDLE = PointerEvent.MIDDLE;
    private static final int SHIFT = 1;

    /** One step: a press at {@code at} ({@code shift}-held), optionally swept over more slots before release. */
    private record Step(String at, int button, boolean shift, List<String> sweep) {
        static Step click(String at) {
            return new Step(at, LEFT, false, List.of());
        }

        static Step shiftClick(String at) {
            return new Step(at, LEFT, true, List.of());
        }

        static Step press(String at, int button) {
            return new Step(at, button, false, List.of());
        }

        static Step rightSweep(String at, String... over) {
            return new Step(at, RIGHT, false, List.of(over));
        }
    }

    private static final List<Step> SCRIPT = List.of(
        Step.click("main0"),                                 // pick up 10 iron ore
        Step.click("ingredient"),                            // place them
        Step.click("main1"),                                 // pick up 8 coal
        Step.click("fuel"),                                  // fuel accepts it (burn time > 0)
        Step.press("ingredient", RIGHT),                     // take one ore
        Step.rightSweep("main5", "main6", "main7"),          // deposit it (one only)
        Step.click("ingredient"),                            // take the other 9
        Step.rightSweep("main9", "main10", "main11", "main10"), // one each, a revisit deposits nothing
        Step.click("panel"),                                 // bare panel: the rest goes back to its slot
        Step.shiftClick("ingredient"),                       // shift: furnace slot into the inventory
        Step.click("hot0"),                                  // pick up dirt
        Step.click("output"),                                // the output refuses drops: back to hot0
        Step.click("fuel"),                                  // ... nothing carried: pick up the coal
        Step.click("ingredient"),                            // coal into the empty ingredient slot
        Step.press("main0", MIDDLE),                         // middle does nothing in the furnace
        Step.click("main9"),                                 // pick up one ore
        Step.click("outside"),                               // beyond the panel: back to its empty origin
        Step.shiftClick("ingredient"),                       // coal to the inventory
        Step.shiftClick("main3")                             // shift on an inventory slot does nothing here
    );

    @Test
    void theDocumentAndTheLegacyScreenReachTheSameStateAfterEveryStep() throws Exception {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh)");
        }
        Inventory legacyInv = inventory();
        FurnaceState legacyFurnace = new FurnaceState(new BlockPos(0, 64, 0));
        FurnaceController legacy = new FurnaceController(null, legacyInv, null, DocumentFurnaceCapture.smelting(), null);
        FurnaceInputManager legacyInput = new FurnaceInputManager(null, legacyInv, legacy);
        legacy.setInputManager(legacyInput);
        legacy.setSlotSink((p, s) -> { });
        legacy.bind(legacyFurnace);
        List<String> legacyDrops = new ArrayList<>();
        legacyInput.setWorldDrop(d -> {
            legacyDrops.add(stack(d.draggedItemStack));
            d.clear();
        });

        try (DocumentFurnaceCapture.Stage doc = new DocumentFurnaceCapture.Stage(GameUiDocuments.readScreen("furnace"),
                W, H, 1f, new FurnaceState(new BlockPos(0, 64, 0)), inventory())) {
            List<String> docDrops = new ArrayList<>();
            doc.input.setWorldDrop(d -> {
                docDrops.add(stack(d.draggedItemStack));
                d.clear();
            });
            int total = total(legacyInv, legacyFurnace, legacyInput);
            assertEquals(snapshot(legacyInv, legacyFurnace, legacyInput), snapshot(doc.inventory, doc.furnace, doc.input));

            Map<String, float[]> legacyRects;
            try (LegacyUiRaster ignored = new LegacyUiRaster(W, H, 1f)) {
                legacyRects = LegacyFurnaceCapture.rects(W, H);
            }
            for (int i = 0; i < SCRIPT.size(); i++) {
                Step step = SCRIPT.get(i);
                runLegacy(legacyInput, legacyRects, step);
                runDocument(doc, step);
                String what = "after step " + i + " " + step;
                assertEquals(snapshot(legacyInv, legacyFurnace, legacyInput),
                    snapshot(doc.inventory, doc.furnace, doc.input), what);
                assertEquals(legacyDrops, docDrops, what + ": world drops");
                assertEquals(snapshot(doc.inventory, doc.furnace, doc.input), shown(doc), what + ": what the document shows");
                assertEquals(total, total(legacyInv, legacyFurnace, legacyInput) + dropped(legacyDrops), what
                    + ": items conserved");
            }
        }
    }

    // ── legacy: the polled pointer frames the screen sees ──────────────────

    private static void runLegacy(FurnaceInputManager input, Map<String, float[]> rects, Step s) {
        float[] at = legacyPoint(rects, s.at());
        boolean right = s.button() == RIGHT;
        input.handlePointer(new PointerFrame(at[0], at[1], s.button() == LEFT, right, right, s.button() == MIDDLE,
            s.shift()), W, H);
        for (String over : s.sweep()) {
            float[] o = legacyPoint(rects, over);
            input.handlePointer(new PointerFrame(o[0], o[1], false, false, true, false, false), W, H);
        }
        float[] end = s.sweep().isEmpty() ? at : legacyPoint(rects, s.sweep().getLast());
        input.handlePointer(PointerFrame.idle(end[0], end[1]), W, H);
    }

    private static float[] legacyPoint(Map<String, float[]> rects, String name) {
        return switch (name) {
            case "outside" -> new float[]{5, 5};
            case "panel" -> {
                float[] p = rects.get("panel");
                yield new float[]{p[0] + 1, p[1] + 1};
            }
            default -> {
                float[] r = rects.get(name);
                yield new float[]{r[0] + r[2] / 2f, r[1] + r[3] / 2f};
            }
        };
    }

    // ── document: pointer events through its router and Lua ────────────────

    private static void runDocument(DocumentFurnaceCapture.Stage doc, Step s) {
        float[] at = docPoint(doc, s.at());
        int mods = s.shift() ? SHIFT : 0;
        var router = doc.view.input();
        router.pointerMove(at[0], at[1]);
        router.pointerDown(at[0], at[1], s.button(), mods);
        float[] end = at;
        for (String over : s.sweep()) {
            end = docPoint(doc, over);
            router.pointerMove(end[0], end[1]);
        }
        router.pointerUp(end[0], end[1], s.button(), mods);
        doc.settle();
    }

    private static float[] docPoint(DocumentFurnaceCapture.Stage doc, String name) {
        return switch (name) {
            case "outside" -> new float[]{5, 5};
            case "panel" -> {
                var r = doc.q("#panel").rect();
                yield new float[]{r.x() + 1, r.y() + 1};
            }
            default -> doc.centre(name);
        };
    }

    // ── state ──────────────────────────────────────────────────────────────

    private static Inventory inventory() {
        Inventory inv = UiTestFixtures.emptyInventory();
        inv.setMainInventorySlot(0, new ItemStack(BlockType.IRON_ORE, 10));
        inv.setMainInventorySlot(1, new ItemStack(BlockType.COAL_ORE, 8));
        inv.setHotbarSlot(0, new ItemStack(BlockType.DIRT, 3));
        return inv;
    }

    private static String snapshot(Inventory inv, FurnaceState f, FurnaceInputManager input) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            b.append("m").append(i).append('=').append(stack(inv.getMainInventorySlot(i))).append(' ');
        }
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            b.append("h").append(i).append('=').append(stack(inv.getHotbarSlot(i))).append(' ');
        }
        b.append("| in=").append(stack(f.getIngredient())).append(" fuel=").append(stack(f.getFuel()))
            .append(" out=").append(stack(f.getOutput()));
        InventoryDragDropHandler.DragState d = input.getDragState();
        b.append(" | carried=").append(d.isDragging() ? stack(d.draggedItemStack) : "-");
        return b.toString();
    }

    /** The same snapshot read off the document's slot elements and its cursor stack. */
    private static String shown(DocumentFurnaceCapture.Stage doc) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            b.append("m").append(i).append('=').append(shown(doc.slot("main" + i))).append(' ');
        }
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            b.append("h").append(i).append('=').append(shown(doc.slot("hot" + i))).append(' ');
        }
        b.append("| in=").append(shown(doc.slot("ingredient"))).append(" fuel=").append(shown(doc.slot("fuel")))
            .append(" out=").append(shown(doc.slot("output")));
        var carried = doc.q("#carried");
        b.append(" | carried=").append(carried.computedStyle().collapsed() ? "-" : record(carried.prop("stack")));
        return b.toString();
    }

    /** The record an item slot instance's inner {@code ItemSlot} draws. */
    private static String shown(com.openmason.engine.ui.runtime.UiElement instance) {
        return record(instance.children().getFirst().prop("stack"));
    }

    private static String record(com.openmason.engine.format.omui.UiValue v) {
        if (!(v instanceof com.openmason.engine.format.omui.UiValue.Obj o)
                || !(o.get("count") instanceof com.openmason.engine.format.omui.UiValue.Num c) || c.value() <= 0) {
            return "-";
        }
        return (int) ((com.openmason.engine.format.omui.UiValue.Num) o.get("item")).value() + "x" + (int) c.value();
    }

    private static String stack(ItemStack s) {
        return s == null || s.isEmpty() ? "-" : s.getBlockTypeId() + "x" + s.getCount();
    }

    private static int total(Inventory inv, FurnaceState f, FurnaceInputManager input) {
        int n = 0;
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            n += count(inv.getMainInventorySlot(i));
        }
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            n += count(inv.getHotbarSlot(i));
        }
        n += count(f.getIngredient()) + count(f.getFuel()) + count(f.getOutput());
        InventoryDragDropHandler.DragState d = input.getDragState();
        return n + (d.isDragging() ? count(d.draggedItemStack) : 0);
    }

    private static int count(ItemStack s) {
        return s == null || s.isEmpty() ? 0 : s.getCount();
    }

    private static int dropped(List<String> drops) {
        return drops.stream().mapToInt(s -> Integer.parseInt(s.substring(s.indexOf('x') + 1))).sum();
    }
}

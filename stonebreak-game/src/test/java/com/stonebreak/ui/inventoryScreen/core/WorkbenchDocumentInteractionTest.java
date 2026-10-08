package com.stonebreak.ui.inventoryScreen.core;

import static com.stonebreak.ui.inventoryScreen.core.ContainerParity.MIDDLE;
import static com.stonebreak.ui.inventoryScreen.core.ContainerParity.RIGHT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openmason.engine.cenda.CendaLua;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.inventoryScreen.core.ContainerParity.Step;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.workbench.WorkbenchScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * #300 acceptance 2 for the crafting table: every press through the shipped workbench document yields
 * the same validated outcome as the legacy mouse path. One script (left, shift, right, middle,
 * right-drag sweeps, crafting batches onto the cursor, shift-crafting, Craft All, Sort, the bare panel,
 * outside the panel, a double click) runs against two identical tables: as the legacy screen's polled
 * frames, and as pointer events on the document. After every step the inventory, the 3x3 grid, the
 * output, the cursor stack and the world drops must be the same, the document must show exactly that,
 * and steps that craft nothing must conserve every item.
 */
@Tag("regression")
class WorkbenchDocumentInteractionTest {

    private static final int W = 1920;
    private static final int H = 1080;

    /** {@code true}: the step crafts (planks from wood), so item counts legitimately change. */
    private static final List<Map.Entry<Step, Boolean>> SCRIPT = List.of(
        Map.entry(Step.click("main0"), false),                            // pick up 10 wood
        Map.entry(Step.click("craft4"), false),                           // into the centre: planks show
        Map.entry(Step.click("output"), true),                            // empty hand: one batch (4 planks)
        Map.entry(Step.click("output"), true),                            // holding planks: another batch
        Map.entry(Step.click("main5"), false),                            // place the 8 planks
        Map.entry(Step.click("craftall"), true),                          // Craft All: the rest into the inventory
        Map.entry(Step.click("main1"), false),                            // pick up 8 stone
        Map.entry(Step.rightSweep("craft0", "craft1", "craft2", "craft1"), false), // one each, no revisit
        Map.entry(Step.press("craft0", MIDDLE), false),                   // middle on the grid balances it
        Map.entry(Step.click("recipes"), false),                          // carrying: a button is bare panel
        Map.entry(Step.click("hot0"), false),                             // pick up dirt
        Map.entry(Step.click("main1"), false),                            // swap onto the stone's slot
        Map.entry(Step.click("panel"), false),                            // bare panel: back where it came from
        Map.entry(Step.press("main0", MIDDLE), false),                    // middle off the grid sorts
        Map.entry(Step.shiftClick("craft0"), false),                      // shift: grid cell into the inventory
        Map.entry(Step.shiftClick("main2"), false),                       // shift: inventory into the hotbar
        Map.entry(Step.shiftClick("hot3"), false),                        // shift: hotbar into the inventory
        Map.entry(Step.click("hot1"), false),                             // pick up 5 wood
        Map.entry(Step.click("hot1"), false),                             // double click: gather onto the cursor
        Map.entry(Step.press("main20", RIGHT), false),                    // right: place one
        Map.entry(Step.click("outside"), false),                          // beyond the panel: into the world
        Map.entry(Step.click("sort"), false),                             // Sort
        Map.entry(Step.shiftClick("output"), true)                        // nothing left to craft: no-op
    );

    /** Spot checks that the script really exercises each rule (block ids from the registry). */
    private static final Map<Integer, String> EXPECT = Map.of(
        2, "carried=" + BlockType.WOOD_PLANKS.getId() + "x4",
        3, "carried=" + BlockType.WOOD_PLANKS.getId() + "x8",
        4, "m5=" + BlockType.WOOD_PLANKS.getId() + "x8",
        5, "m5=" + BlockType.WOOD_PLANKS.getId() + "x40",
        7, "c0=" + BlockType.STONE.getId() + "x1 c1=" + BlockType.STONE.getId() + "x1 c2=" + BlockType.STONE.getId() + "x1",
        12, "carried=-");

    private static Map<String, float[]> legacyRects() {
        try (LegacyUiRaster ignored = new LegacyUiRaster(W, H, 1f)) {
            return WorkbenchFixtures.rects(W, H, true);
        }
    }

    private static WorkbenchFixtures.Table table() {
        WorkbenchFixtures.Table t = WorkbenchFixtures.table("empty");
        t.inventory().setMainInventorySlot(0, new ItemStack(BlockType.WOOD, 10));
        t.inventory().setMainInventorySlot(1, new ItemStack(BlockType.STONE, 8));
        t.inventory().setHotbarSlot(0, new ItemStack(BlockType.DIRT, 3));
        t.inventory().setHotbarSlot(1, new ItemStack(BlockType.WOOD, 5));
        t.inventory().setHotbarSlot(3, new ItemStack(BlockType.SAND, 2));
        return t;
    }

    @Test
    void theDocumentAndTheLegacyScreenReachTheSameStateAfterEveryStep() throws Exception {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh)");
        }
        WorkbenchFixtures.Table legacyTable = table();
        WorkbenchInputManager[] in = new WorkbenchInputManager[1];
        WorkbenchFixtures.controller(legacyTable, in);
        WorkbenchInputManager legacy = in[0];
        Map<String, float[]> rects = legacyRects();
        List<String> legacyDrops = new ArrayList<>();
        List<String> docDrops = new ArrayList<>();
        List<String>[] sink = new List[]{legacyDrops};

        try (AutoCloseable drops = InventoryDragDropHandler.redirectWorldDrops(s -> sink[0].add(ContainerParity.stack(s)));
             DocumentWorkbenchCapture.Stage doc = new DocumentWorkbenchCapture.Stage(
                 GameUiDocuments.readScreen(WorkbenchScreen.DOCUMENT_ID), W, H, 1f, table())) {
            assertEquals(state(legacyTable, legacy), state(doc.table, doc.input));
            for (int i = 0; i < SCRIPT.size(); i++) {
                Step step = SCRIPT.get(i).getKey();
                int before = total(legacyTable, legacy) + dropped(legacyDrops);
                sink[0] = legacyDrops;
                ContainerParity.legacy(legacy, rects, step, W, H);
                sink[0] = docDrops;
                ContainerParity.document(doc.view, doc::centreOrPoint, step, doc::settle);
                String what = "after step " + i + " " + step;
                assertEquals(state(legacyTable, legacy), state(doc.table, doc.input), what);
                assertEquals(legacyDrops, docDrops, what + ": world drops");
                assertEquals(state(doc.table, doc.input), ContainerParity.shown(doc::element, 9, doc.q("#carried")),
                    what + ": what the document shows");
                String expected = EXPECT.get(i);
                if (expected != null) {
                    assertTrue(state(legacyTable, legacy).contains(expected), what + ": expected " + expected + " in "
                        + state(legacyTable, legacy));
                }
                if (!SCRIPT.get(i).getValue()) {
                    assertEquals(before, total(legacyTable, legacy) + dropped(legacyDrops), what + ": items conserved");
                }
            }
            assertTrue(doc.requests.isEmpty(), "a carried stack never opens the recipe book: " + doc.requests);
            assertEquals(1, legacyDrops.size(), "the stack released outside went into the world: " + legacyDrops);
        }
    }

    @Test
    void theRecipesButtonOpensTheRecipeBookOnlyWithAnEmptyHand() throws Exception {
        assumeTrue(CendaLua.isAvailable(), "Cenda Lua host unavailable");
        try (DocumentWorkbenchCapture.Stage doc = new DocumentWorkbenchCapture.Stage(
                GameUiDocuments.readScreen(WorkbenchScreen.DOCUMENT_ID), W, H, 1f, table())) {
            String before = state(doc.table, doc.input);
            float[] at = doc.centre("recipes");
            doc.click(at[0], at[1], ContainerParity.LEFT, ContainerParity.SHIFT);
            assertTrue(doc.requests.isEmpty(), "shift-click on a button does nothing, as in the legacy screen");
            doc.click(at[0], at[1], ContainerParity.RIGHT, 0);
            assertTrue(doc.requests.isEmpty(), "only the left button presses a button");
            doc.click(at[0], at[1], ContainerParity.LEFT, 0);
            assertEquals(List.of("recipes"), doc.requests);
            assertEquals(before, state(doc.table, doc.input), "opening the book moves nothing");
        }
    }

    private static String state(WorkbenchFixtures.Table t, WorkbenchInputManager in) {
        return ContainerParity.snapshot(t.inventory(), t.state().getSlots(),
            in.getCraftingManager().getCraftingOutputSlot(), in.getDragState());
    }

    private static int total(WorkbenchFixtures.Table t, WorkbenchInputManager in) {
        return ContainerParity.total(t.inventory(), t.state().getSlots(), in.getDragState());
    }

    private static int dropped(List<String> drops) {
        return drops.stream().mapToInt(s -> Integer.parseInt(s.substring(s.indexOf('x') + 1))).sum();
    }
}

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
import com.stonebreak.ui.inventoryScreen.InventoryScreen;
import com.stonebreak.ui.inventoryScreen.core.ContainerParity.Step;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.runtime.GameUiDocuments;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * #300 acceptance 2 for the inventory screen: every press through the shipped inventory document yields
 * the same validated outcome as the legacy mouse path, including the presses the legacy screen got
 * wrong before #300 (a stack released over a side column or the tab strip now goes back; it used to
 * be dropped into the world). One script runs against two identical players: as the legacy screen's
 * polled frames, and as pointer events on the document. After every step the inventory, the 2x2 grid,
 * the output, the cursor stack and the world drops must be the same, the document must show exactly
 * that, and steps that craft nothing must conserve every item.
 */
@Tag("regression")
class InventoryDocumentInteractionTest {

    private static final int W = 1920;
    private static final int H = 1080;

    /** {@code true}: the step crafts (planks from wood), so item counts legitimately change. */
    private static final List<Map.Entry<Step, Boolean>> SCRIPT = List.of(
        Map.entry(Step.click("main0"), false),                            // pick up 10 wood
        Map.entry(Step.click("craft3"), false),                           // into the grid: planks show
        Map.entry(Step.click("output"), true),                            // one batch onto the cursor
        Map.entry(Step.click("output"), true),                            // and another
        Map.entry(Step.click("equip0"), false),                           // left column: an output batch has no
                                                                          // origin, so it stays on the cursor
        Map.entry(Step.click("main6"), false),                            // place the 8 planks
        Map.entry(Step.click("main6"), false),                            // pick them up again
        Map.entry(Step.click("str"), false),                              // over the right column: back to main6
        Map.entry(Step.click("main6"), false),                            // pick up again
        Map.entry(Step.click("tab2"), false),                             // over a tab: back to main6
        Map.entry(Step.shiftClick("output"), true),                       // shift: crafts all into the inventory
        Map.entry(Step.click("main1"), false),                            // pick up 8 stone
        Map.entry(Step.rightSweep("craft0", "craft1", "craft2", "craft1"), false), // one each, no revisit
        Map.entry(Step.press("craft0", MIDDLE), false),                   // middle on the grid balances it
        Map.entry(Step.press("status2", RIGHT), false),                   // right off any slot: nothing
        Map.entry(Step.click("recipes"), false),                          // carrying: a button is bare panel
        Map.entry(Step.press("equip3", MIDDLE), false),                   // middle off the grid sorts
        Map.entry(Step.shiftClick("craft0"), false),                      // shift: grid cell into the inventory
        Map.entry(Step.click("hot1"), false),                             // pick up 5 wood
        Map.entry(Step.click("hot1"), false),                             // double click: gather
        Map.entry(Step.press("main20", RIGHT), false),                    // right: place one
        Map.entry(Step.click("outside"), false),                          // beyond the panel: into the world
        Map.entry(Step.shiftClick("main2"), false),                       // shift: inventory into the hotbar
        Map.entry(Step.click("sort"), false),                             // Sort
        Map.entry(Step.shiftClick("output"), true)                        // nothing left to craft
    );

    private static Map<String, float[]> legacyRects() {
        try (LegacyUiRaster ignored = new LegacyUiRaster(W, H, 1f)) {
            Map<String, float[]> r = InventoryFixtures.rects(W, H, true);
            // the right column's first row (str), as the document places it: the oracle has no rows
            r.put("str", strRow(r));
            return r;
        }
    }

    /** drawRightColumn's first stat row at 1x: x = rightColX + 12, y = top + 14 + header, 18 high. */
    private static float[] strRow(Map<String, float[]> r) {
        var l3 = InventoryLayoutCalculator.calculateThreeColumnLayout(W, H);
        return new float[]{l3.rightColX + 12f, l3.rightColY + 14f + 26f, l3.rightColW - 24f, 18f};
    }

    private static InventoryFixtures.Player3 player() {
        InventoryFixtures.Player3 p = InventoryFixtures.player("empty");
        p.inventory().setMainInventorySlot(0, new ItemStack(BlockType.WOOD, 10));
        p.inventory().setMainInventorySlot(1, new ItemStack(BlockType.STONE, 8));
        p.inventory().setHotbarSlot(0, new ItemStack(BlockType.DIRT, 3));
        p.inventory().setHotbarSlot(1, new ItemStack(BlockType.WOOD, 5));
        p.inventory().setHotbarSlot(3, new ItemStack(BlockType.SAND, 2));
        return p;
    }

    /** Spot checks that the script really exercises each rule. */
    private static final Map<Integer, String> EXPECT = Map.of(
        3, "carried=" + BlockType.WOOD_PLANKS.getId() + "x8",
        4, "carried=" + BlockType.WOOD_PLANKS.getId() + "x8",
        5, "m6=" + BlockType.WOOD_PLANKS.getId() + "x8",
        7, "m6=" + BlockType.WOOD_PLANKS.getId() + "x8",
        9, "m6=" + BlockType.WOOD_PLANKS.getId() + "x8",
        12, "c0=" + BlockType.STONE.getId() + "x1 c1=" + BlockType.STONE.getId() + "x1 c2=" + BlockType.STONE.getId() + "x1");

    @Test
    void theDocumentAndTheLegacyScreenReachTheSameStateAfterEveryStep() throws Exception {
        if (!CendaLua.isAvailable()) {
            assumeTrue(Boolean.getBoolean("ui.script.allowMissingNative"), "Cenda Lua host unavailable");
            throw new AssertionError("Cenda Lua host unavailable (build openmason-engine/cenda/build-kernels.sh)");
        }
        InventoryFixtures.Player3 legacyPlayer = player();
        InventoryInputManager legacy = InventoryFixtures.input(legacyPlayer);
        Map<String, float[]> rects = legacyRects();
        List<String> legacyDrops = new ArrayList<>();
        List<String> docDrops = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<String>[] sink = new List[]{legacyDrops};

        try (AutoCloseable drops = InventoryDragDropHandler.redirectWorldDrops(s -> sink[0].add(ContainerParity.stack(s)));
             DocumentInventoryCapture.Stage doc = new DocumentInventoryCapture.Stage(
                 GameUiDocuments.readScreen(InventoryScreen.DOCUMENT_ID), W, H, 1f, player())) {
            assertEquals(state(legacyPlayer, legacy), state(doc.player, doc.input));
            for (int i = 0; i < SCRIPT.size(); i++) {
                Step step = SCRIPT.get(i).getKey();
                int before = total(legacyPlayer, legacy) + dropped(legacyDrops);
                sink[0] = legacyDrops;
                ContainerParity.legacy(legacy, rects, step, W, H);
                sink[0] = docDrops;
                ContainerParity.document(doc.view, doc::centreOrPoint, step, doc::settle);
                String what = "after step " + i + " " + step;
                assertEquals(state(legacyPlayer, legacy), state(doc.player, doc.input), what);
                assertEquals(legacyDrops, docDrops, what + ": world drops");
                assertEquals(state(doc.player, doc.input), ContainerParity.shown(doc::element, 4, doc.q("#carried")),
                    what + ": what the document shows");
                String expected = EXPECT.get(i);
                if (expected != null) {
                    assertTrue(state(legacyPlayer, legacy).contains(expected), what + ": expected " + expected + " in "
                        + state(legacyPlayer, legacy));
                }
                if (!SCRIPT.get(i).getValue()) {
                    assertEquals(before, total(legacyPlayer, legacy) + dropped(legacyDrops), what + ": items conserved");
                }
            }
            assertTrue(doc.requests.isEmpty(), "a carried stack never presses a button: " + doc.requests);
            assertEquals(1, legacyDrops.size(), "only the stack released outside went into the world: " + legacyDrops);
        }
    }

    @Test
    void tabsAndButtonsActOnlyOnAPlainLeftPressWithAnEmptyHand() throws Exception {
        assumeTrue(CendaLua.isAvailable(), "Cenda Lua host unavailable");
        try (DocumentInventoryCapture.Stage doc = new DocumentInventoryCapture.Stage(
                GameUiDocuments.readScreen(InventoryScreen.DOCUMENT_ID), W, H, 1f, player())) {
            String before = state(doc.player, doc.input);
            for (String target : List.of("tab0", "tab1", "tab4", "recipes")) {
                float[] at = doc.centre(target);
                doc.click(at[0], at[1], ContainerParity.LEFT, ContainerParity.SHIFT);
                doc.click(at[0], at[1], ContainerParity.RIGHT, 0);
            }
            assertTrue(doc.requests.isEmpty(), "shift or right presses never switch tabs: " + doc.requests);
            for (String target : List.of("tab0", "tab1", "tab2", "tab3", "tab4", "recipes")) {
                float[] at = doc.centre(target);
                doc.click(at[0], at[1], ContainerParity.LEFT, 0);
            }
            // the first tab is the inventory itself: the legacy strip never hit-tested it
            assertEquals(List.of("tab:character", "tab:classes", "tab:skills", "tab:feats", "recipes"), doc.requests);
            assertEquals(before, state(doc.player, doc.input), "switching moves nothing");
        }
    }

    private static String state(InventoryFixtures.Player3 p, InventoryInputManager in) {
        return ContainerParity.snapshot(p.inventory(), p.grid().getCraftingInputSlots(),
            p.grid().getCraftingOutputSlot(), in.getDragState());
    }

    private static int total(InventoryFixtures.Player3 p, InventoryInputManager in) {
        return ContainerParity.total(p.inventory(), p.grid().getCraftingInputSlots(), in.getDragState());
    }

    private static int dropped(List<String> drops) {
        return drops.stream().mapToInt(s -> Integer.parseInt(s.substring(s.indexOf('x') + 1))).sum();
    }
}

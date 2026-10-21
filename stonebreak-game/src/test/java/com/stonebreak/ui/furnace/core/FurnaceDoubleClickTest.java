package com.stonebreak.ui.furnace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.config.Settings;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.handlers.PointerFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #352: the furnace screen's double-click detection used a fixed 40 px radius, so at small
 * UI scales a quick pick-up-then-place on the next inventory slot was swallowed instead of
 * dropping the stack there. Double-clicks are counted on the same slot only.
 */
class FurnaceDoubleClickTest {

    private static final int W = 1920;
    private static final int H = 1080;
    private static final int DIRT = BlockType.DIRT.getId();

    private float previousScale;
    private Inventory inventory;
    private FurnaceInputManager input;

    @BeforeEach
    void setUp() {
        previousScale = Settings.getInstance().getUiScale();
        Settings.getInstance().setUiScale(0.75f);
        inventory = new Inventory();
        input = new FurnaceInputManager(null, inventory, new FurnaceController(null, null, null));
    }

    @AfterEach
    void restoreScale() {
        Settings.getInstance().setUiScale(previousScale);
    }

    private void press(float x, float y) {
        input.handlePointer(new PointerFrame(x, y, true, false, false, false, false), W, H);
        input.handlePointer(PointerFrame.idle(x, y), W, H);
    }

    private float[] centre(String slot) {
        float[] o = input.slotOrigin(slot, W, H);
        float half = input.slotSize() / 2f;
        return new float[]{o[0] + half, o[1] + half};
    }

    @Test
    void quickClickOnTheNextSlotDropsInsteadOfBeingSwallowed() {
        inventory.setMainInventorySlot(0, new ItemStack(DIRT, 10));
        float[] first = centre("main:0");
        float[] second = centre("main:1");

        press(first[0], first[1]);
        assertTrue(input.getDragState().isDragging());

        press(second[0], second[1]);
        assertFalse(input.getDragState().isDragging(), "the stack was dropped into main:1");
        assertEquals(10, inventory.getMainInventorySlot(1).getCount());
        assertTrue(inventory.getMainInventorySlot(0).isEmpty());
    }
}

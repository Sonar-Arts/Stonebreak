package com.stonebreak.ui.inventoryScreen.handlers;

/**
 * A container screen's slot rules, addressed by slot instead of by pixel (#289). The legacy
 * screens keep owning every rule (pick up, place, stack, swap, split, right-drag distribution,
 * double-click gather, shift transfer, crafting); this seam only lets a caller ask where a slot
 * sits in the screen's own layout and then feed a pointer frame there, so UI documents and the
 * legacy mouse path run the same code.
 *
 * <p>Slot addresses: {@code main:<0-26>}, {@code hotbar:<0-8>}, {@code craft:<i>},
 * {@code craft-output}, {@code ingredient}, {@code fuel}, {@code output} (furnace) and
 * {@code outside} (beyond the panel: a held stack is dropped into the world).
 */
public interface ContainerSlotInput {

    /** A point no panel, slot, button or tab covers. */
    float OUTSIDE = -10_000f;

    /**
     * Top-left corner of {@code slot} in this screen's layout at the given screen size, or null
     * when the screen has no such slot. {@code outside} answers {@link #OUTSIDE}.
     */
    float[] slotOrigin(String slot, int screenWidth, int screenHeight);

    /** Side length of a slot in the same pixel space. */
    float slotSize();

    /** Runs the screen's pointer rules for one frame. */
    void handlePointer(PointerFrame frame, int screenWidth, int screenHeight);

    /** The stack on the cursor. */
    InventoryDragDropHandler.DragState getDragState();

    /** Puts a carried stack back (origin slot, then inventory, then the world), as closing the screen does. */
    void handleCloseWithDraggedItems();

    /** Runs the screen's Craft All rule; false when the screen has none. */
    default boolean craftAll() {
        return false;
    }

    /** Runs the screen's Sort rule; false when the screen has none. */
    default boolean sort() {
        return false;
    }

    /** Centre of {@code slot}, or null. */
    default float[] slotCentre(String slot, int screenWidth, int screenHeight) {
        float[] o = slotOrigin(slot, screenWidth, screenHeight);
        if (o == null) {
            return null;
        }
        if (o[0] == OUTSIDE) {
            return o;
        }
        float half = slotSize() / 2f;
        return new float[]{o[0] + half, o[1] + half};
    }
}

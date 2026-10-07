package com.stonebreak.ui.furnace.core;

import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;

/**
 * Geometry for the radial / crucible furnace UI.
 *
 * <p>Crucible sits at the center of the upper furnace section.
 * Ingredient slot is at 12 o'clock, fuel at 6 o'clock, output at 3 o'clock.
 * Shared between the renderer and the input manager so hit-testing and
 * drawing never drift apart.
 */
public final class FurnaceLayout {

    private FurnaceLayout() {}

    public static final class Slots {
        public final int slotSize;
        public final float crucibleCenterX, crucibleCenterY;
        public final float crucibleRadius;
        public final int ingredientX, ingredientY;
        public final int fuelX, fuelY;
        public final int outputX, outputY;

        Slots(int slotSize,
              float crucibleCenterX, float crucibleCenterY, float crucibleRadius,
              int ingredientX, int ingredientY,
              int fuelX, int fuelY,
              int outputX, int outputY) {
            this.slotSize = slotSize;
            this.crucibleCenterX = crucibleCenterX;
            this.crucibleCenterY = crucibleCenterY;
            this.crucibleRadius = crucibleRadius;
            this.ingredientX = ingredientX;
            this.ingredientY = ingredientY;
            this.fuelX = fuelX;
            this.fuelY = fuelY;
            this.outputX = outputX;
            this.outputY = outputY;
        }
    }

    public static Slots compute(InventoryLayoutCalculator.InventoryLayout layout) {
        int ss   = InventoryLayoutCalculator.getSlotSize();
        int pad  = InventoryLayoutCalculator.getSlotPadding();
        int ppad = InventoryLayoutCalculator.getPanelPadding();
        int th   = InventoryLayoutCalculator.getTitleHeight();
        int sp   = InventoryLayoutCalculator.getSectionSpacing();

        // The workbench panel reserves a 3-row tall band between the "Furnace"
        // title and the inventory section (sized for a 3×3 crafting grid:
        // 3*(SLOT_SIZE+SLOT_PADDING) + SLOT_PADDING).
        float titleY    = layout.panelStartY + ppad + th + sp;
        int   sectionTop = (int) titleY;
        int   sectionHeight = bandHeight(ss, pad);

        float centerX = layout.panelStartX + layout.inventoryPanelWidth / 2f;
        float centerY = sectionTop + sectionHeight / 2f;
        return around(centerX, centerY, ss, pad, com.stonebreak.config.Settings.getInstance().getUiScale());
    }

    /** Height of the band the crucible and its three slots sit in: three slot rows and their gaps. */
    public static int bandHeight(int ss, int pad) {
        return 3 * (ss + pad) + pad;
    }

    /**
     * The radial geometry around a crucible centre: shared by the legacy renderer (centre from the
     * panel layout) and the {@code stonebreak:furnace-crucible} draw provider (centre of its element),
     * so both draw the same pixels for the same centre.
     *
     * @param ss      slot side, device px
     * @param pad     slot padding, device px
     * @param uiScale scale of the absolute chute gap
     */
    public static Slots around(float centerX, float centerY, int ss, int pad, float uiScale) {
        // Distance from crucible center to slot center. Vertical extent
        // becomes 2*radius + ss = 3*ss + 2*pad, matching the reserved band.
        float radius = ss + pad;
        // Leave a clear ~12px chute between the crucible rim and each slot edge
        // (scaled so the gap stays proportional at non-default UI scales).
        float chute = 12f * uiScale;
        float crucibleRadius = radius - ss / 2f - chute;
        if (crucibleRadius < chute) crucibleRadius = chute;

        int ingredientX = Math.round(centerX - ss / 2f);
        int ingredientY = Math.round(centerY - radius - ss / 2f);

        int fuelX = Math.round(centerX - ss / 2f);
        int fuelY = Math.round(centerY + radius - ss / 2f);

        int outputX = Math.round(centerX + radius - ss / 2f);
        int outputY = Math.round(centerY - ss / 2f);

        return new Slots(ss, centerX, centerY, crucibleRadius,
                         ingredientX, ingredientY,
                         fuelX, fuelY,
                         outputX, outputY);
    }
}

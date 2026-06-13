package com.openmason.main.systems.menus.textureCreator.palette;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ordered swatch palette for the texture editor. Colors are packed RGBA ints
 * (same layout as {@code TextureCreatorState.currentColor}: A in the high
 * byte, R in the low byte — identical to ImGui's IM_COL32 packing).
 */
public final class PaletteModel {

    private final List<Integer> swatches = new ArrayList<>();
    private int selectedIndex = -1;
    private Runnable changeListener;

    /**
     * Seed with the DawnBringer 32 palette — the de facto default pixel-art
     * palette (also Aseprite's default).
     */
    public static PaletteModel createDefault() {
        PaletteModel model = new PaletteModel();
        int[][] db32 = {
                {0, 0, 0}, {34, 32, 52}, {69, 40, 60}, {102, 57, 49},
                {143, 86, 59}, {223, 113, 38}, {217, 160, 102}, {238, 195, 154},
                {251, 242, 54}, {153, 229, 80}, {106, 190, 48}, {55, 148, 110},
                {75, 105, 47}, {82, 75, 36}, {50, 60, 57}, {63, 63, 116},
                {48, 96, 130}, {91, 110, 225}, {99, 155, 255}, {95, 205, 228},
                {203, 219, 252}, {255, 255, 255}, {155, 173, 183}, {132, 126, 135},
                {105, 106, 106}, {89, 86, 82}, {118, 66, 138}, {172, 50, 50},
                {217, 87, 99}, {215, 123, 186}, {143, 151, 74}, {138, 111, 48}
        };
        for (int[] rgb : db32) {
            model.swatches.add(packRgb(rgb[0], rgb[1], rgb[2]));
        }
        return model;
    }

    private static int packRgb(int r, int g, int b) {
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    public List<Integer> getSwatches() {
        return Collections.unmodifiableList(swatches);
    }

    public void setSwatches(List<Integer> colors) {
        swatches.clear();
        if (colors != null) {
            swatches.addAll(colors);
        }
        selectedIndex = -1;
        notifyChanged();
    }

    public int size() {
        return swatches.size();
    }

    public int getColor(int index) {
        return swatches.get(index);
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void select(int index) {
        this.selectedIndex = (index >= 0 && index < swatches.size()) ? index : -1;
    }

    public void add(int color) {
        swatches.add(color);
        selectedIndex = swatches.size() - 1;
        notifyChanged();
    }

    public void replace(int index, int color) {
        if (index >= 0 && index < swatches.size()) {
            swatches.set(index, color);
            notifyChanged();
        }
    }

    public void remove(int index) {
        if (index >= 0 && index < swatches.size()) {
            swatches.remove(index);
            if (selectedIndex >= swatches.size()) {
                selectedIndex = swatches.size() - 1;
            }
            notifyChanged();
        }
    }

    /** Listener fired on any structural change (add/remove/replace/set). */
    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    private void notifyChanged() {
        if (changeListener != null) {
            changeListener.run();
        }
    }
}

package com.stonebreak.ui.recipeScreen.core;

import com.stonebreak.crafting.Recipe;
import com.stonebreak.ui.recipeScreen.state.RecipeBookState;

import java.util.ArrayList;
import java.util.List;

/**
 * The recipe grid of the three-pane book: how many recipes a row holds and how many whole rows fit
 * between the category sidebar and the detail pane, and which recipes the scroll offset shows. The
 * {@code stonebreak:screen.recipe-book} contract publishes it and the wheel clamps against it.
 */
public final class RecipeGridLayout {

    private final RecipeBookState state;

    /** {recipes per row, visible rows} of the last window the grid was laid out in. */
    private int[] shape;

    public RecipeGridLayout(RecipeBookState state) {
        this.state = state;
    }

    /** The grid in a {@code sw}x{@code sh} window (0: the game's): recipes per row and whole rows that fit. */
    public int[] gridShape(int sw, int sh) {
        PositionCalculator.PanelDimensions p = sw > 0 && sh > 0
                ? PositionCalculator.calculatePanelDimensions(sw, sh)
                : PositionCalculator.calculatePanelDimensions();
        int pad = RecipeBookConstants.PANEL_PADDING;
        int stride = RecipeBookConstants.SLOT_SIZE + RecipeBookConstants.SLOT_GAP;
        int contentH = p.height - pad - RecipeBookConstants.HEADER_HEIGHT - pad - pad;
        int gridX = p.x + pad + RecipeBookConstants.SIDEBAR_WIDTH + pad;
        int detailX = p.x + p.width - pad - RecipeBookConstants.DETAIL_WIDTH;
        int gridW = detailX - gridX - pad;
        return new int[]{Math.max(1, (gridW + RecipeBookConstants.SLOT_GAP) / stride),
            Math.max(1, (contentH + RecipeBookConstants.SLOT_GAP) / stride)};
    }

    /**
     * Maximum scroll-row offset for {@code recipeCount} recipes in the window the grid was last laid
     * out in ({@link #window}; the game's before that).
     */
    public int maxScrollOffset(int recipeCount) {
        int[] s = shape != null ? shape : gridShape(0, 0);
        int totalRows = (recipeCount + s[0] - 1) / s[0];
        return Math.max(0, totalRows - s[1]);
    }

    /**
     * The recipes the grid shows now, row by row: {@code filtered} from the scroll offset, which is
     * first clamped to the recipes there are, in a {@code sw}x{@code sh} window (0: the game's).
     */
    public List<Recipe> window(List<Recipe> filtered, int sw, int sh) {
        shape = gridShape(sw, sh);
        List<Recipe> out = new ArrayList<>();
        if (filtered.isEmpty()) return out;
        int scroll = state.getUiState().getScrollOffset();
        int maxScroll = maxScrollOffset(filtered.size());
        if (scroll > maxScroll) {
            state.getUiState().setScrollOffset(maxScroll);
            scroll = maxScroll;
        }
        int start = scroll * shape[0];
        int end = Math.min(filtered.size(), start + shape[1] * shape[0]);
        for (int i = start; i < end; i++) out.add(filtered.get(i));
        return out;
    }
}

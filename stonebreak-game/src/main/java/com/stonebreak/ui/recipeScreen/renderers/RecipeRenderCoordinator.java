package com.stonebreak.ui.recipeScreen.renderers;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.input.InputHandler;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MCategoryButton;
import com.stonebreak.rendering.UI.masonryUI.MItemSlot;
import com.stonebreak.rendering.UI.masonryUI.MPainter;
import com.stonebreak.rendering.UI.masonryUI.MSearchField;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rendering.UI.masonryUI.MTooltip;
import com.stonebreak.rendering.UI.masonryUI.MasonryUI;
import com.stonebreak.rendering.UI.masonryUI.textures.MTexture;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import com.stonebreak.ui.recipeScreen.core.PositionCalculator;
import com.stonebreak.ui.recipeScreen.core.RecipeBookConstants;
import com.stonebreak.ui.recipeScreen.logic.RecipeFilterService;
import com.stonebreak.ui.recipeScreen.state.RecipeBookState;
import com.stonebreak.ui.recipeScreen.state.UIState;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glScissor;

/**
 * Three-pane Skija/MasonryUI recipe browser:
 * <pre>
 *   ┌──────────────────────────────────────────────┐
 *   │ [search ____________________________ ]       │
 *   ├────────┬──────────────────────┬──────────────┤
 *   │ All    │  ## ## ## ## ## ##   │ Recipe       │
 *   │ Build  │  ## ## ## ## ## ##   │ [3×3 → out]  │
 *   │ Tools  │  ## ## ## ## ## ##   │ Variant 1/3  │
 *   │ Food   │                      │ &lt; prev next> │
 *   └────────┴──────────────────────┴──────────────┘
 * </pre>
 *
 * Mirrors {@link com.stonebreak.ui.inventoryScreen.renderers.InventoryRenderCoordinator}'s
 * three-phase render: Skija chrome → GL item icons (clipped to grid viewport
 * via {@code glScissor}) → Skija count text + tooltip.
 *
 * Hit-testing is exposed via query methods so {@code RecipeBookInputHandler}
 * doesn't need to duplicate the layout math.
 */
public class RecipeRenderCoordinator {

    private static final int  ELM_TINT_OVERLAY = 0x66000000; // dim overlay over Elm_UI fill
    private static final int  GRID_BG          = 0x40000000; // subtle recess for grid viewport
    private static final int  PANEL_FILL       = 0xFF6B6B6B; // fully opaque stone — no bleed
    private static final int  BACKDROP_OVERLAY = 0xE6000000; // ~90% opaque to mask inventory behind
    private static final int  ELM_TILE_SCALE   = 4;          // 4× upscale of Elm_UI texture pixels
    private static final String ELM_UI_RESOURCE = "/ui/recipeScreen/Elm_UI.sbt";

    private final RecipeBookState state;
    private final RecipeFilterService filterService;
    private final UIRenderer uiRenderer;
    private final Renderer renderer;
    private final InputHandler inputHandler;

    private final MasonryUI ui;

    // Persistent widgets — reused each frame.
    private final List<MCategoryButton<String>> categoryButtons = new ArrayList<>();
    private final MSearchField searchField = new MSearchField();
    private final MItemSlot[][] detailInputs = new MItemSlot[RecipeBookConstants.DETAIL_INPUT_GRID]
                                                            [RecipeBookConstants.DETAIL_INPUT_GRID];
    private final MItemSlot detailOutput = new MItemSlot();
    private final MButton prevButton = new MButton("Prev").fontSize(MStyle.FONT_META);
    private final MButton nextButton = new MButton("Next").fontSize(MStyle.FONT_META);

    // Recycled per-frame structures so we don't allocate during render.
    private final List<MItemSlot> visibleSlots = new ArrayList<>();
    private final List<Recipe> visibleRecipes = new ArrayList<>();

    // Cached layout (recomputed each frame).
    private Layout layout;

    public RecipeRenderCoordinator(RecipeBookState state,
                                   RecipeFilterService filterService,
                                   UIRenderer uiRenderer,
                                   Renderer renderer,
                                   InputHandler inputHandler) {
        this.state         = state;
        this.filterService = filterService;
        this.uiRenderer    = uiRenderer;
        this.renderer      = renderer;
        this.inputHandler  = inputHandler;

        this.ui = new MasonryUI(renderer.getSkijaBackend());

        for (String cat : RecipeBookConstants.CATEGORIES) {
            MCategoryButton<String> btn = new MCategoryButton<>(cat, cat);
            btn.fontSize(MStyle.FONT_ITEM);
            categoryButtons.add(btn);
        }
        for (int r = 0; r < RecipeBookConstants.DETAIL_INPUT_GRID; r++) {
            for (int c = 0; c < RecipeBookConstants.DETAIL_INPUT_GRID; c++) {
                detailInputs[r][c] = new MItemSlot();
            }
        }
    }

    // ─────────────────────────────────────────────── Public render entry points

    /**
     * Default render path matches the inventory: chrome + icons + counts only.
     * Tooltips are rendered later (after block drops) via
     * {@link #renderTooltipsOnly}.
     */
    public void render(int sw, int sh) {
        renderWithoutTooltips(sw, sh);
    }

    public void renderWithoutTooltips(int sw, int sh) {
        state.getUiState().clearHoverStates();
        layout = computeLayout();

        Vector2f mouse = inputHandler.getMousePosition();
        float mx = mouse.x;
        float my = mouse.y;

        List<Recipe> filtered = filterService.getFilteredRecipes(
                state.getRecipes(),
                state.getUiState().getSelectedCategory(),
                state.getSearchState().getSearchText());

        // Sync widget bounds + state pre-render so hover/queries match the paint.
        syncCategoryButtons();
        syncSearchField();
        Recipe selected = state.getPopupState().getSelectedRecipe();
        ItemStack[][] selectedPattern = patternOrNull(selected);
        ItemStack selectedOutput = selected != null ? selected.getOutput() : null;
        syncDetailWidgets();

        // Update hover for category buttons + search field
        for (MCategoryButton<String> cat : categoryButtons) cat.updateHover(mx, my);
        searchField.updateHover(mx, my);
        prevButton.updateHover(mx, my);
        nextButton.updateHover(mx, my);

        // Build visible recipe slots for this frame
        rebuildVisibleSlots(filtered);
        for (MItemSlot slot : visibleSlots) slot.updateHover(mx, my);

        // Phase A — Skija chrome
        if (ui.beginFrame(sw, sh, 1.0f)) {
            Canvas canvas = ui.canvas();
            if (canvas != null) {
                drawBackdrop(canvas, sw, sh);
                drawPanel(canvas);
                drawSidebar(canvas);
                drawHeader();
                drawGridChrome(canvas);
                drawDetailPane(canvas, selected);
                for (MItemSlot slot : visibleSlots) slot.render(ui);
                ui.renderOverlays();
            }
            ui.endFrame();
        }

        // Phase B — GL item icons (recipe outputs in grid + detail inputs/output)
        renderRecipeIconsInGrid();
        renderDetailIcons(selectedPattern, selectedOutput);

        // Phase C — Skija count text + grid output count
        if (ui.beginFrame(sw, sh, 1.0f)) {
            Canvas canvas = ui.canvas();
            drawGridCountTexts(canvas);
            drawDetailCountTexts(canvas, selectedPattern, selectedOutput);
            ui.endFrame();
        }

        // Track hovered recipe / item for tooltip stage
        Recipe hoveredRecipe = recipeAt(mx, my);
        ItemStack hoveredItem = hoveredItemStack(mx, my, selectedPattern, selectedOutput);
        UIState ustate = state.getUiState();
        if (hoveredRecipe != null) ustate.setHoveredRecipe(hoveredRecipe);
        if (hoveredItem != null) ustate.setHoveredItemStack(hoveredItem);
    }

    public void renderTooltipsOnly(int sw, int sh) {
        UIState ustate = state.getUiState();
        ItemStack hovered = ustate.getHoveredItemStack();
        Recipe hoveredRecipe = ustate.getHoveredRecipe();

        if ((hovered == null || hovered.isEmpty()) && hoveredRecipe == null) return;

        Vector2f mouse = inputHandler.getMousePosition();
        if (!ui.beginFrame(sw, sh, 1.0f)) return;

        try {
            if (hovered != null && !hovered.isEmpty()) {
                Item item = hovered.getItem();
                if (item != null && item != BlockType.AIR) {
                    MTooltip.draw(ui, item.getName(), mouse.x + 15, mouse.y + 15, sw, sh);
                }
            } else if (hoveredRecipe != null) {
                Item out = hoveredRecipe.getOutput().getItem();
                if (out != null) {
                    MTooltip.draw(ui, "Recipe: " + out.getName(), mouse.x + 15, mouse.y + 15, sw, sh);
                }
            }
        } finally {
            ui.endFrame();
        }
    }

    // ─────────────────────────────────────────────── Hit-test queries

    public Recipe recipeAt(float mx, float my) {
        if (layout == null) return null;
        for (int i = 0; i < visibleSlots.size(); i++) {
            if (visibleSlots.get(i).contains(mx, my)) return visibleRecipes.get(i);
        }
        return null;
    }

    public String categoryAt(float mx, float my) {
        for (MCategoryButton<String> cat : categoryButtons) {
            if (cat.contains(mx, my)) return cat.tag();
        }
        return null;
    }

    public boolean searchClicked(float mx, float my)  { return searchField.contains(mx, my); }
    public boolean prevVariantClicked(float mx, float my) {
        return state.getPopupState().hasMultipleVariations()
            && state.getPopupState().canNavigatePrevious()
            && prevButton.contains(mx, my);
    }
    public boolean nextVariantClicked(float mx, float my) {
        return state.getPopupState().hasMultipleVariations()
            && state.getPopupState().canNavigateNext()
            && nextButton.contains(mx, my);
    }

    public ItemStack hoveredItemStack(float mx, float my,
                                      ItemStack[][] selectedPattern, ItemStack selectedOutput) {
        // Detail input slots
        if (selectedPattern != null) {
            for (int r = 0; r < detailInputs.length; r++) {
                for (int c = 0; c < detailInputs[r].length; c++) {
                    if (detailInputs[r][c].contains(mx, my)) {
                        if (r < selectedPattern.length && c < selectedPattern[r].length) {
                            ItemStack s = selectedPattern[r][c];
                            if (s != null && !s.isEmpty()) return s;
                        }
                    }
                }
            }
        }
        // Detail output
        if (selectedOutput != null && !selectedOutput.isEmpty() && detailOutput.contains(mx, my)) {
            return selectedOutput;
        }
        return null;
    }

    /**
     * Maximum scroll-row offset for the current grid size and recipe count.
     * Used by the input handler when handling mouse-wheel scrolling.
     */
    public int maxScrollOffset(int recipeCount) {
        Layout l = layout != null ? layout : computeLayout();
        if (l.recipesPerRow <= 0) return 0;
        int totalRows = (recipeCount + l.recipesPerRow - 1) / l.recipesPerRow;
        return Math.max(0, totalRows - l.visibleRows);
    }

    public void dispose() {
        ui.dispose();
    }

    // ─────────────────────────────────────────────── Layout

    private static final class Layout {
        final int panelX, panelY, panelW, panelH;
        final int sidebarX, sidebarY, sidebarW, sidebarH;
        final int headerX, headerY, headerW, headerH;
        final int gridX, gridY, gridW, gridH;
        final int detailX, detailY, detailW, detailH;
        final int recipesPerRow, visibleRows;

        Layout(int panelX, int panelY, int panelW, int panelH) {
            this.panelX = panelX;
            this.panelY = panelY;
            this.panelW = panelW;
            this.panelH = panelH;

            int pad = RecipeBookConstants.PANEL_PADDING;
            int slot = RecipeBookConstants.SLOT_SIZE;
            int gap  = RecipeBookConstants.SLOT_GAP;

            this.headerX = panelX + pad;
            this.headerY = panelY + pad;
            this.headerW = panelW - 2 * pad;
            this.headerH = RecipeBookConstants.HEADER_HEIGHT;

            int contentY = headerY + headerH + pad;
            int contentH = panelH - pad - headerH - pad - pad;

            this.sidebarX = panelX + pad;
            this.sidebarY = contentY;
            this.sidebarW = RecipeBookConstants.SIDEBAR_WIDTH;
            this.sidebarH = contentH;

            this.detailX = panelX + panelW - pad - RecipeBookConstants.DETAIL_WIDTH;
            this.detailY = contentY;
            this.detailW = RecipeBookConstants.DETAIL_WIDTH;
            this.detailH = contentH;

            this.gridX = sidebarX + sidebarW + pad;
            this.gridY = contentY;
            this.gridW = detailX - gridX - pad;
            this.gridH = contentH;

            int colStride = slot + gap;
            this.recipesPerRow = Math.max(1, (gridW + gap) / colStride);
            this.visibleRows   = Math.max(1, (gridH + gap) / colStride);
        }
    }

    private Layout computeLayout() {
        PositionCalculator.PanelDimensions p = PositionCalculator.calculatePanelDimensions();
        return new Layout(p.x, p.y, p.width, p.height);
    }

    // ─────────────────────────────────────────────── Sync widget bounds

    private void syncCategoryButtons() {
        int x = layout.sidebarX + 8;
        int w = layout.sidebarW - 16;
        int y = layout.sidebarY + 8;
        int h = RecipeBookConstants.CATEGORY_BUTTON_HEIGHT;
        int gap = RecipeBookConstants.CATEGORY_BUTTON_GAP;
        String selected = state.getUiState().getSelectedCategory();
        for (MCategoryButton<String> cat : categoryButtons) {
            cat.bounds(x, y, w, h);
            cat.setSelected(cat.tag().equals(selected));
            y += h + gap;
        }
    }

    private void syncSearchField() {
        searchField.bounds(layout.headerX, layout.headerY, layout.headerW, layout.headerH);
        searchField.text(state.getSearchState().getSearchText());
        searchField.placeholder("Search recipes...");
        searchField.active(state.getSearchState().isSearchActive());
    }

    private void syncDetailWidgets() {
        int slot = RecipeBookConstants.SLOT_SIZE;
        int gap  = RecipeBookConstants.SLOT_GAP;
        int gridSize = RecipeBookConstants.DETAIL_INPUT_GRID;

        // Composite: [3×3 inputs] [→ arrow 20] [output slot]
        int inputsW = gridSize * slot + (gridSize - 1) * gap;
        int arrowW  = 20;
        int spacing = 10;
        int totalW  = inputsW + spacing + arrowW + spacing + slot;
        int composX = layout.detailX + (layout.detailW - totalW) / 2;
        int composY = layout.detailY + 64; // below "Recipe" title

        for (int r = 0; r < gridSize; r++) {
            for (int c = 0; c < gridSize; c++) {
                int sx = composX + c * (slot + gap);
                int sy = composY + r * (slot + gap);
                detailInputs[r][c].bounds(sx, sy, slot, slot);
            }
        }
        int outputX = composX + inputsW + spacing + arrowW + spacing;
        int outputY = composY + (inputsW - slot) / 2; // center vertically with the 3-row input
        detailOutput.bounds(outputX, outputY, slot, slot);

        // Pagination buttons under the composite — wider rectangles fit "Prev"/"Next"
        int btnW = 64;
        int btnH = RecipeBookConstants.PAGINATION_BUTTON_SIZE;
        int btnY = composY + inputsW + 28;
        int prevX = layout.detailX + 20;
        int nextX = layout.detailX + layout.detailW - btnW - 20;
        prevButton.bounds(prevX, btnY, btnW, btnH);
        nextButton.bounds(nextX, btnY, btnW, btnH);
        prevButton.enabled(state.getPopupState().canNavigatePrevious());
        nextButton.enabled(state.getPopupState().canNavigateNext());
    }

    private void rebuildVisibleSlots(List<Recipe> filtered) {
        visibleSlots.clear();
        visibleRecipes.clear();
        if (filtered.isEmpty()) return;

        int slot = RecipeBookConstants.SLOT_SIZE;
        int gap  = RecipeBookConstants.SLOT_GAP;
        int scroll = state.getUiState().getScrollOffset();
        // Clamp scroll if it became out-of-range after filtering
        int maxScroll = maxScrollOffset(filtered.size());
        if (scroll > maxScroll) {
            state.getUiState().setScrollOffset(maxScroll);
            scroll = maxScroll;
        }

        for (int row = 0; row < layout.visibleRows; row++) {
            for (int col = 0; col < layout.recipesPerRow; col++) {
                int idx = (row + scroll) * layout.recipesPerRow + col;
                if (idx >= filtered.size()) return;
                int sx = layout.gridX + col * (slot + gap);
                int sy = layout.gridY + row * (slot + gap);
                MItemSlot s = new MItemSlot().bounds(sx, sy, slot, slot);
                visibleSlots.add(s);
                visibleRecipes.add(filtered.get(idx));
            }
        }
    }

    // ─────────────────────────────────────────────── Phase A — Skija chrome

    private void drawBackdrop(Canvas canvas, int sw, int sh) {
        // Near-opaque overlay so the inventory / hotbar behind us doesn't bleed through.
        try (Paint p = new Paint().setColor(BACKDROP_OVERLAY)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, sw, sh), p);
        }
    }

    private void drawPanel(Canvas canvas) {
        MPainter.stoneSurface(canvas,
                layout.panelX, layout.panelY, layout.panelW, layout.panelH,
                MStyle.PANEL_RADIUS,
                PANEL_FILL, MStyle.PANEL_BORDER,
                MStyle.PANEL_HIGHLIGHT, MStyle.PANEL_SHADOW, MStyle.PANEL_DROP_SHADOW,
                MStyle.PANEL_NOISE_DARK, MStyle.PANEL_NOISE_LIGHT);
    }

    private void drawSidebar(Canvas canvas) {
        drawElmFill(canvas, layout.sidebarX, layout.sidebarY, layout.sidebarW, layout.sidebarH);
        // Hairline border to separate from grid
        try (Paint p = new Paint().setColor(MStyle.PANEL_BORDER)) {
            canvas.drawRect(Rect.makeXYWH(layout.sidebarX + layout.sidebarW, layout.sidebarY,
                    1f, layout.sidebarH), p);
        }
        for (MCategoryButton<String> cat : categoryButtons) cat.render(ui);
    }

    private void drawHeader() {
        searchField.render(ui);
    }

    private void drawGridChrome(Canvas canvas) {
        // Subtle recess so the grid reads as the focus area.
        try (Paint p = new Paint().setColor(GRID_BG)) {
            canvas.drawRect(Rect.makeXYWH(layout.gridX, layout.gridY, layout.gridW, layout.gridH), p);
        }
    }

    private void drawDetailPane(Canvas canvas, Recipe selected) {
        drawElmFill(canvas, layout.detailX, layout.detailY, layout.detailW, layout.detailH);
        try (Paint p = new Paint().setColor(MStyle.PANEL_BORDER)) {
            canvas.drawRect(Rect.makeXYWH(layout.detailX - 1f, layout.detailY, 1f, layout.detailH), p);
        }

        Font titleFont = ui.fonts().get(MStyle.FONT_BUTTON);
        float titleX = layout.detailX + layout.detailW / 2f;
        float titleY = layout.detailY + 28f;
        MPainter.drawCenteredStringWithShadow(canvas, "Recipe", titleX, titleY,
                titleFont, MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);

        if (selected == null) {
            Font hint = ui.fonts().get(MStyle.FONT_ITEM);
            MPainter.drawCenteredStringWithShadow(canvas, "Select a recipe",
                    titleX, layout.detailY + layout.detailH / 2f,
                    hint, MStyle.TEXT_DISABLED, MStyle.TEXT_SHADOW);
            return;
        }

        // 3×3 input slot backgrounds
        for (MItemSlot[] row : detailInputs) for (MItemSlot s : row) s.render(ui);

        // Arrow between inputs and output
        int slot = RecipeBookConstants.SLOT_SIZE;
        float ax = detailInputs[0][2].x() + slot + 10f;
        float ay = detailOutput.y() + (slot - 20f) / 2f;
        MPainter.craftingArrow(canvas, ax, ay, 20, 20, 0xB48C8C8C);

        // Output slot background
        detailOutput.render(ui);

        // Variation label + buttons
        if (state.getPopupState().hasMultipleVariations()) {
            Font small = ui.fonts().get(MStyle.FONT_META);
            String label = "Variant " + (state.getPopupState().getCurrentVariationIndex() + 1)
                         + " / " + state.getPopupState().getCurrentRecipeVariations().size();
            MPainter.drawCenteredStringWithShadow(canvas, label,
                    titleX, prevButton.y() - 8f,
                    small, MStyle.TEXT_PRIMARY, MStyle.TEXT_SHADOW);
            prevButton.render(ui);
            nextButton.render(ui);
        }
    }

    private void drawElmFill(Canvas canvas, float x, float y, float w, float h) {
        MTexture tex = MTextureRegistry.get(ELM_UI_RESOURCE);
        if (tex != null) {
            int tw = tex.width();
            int th = tex.height();
            if (tw > 0 && th > 0) {
                // Render at integer-multiple scale so pixel art stays crisp.
                float tileW = tw * ELM_TILE_SCALE;
                float tileH = th * ELM_TILE_SCALE;
                int save = canvas.save();
                try {
                    canvas.clipRect(Rect.makeXYWH(x, y, w, h));
                    for (float py = y; py < y + h; py += tileH) {
                        for (float px = x; px < x + w; px += tileW) {
                            MPainter.drawImage(canvas, tex.image(), px, py, tileW, tileH);
                        }
                    }
                } finally {
                    canvas.restoreToCount(save);
                }
            }
        } else {
            try (Paint p = new Paint().setColor(MStyle.PANEL_FILL_DEEP)) {
                canvas.drawRect(Rect.makeXYWH(x, y, w, h), p);
            }
        }
        // Dark overlay for legibility against the texture
        try (Paint p = new Paint().setColor(ELM_TINT_OVERLAY)) {
            canvas.drawRect(Rect.makeXYWH(x, y, w, h), p);
        }
    }

    // ─────────────────────────────────────────────── Phase B — GL item icons

    private void renderRecipeIconsInGrid() {
        if (visibleSlots.isEmpty()) return;

        // Scissor the framebuffer to the grid viewport so partial rows clip.
        int sh = com.stonebreak.core.Game.getWindowHeight();
        int sx = layout.gridX;
        int sy = sh - (layout.gridY + layout.gridH);
        int sw = layout.gridW;
        int swh = layout.gridH;
        glEnable(GL_SCISSOR_TEST);
        try {
            glScissor(sx, sy, sw, swh);
            int slot = RecipeBookConstants.SLOT_SIZE;
            int inset = 3;
            int icon = slot - inset * 2;
            for (int i = 0; i < visibleSlots.size(); i++) {
                MItemSlot s = visibleSlots.get(i);
                Recipe r = visibleRecipes.get(i);
                drawItemIcon(r.getOutput(),
                        (int)(s.x() + inset), (int)(s.y() + inset), icon);
            }
        } finally {
            glDisable(GL_SCISSOR_TEST);
        }
    }

    private void renderDetailIcons(ItemStack[][] pattern, ItemStack output) {
        int slot = RecipeBookConstants.SLOT_SIZE;
        int inset = 3;
        int icon = slot - inset * 2;
        if (pattern != null) {
            for (int r = 0; r < detailInputs.length; r++) {
                for (int c = 0; c < detailInputs[r].length; c++) {
                    ItemStack s = (r < pattern.length && c < pattern[r].length) ? pattern[r][c] : null;
                    drawItemIcon(s,
                            (int)(detailInputs[r][c].x() + inset),
                            (int)(detailInputs[r][c].y() + inset), icon);
                }
            }
        }
        if (output != null) {
            drawItemIcon(output,
                    (int)(detailOutput.x() + inset),
                    (int)(detailOutput.y() + inset), icon);
        }
    }

    private void drawItemIcon(ItemStack itemStack, int x, int y, int size) {
        if (itemStack == null || itemStack.isEmpty()) return;
        Item item = itemStack.getItem();
        if (item == null || !item.hasIcon()) return;

        if (item instanceof BlockType bt) {
            uiRenderer.draw3DItemInSlot(renderer.getShaderProgram(), bt, x, y, size, size,
                    renderer.getTextureAtlas());
        } else {
            uiRenderer.renderItemIcon(x, y, size, size, item, renderer.getTextureAtlas());
        }
    }

    // ─────────────────────────────────────────────── Phase C — count text

    private void drawGridCountTexts(Canvas canvas) {
        Font font = ui.fonts().get(MStyle.FONT_META);
        int slot = RecipeBookConstants.SLOT_SIZE;
        for (int i = 0; i < visibleSlots.size(); i++) {
            ItemStack out = visibleRecipes.get(i).getOutput();
            if (out == null || out.isEmpty() || out.getCount() <= 1) continue;
            MItemSlot s = visibleSlots.get(i);
            String count = String.valueOf(out.getCount());
            float tx = s.x() + slot - MPainter.measureWidth(font, count) - 2f;
            float ty = s.y() + slot - 2f;
            MPainter.drawStringWithShadow(canvas, count, tx, ty, font,
                    MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        }
    }

    private void drawDetailCountTexts(Canvas canvas, ItemStack[][] pattern, ItemStack output) {
        Font font = ui.fonts().get(MStyle.FONT_META);
        int slot = RecipeBookConstants.SLOT_SIZE;
        if (pattern != null) {
            for (int r = 0; r < detailInputs.length; r++) {
                for (int c = 0; c < detailInputs[r].length; c++) {
                    ItemStack s = (r < pattern.length && c < pattern[r].length) ? pattern[r][c] : null;
                    if (s == null || s.isEmpty() || s.getCount() <= 1) continue;
                    String count = String.valueOf(s.getCount());
                    float tx = detailInputs[r][c].x() + slot - MPainter.measureWidth(font, count) - 2f;
                    float ty = detailInputs[r][c].y() + slot - 2f;
                    MPainter.drawStringWithShadow(canvas, count, tx, ty, font,
                            MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
                }
            }
        }
        if (output != null && !output.isEmpty() && output.getCount() > 1) {
            String count = String.valueOf(output.getCount());
            float tx = detailOutput.x() + slot - MPainter.measureWidth(font, count) - 2f;
            float ty = detailOutput.y() + slot - 2f;
            MPainter.drawStringWithShadow(canvas, count, tx, ty, font,
                    MStyle.TEXT_ACCENT, MStyle.TEXT_SHADOW);
        }
    }

    // ─────────────────────────────────────────────── Helpers

    private static ItemStack[][] patternOrNull(Recipe r) {
        return r != null ? r.getPattern() : null;
    }
}

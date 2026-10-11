package com.stonebreak.ui.recipeScreen.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.recipeScreen.logic.RecipeFilterService;
import com.stonebreak.ui.recipeScreen.logic.RecipeSearchService;
import com.stonebreak.ui.recipeScreen.logic.RecipeVariationService;
import com.stonebreak.ui.recipeScreen.state.RecipeBookState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The recipe book's #301 fidelity cases: a fixed recipe table (block outputs only: block icons are GL on
 * both sides, so a raster capture shows frames and counts), the book's state per variant, and the legacy
 * layout as a geometry oracle. Not a test class.
 *
 * <p>Variants: {@code empty}, {@code selected} (one variant), {@code variants} (planks, 1 of 2),
 * {@code search} (typed, unfocused), {@code category} (Building), {@code category-empty} (Tools),
 * {@code scrolled} (one recipe per block type, two rows down, in small windows that need scrolling), and {@code hover-<target>} over {@code selected} or
 * {@code variants}: {@code cat2}, {@code cell3}, {@code in0}, {@code out}, {@code next}, {@code prev}.
 */
public final class RecipeBookFixtures {

    private RecipeBookFixtures() {
    }

    public static final List<String> STATES = List.of("empty", "selected", "variants", "search", "category",
        "category-empty");
    /** Windows small enough for the block table to scroll (a 3 x 5 grid; 4 x 5 at 800 px). */
    public static final FidelityCase.Viewport SMALL = new FidelityCase.Viewport(640, 360, 1f, 1f);
    public static final FidelityCase.Viewport SMALL_SCALED = new FidelityCase.Viewport(801, 451, 1.25f, 1f);

    public static final List<String> HOVERS = List.of("hover-cat2", "hover-cell3", "hover-in0", "hover-out",
        "hover-next", "hover-prev");

    public static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("recipe-book", STATES, FidelityCase.STANDARD));
        out.add(new FidelityCase("recipe-book", "scrolled", SMALL));
        out.add(new FidelityCase("recipe-book", "scrolled", SMALL_SCALED));
        for (String h : HOVERS) {
            out.add(new FidelityCase("recipe-book", h, FidelityCase.STANDARD.get(0)));
            out.add(new FidelityCase("recipe-book", h, FidelityCase.STANDARD.get(3)));
        }
        return out;
    }

    private static Recipe recipe(String id, ItemStack out, BlockType... row) {
        List<ItemStack> r = new ArrayList<>();
        for (BlockType b : row) {
            r.add(b == null ? null : new ItemStack(b, 1));
        }
        return new Recipe(id, List.of(r), out);
    }

    private static Recipe grid(String id, ItemStack out, BlockType[][] rows) {
        List<List<ItemStack>> p = new ArrayList<>();
        for (BlockType[] row : rows) {
            List<ItemStack> r = new ArrayList<>();
            for (BlockType b : row) {
                r.add(b == null ? null : new ItemStack(b, 1));
            }
            p.add(r);
        }
        return new Recipe(id, p, out);
    }

    /** The table: the planks have two recipes (variants), the flowers are Decorative, the rest Building. */
    public static List<Recipe> recipes(String variant) {
        List<Recipe> out = new ArrayList<>();
        out.add(recipe("planks", new ItemStack(BlockType.WOOD_PLANKS, 4), BlockType.WOOD));
        out.add(grid("cobble", new ItemStack(BlockType.COBBLESTONE, 1), new BlockType[][]{
            {BlockType.STONE, BlockType.STONE}, {BlockType.STONE, BlockType.STONE}}));
        out.add(grid("workbench", new ItemStack(BlockType.WORKBENCH, 1), new BlockType[][]{
            {BlockType.WOOD_PLANKS, BlockType.WOOD_PLANKS}, {BlockType.WOOD_PLANKS, BlockType.WOOD_PLANKS}}));
        out.add(grid("furnace", new ItemStack(BlockType.FURNACE, 1), new BlockType[][]{
            {BlockType.COBBLESTONE, BlockType.COBBLESTONE, BlockType.COBBLESTONE},
            {BlockType.COBBLESTONE, null, BlockType.COBBLESTONE},
            {BlockType.COBBLESTONE, BlockType.COBBLESTONE, BlockType.COBBLESTONE}}));
        out.add(recipe("planks-pine", new ItemStack(BlockType.WOOD_PLANKS, 2), BlockType.PINE, BlockType.PINE));
        out.add(recipe("sandstone", new ItemStack(BlockType.SANDSTONE, 4), BlockType.SAND, BlockType.SAND));
        out.add(recipe("gravel", new ItemStack(BlockType.GRAVEL, 16), BlockType.COBBLESTONE));
        out.add(recipe("glass-ish", new ItemStack(BlockType.ICE, 1), BlockType.SNOW, BlockType.SNOW, BlockType.SNOW));
        out.add(recipe("rose", new ItemStack(BlockType.ROSE, 2), BlockType.LEAVES));
        out.add(recipe("dandelion", new ItemStack(BlockType.DANDELION, 1), BlockType.GRASS));
        out.add(recipe("clay", new ItemStack(BlockType.CLAY, 3), BlockType.DIRT, BlockType.SAND));
        out.add(recipe("wool", new ItemStack(BlockType.WOOL, 1), BlockType.SNOW));
        if ("scrolled".equals(variant)) {
            // the book shows one recipe per output: one per block type, in id order
            List<BlockType> blocks = new ArrayList<>(java.util.Arrays.asList(BlockType.values()));
            blocks.removeIf(b -> b == BlockType.AIR || b == BlockType.WATER);
            blocks.sort(java.util.Comparator.comparingInt(BlockType::getId));
            for (int i = 0; i < blocks.size(); i++) {
                out.add(recipe("filler" + i, new ItemStack(blocks.get(i), 1 + i % 5), BlockType.DIRT));
            }
        }
        return out;
    }

    /** The book open on {@code variant}'s state, as the legacy screen and the document both see it. */
    public static RecipeBookState state(String variant) {
        RecipeBookState s = new RecipeBookState();
        prepare(s, variant);
        return s;
    }

    /** Puts {@code s} (any state object, e.g. a real RecipeScreen's) into {@code variant}'s state. */
    public static void prepare(RecipeBookState s, String variant) {
        String base = base(variant);
        s.setRecipes(recipes(base));
        s.show();
        List<Recipe> all = s.getRecipes();
        switch (base) {
            case "selected" -> select(s, all, "cobble");
            case "variants" -> select(s, all, "planks");
            case "search" -> {
                s.getSearchState().setSearchText("pla");
                s.getSearchState().setSearchActive(false);
            }
            case "category" -> s.getUiState().setSelectedCategory("Building");
            case "category-empty" -> s.getUiState().setSelectedCategory("Tools");
            case "scrolled" -> s.getUiState().setScrollOffset(2);
            default -> {
            }
        }
        s.getPopupState().updateAfterFrame(); // a frame has passed: the variant buttons take presses
    }

    private static void select(RecipeBookState s, List<Recipe> all, String id) {
        Recipe r = all.stream().filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
        s.getPopupState().openPopup(r, new RecipeVariationService().getRecipeVariations(r, all));
    }

    /** The state a hover case shows: next/prev over {@code variants}, the rest over {@code selected}. */
    public static String base(String variant) {
        if (!variant.startsWith("hover-")) {
            return variant;
        }
        String t = hover(variant);
        return t.equals("next") || t.equals("prev") ? "variants" : "selected";
    }

    public static String hover(String variant) {
        return variant.startsWith("hover-") ? variant.substring("hover-".length()) : "";
    }

    // ── the legacy layout (RecipeRenderCoordinator.Layout + syncDetailWidgets): the geometry oracle ──

    /** {panelX, panelY, panelW, panelH, headerX, headerY, headerW, contentY, contentH, sidebarX, gridX, gridW, detailX, perRow, rows} */
    static int[] layout(int w, int h) {
        int panelW = Math.min(960, w - 80);
        int panelH = Math.min(700, h - 80);
        int panelX = (w - panelW) / 2;
        int panelY = (h - panelH) / 2;
        int headerX = panelX + 12;
        int headerY = panelY + 12;
        int contentY = headerY + 36 + 12;
        int contentH = panelH - 12 - 36 - 12 - 12;
        int sidebarX = panelX + 12;
        int detailX = panelX + panelW - 12 - 250;
        int gridX = sidebarX + 140 + 12;
        int gridW = detailX - gridX - 12;
        return new int[]{panelX, panelY, panelW, panelH, headerX, headerY, panelW - 24, contentY, contentH, sidebarX,
            gridX, gridW, detailX, Math.max(1, (gridW + 4) / 40), Math.max(1, (contentH + 4) / 40)};
    }

    /** Named rects of what {@code s} shows in a {@code w}x{@code h} window. */
    public static Map<String, float[]> rects(int w, int h, RecipeBookState s) {
        int[] l = layout(w, h);
        Map<String, float[]> out = new LinkedHashMap<>();
        out.put("panel", f(l[0], l[1], l[2], l[3]));
        out.put("search", f(l[4], l[5], l[6], 36));
        for (int i = 0; i < 5; i++) {
            out.put("cat" + i, f(l[9] + 8, l[7] + 8 + i * 36, 124, 32));
        }
        List<Recipe> filtered = new RecipeFilterService(new RecipeSearchService()).getFilteredRecipes(s.getRecipes(),
            s.getUiState().getSelectedCategory(), s.getSearchState().getSearchText());
        int perRow = l[13];
        int rows = l[14];
        int totalRows = (filtered.size() + perRow - 1) / perRow;
        int scroll = Math.min(s.getUiState().getScrollOffset(), Math.max(0, totalRows - rows));
        int shown = Math.max(0, Math.min(filtered.size() - scroll * perRow, rows * perRow));
        for (int i = 0; i < shown; i++) {
            out.put("cell" + i, f(l[10] + (i % perRow) * 40, l[7] + (i / perRow) * 40, 36, 36));
        }
        if (s.getPopupState().getSelectedRecipe() != null) {
            int cx = l[12] + 29;
            int cy = l[7] + 64;
            for (int i = 0; i < 9; i++) {
                out.put("in" + i, f(cx + (i % 3) * 40, cy + (i / 3) * 40, 36, 36));
            }
            out.put("out", f(cx + 156, cy + 40, 36, 36));
            if (s.getPopupState().hasMultipleVariations()) {
                out.put("prev", f(l[12] + 20, cy + 144, 64, 28));
                out.put("next", f(l[12] + 166, cy + 144, 64, 28));
            }
        }
        return out;
    }

    private static float[] f(float x, float y, float w, float h) {
        return new float[]{x, y, w, h};
    }
}

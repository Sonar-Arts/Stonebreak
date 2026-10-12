package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.crafting.Recipe;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.recipeScreen.RecipeScreen;
import com.stonebreak.ui.recipeScreen.core.RecipeBookConstants;
import com.stonebreak.ui.recipeScreen.state.PopupState;
import com.stonebreak.ui.recipeScreen.state.RecipeBookState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * The recipe book ({@code stonebreak:screen.recipe-book} 1, #301). {@link RecipeScreen} stays the
 * controller (categories, search, the variation cycler, the scroll clamp, the keyboard); this is what it
 * shows:
 * <ul>
 *   <li>root {@code recipeBook}: {@code categories} ({@code category}, {@code selected}), the search
 *       ({@code searchDisplay}: the text or the placeholder, {@code searchEmpty}, {@code searchActive},
 *       {@code caretOn}: the legacy 2 Hz blink), {@code columns} (recipes per row at this window size) and the
 *       detail pane ({@code hasSelection}, {@code variants}, {@code variantLabel}, {@code canPrev},
 *       {@code canNext});</li>
 *   <li>collection {@code recipeGrid}: the recipes the grid shows now, row by row (slot records
 *       {@code cell:i}, the output stack, {@code tooltip} "Recipe: name");</li>
 *   <li>root {@code recipeDetail}: the selected recipe's 3x3 inputs ({@code in0..in8}) and its output
 *       ({@code out}), each a slot record with its item name as {@code tooltip} (all empty with nothing
 *       selected).</li>
 * </ul>
 * Actions {@code .category {name}}, {@code .press {search, cell}} (focus or unfocus the search, then select
 * grid cell {@code cell}, -1 none), {@code .variant {direction}} (a press the variant cycler does not take
 * falls through like any other press, unfocusing the search), {@code .wheel {dy}}; each refuses unless the
 * book is open.
 */
public final class RecipeBookContracts {

    public static final HostContract BOOK = HostContract.of("stonebreak:screen.recipe-book", 1);

    public static final DataType.Obj STATE = DataType.object(
        "categories", DataType.list(DataType.object("category", DataType.string(), "selected", DataType.bool()),
            "category"),
        "searchText", DataType.string(), "searchDisplay", DataType.string(), "searchEmpty", DataType.bool(), "searchActive", DataType.bool(),
        "caretOn", DataType.bool(), "columns", DataType.integer(), "hasSelection", DataType.bool(),
        "variants", DataType.bool(), "variantLabel", DataType.string(), "canPrev", DataType.bool(),
        "canNext", DataType.bool());
    public static final DataType.Obj CELL = DataType.object("slot", DataType.string(),
        "item", DataType.integer(), "objectId", DataType.string(), "name", DataType.string(),
        "count", DataType.integer(), "state", DataType.string(), "maxStack", DataType.integer(),
        "selected", DataType.bool(), "tooltip", DataType.string());
    public static final DataType.ListOf CELLS = DataType.list(CELL, "slot");
    public static final DataType.Obj DETAIL = DataType.object("in0", CELL, "in1", CELL, "in2", CELL, "in3", CELL,
        "in4", CELL, "in5", CELL, "in6", CELL, "in7", CELL, "in8", CELL, "out", CELL);

    /** The game behind the book. */
    public interface Services {
        /** The open recipe book, or null. */
        default RecipeScreen recipeScreen() {
            return null;
        }

        /** The window in device px ({@code {w, h}}); null or zeros: the game's. */
        default int[] screenSize() {
            return null;
        }
    }

    private final Services services;
    private final DataCell state;
    private final DataCollection grid = new DataCollection(CELLS);
    private final DataCell detail;
    private UiValue lastState;
    private List<UiValue> lastGrid = List.of();
    private UiValue lastDetail;
    private List<Recipe> window = List.of();

    public RecipeBookContracts(UiHost ui, Services services) {
        this.services = services;
        state = ui.data().register("recipeBook", new DataCell(STATE, state(null, 1)), BOOK);
        ui.data().register("recipeGrid", grid, BOOK);
        detail = ui.data().register("recipeDetail", new DataCell(DETAIL, detail(null)), BOOK);
        action(ui, "category", DataType.object("name", DataType.string()), (r, a) -> {
            r.intents().pressCategory(str(a, "name"));
            return null;
        });
        action(ui, "press", DataType.object("search", DataType.bool(), "cell", DataType.number()), (r, a) -> {
            int cell = (int) num(a, "cell");
            Recipe recipe = cell >= 0 && cell < window.size() ? window.get(cell) : null;
            r.intents().pressContent(a.get("search") instanceof UiValue.Bool b && b.value(), recipe);
            return null;
        });
        action(ui, "variant", DataType.object("direction", DataType.number()), (r, a) -> {
            if (!r.intents().pressVariant((int) num(a, "direction"))) {
                r.intents().pressContent(false, null); // the legacy press fell through to the rest of the book
            }
            return null;
        });
        action(ui, "wheel", DataType.object("dy", DataType.number()), (r, a) -> {
            r.intents().wheel(num(a, "dy"));
            return null;
        });
    }

    /** UI thread, once per frame and right before the book paints (the caret blinks, the grid scrolls). */
    public void poll() {
        RecipeScreen r = services.recipeScreen();
        int[] size = services.screenSize();
        int w = size == null ? 0 : size[0];
        int h = size == null ? 0 : size[1];
        window = r == null ? List.of() : r.window(w, h);
        int columns = r == null ? 1 : r.gridShape(w, h)[0];
        UiValue s = state(r, columns);
        if (!s.equals(lastState)) {
            lastState = s;
            state.set(s);
        }
        List<UiValue> g = new ArrayList<>(window.size());
        for (int i = 0; i < window.size(); i++) {
            ItemStack out = window.get(i).getOutput();
            Item item = out == null ? null : out.getItem();
            g.add(cell("cell:" + i, out, item == null ? "" : "Recipe: " + item.getName()));
        }
        if (!g.equals(lastGrid)) {
            lastGrid = g;
            grid.setAll(g);
        }
        UiValue d = detail(r);
        if (!d.equals(lastDetail)) {
            lastDetail = d;
            detail.set(d);
        }
    }

    private static UiValue state(RecipeScreen r, int columns) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        RecipeBookState s = r == null ? null : r.state();
        String selected = s == null ? RecipeBookConstants.CATEGORIES[0] : s.getUiState().getSelectedCategory();
        List<UiValue> cats = new ArrayList<>();
        for (String c : RecipeBookConstants.CATEGORIES) {
            cats.add(new UiValue.Obj(Map.of("category", UiValue.of(c), "selected", UiValue.of(c.equals(selected)))));
        }
        m.put("categories", new UiValue.Arr(cats));
        String text = s == null ? "" : s.getSearchState().getSearchText();
        boolean active = s != null && s.getSearchState().isSearchActive();
        m.put("searchText", UiValue.of(text));
        m.put("searchDisplay", UiValue.of(text.isEmpty() ? "Search recipes..." : text));
        m.put("searchEmpty", UiValue.of(text.isEmpty()));
        m.put("searchActive", UiValue.of(active));
        // MSearchField's caret: 2 Hz on the wall clock while focused
        m.put("caretOn", UiValue.of(active && (System.currentTimeMillis() / 500L) % 2L == 0L));
        m.put("columns", UiValue.of(columns));
        PopupState p = s == null ? null : s.getPopupState();
        boolean has = p != null && p.getSelectedRecipe() != null;
        boolean variants = has && p.hasMultipleVariations();
        m.put("hasSelection", UiValue.of(has));
        m.put("variants", UiValue.of(variants));
        m.put("variantLabel", UiValue.of(variants ? "Variant " + (p.getCurrentVariationIndex() + 1) + " / "
            + p.getCurrentRecipeVariations().size() : ""));
        m.put("canPrev", UiValue.of(variants && p.canNavigatePrevious()));
        m.put("canNext", UiValue.of(variants && p.canNavigateNext()));
        return new UiValue.Obj(m);
    }

    private static UiValue detail(RecipeScreen r) {
        Recipe sel = r == null ? null : r.state().getPopupState().getSelectedRecipe();
        ItemStack[][] pattern = sel == null ? null : sel.getPattern();
        Map<String, UiValue> out = new LinkedHashMap<>();
        for (int row = 0; row < RecipeBookConstants.DETAIL_INPUT_GRID; row++) {
            for (int col = 0; col < RecipeBookConstants.DETAIL_INPUT_GRID; col++) {
                ItemStack st = pattern != null && row < pattern.length && col < pattern[row].length
                    ? pattern[row][col] : null;
                String slot = "in" + (row * 3 + col);
                out.put(slot, cell(slot, st, name(st)));
            }
        }
        ItemStack output = sel == null ? null : sel.getOutput();
        out.put("out", cell("out", output, name(output)));
        return new UiValue.Obj(out);
    }

    private static String name(ItemStack st) {
        if (st == null || st.isEmpty()) {
            return "";
        }
        Item item = st.getItem();
        return item == null || item == BlockType.AIR ? "" : item.getName();
    }

    private static UiValue cell(String slot, ItemStack stack, String tooltip) {
        Map<String, UiValue> m = new LinkedHashMap<>(SlotRecords.slot(slot, stack, false).fields());
        m.put("tooltip", UiValue.of(tooltip));
        return new UiValue.Obj(m);
    }

    private void action(UiHost ui, String name, DataType.Obj params, BiFunction<RecipeScreen, UiValue.Obj, String> rule) {
        ui.actions().register(ActionSpec.of(BOOK.id() + "." + name, BOOK, params, DataType.ANY), (args, ctx) -> {
            RecipeScreen r = services.recipeScreen();
            String problem = r == null || !r.isVisible() ? "the recipe book is not open"
                : rule.apply(r, args instanceof UiValue.Obj o ? o : new UiValue.Obj(Map.of()));
            poll();
            return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
                : CompletableFuture.failedFuture(new IllegalStateException(problem));
        });
    }

    private static String str(UiValue.Obj a, String k) {
        return a.get(k) instanceof UiValue.Str s ? s.value() : "";
    }

    private static double num(UiValue.Obj a, String k) {
        return a.get(k) instanceof UiValue.Num n ? n.value() : 0;
    }
}

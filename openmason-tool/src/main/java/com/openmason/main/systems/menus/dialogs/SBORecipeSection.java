package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.parts.MortarBadge;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;
import imgui.ImGui;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiKey;
import imgui.type.ImInt;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Recipes tab body for the SBO editor.
 *
 * <p>Edits the {@link SBOFormat.RecipeData} of an SBO: a list of shaped
 * recipes whose output is the SBO's own item. Each recipe is an N×M grid of
 * objectId slots plus an output count.
 *
 * <p>The tab is a MortarUI surface (with a plain-ImGui fallback when no Skija
 * context exists): recipes appear as selectable pills, the pattern is a
 * crafting-table grid of icon tiles flowing into an output tile, and a
 * "recent ingredients" palette strip supports hold-to-paint. Cell-level
 * copy/paste rides {@link RecipeClipboard} (Ctrl+C/V, context menu) and whole
 * recipes copy/paste across SBO editors via the same clipboard.
 *
 * <p>Pure UI — never touches disk. The owning editor reads the result via
 * {@link #toRecipeData()} on save and provides initial state via
 * {@link #setFromRecipeData}.
 */
public class SBORecipeSection implements AutoCloseable {

    /** Mutable working copy of one recipe. */
    private static final class EditableRecipe {
        int width = 3;
        int height = 3;
        int outputCount = 1;
        /** Always sized {@code 3 * 3} — slots beyond width/height are ignored on save. */
        final String[] slots = new String[9];

        EditableRecipe() {
            Arrays.fill(slots, "");
        }

        EditableRecipe(SBOFormat.ShapedRecipe r) {
            this.width = r.width();
            this.height = r.height();
            this.outputCount = Math.max(1, r.outputCount());
            Arrays.fill(slots, "");
            // Source pattern is row-major width*height; copy into the
            // top-left of our 3x3 working buffer.
            List<String> p = r.pattern();
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    slots[row * 3 + col] = p.get(row * width + col);
                }
            }
        }

        EditableRecipe(EditableRecipe other) {
            this.width = other.width;
            this.height = other.height;
            this.outputCount = other.outputCount;
            System.arraycopy(other.slots, 0, this.slots, 0, 9);
        }

        SBOFormat.ShapedRecipe toShaped() {
            List<String> flat = new ArrayList<>(width * height);
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    String slot = slots[row * 3 + col];
                    flat.add(slot == null ? "" : slot);
                }
            }
            return new SBOFormat.ShapedRecipe(width, height, flat, outputCount);
        }

        RecipeClipboard.PatternClip toClip() {
            return new RecipeClipboard.PatternClip(
                    width, height, new ArrayList<>(Arrays.asList(slots)), outputCount);
        }

        void applyClip(RecipeClipboard.PatternClip clip) {
            width = clip.width();
            height = clip.height();
            outputCount = clip.outputCount();
            for (int i = 0; i < 9; i++) {
                slots[i] = clip.slots().get(i);
            }
        }
    }

    // Grid geometry (Mortar surface).
    private static final float CELL = 56f;
    private static final float CELL_GAP = 8f;
    private static final float CELL_RADIUS = 8f;
    private static final float GRID_PAD = 4f;
    private static final float ARROW_ZONE = 44f;
    private static final float PILL_W = 92f;
    private static final float PILL_H = 26f;
    private static final float PILL_GAP = 6f;
    private static final float CHIP_W = 118f;
    private static final float CHIP_H = 26f;
    private static final float CHIP_GAP = 6f;

    private static final String CELL_CTX_POPUP = "##recipe_cell_ctx";
    private static final String PILL_CTX_POPUP = "##recipe_pill_ctx";

    private final List<EditableRecipe> recipes = new ArrayList<>();
    private final ImInt selected = new ImInt(0);
    private final SBOObjectPickerPopup picker = new SBOObjectPickerPopup();
    private final Runnable onDirty;
    /** objectId of the SBO being edited — the recipe's implicit output. May be null. */
    private final Supplier<String> selfObjectId;

    private final MortarRegion pillRegion = new MortarRegion();
    private final MortarRegion toolbarRegion = new MortarRegion();
    private final MortarRegion gridRegion = new MortarRegion();
    private final MortarRegion paletteRegion = new MortarRegion();

    /** Palette "held" ingredient painted into cells on click; null when idle. */
    private String heldIngredient;
    /** Cell index the open context menu targets. */
    private int contextCellIdx = -1;
    /** Recipe index the open pill context menu targets. */
    private int pillContextIdx = -1;
    /** Previous frame's hovered grid part id — drives the held-icon preview. */
    private String lastHoveredCell;

    /**
     * @param onDirty callback fired whenever the user mutates recipe state.
     *                Owning editor uses this to flip its dirty flag.
     */
    public SBORecipeSection(Runnable onDirty) {
        this(onDirty, null);
    }

    /**
     * @param onDirty      dirty callback, as above.
     * @param selfObjectId live supplier of the edited SBO's objectId, used to
     *                     draw the output tile's icon. Null-safe.
     */
    public SBORecipeSection(Runnable onDirty, Supplier<String> selfObjectId) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
        this.selfObjectId = selfObjectId != null ? selfObjectId : () -> "";
    }

    public void setFromRecipeData(SBOFormat.RecipeData data) {
        recipes.clear();
        if (data != null) {
            for (SBOFormat.ShapedRecipe r : data.shaped()) {
                recipes.add(new EditableRecipe(r));
            }
        }
        selected.set(0);
        heldIngredient = null;
    }

    /** Returns null when there are no recipes (so the manifest field stays absent). */
    public SBOFormat.RecipeData toRecipeData() {
        if (recipes.isEmpty()) return null;
        List<SBOFormat.ShapedRecipe> shaped = new ArrayList<>(recipes.size());
        for (EditableRecipe r : recipes) shaped.add(r.toShaped());
        return new SBOFormat.RecipeData(shaped);
    }

    public void render() {
        boolean mortar = gridRegion.isAvailable();

        renderRecipeBar(mortar);

        if (recipes.isEmpty()) {
            ImGui.dummy(0, 4);
            ImGui.textDisabled("No recipes yet - add one to define how this object is crafted.");
            picker.render();
            return;
        }
        if (selected.get() >= recipes.size()) selected.set(recipes.size() - 1);

        EditableRecipe r = recipes.get(selected.get());

        ImGui.dummy(0, 4);
        if (mortar) {
            renderToolbarMortar(r);
        } else {
            renderToolbarFallback(r);
        }
        ImGui.dummy(0, 6);

        if (mortar) {
            renderGridMortar(r);
        } else {
            renderGridFallback(r);
        }

        ImGui.dummy(0, 8);
        renderPalette(mortar);
        renderHeldBanner();

        // Esc releases the held ingredient (checked last so open popups that
        // consumed the frame's Escape have already closed themselves).
        if (heldIngredient != null && ImGui.isKeyPressed(ImGuiKey.Escape)) {
            heldIngredient = null;
        }

        renderCellContextMenu(r);
        renderPillContextMenu();

        // Picker is a singleton popup — must render every frame regardless of
        // whether a cell triggered open() this frame.
        picker.render();
    }

    // ---- recipe pill bar ---------------------------------------------------

    private void renderRecipeBar(boolean mortar) {
        if (!mortar) {
            // Plain ImGui fallback: combo + add/delete buttons.
            if (ImGui.button("+ Add Recipe")) addRecipe();
            if (recipes.isEmpty()) return;
            ImGui.sameLine();
            String[] labels = new String[recipes.size()];
            for (int i = 0; i < recipes.size(); i++) labels[i] = "Recipe " + (i + 1);
            ImGui.pushItemWidth(160);
            ImGui.combo("##recipe_picker", selected, labels);
            ImGui.popItemWidth();
            ImGui.sameLine();
            if (ImGui.button("Delete Recipe")) deleteRecipe(selected.get());
            return;
        }

        float availW = Math.max(1f, ImGui.getContentRegionAvailX());
        int perRow = Math.max(1, (int) ((availW + PILL_GAP) / (PILL_W + PILL_GAP)));
        // The trailing "+" pill occupies slot index recipes.size().
        int totalSlots = recipes.size() + 1;
        int rows = (totalSlots + perRow - 1) / perRow;
        float height = rows * (PILL_H + PILL_GAP) - PILL_GAP + 2f;

        pillRegion.begin(availW, height);
        for (int i = 0; i < recipes.size(); i++) {
            float x = (i % perRow) * (PILL_W + PILL_GAP);
            float y = (float) (i / perRow) * (PILL_H + PILL_GAP);
            final String label = "Recipe " + (i + 1);
            pillRegion.add("pill." + i, x, y, PILL_W, PILL_H, i == selected.get(),
                    (g, px, py, pw, ph, state) -> paintPill(g, px, py, pw, ph, state, label));
        }
        int n = recipes.size();
        float addX = (n % perRow) * (PILL_W + PILL_GAP);
        float addY = (float) (n / perRow) * (PILL_H + PILL_GAP);
        pillRegion.add("pill.add", addX, addY, PILL_H + 6f, PILL_H,
                (g, px, py, pw, ph, state) -> paintAddPill(g, px, py, pw, ph, state));

        MortarFrameResult input = pillRegion.render();
        String clicked = input.clicked();
        if (clicked != null) {
            if (clicked.equals("pill.add")) {
                addRecipe();
            } else if (clicked.startsWith("pill.")) {
                selected.set(Integer.parseInt(clicked.substring(5)));
            }
        }
        String rightClicked = input.rightClicked();
        if (rightClicked != null && rightClicked.startsWith("pill.") && !rightClicked.equals("pill.add")) {
            pillContextIdx = Integer.parseInt(rightClicked.substring(5));
            ImGui.openPopup(PILL_CTX_POPUP);
        }
        pillRegion.update(ImGui.getIO().getDeltaTime());
    }

    private static void paintPill(MortarPainter g, float x, float y, float w, float h,
                                  PartState state, String label) {
        float sel = state.selected();
        float hover = state.hover();
        float press = state.press();

        int fill = Argb.lerp(g.theme().surface, g.theme().surfaceHover, hover);
        fill = Argb.lerp(fill, g.theme().accent, sel);
        fill = Argb.shade(fill, -0.06f * press);
        g.fillRoundRect(x, y, w, h, h / 2f, fill);
        if (sel < 0.5f) {
            g.strokeRoundRect(x, y, w, h, h / 2f, 1f, g.theme().border);
        }
        int textColor = Argb.lerp(g.theme().textDim, 0xFFFFFFFF, sel);
        textColor = Argb.lerp(textColor, g.theme().text, Math.max(0f, hover - sel));
        g.text(label, x + w / 2f, y + h / 2f, MortarPainter.Align.CENTER,
                Weight.MEDIUM, 12f, textColor);
    }

    private static void paintAddPill(MortarPainter g, float x, float y, float w, float h,
                                     PartState state) {
        float hover = state.hover();
        int fill = Argb.lerp(Argb.withAlpha(g.theme().surface, 0.6f), g.theme().surfaceHover, hover);
        g.fillRoundRect(x, y, w, h, h / 2f, fill);
        g.strokeRoundRect(x, y, w, h, h / 2f, 1f,
                Argb.lerp(g.theme().border, g.theme().borderStrong, hover));
        g.text("+", x + w / 2f, y + h / 2f, MortarPainter.Align.CENTER,
                Weight.MEDIUM, 14f, Argb.lerp(g.theme().textDim, g.theme().text, hover));
    }

    // ---- toolbar -----------------------------------------------------------

    // Toolbar geometry (Mortar strip).
    private static final float TB_H = 26f;
    private static final float TB_GAP = 10f;
    private static final float SEG_W = 22f;
    private static final float VAL_W = 30f;
    private static final float TB_LABEL_PAD = 10f;

    /**
     * The recipe toolbar as one Mortar strip: segmented [label − value +]
     * stepper pills for grid size and output count, a hairline divide, then
     * secondary action pills (Copy / Paste / Duplicate / Delete). Wraps onto a
     * second row when the panel is narrow.
     */
    private void renderToolbarMortar(EditableRecipe r) {
        float availW = Math.max(1f, ImGui.getContentRegionAvailX());

        float wGroup = stepperGroupWidth("Width");
        float hGroup = stepperGroupWidth("Height");
        float oGroup = stepperGroupWidth("Output");
        float steppersW = wGroup + hGroup + oGroup + TB_GAP * 2;

        float copyW = 56f, pasteW = 58f, dupW = 92f, delW = 62f;
        float actionsW = copyW + pasteW + dupW + delW + 6f * 3;

        boolean twoRows = steppersW + TB_GAP + 12f + actionsW > availW;
        float height = twoRows ? TB_H * 2 + 6f : TB_H;

        toolbarRegion.begin(availW, height);

        float x = 0f;
        x = addStepperGroup("w", x, 0f, "Width", r.width, 1, 3);
        x += TB_GAP;
        x = addStepperGroup("h", x, 0f, "Height", r.height, 1, 3);
        x += TB_GAP;
        x = addStepperGroup("o", x, 0f, "Output", r.outputCount, 1, Integer.MAX_VALUE);

        float ax;
        float ay;
        if (twoRows) {
            ax = 0f;
            ay = TB_H + 6f;
        } else {
            // Hairline divide between the steppers and the actions.
            float sepX = x + TB_GAP;
            toolbarRegion.add("deco.sep", sepX, 4f, 1f, TB_H - 8f,
                    (g, px, py, pw, ph, state) -> g.fillRect(px, py, 1f, ph, g.theme().separator));
            ax = sepX + 12f;
            ay = 0f;
        }

        boolean canPaste = RecipeClipboard.hasPattern();
        addActionPill("act.copy", ax, ay, copyW, "Copy", true);
        addActionPill("act.paste", ax + copyW + 6f, ay, pasteW, "Paste", canPaste);
        addActionPill("act.dup", ax + copyW + pasteW + 12f, ay, dupW, "Duplicate", true);
        addActionPill("act.del", ax + copyW + pasteW + dupW + 18f, ay, delW, "Delete", true);

        MortarFrameResult input = toolbarRegion.render();
        handleToolbarInput(r, input, canPaste);
        toolbarRegion.update(ImGui.getIO().getDeltaTime());
    }

    private static float stepperGroupWidth(String label) {
        // JetBrains Mono at 11px is ~6.8px/char; padding on both sides.
        return TB_LABEL_PAD + label.length() * 6.8f + 6f + SEG_W + VAL_W + SEG_W;
    }

    /**
     * One [label − value +] pill: a rounded surface group whose −/+ segments
     * are their own hit-tested parts. Returns the x just past the group.
     */
    private float addStepperGroup(String prefix, float x, float y, String label,
                                  int value, int min, int max) {
        float groupW = stepperGroupWidth(label);
        float labelZone = TB_LABEL_PAD + label.length() * 6.8f + 6f;
        float decX = x + labelZone;
        float valX = decX + SEG_W;
        float incX = valX + VAL_W;

        toolbarRegion.add(prefix + ".bg", x, y, groupW, TB_H, (g, px, py, pw, ph, state) -> {
            g.fillRoundRect(px, py, pw, ph, ph / 2f, Argb.withAlpha(g.theme().surface, 0.75f));
            g.strokeRoundRect(px, py, pw, ph, ph / 2f, 1f, g.theme().border);
            g.text(label, px + TB_LABEL_PAD, py + ph / 2f, MortarPainter.Align.LEFT,
                    Weight.REGULAR, 11f, g.theme().textDim);
        });
        boolean canDec = value > min;
        boolean canInc = value < max;
        toolbarRegion.add(prefix + ".dec", decX, y, SEG_W, TB_H, (g, px, py, pw, ph, state) ->
                paintStepperSegment(g, px, py, pw, ph, state, "−", canDec));
        toolbarRegion.add(prefix + ".val", valX, y, VAL_W, TB_H, (g, px, py, pw, ph, state) ->
                g.text(String.valueOf(value), px + pw / 2f, py + ph / 2f,
                        MortarPainter.Align.CENTER, Weight.MEDIUM, 12.5f, g.theme().text));
        toolbarRegion.add(prefix + ".inc", incX, y, SEG_W, TB_H, (g, px, py, pw, ph, state) ->
                paintStepperSegment(g, px, py, pw, ph, state, "+", canInc));
        return x + groupW;
    }

    private static void paintStepperSegment(MortarPainter g, float x, float y, float w, float h,
                                            PartState state, String glyph, boolean enabled) {
        float hover = enabled ? state.hover() : 0f;
        float press = enabled ? state.press() : 0f;
        if (hover > 0.02f) {
            g.fillRoundRect(x + 2f, y + 3f, w - 4f, h - 6f, (h - 6f) / 2f,
                    Argb.withAlpha(g.theme().surfaceHover, hover));
        }
        int color = enabled
                ? Argb.lerp(g.theme().textDim, g.theme().text, Math.max(hover, press))
                : g.theme().textFaint;
        g.text(glyph, x + w / 2f, y + h / 2f, MortarPainter.Align.CENTER,
                Weight.MEDIUM, 13f, color);
    }

    /** A secondary action pill in the MortarButton idiom; dims when disabled. */
    private void addActionPill(String id, float x, float y, float w, String label, boolean enabled) {
        toolbarRegion.add(id, x, y, w, TB_H, (g, px, py, pw, ph, state) -> {
            float hover = enabled ? state.hover() : 0f;
            float press = enabled ? state.press() : 0f;
            float inset = press;
            float bx = px + inset;
            float by = py + inset;
            float bw = pw - inset * 2f;
            float bh = ph - inset * 2f;

            int fill = Argb.lerp(g.theme().surface, g.theme().surfaceHover, hover);
            fill = Argb.shade(fill, -0.05f * press);
            if (!enabled) fill = Argb.withAlpha(g.theme().surface, 0.45f);
            g.fillRoundRect(bx, by, bw, bh, bh / 2f, fill);
            g.strokeRoundRect(bx, by, bw, bh, bh / 2f, 1f,
                    enabled ? Argb.lerp(g.theme().border, g.theme().borderStrong, hover * 0.6f)
                            : Argb.withAlpha(g.theme().border, 0.5f));
            g.text(label, bx + bw / 2f, by + bh / 2f, MortarPainter.Align.CENTER,
                    Weight.MEDIUM, 12f, enabled
                            ? Argb.lerp(g.theme().textDim, g.theme().text, hover)
                            : g.theme().textFaint);
        });
    }

    private void handleToolbarInput(EditableRecipe r, MortarFrameResult input, boolean canPaste) {
        String hovered = input.hovered();
        if (hovered != null) {
            switch (hovered) {
                case "act.copy" -> ImGui.setTooltip("Copy this whole recipe.\nPaste it into any SBO's recipe editor.");
                case "act.paste" -> ImGui.setTooltip(canPaste
                        ? "Replace this recipe with the copied one"
                        : "Nothing copied yet");
                case "act.dup" -> ImGui.setTooltip("Add a copy of this recipe");
                case "act.del" -> ImGui.setTooltip("Delete this recipe");
                case "o.dec", "o.inc" -> ImGui.setTooltip("Output count - Shift+Click steps by 10");
                default -> { }
            }
        }

        String clicked = input.clicked();
        if (clicked == null) return;
        boolean shift = ImGui.getIO().getKeyShift();
        int step = shift ? 10 : 1;
        switch (clicked) {
            case "w.dec" -> { if (r.width > 1) { r.width--; onDirty.run(); } }
            case "w.inc" -> { if (r.width < 3) { r.width++; onDirty.run(); } }
            case "h.dec" -> { if (r.height > 1) { r.height--; onDirty.run(); } }
            case "h.inc" -> { if (r.height < 3) { r.height++; onDirty.run(); } }
            case "o.dec" -> {
                int next = Math.max(1, r.outputCount - step);
                if (next != r.outputCount) { r.outputCount = next; onDirty.run(); }
            }
            case "o.inc" -> { r.outputCount += step; onDirty.run(); }
            case "act.copy" -> RecipeClipboard.copyPattern(r.toClip());
            case "act.paste" -> {
                if (canPaste) {
                    r.applyClip(RecipeClipboard.pattern());
                    onDirty.run();
                }
            }
            case "act.dup" -> {
                recipes.add(selected.get() + 1, new EditableRecipe(r));
                selected.set(selected.get() + 1);
                onDirty.run();
            }
            case "act.del" -> deleteRecipe(selected.get());
            default -> { }
        }
    }

    /** Plain-ImGui toolbar for when no Skija context exists. */
    private void renderToolbarFallback(EditableRecipe r) {
        int w = stepper("Width", r.width, 1, 3);
        if (w != r.width) {
            r.width = w;
            onDirty.run();
        }
        ImGui.sameLine();
        ImGui.textDisabled(" x ");
        ImGui.sameLine();
        int h = stepper("Height", r.height, 1, 3);
        if (h != r.height) {
            r.height = h;
            onDirty.run();
        }

        ImGui.sameLine();
        ImGui.textDisabled("  |  ");
        ImGui.sameLine();
        ImGui.text("Output");
        ImGui.sameLine();
        ImInt count = new ImInt(r.outputCount);
        ImGui.pushItemWidth(84);
        if (ImGui.inputInt("##output_count", count)) {
            r.outputCount = Math.max(1, count.get());
            onDirty.run();
        }
        ImGui.popItemWidth();

        ImGui.sameLine();
        ImGui.textDisabled("  |  ");
        ImGui.sameLine();
        if (ImGui.smallButton("Copy")) {
            RecipeClipboard.copyPattern(r.toClip());
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Copy this whole recipe. Paste it into any SBO's recipe editor.");
        }
        ImGui.sameLine();
        boolean canPaste = RecipeClipboard.hasPattern();
        if (!canPaste) ImGui.beginDisabled();
        if (ImGui.smallButton("Paste")) {
            r.applyClip(RecipeClipboard.pattern());
            onDirty.run();
        }
        if (!canPaste) ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.smallButton("Duplicate")) {
            recipes.add(selected.get() + 1, new EditableRecipe(r));
            selected.set(selected.get() + 1);
            onDirty.run();
        }
        ImGui.sameLine();
        if (ImGui.smallButton("Delete")) {
            deleteRecipe(selected.get());
        }
    }

    /** Compact ‹ value › stepper. Returns the (possibly unchanged) new value. */
    private static int stepper(String id, int value, int min, int max) {
        ImGui.pushID("stepper_" + id);
        ImGui.text(id);
        ImGui.sameLine();
        if (ImGui.arrowButton("dec", ImGuiDir.Left) && value > min) value--;
        ImGui.sameLine();
        ImGui.text(String.valueOf(value));
        ImGui.sameLine();
        if (ImGui.arrowButton("inc", ImGuiDir.Right) && value < max) value++;
        ImGui.popID();
        return value;
    }

    // ---- pattern grid (Mortar) ----------------------------------------------

    private void renderGridMortar(EditableRecipe r) {
        ImGui.text("Pattern");
        ImGui.sameLine();
        ImGui.textDisabled("(click a cell to pick - right-click for copy/paste - dim cells grow the grid)");

        float gridW = 3 * CELL + 2 * CELL_GAP;
        float gridH = 3 * CELL + 2 * CELL_GAP;
        float outputX = GRID_PAD + gridW + ARROW_ZONE + 8f;
        float regionW = outputX + CELL + GRID_PAD;
        float regionH = gridH + GRID_PAD * 2;

        gridRegion.begin(regionW, regionH);

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                float x = GRID_PAD + col * (CELL + CELL_GAP);
                float y = GRID_PAD + row * (CELL + CELL_GAP);
                boolean active = row < r.height && col < r.width;
                String slot = r.slots[idx] == null ? "" : r.slots[idx];
                String id = "cell." + idx;
                boolean previewHeld = heldIngredient != null && id.equals(lastHoveredCell) && active;
                String held = heldIngredient;
                gridRegion.add(id, x, y, CELL, CELL,
                        (g, px, py, pw, ph, state) ->
                                paintCell(g, px, py, pw, ph, state, slot, active, previewHeld ? held : null));
            }
        }

        // Arrow + output tile (decorative — not hit-tested targets we act on).
        float arrowCx = GRID_PAD + gridW + ARROW_ZONE / 2f + 4f;
        float centerY = GRID_PAD + gridH / 2f;
        String self = selfObjectId.get();
        int outCount = r.outputCount;
        gridRegion.add("deco.arrow", arrowCx - ARROW_ZONE / 2f, centerY - 14f, ARROW_ZONE, 28f,
                (g, px, py, pw, ph, state) ->
                        g.text("→", px + pw / 2f, py + ph / 2f, MortarPainter.Align.CENTER,
                                Weight.MEDIUM, 20f, g.theme().textDim));
        gridRegion.add("deco.out", outputX, centerY - CELL / 2f, CELL, CELL,
                (g, px, py, pw, ph, state) -> paintOutputTile(g, px, py, pw, ph, self, outCount));

        MortarFrameResult input = gridRegion.render();
        lastHoveredCell = input.hovered();
        handleGridInput(r, input);
        gridRegion.update(ImGui.getIO().getDeltaTime());
    }

    private void paintCell(MortarPainter g, float x, float y, float w, float h, PartState state,
                           String slot, boolean active, String previewHeld) {
        float hover = active ? state.hover() : state.hover() * 0.5f;
        float press = state.press();
        boolean filled = !slot.isEmpty();

        if (!active) {
            // Outside the recipe's declared size: faint ghost tile. Hovering
            // hints that clicking grows the grid.
            int fill = Argb.withAlpha(g.theme().surface, 0.30f + 0.15f * hover);
            g.fillRoundRect(x, y, w, h, CELL_RADIUS, fill);
            g.strokeRoundRect(x, y, w, h, CELL_RADIUS, 1f, Argb.withAlpha(g.theme().border, 0.5f));
            if (hover > 0.05f) {
                g.text("+", x + w / 2f, y + h / 2f, MortarPainter.Align.CENTER,
                        Weight.REGULAR, 15f, Argb.withAlpha(g.theme().textFaint, hover));
            }
            return;
        }

        float inset = press; // 1px settle when held down
        float bx = x + inset;
        float by = y + inset;
        float bw = w - inset * 2f;
        float bh = h - inset * 2f;

        int fill = Argb.lerp(Argb.shade(g.theme().surface, -0.03f), g.theme().surfaceHover, hover);
        g.fillRoundRect(bx, by, bw, bh, CELL_RADIUS, fill);
        int border = filled
                ? Argb.lerp(g.theme().border, g.theme().borderStrong, hover)
                : Argb.lerp(Argb.withAlpha(g.theme().border, 0.8f), g.theme().borderStrong, hover * 0.7f);
        g.strokeRoundRect(bx, by, bw, bh, CELL_RADIUS, 1f, border);

        if (filled) {
            drawIngredient(g, slot, bx, by, bw, bh, 1f);
        } else if (previewHeld == null) {
            g.text("+", bx + bw / 2f, by + bh / 2f, MortarPainter.Align.CENTER,
                    Weight.REGULAR, 16f,
                    Argb.lerp(g.theme().textFaint, g.theme().textDim, hover));
        }

        if (previewHeld != null) {
            // Ghost of the held ingredient over the hovered cell.
            drawIngredient(g, previewHeld, bx, by, bw, bh, 0.45f);
        }
    }

    /** Icon (or short-label text when no icon exists) centered in a cell. */
    private static void drawIngredient(MortarPainter g, String objectId,
                                       float x, float y, float w, float h, float alpha) {
        Image icon = SBOIngredientIcons.skijaIcon(objectId);
        if (icon != null) {
            Rect dst = Rect.makeXYWH(x + 8f, y + 8f, w - 16f, h - 16f);
            if (alpha >= 1f) {
                g.canvas().drawImageRect(icon, dst);
            } else {
                try (Paint p = new Paint()) {
                    p.setAlphaf(alpha);
                    g.canvas().drawImageRect(icon, dst, p);
                }
            }
        } else {
            int color = alpha >= 1f ? g.theme().text : Argb.withAlpha(g.theme().text, alpha);
            g.textEllipsized(shortLabel(objectId), x + 5f, y + h / 2f, w - 10f,
                    Weight.REGULAR, 10.5f, color);
        }
    }

    private static void paintOutputTile(MortarPainter g, float x, float y, float w, float h,
                                        String selfId, int outputCount) {
        g.fillRoundRect(x, y, w, h, CELL_RADIUS, Argb.shade(g.theme().surface, 0.04f));
        g.strokeRoundRect(x, y, w, h, CELL_RADIUS, 1.4f, Argb.withAlpha(g.theme().accent, 0.55f));
        if (selfId != null && !selfId.isEmpty()) {
            drawIngredient(g, selfId, x, y, w, h, 1f);
        }
        if (outputCount > 1) {
            String badge = "x" + outputCount;
            float badgeW = MortarBadge.measureWidth(g, badge);
            MortarBadge.paint(g, x + w - badgeW - 3f, y + h - 12f, badge);
        }
    }

    private void handleGridInput(EditableRecipe r, MortarFrameResult input) {
        Integer hoveredIdx = cellIndex(input.hovered());
        if (hoveredIdx != null) {
            boolean active = isActive(r, hoveredIdx);
            String slot = r.slots[hoveredIdx] == null ? "" : r.slots[hoveredIdx];
            if (active && !slot.isEmpty()) {
                ImGui.setTooltip(slot + "\nCtrl+C copy - Ctrl+V paste - Del clear");
            } else if (!active) {
                ImGui.setTooltip("Click to grow the grid to " + (hoveredIdx % 3 + 1)
                        + "x" + (hoveredIdx / 3 + 1));
            }
            if (active) {
                handleCellKeyboard(r, hoveredIdx, slot);
            }
        }

        Integer clickedIdx = cellIndex(input.clicked());
        if (clickedIdx != null) {
            handleCellClick(r, clickedIdx);
        }

        Integer rightIdx = cellIndex(input.rightClicked());
        if (rightIdx != null && isActive(r, rightIdx)) {
            contextCellIdx = rightIdx;
            ImGui.openPopup(CELL_CTX_POPUP);
        }
    }

    private void handleCellKeyboard(EditableRecipe r, int idx, String slot) {
        boolean ctrl = ImGui.getIO().getKeyCtrl();
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.C) && !slot.isEmpty()) {
            RecipeClipboard.copyIngredient(slot);
        }
        if (ctrl && ImGui.isKeyPressed(ImGuiKey.V) && RecipeClipboard.hasIngredient()) {
            setCell(r, idx, RecipeClipboard.ingredient());
        }
        if (ImGui.isKeyPressed(ImGuiKey.Delete) || ImGui.isKeyPressed(ImGuiKey.Backspace)) {
            if (!slot.isEmpty()) setCell(r, idx, "");
        }
    }

    private void handleCellClick(EditableRecipe r, int idx) {
        int col = idx % 3;
        int row = idx / 3;
        if (!isActive(r, idx)) {
            // Grow the recipe to include the clicked cell.
            r.width = Math.max(r.width, col + 1);
            r.height = Math.max(r.height, row + 1);
            onDirty.run();
            return;
        }
        if (heldIngredient != null) {
            setCell(r, idx, heldIngredient);
            return;
        }
        if (ImGui.getIO().getKeyCtrl() && RecipeClipboard.hasIngredient()) {
            setCell(r, idx, RecipeClipboard.ingredient());
            return;
        }
        openPickerFor(r, idx);
    }

    private void openPickerFor(EditableRecipe r, int idx) {
        picker.open(picked -> {
            r.slots[idx] = picked;
            onDirty.run();
        });
    }

    private void setCell(EditableRecipe r, int idx, String objectId) {
        String value = objectId == null ? "" : objectId;
        if (value.equals(r.slots[idx])) return;
        r.slots[idx] = value;
        if (!value.isEmpty()) IngredientMru.shared().touch(value);
        onDirty.run();
    }

    private static boolean isActive(EditableRecipe r, int idx) {
        return (idx / 3) < r.height && (idx % 3) < r.width;
    }

    /** "cell.4" -> 4; null for any other (or null) part id. */
    private static Integer cellIndex(String partId) {
        if (partId == null || !partId.startsWith("cell.")) return null;
        try {
            return Integer.parseInt(partId.substring(5));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- pattern grid (ImGui fallback) ---------------------------------------

    private void renderGridFallback(EditableRecipe r) {
        ImGui.text("Pattern");
        ImGui.sameLine();
        ImGui.textDisabled("(click a cell to pick - right-click for copy/paste - dim cells grow the grid)");
        ImGui.dummy(0, 4);

        imgui.ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.ItemSpacing, CELL_GAP, CELL_GAP);
        imgui.ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.FrameRounding, 6.0f);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int idx = row * 3 + col;
                boolean active = isActive(r, idx);
                String slot = r.slots[idx] == null ? "" : r.slots[idx];

                ImGui.pushID("cell_" + idx);
                boolean clicked;
                if (!active) {
                    ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.Alpha, 0.35f);
                    clicked = ImGui.button("##inactive", CELL, CELL);
                    ImGui.popStyleVar();
                    if (ImGui.isItemHovered()) {
                        ImGui.setTooltip("Click to grow the grid to " + (col + 1) + "x" + (row + 1));
                    }
                } else {
                    int icon = slot.isEmpty() ? 0 : SBOIngredientIcons.glIcon(slot);
                    if (icon > 0) {
                        clicked = ImGui.imageButton("##cimg", icon, CELL - 12, CELL - 12);
                    } else {
                        clicked = ImGui.button(slot.isEmpty() ? "+" : shortLabel(slot), CELL, CELL);
                    }
                    if (ImGui.isItemHovered() && !slot.isEmpty()) {
                        ImGui.setTooltip(slot + "\nCtrl+C copy - Ctrl+V paste - Del clear");
                    }
                    if (ImGui.isItemHovered()) {
                        handleCellKeyboard(r, idx, slot);
                    }
                    if (ImGui.isItemClicked(1)) {
                        contextCellIdx = idx;
                        ImGui.openPopup(CELL_CTX_POPUP);
                    }
                }
                if (clicked) {
                    handleCellClick(r, idx);
                }
                ImGui.popID();
                if (col < 2) ImGui.sameLine();
            }
            if (row == 1) {
                // Arrow + output hint on the middle row.
                ImGui.sameLine();
                ImGui.text("  ->  ");
                ImGui.sameLine();
                String self = selfObjectId.get();
                int outIcon = self.isEmpty() ? 0 : SBOIngredientIcons.glIcon(self);
                if (outIcon > 0) {
                    ImGui.image(outIcon, CELL - 12, CELL - 12);
                    ImGui.sameLine();
                }
                ImGui.text("x" + r.outputCount);
            }
        }
        imgui.ImGui.popStyleVar(2);
    }

    // ---- recent-ingredients palette ------------------------------------------

    private void renderPalette(boolean mortar) {
        List<String> recents = IngredientMru.shared().list();
        ImGui.text("Recent ingredients");
        ImGui.sameLine();
        ImGui.textDisabled("(click a chip to hold it, then click cells to place)");
        if (recents.isEmpty()) {
            ImGui.textDisabled("Pick ingredients to build your palette.");
            return;
        }

        if (!mortar) {
            renderPaletteFallback(recents);
            return;
        }

        float availW = Math.max(1f, ImGui.getContentRegionAvailX());
        int fit = Math.max(1, (int) ((availW + CHIP_GAP) / (CHIP_W + CHIP_GAP)));
        int count = Math.min(recents.size(), fit);

        paletteRegion.begin(availW, CHIP_H + 2f);
        for (int i = 0; i < count; i++) {
            String id = recents.get(i);
            float x = i * (CHIP_W + CHIP_GAP);
            boolean held = id.equals(heldIngredient);
            paletteRegion.add("chip." + i, x, 0f, CHIP_W, CHIP_H, held,
                    (g, px, py, pw, ph, state) -> paintChip(g, px, py, pw, ph, state, id));
        }
        MortarFrameResult input = paletteRegion.render();
        String hovered = input.hovered();
        if (hovered != null && hovered.startsWith("chip.")) {
            int i = Integer.parseInt(hovered.substring(5));
            if (i < count) ImGui.setTooltip(recents.get(i));
        }
        String clicked = input.clicked();
        if (clicked != null && clicked.startsWith("chip.")) {
            int i = Integer.parseInt(clicked.substring(5));
            if (i < count) {
                String id = recents.get(i);
                heldIngredient = id.equals(heldIngredient) ? null : id;
            }
        }
        paletteRegion.update(ImGui.getIO().getDeltaTime());
    }

    private static void paintChip(MortarPainter g, float x, float y, float w, float h,
                                  PartState state, String objectId) {
        float hover = state.hover();
        float sel = state.selected();

        int fill = Argb.lerp(g.theme().surface, g.theme().surfaceHover, hover);
        fill = Argb.lerp(fill, Argb.withAlpha(g.theme().accent, 0.25f), sel);
        g.fillRoundRect(x, y, w, h, h / 2f, fill);
        int border = Argb.lerp(g.theme().border, g.theme().borderStrong, Math.max(hover * 0.6f, sel));
        g.strokeRoundRect(x, y, w, h, h / 2f, sel > 0.5f ? 1.5f : 1f, border);

        float iconSize = h - 8f;
        Image icon = SBOIngredientIcons.skijaIcon(objectId);
        if (icon != null) {
            g.canvas().drawImageRect(icon, Rect.makeXYWH(x + 6f, y + 4f, iconSize, iconSize));
        }
        float textX = x + 6f + iconSize + 5f;
        g.textEllipsized(shortLabel(objectId), textX, y + h / 2f, x + w - textX - 8f,
                Weight.REGULAR, 11f, Argb.lerp(g.theme().textDim, g.theme().text, Math.max(hover, sel)));
    }

    private void renderPaletteFallback(List<String> recents) {
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.ItemSpacing, 4, 4);
        float avail = ImGui.getContentRegionAvailX();
        float used = 0;
        for (int i = 0; i < recents.size(); i++) {
            String id = recents.get(i);
            if (used + 34 > avail && used > 0) break;
            if (i > 0) ImGui.sameLine();
            used += 34;
            ImGui.pushID("pal_" + i);
            boolean held = id.equals(heldIngredient);
            int icon = SBOIngredientIcons.glIcon(id);
            boolean clicked = icon > 0
                    ? ImGui.imageButton("##pimg", icon, 24, 24)
                    : ImGui.button(shortLabel(id).substring(0, 1).toUpperCase(), 30, 30);
            if (ImGui.isItemHovered()) ImGui.setTooltip(id + (held ? " (held)" : ""));
            if (clicked) heldIngredient = held ? null : id;
            ImGui.popID();
        }
        ImGui.popStyleVar();
    }

    private void renderHeldBanner() {
        if (heldIngredient == null) return;
        ImGui.dummy(0, 2);
        ImGui.text("Holding:");
        ImGui.sameLine();
        int icon = SBOIngredientIcons.glIcon(heldIngredient);
        if (icon > 0) {
            ImGui.image(icon, 16, 16);
            ImGui.sameLine();
        }
        ImGui.text(heldIngredient);
        ImGui.sameLine();
        ImGui.textDisabled(" - click cells to place, Esc to release ");
        ImGui.sameLine();
        if (ImGui.smallButton("Release")) {
            heldIngredient = null;
        }
    }

    // ---- context menus --------------------------------------------------------

    private void renderCellContextMenu(EditableRecipe r) {
        if (!ImGui.beginPopup(CELL_CTX_POPUP)) return;
        int idx = contextCellIdx;
        if (idx < 0 || idx > 8) {
            ImGui.endPopup();
            return;
        }
        String slot = r.slots[idx] == null ? "" : r.slots[idx];
        boolean filled = !slot.isEmpty();

        if (ImGui.menuItem("Pick ingredient...")) {
            openPickerFor(r, idx);
        }
        if (ImGui.menuItem("Copy", "Ctrl+C", false, filled)) {
            RecipeClipboard.copyIngredient(slot);
        }
        String pasteLabel = RecipeClipboard.hasIngredient()
                ? "Paste " + shortLabel(RecipeClipboard.ingredient())
                : "Paste";
        if (ImGui.menuItem(pasteLabel, "Ctrl+V", false, RecipeClipboard.hasIngredient())) {
            setCell(r, idx, RecipeClipboard.ingredient());
        }
        if (ImGui.menuItem("Hold for painting", null, false, filled)) {
            heldIngredient = slot;
        }
        ImGui.separator();
        if (ImGui.menuItem("Clear", "Del", false, filled)) {
            setCell(r, idx, "");
        }
        ImGui.endPopup();
    }

    private void renderPillContextMenu() {
        if (!ImGui.beginPopup(PILL_CTX_POPUP)) return;
        int idx = pillContextIdx;
        if (idx < 0 || idx >= recipes.size()) {
            ImGui.endPopup();
            return;
        }
        if (ImGui.menuItem("Duplicate")) {
            recipes.add(idx + 1, new EditableRecipe(recipes.get(idx)));
            selected.set(idx + 1);
            onDirty.run();
        }
        if (ImGui.menuItem("Copy recipe")) {
            RecipeClipboard.copyPattern(recipes.get(idx).toClip());
        }
        ImGui.separator();
        if (ImGui.menuItem("Delete")) {
            deleteRecipe(idx);
        }
        ImGui.endPopup();
    }

    // ---- mutations --------------------------------------------------------------

    private void addRecipe() {
        recipes.add(new EditableRecipe());
        selected.set(recipes.size() - 1);
        onDirty.run();
    }

    private void deleteRecipe(int idx) {
        if (idx < 0 || idx >= recipes.size()) return;
        recipes.remove(idx);
        selected.set(Math.max(0, Math.min(selected.get(), recipes.size() - 1)));
        onDirty.run();
    }

    /** Render a slot's objectId compactly (drop namespace). */
    private static String shortLabel(String objectId) {
        if (objectId == null) return "";
        int colon = objectId.indexOf(':');
        return colon >= 0 ? objectId.substring(colon + 1) : objectId;
    }

    /** Test/util hook — allows clearing all recipes (e.g. on editor close). */
    public void clear(Consumer<Void> ack) {
        recipes.clear();
        selected.set(0);
        heldIngredient = null;
        if (ack != null) ack.accept(null);
    }

    /** Release the Mortar Skija regions. Must run before the SkijaContext closes. */
    @Override
    public void close() {
        pillRegion.close();
        toolbarRegion.close();
        gridRegion.close();
        paletteRegion.close();
    }
}

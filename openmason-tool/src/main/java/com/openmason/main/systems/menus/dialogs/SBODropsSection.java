package com.openmason.main.systems.menus.dialogs;

import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.core.MortarRegionPool;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.parts.MortarBadge;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImInt;
import io.github.humbleui.skija.Image;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drops tab body for the SBO editor.
 *
 * <p>Edits {@link SBOFormat.DropData} (SBO 1.8+): what this block yields when
 * broken. The section has two parts:
 * <ul>
 *   <li><b>Default drops</b> — one card per drop line (item tile, name, count
 *       range, chance), used whenever no tool override matches — including
 *       breaking by hand.</li>
 *   <li><b>Tool overrides</b> — one group per tool objectId whose cards
 *       <em>replace</em> the default list when the block is broken with that
 *       tool. An override with no cards means "this tool yields nothing".</li>
 * </ul>
 *
 * <p>Presence is meaningful: an enabled-but-empty table means the block drops
 * nothing, while a disabled table ("use game default") leaves the manifest
 * field absent so the game's built-in rule (drop itself) applies.
 *
 * <p>Like {@link SoundsEditor}, each card is a MortarUI surface (icon tile,
 * title, summary pill, remove) with the editable count/chance sliders as ImGui
 * widgets beneath; a plain-ImGui fallback covers contexts without Skija.
 * Pure UI — never touches disk. The owning editor reads the result via
 * {@link #toDropData()} on save and provides initial state via
 * {@link #setFromDropData}. Item tiles share the ingredient picker, icon cache
 * and recent-ingredients MRU with the Recipes/Smelting tabs.
 */
public class SBODropsSection implements AutoCloseable {

    // Card geometry.
    private static final float CARD_H = 48f;
    private static final float TILE = 36f;
    private static final float TILE_PAD = (CARD_H - TILE) / 2f;
    private static final float REMOVE_W = 26f;
    private static final float CARD_RADIUS = 8f;
    private static final float TILE_RADIUS = 7f;
    private static final float INDENT = 18f;

    /** Mutable working copy of one drop line. */
    private static final class EditableDrop {
        String objectId = "";
        final ImInt min = new ImInt(1);
        final ImInt max = new ImInt(1);
        /** Percent, 0..100 — what the slider edits. */
        final ImFloat chancePct = new ImFloat(100f);

        EditableDrop() {}

        EditableDrop(SBOFormat.DropEntry e) {
            this.objectId = e.objectId();
            this.min.set(e.minCount());
            this.max.set(e.maxCount());
            this.chancePct.set(e.chance() * 100f);
        }

        SBOFormat.DropEntry toEntry() {
            int lo = Math.max(0, min.get());
            int hi = Math.max(lo, max.get());
            float ch = Math.max(0f, Math.min(100f, chancePct.get())) / 100f;
            return new SBOFormat.DropEntry(objectId, lo, hi, ch);
        }

        String countLabel() {
            int lo = min.get(), hi = max.get();
            return lo == hi ? "×" + lo : "×" + lo + "–" + hi;
        }

        String chanceLabel() {
            float p = chancePct.get();
            return (p == Math.rint(p) ? String.valueOf((int) p) : String.format("%.1f", p)) + "%";
        }
    }

    /** Mutable working copy of one per-tool override. */
    private static final class EditableOverride {
        String toolObjectId = "";
        final List<EditableDrop> drops = new ArrayList<>();

        EditableOverride() {}

        EditableOverride(SBOFormat.ToolDropOverride o) {
            this.toolObjectId = o.toolObjectId();
            for (SBOFormat.DropEntry e : o.drops()) drops.add(new EditableDrop(e));
        }
    }

    private final ImBoolean enabled = new ImBoolean(false);
    private final List<EditableDrop> defaults = new ArrayList<>();
    private final List<EditableOverride> overrides = new ArrayList<>();
    private final SBOObjectPickerPopup picker = new SBOObjectPickerPopup();
    private final Runnable onDirty;

    /** One Mortar region per drop card (default + every override's cards) and per override header. */
    private final MortarRegionPool cardPool = new MortarRegionPool();
    private final MortarRegionPool overridePool = new MortarRegionPool();
    private int cardCursor;

    public SBODropsSection(Runnable onDirty) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
    }

    public void setFromDropData(SBOFormat.DropData data) {
        defaults.clear();
        overrides.clear();
        enabled.set(data != null);
        if (data != null) {
            for (SBOFormat.DropEntry e : data.drops()) defaults.add(new EditableDrop(e));
            for (SBOFormat.ToolDropOverride o : data.toolOverrides()) overrides.add(new EditableOverride(o));
        }
    }

    /**
     * Returns {@code null} when the table is disabled (manifest field absent →
     * game default). When enabled, always returns a table — possibly empty,
     * which the game reads as "drops nothing".
     */
    public SBOFormat.DropData toDropData() {
        if (!enabled.get()) return null;
        List<SBOFormat.ToolDropOverride> outOverrides = new ArrayList<>(overrides.size());
        Set<String> seenTools = new HashSet<>();
        for (EditableOverride o : overrides) {
            // Blank or duplicate tool keys are silently dropped — the format record rejects them.
            if (o.toolObjectId == null || o.toolObjectId.isBlank() || !seenTools.add(o.toolObjectId)) continue;
            outOverrides.add(new SBOFormat.ToolDropOverride(o.toolObjectId, collect(o.drops)));
        }
        return new SBOFormat.DropData(collect(defaults), outOverrides);
    }

    /** Returns a human-readable problem with the current table, or {@code null} when it is saveable. */
    public String validate() {
        if (!enabled.get()) return null;
        Set<String> seenTools = new HashSet<>();
        for (int i = 0; i < overrides.size(); i++) {
            String tool = overrides.get(i).toolObjectId;
            if (tool == null || tool.isBlank()) return "Tool override #" + (i + 1) + " has no tool selected.";
            if (!seenTools.add(tool)) return "Tool '" + tool + "' has more than one override.";
        }
        return null;
    }

    private static List<SBOFormat.DropEntry> collect(List<EditableDrop> rows) {
        List<SBOFormat.DropEntry> out = new ArrayList<>(rows.size());
        for (EditableDrop d : rows) {
            // Blank items are silently dropped — the format record rejects them.
            if (d.objectId == null || d.objectId.isBlank()) continue;
            out.add(d.toEntry());
        }
        return out;
    }

    // ---- render -------------------------------------------------------------

    public void render() {
        boolean mortar = cardPool.isAvailable();
        cardCursor = 0;

        if (ImGui.checkbox("Custom drop table", enabled)) onDirty.run();
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Off: the game's built-in rule applies (the block drops itself).\n"
                    + "On: exactly what is listed here drops - an empty list drops nothing.");
        }
        if (!enabled.get()) {
            picker.render();
            return;
        }

        ImGui.dummy(0, 6);
        ImGui.separator();
        ImGui.dummy(0, 6);

        // ── Default drops ───────────────────────────────────────────────────
        sectionHeader("Default drops", defaults.size(),
                "When broken by hand, or by a tool that has no override below.");
        if (defaults.isEmpty()) {
            ImGui.textDisabled("  Drops nothing.");
            ImGui.dummy(0, 4);
        }
        renderDropCards("def", defaults, mortar);
        if (ImGui.button("+ Add drop##def")) {
            defaults.add(new EditableDrop());
            onDirty.run();
        }

        ImGui.dummy(0, 10);
        ImGui.separator();
        ImGui.dummy(0, 6);

        // ── Tool overrides ──────────────────────────────────────────────────
        sectionHeader("Tool overrides", overrides.size(),
                "Breaking with a listed tool drops that list instead of the defaults.");

        int removeOverride = -1;
        for (int i = 0; i < overrides.size(); i++) {
            EditableOverride o = overrides.get(i);
            ImGui.pushID("ovr_" + i);

            boolean removeRequested = mortar
                    ? renderOverrideHeaderMortar(i, o)
                    : renderOverrideHeaderFallback(o);
            if (removeRequested) removeOverride = i;

            ImGui.indent(INDENT);
            if (o.drops.isEmpty()) {
                ImGui.textDisabled("Drops nothing with this tool.");
                ImGui.dummy(0, 4);
            }
            renderDropCards("ovr" + i, o.drops, mortar);
            if (ImGui.button("+ Add drop##ovr")) {
                o.drops.add(new EditableDrop());
                onDirty.run();
            }
            ImGui.unindent(INDENT);

            ImGui.dummy(0, 10);
            ImGui.popID();
        }
        overridePool.trim(overrides.size());
        cardPool.trim(cardCursor);

        if (removeOverride >= 0) {
            overrides.remove(removeOverride);
            onDirty.run();
        }

        if (ImGui.button("+ Add tool override")) {
            overrides.add(new EditableOverride());
            onDirty.run();
        }

        // Picker is a singleton popup — must render every frame regardless of
        // whether a card triggered open() this frame.
        picker.render();
    }

    private static void sectionHeader(String title, int count, String hint) {
        ImGui.text(title);
        if (count > 0) {
            ImGui.sameLine();
            ImGui.textDisabled("(" + count + ")");
        }
        ImGui.textDisabled(hint);
        ImGui.dummy(0, 6);
    }

    /** One card per drop line, then the editable sliders beneath each. */
    private void renderDropCards(String idPrefix, List<EditableDrop> rows, boolean mortar) {
        int removeIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            EditableDrop d = rows.get(i);
            ImGui.pushID(idPrefix + "_" + i);

            boolean removeRequested = mortar
                    ? renderCardMortar(cardPool.get(cardCursor++), d)
                    : renderCardFallback(d);
            if (removeRequested) removeIndex = i;

            renderCardDetails(d);

            ImGui.dummy(0, 6);
            ImGui.popID();
        }
        if (removeIndex >= 0) {
            rows.remove(removeIndex);
            onDirty.run();
        }
    }

    // ---- drop card (Mortar) --------------------------------------------------

    /** Paints the card strip; returns true when the remove control was clicked. */
    private boolean renderCardMortar(MortarRegion region, EditableDrop d) {
        float availW = Math.max(1f, ImGui.getContentRegionAvailX());
        final boolean hasItem = d.objectId != null && !d.objectId.isBlank();
        final String objectId = hasItem ? d.objectId : "";
        final String title = hasItem ? displayName(objectId) : "Pick a block or item";
        final String summary = d.countLabel() + "   ·   " + d.chanceLabel();

        region.begin(availW, CARD_H);

        region.add("bg", 0f, 0f, availW, CARD_H, (g, px, py, pw, ph, state) -> {
            g.fillRoundRect(px, py, pw, ph, CARD_RADIUS, Argb.withAlpha(g.theme().surface, 0.65f));
            g.strokeRoundRect(px, py, pw, ph, CARD_RADIUS, 1f, g.theme().border);
        });

        // Item tile: icon (or "+") — click to pick.
        region.add("tile", TILE_PAD, TILE_PAD, TILE, TILE, (g, px, py, pw, ph, state) ->
                paintTile(g, px, py, pw, ph, state, hasItem ? objectId : null, false));

        // Title + dim objectId, clipped before the summary pill.
        float pillW = 150f;
        float xRemove = availW - REMOVE_W - 6f;
        float xPill = xRemove - 10f - pillW;
        float textX = TILE_PAD + TILE + 12f;
        float textW = Math.max(20f, xPill - 10f - textX);
        region.add("deco.title", textX, 0f, textW, CARD_H, (g, px, py, pw, ph, state) -> {
            int titleColor = hasItem ? g.theme().text : g.theme().textFaint;
            if (hasItem) {
                g.textEllipsized(title, px, py + ph / 2f - 8f, pw, Weight.MEDIUM, 13f, titleColor);
                g.textEllipsized(objectId, px, py + ph / 2f + 8f, pw, Weight.REGULAR, 11f, g.theme().textFaint);
            } else {
                g.text(title, px, py + ph / 2f, MortarPainter.Align.LEFT, Weight.MEDIUM, 13f, titleColor);
            }
        });

        // Summary pill: "×3–4 · 100%".
        region.add("deco.pill", xPill, (CARD_H - 24f) / 2f, pillW, 24f, (g, px, py, pw, ph, state) -> {
            g.fillRoundRect(px, py, pw, ph, ph / 2f, Argb.shade(g.theme().surface, 0.06f));
            g.strokeRoundRect(px, py, pw, ph, ph / 2f, 1f, g.theme().border);
            g.text(summary, px + pw / 2f, py + ph / 2f, MortarPainter.Align.CENTER,
                    Weight.MEDIUM, 12f, g.theme().textDim);
        });

        region.add("remove", xRemove, (CARD_H - 24f) / 2f, REMOVE_W, 24f,
                SBODropsSection::paintRemove);

        MortarFrameResult input = region.render();
        region.update(ImGui.getIO().getDeltaTime());

        String hovered = input.hovered();
        if (hovered != null) {
            switch (hovered) {
                case "tile" -> ImGui.setTooltip(hasItem
                        ? objectId + "\nClick to change - right-click to clear"
                        : "Click to pick the dropped block or item");
                case "remove" -> ImGui.setTooltip("Remove this drop");
                default -> { }
            }
        }
        if ("tile".equals(hovered) && ImGui.isMouseClicked(1) && hasItem) {
            d.objectId = "";
            onDirty.run();
        }

        String clicked = input.clicked();
        if (clicked != null) {
            switch (clicked) {
                case "tile" -> openPicker(picked -> d.objectId = picked);
                case "remove" -> { return true; }
                default -> { }
            }
        }
        return false;
    }

    /** Sliders beneath the card: count range + chance. */
    private void renderCardDetails(EditableDrop d) {
        ImGui.dummy(0, 2);
        ImGui.textDisabled("  ");
        ImGui.sameLine();
        ImGui.pushItemWidth(110f);
        if (ImGui.sliderInt("Min##min", d.min.getData(), 0, 64)) {
            if (d.max.get() < d.min.get()) d.max.set(d.min.get());
            onDirty.run();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Minimum count (Ctrl+click to type)");
        ImGui.sameLine();
        if (ImGui.sliderInt("Max##max", d.max.getData(), 0, 64)) {
            if (d.min.get() > d.max.get()) d.min.set(d.max.get());
            onDirty.run();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Maximum count, inclusive — rolled uniformly (Ctrl+click to type)");
        ImGui.popItemWidth();

        ImGui.sameLine();
        ImGui.textDisabled("   ");
        ImGui.sameLine();
        ImGui.pushItemWidth(150f);
        if (ImGui.sliderFloat("Chance##chance", d.chancePct.getData(), 0f, 100f, "%.0f%%")) {
            onDirty.run();
        }
        if (ImGui.isItemHovered()) ImGui.setTooltip("Probability this line drops at all (100% = always; Ctrl+click to type)");
        ImGui.popItemWidth();
    }

    // ---- override header (Mortar) --------------------------------------------

    private boolean renderOverrideHeaderMortar(int i, EditableOverride o) {
        MortarRegion region = overridePool.get(i);
        float availW = Math.max(1f, ImGui.getContentRegionAvailX());
        final boolean hasTool = o.toolObjectId != null && !o.toolObjectId.isBlank();
        final String tool = hasTool ? o.toolObjectId : "";
        final String title = hasTool ? "When broken with " + displayName(tool) : "Pick a tool";
        final String detail = hasTool ? tool : "this override does nothing until a tool is chosen";
        final int cardCount = o.drops.size();

        region.begin(availW, CARD_H);

        region.add("bg", 0f, 0f, availW, CARD_H, (g, px, py, pw, ph, state) -> {
            g.fillRoundRect(px, py, pw, ph, CARD_RADIUS, Argb.withAlpha(g.theme().accent, 0.08f));
            g.strokeRoundRect(px, py, pw, ph, CARD_RADIUS, 1f, Argb.withAlpha(g.theme().accent, 0.45f));
        });

        region.add("tile", TILE_PAD, TILE_PAD, TILE, TILE, (g, px, py, pw, ph, state) ->
                paintTile(g, px, py, pw, ph, state, hasTool ? tool : null, true));

        float xRemove = availW - REMOVE_W - 6f;
        float textX = TILE_PAD + TILE + 12f;
        float textW = Math.max(20f, xRemove - 10f - textX);
        region.add("deco.title", textX, 0f, textW, CARD_H, (g, px, py, pw, ph, state) -> {
            float cyTop = py + ph / 2f - 8f;
            float bx = px;
            bx += MortarBadge.paint(g, bx, cyTop, "TOOL") + 8f;
            g.textEllipsized(title, bx, cyTop, px + pw - bx, Weight.MEDIUM, 13f,
                    hasTool ? g.theme().text : g.theme().textFaint);
            String sub = detail + (cardCount == 0 ? "   ·   drops nothing" : "   ·   " + cardCount
                    + (cardCount == 1 ? " drop" : " drops") + " replace the defaults");
            g.textEllipsized(sub, px, py + ph / 2f + 8f, pw, Weight.REGULAR, 11f, g.theme().textFaint);
        });

        region.add("remove", xRemove, (CARD_H - 24f) / 2f, REMOVE_W, 24f,
                SBODropsSection::paintRemove);

        MortarFrameResult input = region.render();
        region.update(ImGui.getIO().getDeltaTime());

        String hovered = input.hovered();
        if (hovered != null) {
            switch (hovered) {
                case "tile" -> ImGui.setTooltip(hasTool
                        ? tool + "\nClick to change the tool"
                        : "Click to pick the tool this override applies to");
                case "remove" -> ImGui.setTooltip("Remove this override and its drops");
                default -> { }
            }
        }
        String clicked = input.clicked();
        if (clicked != null) {
            switch (clicked) {
                case "tile" -> openPicker(picked -> o.toolObjectId = picked);
                case "remove" -> { return true; }
                default -> { }
            }
        }
        ImGui.dummy(0, 6);
        return false;
    }

    // ---- painters -----------------------------------------------------------

    private static void paintTile(MortarPainter g, float x, float y, float w, float h,
                                  PartState state, String objectId, boolean accent) {
        float hover = state.hover();
        int base = accent ? Argb.withAlpha(g.theme().accent, 0.18f) : Argb.shade(g.theme().surface, 0.05f);
        g.fillRoundRect(x, y, w, h, TILE_RADIUS, Argb.lerp(base, g.theme().surfaceHover, hover * 0.6f));
        int border = accent ? Argb.withAlpha(g.theme().accent, 0.6f) : g.theme().border;
        g.strokeRoundRect(x, y, w, h, TILE_RADIUS, 1f,
                Argb.lerp(border, g.theme().borderStrong, hover));
        Image icon = objectId != null ? SBOIngredientIcons.skijaIcon(objectId) : null;
        if (icon != null) {
            g.canvas().drawImageRect(icon, Rect.makeXYWH(x + 5f, y + 5f, w - 10f, h - 10f));
        } else if (objectId != null) {
            g.textEllipsized(shortLabel(objectId), x + 4f, y + h / 2f, w - 8f,
                    Weight.REGULAR, 10f, g.theme().text);
        } else {
            g.text("+", x + w / 2f, y + h / 2f, MortarPainter.Align.CENTER, Weight.REGULAR, 18f,
                    Argb.lerp(g.theme().textFaint, g.theme().text, hover));
        }
    }

    private static void paintRemove(MortarPainter g, float px, float py, float pw, float ph, PartState state) {
        float hover = state.hover();
        if (hover > 0.02f) {
            g.fillRoundRect(px, py, pw, ph, 6f, Argb.withAlpha(0xFFB44242, 0.30f * hover));
        }
        g.text("×", px + pw / 2f, py + ph / 2f, MortarPainter.Align.CENTER, Weight.MEDIUM, 13f,
                Argb.lerp(g.theme().textDim, 0xFFE07A7A, hover));
    }

    // ---- fallbacks (no Skija context) ---------------------------------------

    private boolean renderCardFallback(EditableDrop d) {
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.FrameRounding, 6.0f);
        boolean hasItem = d.objectId != null && !d.objectId.isBlank();
        int icon = hasItem ? SBOIngredientIcons.glIcon(d.objectId) : 0;
        boolean pick = icon > 0
                ? ImGui.imageButton("##drop_item", icon, TILE - 8, TILE - 8)
                : ImGui.button(hasItem ? shortLabel(d.objectId) : "+", TILE, TILE);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(hasItem ? d.objectId + "\nClick to change - right-click to clear"
                    : "Click to pick the dropped block or item");
        }
        if (pick) openPicker(picked -> d.objectId = picked);
        if (ImGui.isItemClicked(1) && hasItem) { d.objectId = ""; onDirty.run(); }
        ImGui.sameLine();
        ImGui.text(hasItem ? displayName(d.objectId) : "Pick a block or item");
        ImGui.sameLine();
        ImGui.textDisabled(d.countLabel() + " · " + d.chanceLabel());
        ImGui.sameLine();
        boolean remove = EditorWidgets.dangerButton("Remove", 0f);
        ImGui.popStyleVar();
        return remove;
    }

    private boolean renderOverrideHeaderFallback(EditableOverride o) {
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.FrameRounding, 6.0f);
        boolean hasTool = o.toolObjectId != null && !o.toolObjectId.isBlank();
        int icon = hasTool ? SBOIngredientIcons.glIcon(o.toolObjectId) : 0;
        boolean pick = icon > 0
                ? ImGui.imageButton("##ovr_tool", icon, TILE - 8, TILE - 8)
                : ImGui.button(hasTool ? shortLabel(o.toolObjectId) : "tool?", TILE, TILE);
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(hasTool ? o.toolObjectId + "\nClick to change the tool"
                    : "Click to pick the tool this override applies to");
        }
        if (pick) openPicker(picked -> o.toolObjectId = picked);
        ImGui.sameLine();
        ImGui.text(hasTool ? "When broken with " + displayName(o.toolObjectId) : "Pick a tool");
        ImGui.sameLine();
        boolean remove = EditorWidgets.dangerButton("Remove override", 0f);
        ImGui.popStyleVar();
        return remove;
    }

    // ---- helpers ------------------------------------------------------------

    private void openPicker(java.util.function.Consumer<String> assign) {
        picker.open(picked -> {
            assign.accept(picked == null ? "" : picked);
            if (picked != null && !picked.isEmpty()) IngredientMru.shared().touch(picked);
            onDirty.run();
        });
    }

    /** "stonebreak:clay_chunk" → "Clay Chunk". */
    private static String displayName(String objectId) {
        int colon = objectId.indexOf(':');
        String local = colon >= 0 ? objectId.substring(colon + 1) : objectId;
        StringBuilder sb = new StringBuilder(local.length());
        boolean upper = true;
        for (char c : local.toCharArray()) {
            if (c == '_' || c == '-') { sb.append(' '); upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    /** Render an objectId compactly (drop namespace, truncate). */
    private static String shortLabel(String objectId) {
        int colon = objectId.indexOf(':');
        String local = colon >= 0 ? objectId.substring(colon + 1) : objectId;
        if (local.length() > 6) local = local.substring(0, 5) + "…";
        return local;
    }

    @Override
    public void close() {
        cardPool.close();
        overridePool.close();
    }
}

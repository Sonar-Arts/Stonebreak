package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;
import imgui.ImGui;
import imgui.flag.ImGuiTabBarFlags;

/**
 * Shared window chrome for the SBO and SBE editor windows: a Mortar-painted
 * action bar (Save / Save As / Open, with Save promoted to the accent PRIMARY
 * treatment while there are unsaved changes) plus an animated pill tab strip,
 * with a right-aligned file label carrying a dirty dot. One
 * {@link MortarRegion} (one FBO) paints the whole strip; when no Skija context
 * exists the chrome falls back to plain ImGui buttons and a standard tab bar.
 *
 * <p>The caller owns the selected tab index — {@link #render} returns the
 * (possibly changed) index and fires the action callbacks directly.
 */
final class EditorChrome implements AutoCloseable {

    private static final float BTN_H = 26f;
    private static final float BTN_GAP = 6f;
    private static final float TAB_H = 26f;
    private static final float TAB_GAP = 6f;
    private static final float SEP_PAD = 5f;
    /** JetBrains Mono at ~12-13px is ~6.8px per character. */
    private static final float CHAR_W = 6.8f;

    private final MortarRegion region = new MortarRegion();
    private final String fallbackId;

    /**
     * @param fallbackId unique suffix for the ImGui-fallback tab bar id so two
     *                   chrome instances never collide ("sbo", "sbe").
     */
    EditorChrome(String fallbackId) {
        this.fallbackId = fallbackId;
    }

    /**
     * Draw the chrome and return the selected tab index. {@code tabs} are only
     * shown when {@code loaded}; callbacks fire at most once per frame.
     */
    int render(boolean loaded, boolean canSave, boolean dirty, String fileLabel,
               String openLabel, String[] tabs, int selectedTab,
               Runnable onSave, Runnable onSaveAs, Runnable onOpen) {
        if (!region.isAvailable()) {
            return renderFallback(loaded, canSave, fileLabel, openLabel, tabs, selectedTab,
                    onSave, onSaveAs, onOpen);
        }

        float availW = Math.max(1f, ImGui.getContentRegionAvailX());
        float height = loaded ? BTN_H + SEP_PAD * 2f + 1f + TAB_H + 2f : BTN_H + 2f;

        region.begin(availW, height);

        float saveW = 56f;
        float saveAsW = 84f;
        float openW = pillWidth(openLabel);
        float x = 0f;
        addAction("act.save", x, saveW, "Save", canSave, canSave);
        x += saveW + BTN_GAP;
        addAction("act.saveAs", x, saveAsW, "Save As...", loaded, false);
        x += saveAsW + BTN_GAP;
        addAction("act.open", x, openW, openLabel, true, false);

        // Right-aligned file label with a dirty dot.
        float statusX = x + openW + BTN_GAP;
        if (fileLabel != null && !fileLabel.isEmpty() && statusX < availW - 40f) {
            final String label = fileLabel;
            final boolean isDirty = dirty;
            region.add("deco.status", statusX, 0f, availW - statusX, BTN_H,
                    (g, px, py, pw, ph, state) -> {
                        float cy = py + ph / 2f;
                        float labelW = g.measureWidth(label, Weight.REGULAR, 12f);
                        float textRight = px + pw - 4f;
                        g.textEllipsized(label, Math.max(px, textRight - labelW), cy,
                                pw - 16f, Weight.REGULAR, 12f, g.theme().textDim);
                        if (isDirty) {
                            float dotX = Math.max(px, textRight - labelW) - 11f;
                            g.fillRoundRect(dotX, cy - 3f, 6f, 6f, 3f, g.theme().accent);
                        }
                    });
        }

        if (loaded) {
            float sepY = BTN_H + SEP_PAD + 1f;
            region.add("deco.sep", 0f, sepY, availW, 1f,
                    (g, px, py, pw, ph, state) -> g.fillRect(px, py, pw, 1f, g.theme().separator));

            float tabY = sepY + SEP_PAD + 1f;
            float tx = 0f;
            for (int i = 0; i < tabs.length; i++) {
                float w = pillWidth(tabs[i]);
                final String label = tabs[i];
                region.add("tab." + i, tx, tabY, w, TAB_H, i == selectedTab,
                        (g, px, py, pw, ph, state) -> paintTab(g, px, py, pw, ph, state, label));
                tx += w + TAB_GAP;
            }
        }

        MortarFrameResult input = region.render();
        region.update(ImGui.getIO().getDeltaTime());

        String hovered = input.hovered();
        if (hovered != null) {
            switch (hovered) {
                case "act.save" -> ImGui.setTooltip(canSave
                        ? "Write changes back to the opened file"
                        : (loaded ? "All changes saved" : "Nothing loaded"));
                case "act.saveAs" -> {
                    if (!loaded) ImGui.setTooltip("Nothing loaded");
                }
                default -> { }
            }
        }

        String clicked = input.clicked();
        if (clicked != null) {
            switch (clicked) {
                case "act.save" -> { if (canSave) onSave.run(); }
                case "act.saveAs" -> { if (loaded) onSaveAs.run(); }
                case "act.open" -> onOpen.run();
                default -> {
                    if (clicked.startsWith("tab.")) {
                        selectedTab = Integer.parseInt(clicked.substring(4));
                    }
                }
            }
        }
        return Math.max(0, Math.min(selectedTab, tabs.length - 1));
    }

    private static float pillWidth(String label) {
        return label.length() * CHAR_W + 26f;
    }

    /** Action pill: PRIMARY accent when {@code primary}, dimmed when disabled. */
    private void addAction(String id, float x, float w, String label,
                           boolean enabled, boolean primary) {
        region.add(id, x, 1f, w, BTN_H, (g, px, py, pw, ph, state) -> {
            float hover = enabled ? state.hover() : 0f;
            float press = enabled ? state.press() : 0f;
            float inset = press;
            float bx = px + inset;
            float by = py + inset;
            float bw = pw - inset * 2f;
            float bh = ph - inset * 2f;

            if (primary) {
                int fill = Argb.lerp(g.theme().accent, g.theme().accentHover, hover);
                fill = Argb.shade(fill, -0.10f * press);
                g.fillRoundRect(bx, by, bw, bh, 6f, fill);
                g.text(label, bx + bw / 2f, by + bh / 2f, MortarPainter.Align.CENTER,
                        Weight.MEDIUM, 13f, 0xFFFFFFFF);
            } else {
                int fill = Argb.lerp(g.theme().surface, g.theme().surfaceHover, hover);
                fill = Argb.shade(fill, -0.05f * press);
                if (!enabled) fill = Argb.withAlpha(g.theme().surface, 0.45f);
                g.fillRoundRect(bx, by, bw, bh, 6f, fill);
                g.strokeRoundRect(bx, by, bw, bh, 6f, 1f,
                        enabled ? Argb.lerp(g.theme().border, g.theme().borderStrong, hover * 0.6f)
                                : Argb.withAlpha(g.theme().border, 0.5f));
                g.text(label, bx + bw / 2f, by + bh / 2f, MortarPainter.Align.CENTER,
                        Weight.MEDIUM, 13f,
                        enabled ? Argb.lerp(g.theme().textDim, g.theme().text, hover)
                                : g.theme().textFaint);
            }
        });
    }

    private static void paintTab(MortarPainter g, float x, float y, float w, float h,
                                 PartState state, String label) {
        float sel = state.selected();
        float hover = state.hover();

        int fill = Argb.lerp(Argb.withAlpha(g.theme().surface, 0.55f), g.theme().surfaceHover, hover);
        fill = Argb.lerp(fill, g.theme().accent, sel);
        g.fillRoundRect(x, y, w, h, h / 2f, fill);
        if (sel < 0.5f) {
            g.strokeRoundRect(x, y, w, h, h / 2f, 1f, g.theme().border);
        }
        int textColor = Argb.lerp(g.theme().textDim, 0xFFFFFFFF, sel);
        textColor = Argb.lerp(textColor, g.theme().text, Math.max(0f, hover - sel));
        g.text(label, x + w / 2f, y + h / 2f, MortarPainter.Align.CENTER,
                Weight.MEDIUM, 12.5f, textColor);
    }

    // ---- ImGui fallback ----------------------------------------------------

    private int renderFallback(boolean loaded, boolean canSave, String fileLabel,
                               String openLabel, String[] tabs, int selectedTab,
                               Runnable onSave, Runnable onSaveAs, Runnable onOpen) {
        if (!canSave) ImGui.beginDisabled();
        if (ImGui.button("Save")) onSave.run();
        if (!canSave) ImGui.endDisabled();
        ImGui.sameLine();
        if (!loaded) ImGui.beginDisabled();
        if (ImGui.button("Save As...")) onSaveAs.run();
        if (!loaded) ImGui.endDisabled();
        ImGui.sameLine();
        if (ImGui.button(openLabel)) onOpen.run();
        if (fileLabel != null && !fileLabel.isEmpty()) {
            ImGui.sameLine();
            ImGui.textDisabled(fileLabel);
        }

        if (!loaded) return selectedTab;
        ImGui.separator();
        if (ImGui.beginTabBar("##chrome_tabs_" + fallbackId, ImGuiTabBarFlags.None)) {
            for (int i = 0; i < tabs.length; i++) {
                if (ImGui.beginTabItem(tabs[i])) {
                    selectedTab = i;
                    ImGui.endTabItem();
                }
            }
            ImGui.endTabBar();
        }
        return selectedTab;
    }

    /** Release the Mortar region. Must run before the SkijaContext closes. */
    @Override
    public void close() {
        region.close();
    }
}

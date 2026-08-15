package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;
import imgui.ImGui;

import java.util.List;

/**
 * The shared Mortar card-header strip for row-based editor sections (SBO/SBE
 * states, SBE variants — the Sounds tab pioneered the look with its bespoke
 * header): a rounded surface carrying a kind badge, the row title with a dim
 * detail summary, right-aligned secondary action pills, and a danger-tinted
 * remove button at the far edge. One strip paints into one caller-owned
 * {@link MortarRegion} (pool them per row via
 * {@link com.openmason.main.systems.mortar.core.MortarRegionPool}).
 *
 * <p>Pure chrome — the caller reads {@link Result} for hover (tooltips) and
 * clicks (action ids, {@code "remove"}) and owns all state. Callers must keep
 * an ImGui fallback for when no Skija context exists.
 */
final class RowHeaderStrip {

    static final float HEIGHT = 34f;

    private static final float PILL_H = 24f;
    private static final float REMOVE_W = 26f;
    private static final float GAP = 8f;
    private static final float CHAR_W = 6.8f;

    /** One right-aligned secondary action pill. */
    record Action(String id, String label, boolean enabled) {
    }

    /** Hit-test outcome; ids are action ids plus {@code "remove"}. */
    record Result(String hovered, String clicked) {
        boolean isClicked(String id) {
            return id.equals(clicked);
        }

        boolean removeClicked() {
            return isClicked("remove");
        }
    }

    private RowHeaderStrip() {
    }

    /**
     * Paint the strip at the current cursor into {@code region} and hit-test
     * it. Width tracks the available content region; height is {@link #HEIGHT}.
     */
    static Result render(MortarRegion region, String badge, boolean badgeAccent,
                         String title, boolean titleFaint, String detail,
                         List<Action> actions) {
        float availW = Math.max(1f, ImGui.getContentRegionAvailX());
        region.begin(availW, HEIGHT);

        region.add("bg", 0f, 0f, availW, HEIGHT, (g, px, py, pw, ph, state) -> {
            g.fillRoundRect(px, py, pw, ph, 8f, Argb.withAlpha(g.theme().surface, 0.65f));
            g.strokeRoundRect(px, py, pw, ph, 8f, 1f, g.theme().border);
        });

        // Right-aligned controls, laid out from the right edge inward.
        float x = availW - REMOVE_W - 4f;
        float ctrlY = (HEIGHT - PILL_H) / 2f;
        region.add("remove", x, ctrlY, REMOVE_W, PILL_H, (g, px, py, pw, ph, state) -> {
            float hover = state.hover();
            if (hover > 0.02f) {
                g.fillRoundRect(px, py, pw, ph, 6f, Argb.withAlpha(0xFFB44242, 0.30f * hover));
            }
            g.text("×", px + pw / 2f, py + ph / 2f, MortarPainter.Align.CENTER,
                    Weight.MEDIUM, 13f, Argb.lerp(g.theme().textDim, 0xFFE07A7A, hover));
        });
        for (int i = actions.size() - 1; i >= 0; i--) {
            Action action = actions.get(i);
            float w = action.label().length() * CHAR_W + 22f;
            x -= GAP + w;
            region.add(action.id(), x, ctrlY, w, PILL_H, (g, px, py, pw, ph, state) ->
                    paintActionPill(g, px, py, pw, ph, state.hover(), state.press(), action));
        }

        // Title zone: badge + title + dim detail, clipped to the free width.
        float titleZoneW = x - GAP - 10f;
        region.add("deco.title", 10f, 0f, Math.max(20f, titleZoneW), HEIGHT,
                (g, px, py, pw, ph, state) -> {
                    float cy = py + ph / 2f;
                    float bx = px;
                    bx += paintBadge(g, bx, cy, badge, badgeAccent) + 8f;
                    int titleColor = titleFaint ? g.theme().textFaint : g.theme().text;
                    g.text(title, bx, cy, MortarPainter.Align.LEFT, Weight.MEDIUM, 13f, titleColor);
                    bx += g.measureWidth(title, Weight.MEDIUM, 13f) + 10f;
                    float remaining = px + pw - bx;
                    if (detail != null && !detail.isEmpty() && remaining > 30f) {
                        g.textEllipsized(detail, bx, cy, remaining, Weight.REGULAR, 11f,
                                g.theme().textFaint);
                    }
                });

        MortarFrameResult input = region.render();
        region.update(ImGui.getIO().getDeltaTime());
        return new Result(input.hovered(), input.clicked());
    }

    private static void paintActionPill(MortarPainter g, float x, float y, float w, float h,
                                        float rawHover, float rawPress, Action action) {
        float hover = action.enabled() ? rawHover : 0f;
        float press = action.enabled() ? rawPress : 0f;
        float inset = press;
        float bx = x + inset;
        float by = y + inset;
        float bw = w - inset * 2f;
        float bh = h - inset * 2f;

        int fill = Argb.lerp(g.theme().surface, g.theme().surfaceHover, hover);
        fill = Argb.shade(fill, -0.05f * press);
        if (!action.enabled()) fill = Argb.withAlpha(g.theme().surface, 0.45f);
        g.fillRoundRect(bx, by, bw, bh, bh / 2f, fill);
        g.strokeRoundRect(bx, by, bw, bh, bh / 2f, 1f,
                action.enabled()
                        ? Argb.lerp(g.theme().border, g.theme().borderStrong, hover * 0.6f)
                        : Argb.withAlpha(g.theme().border, 0.5f));
        g.text(action.label(), bx + bw / 2f, by + bh / 2f, MortarPainter.Align.CENTER,
                Weight.MEDIUM, 11.5f,
                action.enabled()
                        ? Argb.lerp(g.theme().textDim, g.theme().text, hover)
                        : g.theme().textFaint);
    }

    /** Kind badge; accent variant marks the special row (e.g. the default state). */
    private static float paintBadge(MortarPainter g, float x, float cy, String text,
                                    boolean accent) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        float textW = g.measureWidth(text, Weight.MEDIUM, 11f);
        float pillW = textW + 16f;
        float top = cy - 9f;
        if (accent) {
            g.fillRoundRect(x, top, pillW, 18f, 9f, Argb.withAlpha(g.theme().accent, 0.90f));
            g.text(text, x + 8f, cy, MortarPainter.Align.LEFT, Weight.MEDIUM, 11f, 0xFFFFFFFF);
        } else {
            g.fillRoundRect(x, top, pillW, 18f, 9f, g.theme().badgeBg);
            g.text(text, x + 8f, cy, MortarPainter.Align.LEFT, Weight.MEDIUM, 11f, g.theme().textDim);
        }
        return pillW;
    }
}

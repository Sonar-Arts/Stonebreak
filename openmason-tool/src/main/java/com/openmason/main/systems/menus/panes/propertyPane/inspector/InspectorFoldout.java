package com.openmason.main.systems.menus.panes.propertyPane.inspector;

import com.openmason.main.systems.mortar.anim.Smoother;
import com.openmason.main.systems.mortar.core.MortarFrameResult;
import com.openmason.main.systems.mortar.core.MortarRegion;
import com.openmason.main.systems.mortar.core.PartState;
import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.Argb;
import com.openmason.main.systems.skija.SkijaFontStore.Weight;
import imgui.ImGui;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;

/**
 * A collapsible inspector section header, painted as a Skija/Mortar strip:
 * rounded surface bar with animated hover, a chevron that rotates as the
 * section opens/closes, a medium-weight title, and a hairline rule. Falls
 * back to a plain ImGui selectable header when no SkijaContext is available.
 *
 * <p>Usage per frame:
 * <pre>
 *   if (foldout.begin()) {
 *       // section body
 *   }
 *   foldout.end();
 * </pre>
 * The body snaps open/closed; only the chevron animates (v1).</p>
 */
public final class InspectorFoldout implements AutoCloseable {

    private static final float HEADER_HEIGHT = 26f;
    private static final float CHEVRON_CENTER_X = 13f;
    private static final float CHEVRON_HALF = 3f;
    private static final float TITLE_INSET = 26f;
    private static final float TITLE_SIZE = 13f;

    private final String title;
    private final MortarRegion region = new MortarRegion();
    /** 0 = closed (chevron pointing right), 1 = open (pointing down). */
    private final Smoother openFraction;

    private boolean open;

    public InspectorFoldout(String title, boolean defaultOpen) {
        this.title = title;
        this.open = defaultOpen;
        this.openFraction = new Smoother(16f, defaultOpen ? 1f : 0f);
    }

    /**
     * Draw the header strip and return whether the section body should render.
     */
    public boolean begin() {
        float width = ImGui.getContentRegionAvailX();
        if (width < 1f) {
            return open;
        }

        if (!region.isAvailable()) {
            // No Skija context — plain ImGui fallback.
            if (ImGui.selectable((open ? "v  " : ">  ") + title + "##foldout_" + title)) {
                open = !open;
            }
            ImGui.separator();
            return open;
        }

        region.begin(width, HEADER_HEIGHT);
        region.add("header", 0f, 0f, width, HEADER_HEIGHT, open, this::paintHeader);
        MortarFrameResult input = region.render();
        if (input.isClicked("header")) {
            open = !open;
        }

        openFraction.setTarget(open ? 1f : 0f);
        float dt = ImGui.getIO().getDeltaTime();
        openFraction.update(dt);
        region.update(dt);

        ImGui.spacing();
        return open;
    }

    /** Close the section: bottom spacing so consecutive sections breathe. */
    public void end() {
        ImGui.spacing();
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    private void paintHeader(MortarPainter g, float x, float y, float w, float h, PartState state) {
        float hover = state.hover();
        float press = state.press();

        // Subtle rounded surface bar; brightens on hover, settles on press.
        int fill = Argb.withAlpha(g.theme().surface, 0.9f);
        fill = Argb.lerp(fill, g.theme().surfaceHover, hover);
        if (press > 0f) {
            fill = Argb.shade(fill, -0.05f * press);
        }
        g.fillRoundRect(x, y, w, h - 2f, 5f, fill);

        // Chevron rotating 0° (right) → 90° (down) with the open fraction.
        float frac = openFraction.getValue();
        float cx = x + CHEVRON_CENTER_X;
        float cy = y + (h - 2f) / 2f;
        int chevronColor = Argb.lerp(g.theme().textDim, g.theme().text, Math.max(hover, frac * 0.5f));
        Canvas canvas = g.canvas();
        int save = canvas.save();
        // Rotate around the chevron center (this Skija Canvas.rotate has no pivot overload).
        canvas.translate(cx, cy);
        canvas.rotate(90f * frac);
        try (Paint p = new Paint()) {
            p.setAntiAlias(true);
            p.setColor(chevronColor);
            p.setMode(PaintMode.STROKE);
            p.setStrokeWidth(1.6f);
            // Chevron in local coords, ">" pointing right at 0°.
            canvas.drawLine(-CHEVRON_HALF, -CHEVRON_HALF - 1f, CHEVRON_HALF, 0f, p);
            canvas.drawLine(CHEVRON_HALF, 0f, -CHEVRON_HALF, CHEVRON_HALF + 1f, p);
        }
        canvas.restoreToCount(save);

        // Title.
        g.text(title, x + TITLE_INSET, cy, MortarPainter.Align.LEFT,
                Weight.MEDIUM, TITLE_SIZE, g.theme().text);

        // Hairline rule under the bar.
        g.fillRect(x, y + h - 1f, w, 1f, g.theme().separator);
    }

    /** Release the Skija region. Must run before the SkijaContext closes. */
    @Override
    public void close() {
        region.close();
    }
}

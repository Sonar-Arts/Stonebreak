package com.openmason.main.systems.mortar.core;

import com.openmason.main.systems.mortar.paint.MortarPainter;
import com.openmason.main.systems.mortar.theme.MortarTheme;
import com.openmason.main.systems.skija.SkijaContext;
import com.openmason.main.systems.skija.SkijaImGuiPanel;
import imgui.ImGui;
import imgui.flag.ImGuiMouseButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A composite, animated, interactive surface — MortarUI's core bridge. One
 * {@link SkijaImGuiPanel} (one FBO) paints many {@link MortarPart}s; the panel's
 * single overlaid invisible button captures input; the previous frame's
 * animated {@link PartState}s feed this frame's paint. This generalizes the
 * proven {@code SkijaToolStripRenderer} pattern from one fixed grid of cells to
 * an arbitrary set of caller-positioned parts.
 *
 * <p>Per-frame usage:</p>
 * <pre>
 *   region.begin(width, height);
 *   region.add("card.0", x, y, w, h, selected, cardPart);
 *   ... more parts ...
 *   MortarFrameResult input = region.render();   // paint + composite + hit-test
 *   if (input.isClicked("card.0")) { ... }
 *   // later, once per frame:
 *   region.update(deltaTime);                     // advance animations
 * </pre>
 *
 * <p>Because paint runs before ImGui can hit-test the freshly drawn image,
 * hover/press lag the mouse by one frame — imperceptible at frame rate and the
 * same trade-off the tool strip and color picker already make. Requires a live
 * {@link SkijaContext}; {@link #isAvailable()} reports whether one exists.</p>
 *
 * <p>Not thread-safe; create, use, and {@link #close()} on the GL thread.</p>
 */
public final class MortarRegion implements AutoCloseable {

    private record Entry(String id, float x, float y, float w, float h,
                         boolean selected, MortarPart part) {
        boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    private final MortarFonts fonts = new MortarFonts();
    private final Map<String, PartState> states = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();

    private SkijaImGuiPanel panel;
    private float width;
    private float height;

    /** True when a Skija context exists and the region can paint. */
    public boolean isAvailable() {
        return SkijaContext.getInstance() != null;
    }

    /** Start a frame at the given surface size; clears queued parts. */
    public void begin(float width, float height) {
        this.width = width;
        this.height = height;
        entries.clear();
    }

    public void add(String id, float x, float y, float w, float h, MortarPart part) {
        add(id, x, y, w, h, false, part);
    }

    /** Queue a part with a hit rect; {@code selected} drives its selected blend. */
    public void add(String id, float x, float y, float w, float h, boolean selected, MortarPart part) {
        entries.add(new Entry(id, x, y, w, h, selected, part));
    }

    /**
     * Paint all queued parts to the surface, composite it into the current
     * ImGui window, hit-test the mouse against the part rects, retarget each
     * part's animation, and return the input outcome.
     */
    public MortarFrameResult render() {
        ensurePanel();
        if (width < 1f || height < 1f || entries.isEmpty()) {
            return MortarFrameResult.NONE;
        }

        MortarTheme theme = MortarTheme.capture();
        for (Entry e : entries) {
            states.computeIfAbsent(e.id, k -> new PartState()).touchedThisFrame = true;
        }

        panel.draw(width, height, canvas -> {
            MortarPainter g = new MortarPainter(canvas, theme, fonts);
            for (Entry e : entries) {
                e.part.paint(g, e.x, e.y, e.w, e.h, states.get(e.id));
            }
        });

        // Hit-test against this frame's rects using the panel's invisible button.
        boolean regionHovered = ImGui.isItemHovered();
        boolean regionActive = ImGui.isItemActive();
        String hoveredId = null;
        if (regionHovered) {
            float localX = panel.getItemRelativeMouseX();
            float localY = panel.getItemRelativeMouseY();
            // Iterate in reverse so later (topmost) parts win overlaps.
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = entries.get(i);
                if (e.contains(localX, localY)) {
                    hoveredId = e.id;
                    break;
                }
            }
        }

        String clickedId = null;
        String doubleClickedId = null;
        String rightClickedId = null;
        if (hoveredId != null) {
            if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
                clickedId = hoveredId;
            }
            if (ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)) {
                doubleClickedId = hoveredId;
            }
            if (ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
                rightClickedId = hoveredId;
            }
        }

        retarget(hoveredId, regionActive);
        evictUntouched();

        return new MortarFrameResult(hoveredId, clickedId, doubleClickedId, rightClickedId);
    }

    /** Advance every live part's animation by {@code dt} seconds. */
    public void update(float dt) {
        for (PartState state : states.values()) {
            state.update(dt);
        }
    }

    private void retarget(String hoveredId, boolean regionActive) {
        for (Entry e : entries) {
            PartState state = states.get(e.id);
            boolean hovered = e.id.equals(hoveredId);
            boolean pressed = hovered && regionActive;
            state.setTargets(hovered, pressed, e.selected);
        }
    }

    private void evictUntouched() {
        Iterator<Map.Entry<String, PartState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PartState> entry = it.next();
            if (!entry.getValue().touchedThisFrame) {
                it.remove();
            } else {
                entry.getValue().touchedThisFrame = false;
            }
        }
    }

    private void ensurePanel() {
        if (panel == null) {
            SkijaContext context = SkijaContext.getInstance();
            if (context == null) {
                throw new IllegalStateException("MortarRegion requires an initialized SkijaContext");
            }
            panel = new SkijaImGuiPanel(context);
        }
    }

    @Override
    public void close() {
        if (panel != null) {
            panel.close();
            panel = null;
        }
        fonts.close();
        states.clear();
        entries.clear();
    }
}

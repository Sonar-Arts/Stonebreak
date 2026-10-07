package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiTexts;

/**
 * Tooltips (#288). The element under the pointer, or its nearest ancestor with a
 * {@code tooltip}/{@code tooltipKey}, shows its text after {@link InputSettings#tooltipDelay()}
 * of steady hovering, never while a pointer-anchored element (a carried item) rides the cursor;
 * keyboard and controller focus shows the focused element's tooltip below it, so the text is
 * reachable without a mouse.
 *
 * <p>A tooltip hides on a press, wheel, key press, drag, when its element becomes unable to
 * receive input, and when interactions are cancelled. After a press it stays hidden until the
 * pointer moves onto another element. It never takes input itself.
 */
public final class TooltipController {

    /**
     * What to draw.
     *
     * @param anchor  the target's rect (device pixels)
     * @param x       pointer position (or the anchor's bottom-left for focus tooltips)
     * @param fromFocus shown for keyboard/controller focus rather than hover
     */
    public record Tooltip(UiElement target, String text, UiRect anchor, float x, float y, boolean fromFocus) {
    }

    private final java.util.function.Supplier<InputSettings> settings;
    private final java.util.function.BooleanSupplier ghost;
    private UiElement hoverTarget;
    private float pointerX;
    private float pointerY;
    private double hoverTime;
    private boolean suppressed;
    private UiElement focusTarget;
    private Tooltip current;

    TooltipController(java.util.function.Supplier<InputSettings> settings) {
        this(settings, () -> false);
    }

    /** @param ghost whether something rides the cursor (a carried item): no hover tooltip then */
    TooltipController(java.util.function.Supplier<InputSettings> settings, java.util.function.BooleanSupplier ghost) {
        this.settings = settings;
        this.ghost = ghost;
    }

    public Tooltip current() {
        return current;
    }

    void pointer(UiElement under, float x, float y) {
        UiElement t = owner(under);
        pointerX = x;
        pointerY = y;
        if (t != hoverTarget) {
            hoverTarget = t;
            hoverTime = 0;
            suppressed = false;
            if (current != null && !current.fromFocus()) {
                current = null;
            }
        }
        refresh();
    }

    void focus(UiElement focused, boolean visible) {
        focusTarget = visible ? owner(focused) : null;
        if (current != null && current.fromFocus() && current.target() != focusTarget) {
            current = null;
        }
        refresh();
    }

    void tick(double dt) {
        if (hoverTarget != null && !suppressed) {
            hoverTime += dt;
        }
        refresh();
    }

    /**
     * Hides the hover tooltip until the pointer reaches another element. A focus tooltip stays:
     * it follows keyboard and controller focus, which is exactly what those keys move.
     */
    void hide() {
        suppressed = true;
        if (current != null && !current.fromFocus()) {
            current = null;
        }
    }

    void clear() {
        hoverTarget = null;
        focusTarget = null;
        current = null;
        hoverTime = 0;
        suppressed = false;
    }

    /** Drops targets that can no longer receive input. */
    void validate() {
        if (hoverTarget != null && !InputTraits.canReceivePointer(hoverTarget)) {
            hoverTarget = null;
        }
        if (focusTarget != null && !InputTraits.canReceivePointer(focusTarget)) {
            focusTarget = null;
        }
        if (current != null && !InputTraits.canReceivePointer(current.target())) {
            current = null;
        }
        refresh();
    }

    private void refresh() {
        if (hoverTarget != null && !suppressed && hoverTime >= settings.get().tooltipDelay() && !ghost.getAsBoolean()) {
            String text = UiTexts.tooltip(hoverTarget);
            if (!text.isEmpty()) {
                current = new Tooltip(hoverTarget, text, hoverTarget.rect(), pointerX, pointerY, false);
                return;
            }
        }
        if (focusTarget != null) {
            String text = UiTexts.tooltip(focusTarget);
            if (!text.isEmpty()) {
                UiRect r = focusTarget.rect();
                current = new Tooltip(focusTarget, text, r, r.x(), r.bottom(), true);
                return;
            }
        }
        if (current != null && (current.fromFocus() ? focusTarget == null
            : hoverTarget == null || suppressed || ghost.getAsBoolean())) {
            current = null;
        }
    }

    private static UiElement owner(UiElement el) {
        for (UiElement e = el; e != null; e = e.parent()) {
            if (!UiTexts.tooltip(e).isEmpty()) {
                return InputTraits.canReceivePointer(e) ? e : null;
            }
        }
        return null;
    }
}

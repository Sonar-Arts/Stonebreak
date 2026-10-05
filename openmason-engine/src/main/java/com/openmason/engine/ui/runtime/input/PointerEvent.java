package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.runtime.UiElement;

/**
 * Pointer down/up/move/enter/leave/cancel and click (#288). Positions are device pixels of the
 * document's viewport (the frame the document is laid out in; the editor converts its own
 * coordinates first with {@code PreviewMapping}); {@link #localX()}/{@link #localY()} are
 * relative to the element whose handler is running, through {@link UiCoordinates}.
 */
public final class PointerEvent extends UiEvent {

    /** Primary (left) button. */
    public static final int PRIMARY = 0;
    public static final int SECONDARY = 1;
    public static final int MIDDLE = 2;

    private final float x;
    private final float y;
    private final float scale;
    private final int button;
    private final int modifiers;
    private final int clickCount;
    private final InputDevice device;

    public PointerEvent(UiEventType type, double time, float x, float y, float scale, int button, int modifiers,
                        int clickCount, InputDevice device) {
        super(type, time);
        this.x = x;
        this.y = y;
        this.scale = scale;
        this.button = button;
        this.modifiers = modifiers;
        this.clickCount = clickCount;
        this.device = device;
    }

    /** Device-pixel x in the viewport. NaN for a keyboard/controller click. */
    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    /** Logical (authored) pixels: device pixels divided by the document's scale. */
    public float logicalX() {
        return x / scale;
    }

    public float logicalY() {
        return y / scale;
    }

    /** Device pixels relative to the current target's top-left, through its inverse transform. */
    public float localX() {
        UiElement el = currentTarget() != null ? currentTarget() : target();
        return el == null ? x : UiCoordinates.toLocal(el, x, y)[0];
    }

    public float localY() {
        UiElement el = currentTarget() != null ? currentTarget() : target();
        return el == null ? y : UiCoordinates.toLocal(el, x, y)[1];
    }

    /** The button that changed; {@code -1} for moves. */
    public int button() {
        return button;
    }

    /** GLFW modifier bits ({@code MKeys.MOD_*}). */
    public int modifiers() {
        return modifiers;
    }

    /** 1 for a single click, 2 for a double click, ... */
    public int clickCount() {
        return clickCount;
    }

    public InputDevice device() {
        return device;
    }
}

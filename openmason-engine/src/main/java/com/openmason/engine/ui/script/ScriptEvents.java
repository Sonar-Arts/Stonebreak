package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.input.ChangeEvent;
import com.openmason.engine.ui.runtime.input.CompositionEvent;
import com.openmason.engine.ui.runtime.input.DismissEvent;
import com.openmason.engine.ui.runtime.input.DragEvent;
import com.openmason.engine.ui.runtime.input.FocusEvent;
import com.openmason.engine.ui.runtime.input.KeyEvent;
import com.openmason.engine.ui.runtime.input.NavigationEvent;
import com.openmason.engine.ui.runtime.input.PointerEvent;
import com.openmason.engine.ui.runtime.input.TextInputEvent;
import com.openmason.engine.ui.runtime.input.UiEvent;
import com.openmason.engine.ui.runtime.input.UiEventType;
import com.openmason.engine.ui.runtime.input.WheelEvent;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * UI events as Lua sees them (#292): kebab-case names ({@code click}, {@code pointer-down},
 * {@code key-down}) and plain tables with the event's data. Element references are keys; the
 * prelude turns them into {@code ui.Element}s. Coordinates are logical px.
 */
final class ScriptEvents {

    /** Flags a handler sets on its event (mirrored in the prelude's event methods). */
    static final int STOP = 1;
    static final int STOP_IMMEDIATE = 2;
    static final int PREVENT = 4;
    static final int ACCEPT_DROP = 8;

    /** Events the {@code on_input} hook sees: raw input before the element handlers. */
    static final List<UiEventType> INPUT = List.of(UiEventType.KEY_DOWN, UiEventType.KEY_UP, UiEventType.TEXT_INPUT,
        UiEventType.NAVIGATE, UiEventType.SUBMIT, UiEventType.CANCEL, UiEventType.POINTER_DOWN,
        UiEventType.POINTER_UP, UiEventType.WHEEL);

    private ScriptEvents() {
    }

    static String name(UiEventType type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The event type for a Lua name, or null. */
    static UiEventType type(String name) {
        if (name == null) {
            return null;
        }
        try {
            return UiEventType.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String names() {
        return String.join(", ", Arrays.stream(UiEventType.values()).map(ScriptEvents::name).toList());
    }

    static UiValue.Obj encode(UiEvent ev, float scale) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("type", UiValue.of(name(ev.type())));
        key(m, "target", ev.target());
        key(m, "current", ev.currentTarget());
        m.put("phase", UiValue.of(ev.phase().name().toLowerCase(Locale.ROOT)));
        float s = scale <= 0 ? 1 : scale;
        switch (ev) {
            case PointerEvent p -> {
                m.put("x", UiValue.of(p.logicalX()));
                m.put("y", UiValue.of(p.logicalY()));
                m.put("lx", UiValue.of(p.localX() / s));
                m.put("ly", UiValue.of(p.localY() / s));
                m.put("button", UiValue.of(p.button()));
                m.put("mods", UiValue.of(p.modifiers()));
                m.put("clicks", UiValue.of(p.clickCount()));
                m.put("device", UiValue.of(p.device().name().toLowerCase(Locale.ROOT)));
            }
            case KeyEvent k -> {
                m.put("key", UiValue.of(k.key()));
                m.put("mods", UiValue.of(k.modifiers()));
                m.put("repeat", UiValue.of(k.isRepeat()));
                if (k.action() != null) {
                    m.put("action", UiValue.of(k.action().name().toLowerCase(Locale.ROOT)));
                }
            }
            case WheelEvent w -> {
                m.put("x", UiValue.of(w.x() / s));
                m.put("y", UiValue.of(w.y() / s));
                m.put("dx", UiValue.of(w.deltaX()));
                m.put("dy", UiValue.of(w.deltaY()));
                m.put("mods", UiValue.of(w.modifiers()));
            }
            case TextInputEvent t -> m.put("text", UiValue.of(t.text()));
            case ChangeEvent c -> {
                m.put("value", UiValue.of(c.value()));
                m.put("previous", UiValue.of(c.previous()));
            }
            case CompositionEvent c -> {
                m.put("text", UiValue.of(c.text()));
                m.put("cursor", UiValue.of(c.cursor()));
            }
            case NavigationEvent n -> {
                m.put("action", UiValue.of(n.action().name().toLowerCase(Locale.ROOT)));
                m.put("repeat", UiValue.of(n.isRepeat()));
            }
            case FocusEvent f -> key(m, "related", f.related());
            case DragEvent d -> {
                m.put("x", UiValue.of(d.x() / s));
                m.put("y", UiValue.of(d.y() / s));
                if (d.payload() instanceof UiValue v) {
                    m.put("payload", v);
                }
            }
            case DismissEvent d -> m.put("reason", UiValue.of(d.reason().name().toLowerCase(Locale.ROOT)));
            default -> {
            }
        }
        return new UiValue.Obj(m);
    }

    /** Applies the flags a Lua handler set. */
    static void apply(UiEvent ev, int flags) {
        if ((flags & STOP_IMMEDIATE) != 0) {
            ev.stopImmediatePropagation();
        } else if ((flags & STOP) != 0) {
            ev.stopPropagation();
        }
        if ((flags & PREVENT) != 0) {
            ev.preventDefault();
        }
        if ((flags & ACCEPT_DROP) != 0 && ev instanceof DragEvent d) {
            d.acceptDrop();
        }
    }

    private static void key(Map<String, UiValue> m, String field, UiElement el) {
        if (el != null) {
            m.put(field, UiValue.of(el.key()));
        }
    }
}

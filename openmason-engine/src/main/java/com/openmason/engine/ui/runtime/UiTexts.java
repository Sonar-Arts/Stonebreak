package com.openmason.engine.ui.runtime;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.l10n.UiLocalizer;
import com.openmason.engine.ui.runtime.widget.InputProps;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The text an element shows, after localization (#288). Measuring, painting, tooltips and the
 * accessibility tree all read text through here, so a locale change reaches every one of them
 * the same way.
 *
 * <p>A {@code *Key} property names a message in the context's {@link UiLocalizer}
 * (ICU-style: arguments, plurals, selects; {@code textArgs} supplies arguments). When the key
 * resolves nowhere, the plain property ({@code text}, {@code tooltip}, {@code placeholder}) is
 * the fallback, else the key itself, and a {@code MISSING_TEXT_KEY} warning is reported once:
 * a missing translation is visible in diagnostics, never a blank control.
 */
public final class UiTexts {

    private UiTexts() {
    }

    /** A {@code Label}'s text: {@code textKey} with {@code textArgs}, else {@code text}. */
    public static String label(UiElement el) {
        return localized(el, "textKey", el.text("text"));
    }

    /** Tooltip text ({@code tooltipKey}, else {@code tooltip}), or "" when the element has none. */
    public static String tooltip(UiElement el) {
        String plain = el.prop(InputProps.TOOLTIP) instanceof UiValue.Str s ? s.value() : "";
        return localized(el, InputProps.TOOLTIP_KEY, plain);
    }

    /** A {@code TextField}'s placeholder ({@code placeholderKey}, else {@code placeholder}). */
    public static String placeholder(UiElement el) {
        return localized(el, "placeholderKey", el.text("placeholder"));
    }

    private static String localized(UiElement el, String keyProp, String plain) {
        if (!(el.prop(keyProp) instanceof UiValue.Str key) || key.value().isEmpty()) {
            return plain;
        }
        UiLocalizer l10n = el.owner().context().localizer();
        if (!l10n.has(key.value())) {
            el.owner().reportDiagnostic(UiRuntimeDiagnostic.warning(UiRuntimeDiagnostic.Code.MISSING_TEXT_KEY,
                el.key(), "no message " + key.value() + " for " + l10n.locale().toLanguageTag()));
        }
        return l10n.text(key.value(), args(el.prop("textArgs")), plain.isEmpty() ? null : plain);
    }

    /** {@code textArgs} as formatter arguments: integral numbers become longs, so plurals and grouping work. */
    static Map<String, Object> args(UiValue value) {
        if (!(value instanceof UiValue.Obj obj)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        obj.fields().forEach((k, v) -> {
            switch (v) {
                case UiValue.Num n -> out.put(k, n.isIntegral() ? (Object) (long) n.value() : (Object) n.value());
                case UiValue.Str s -> out.put(k, s.value());
                case UiValue.Bool b -> out.put(k, b.value());
                default -> {
                }
            }
        });
        return out;
    }
}

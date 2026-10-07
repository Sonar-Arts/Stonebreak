package com.openmason.main.systems.uiEditor.view;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiStyleProperties;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.anim.AnimProperty;
import com.openmason.engine.ui.runtime.widget.PropertyDescriptor;
import com.openmason.main.systems.uiEditor.view.widgets.ValueFields;

import java.util.ArrayList;
import java.util.List;

/**
 * The value field of a Timeline key (#295), chosen by the track's property: lengths, numbers,
 * opacity, colours, keywords, assets (sprite frames) and widget properties by their type.
 */
final class TimelineKeyFields {

    private TimelineKeyFields() {
    }

    /** One value field; the result's value is null when nothing was committed. */
    static ValueFields.Result field(String id, String target, UiValue value, UiElement el, OmuiArchive doc, float width) {
        if (target.startsWith(AnimProperty.STYLE)) {
            String name = target.substring(AnimProperty.STYLE.length());
            UiStyleProperties.Spec spec = UiStyleProperties.spec(name);
            if (spec == null) {
                return ValueFields.json(id, value, width);
            }
            return switch (spec.kind()) {
                case LENGTH -> ValueFields.length(id, value, null, true, width);
                case LENGTH_NO_AUTO -> ValueFields.length(id, value, null, false, width);
                case NUMBER -> ValueFields.number(id, value, null, 0.05f, -100000f, 100000f, false, width);
                case UNIT_INTERVAL -> ValueFields.unit(id, value, null, width);
                case COLOR -> ValueFields.color(id, value, null, List.of(), width);
                case KEYWORD -> ValueFields.keyword(id, value, null, spec.keywords(), width);
                case ASSET -> ValueFields.asset(id, value, null, assetIds(doc), width);
                case STRING -> ValueFields.text(id, value instanceof UiValue.Str s ? s.value() : null, "", width,
                    UiValue::of);
            };
        }
        String name = target.substring(AnimProperty.PROP.length());
        PropertyDescriptor p = el == null ? null : el.descriptor().property(name);
        if (p == null) {
            return ValueFields.json(id, value, width);
        }
        return switch (p.type()) {
            case NUMBER -> ValueFields.number(id, value, null, 0.05f, -100000f, 100000f, false, width);
            case INT -> ValueFields.number(id, value, null, 0.2f, -100000f, 100000f, true, width);
            case BOOL -> ValueFields.bool(id, value, null);
            case COLOR -> ValueFields.color(id, value, null, List.of(), width);
            case ASSET -> ValueFields.asset(id, value, null, assetIds(doc), width);
            case STRING -> ValueFields.text(id, value instanceof UiValue.Str s ? s.value() : null, "", width, UiValue::of);
            default -> ValueFields.json(id, value, width);
        };
    }

    /** Animatable targets of {@code el}: style properties that blend or switch, then its widget properties. */
    static List<String> animatable(UiElement el) {
        List<String> out = new ArrayList<>();
        for (String s : UiStyleProperties.names()) {
            if (AnimProperty.style(s) != null) {
                out.add(AnimProperty.STYLE + s);
            }
        }
        if (el != null) {
            el.descriptor().properties().keySet().stream().sorted().forEach(n -> out.add(AnimProperty.PROP + n));
        }
        return out;
    }

    private static List<String> assetIds(OmuiArchive doc) {
        List<String> ids = new ArrayList<>();
        doc.dependencies().entries().forEach(d -> ids.add(d.id()));
        return ids;
    }
}

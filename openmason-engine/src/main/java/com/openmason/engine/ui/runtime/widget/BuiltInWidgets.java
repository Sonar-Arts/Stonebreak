package com.openmason.engine.ui.runtime.widget;

import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;

import java.util.List;
import java.util.Set;

import static com.openmason.engine.ui.runtime.widget.WidgetRegistry.prop;
import static com.openmason.engine.ui.runtime.widget.WidgetRegistry.props;

/**
 * Descriptors of the schema-1.0 built-in widgets: the set the pause and furnace pilots need
 * (#283 contracts). Extend them, with a version bump, as later screens migrate.
 */
public final class BuiltInWidgets {

    public static final WidgetDescriptor BOX = WidgetDescriptor.of("Box", 1, true, false,
        "Flex container with optional background", props());

    public static final WidgetDescriptor LABEL = WidgetDescriptor.of("Label", 1, false, true,
        "Single run of text, measured by the host font and placed by baseline",
        props(prop("text", ValueType.STRING, UiValue.of(""), "Displayed text (the fallback when textKey is set)"),
            prop("textKey", ValueType.STRING, UiValue.NULL, "Localized message key (ui-l10n)"),
            prop("textArgs", ValueType.OBJECT, UiValue.NULL, "Arguments of the localized message")));

    public static final WidgetDescriptor BUTTON = WidgetDescriptor.of("Button", 1, true, false,
        "Pressable Masonry stone surface; content goes in children", props());

    public static final WidgetDescriptor IMAGE = WidgetDescriptor.of("Image", 1, false, true,
        "SBT, OMT or sprite region; intrinsic size comes from the asset",
        props(prop("source", ValueType.ASSET, UiValue.NULL, "Texture or sprite reference")));

    public static final WidgetDescriptor ITEM_SLOT = WidgetDescriptor.of("ItemSlot", 1, false, false,
        "Masonry slot frame; a host draw provider paints the icon and count",
        props(prop("provider", ValueType.STRING, UiValue.NULL, "Host draw provider id"),
            prop("slot", ValueType.INT, UiValue.NULL, "Slot index passed to the provider")));

    public static final WidgetDescriptor DRAW_PROVIDER = WidgetDescriptor.of("DrawProvider", 1, false, false,
        "Host immediate drawing inside the element's rect",
        props(prop("provider", ValueType.STRING, UiValue.NULL, "Host draw provider id")));

    /**
     * Scroll container (needs the {@code ui-scroll} feature): content may exceed the element;
     * offsets come from the runtime ({@code scrollTo}, input routing), children are clipped.
     */
    public static final WidgetDescriptor SCROLL_VIEW = WidgetDescriptor.of("ScrollView", 1, true, false,
        "Clipped container whose content scrolls",
        props(prop("vertical", ValueType.BOOL, UiValue.TRUE, "Scrolls along y"),
            prop("horizontal", ValueType.BOOL, UiValue.FALSE, "Scrolls along x")));

    /**
     * Editable text (needs the {@code ui-input} feature, #288): caret, selection, clipboard,
     * IME composition, validation and commit run in the runtime's text controller; the value
     * is the {@code text} property.
     */
    public static final WidgetDescriptor TEXT_FIELD = WidgetDescriptor.of("TextField", 1, false, true,
        "Editable single- or multi-line text",
        props(prop("text", ValueType.STRING, UiValue.of(""), "Current value"),
            prop("placeholder", ValueType.STRING, UiValue.of(""), "Shown while empty"),
            prop("placeholderKey", ValueType.STRING, UiValue.NULL, "Localized placeholder key (ui-l10n)"),
            prop("multiline", ValueType.BOOL, UiValue.FALSE, "Enter inserts a line break"),
            prop("maxLength", ValueType.INT, UiValue.of(-1), "Maximum length in user-perceived characters; -1 = none"),
            prop("readOnly", ValueType.BOOL, UiValue.FALSE, "Selectable and copyable, not editable"),
            prop("password", ValueType.BOOL, UiValue.FALSE, "Masks the value; copy and cut are refused"),
            new PropertyDescriptor("inputFilter", ValueType.STRING, UiValue.of("any"),
                "Characters accepted while typing and pasting", Set.of("any", "ascii", "digits", "integer",
                "decimal", "identifier")),
            prop("pattern", ValueType.STRING, UiValue.NULL, "Regular expression the value must fully match to commit"),
            prop("commitOnBlur", ValueType.BOOL, UiValue.TRUE, "Losing focus commits a valid value")));

    /** Instance nodes become a container element whose one child is the component's root. */
    public static final WidgetDescriptor INSTANCE = WidgetDescriptor.of(UiNode.INSTANCE_TYPE, 1, true, false,
        "Component instance container (Unity's TemplateContainer)", props());

    private BuiltInWidgets() {
    }

    public static List<WidgetDescriptor> all() {
        return List.of(BOX, LABEL, BUTTON, IMAGE, ITEM_SLOT, DRAW_PROVIDER, SCROLL_VIEW, TEXT_FIELD, INSTANCE);
    }
}

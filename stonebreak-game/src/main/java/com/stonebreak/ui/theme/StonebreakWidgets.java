package com.stonebreak.ui.theme;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.ValueType;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.widget.PropertyDescriptor;
import com.openmason.engine.ui.runtime.widget.WidgetDescriptor;
import com.openmason.engine.ui.theme.UiThemeWidget;
import com.stonebreak.ui.masonry.MItemSlot;

import java.util.List;

/**
 * Stonebreak's document widgets (#384): {@code stonebreak:ItemSlot}, the inventory/hotbar slot,
 * which documents written before #384 still name by its legacy bare type {@code ItemSlot}.
 */
public final class StonebreakWidgets {

    /** The namespaced type. */
    public static final String ITEM_SLOT = "stonebreak:ItemSlot";
    /** The bare type documents used while the slot was an engine built-in (schema 1.0). */
    public static final String ITEM_SLOT_LEGACY = "ItemSlot";

    /** Version 1 = the engine built-in it replaces, property for property. */
    public static final WidgetDescriptor ITEM_SLOT_DESCRIPTOR = WidgetDescriptor.of(ITEM_SLOT, 1, false, false,
        "Masonry slot frame; a host draw provider paints the icon and count",
        List.of(prop("provider", ValueType.STRING, UiValue.NULL, "Host draw provider id"),
            prop("slot", ValueType.INT, UiValue.NULL, "Slot index passed to the provider"),
            prop("item", ValueType.STRING, UiValue.NULL, "Item shown by an item provider: objectId or numeric id"),
            prop("count", ValueType.INT, UiValue.NULL, "Stack count shown by an item provider (drawn when above 1)"),
            prop("state", ValueType.STRING, UiValue.NULL, "SBO state of the shown item"),
            prop("durability", ValueType.NUMBER, UiValue.NULL, "Remaining durability 0..1 (a bar below 1)"),
            prop("stack", ValueType.OBJECT, UiValue.NULL, "Whole slot record {objectId, count, state, durability}"),
            prop("params", ValueType.OBJECT, UiValue.NULL, "Provider-specific parameters")));

    private StonebreakWidgets() {
    }

    /** True for an item slot element type, namespaced or legacy. */
    public static boolean isItemSlot(String type) {
        return ITEM_SLOT.equals(type) || ITEM_SLOT_LEGACY.equals(type);
    }

    /** The slot widget: the recessed Masonry frame (hover, selected-hotbar ring), then its provider. */
    static UiThemeWidget itemSlot() {
        // one frame per painting thread: the theme is process-wide (game, editor preview, tests)
        ThreadLocal<MItemSlot> frames = ThreadLocal.withInitial(MItemSlot::new);
        return new UiThemeWidget(ITEM_SLOT_DESCRIPTOR, p -> {
            MItemSlot frame = frames.get();
            frame.bounds(p.rect().x(), p.rect().y(), p.rect().width(), p.rect().height());
            frame.setHovered(p.element().hasState(UiElement.HOVER));
            frame.hotbarSelected(selectedSlot(p.element()));
            frame.render(p.masonry());
            p.provider().run();
        }, "cell", true);
    }

    /** A slot record ({@code stack} prop) flagged {@code selected}: the selected hotbar slot's ring. */
    private static boolean selectedSlot(UiElement el) {
        return el.prop("stack") instanceof UiValue.Obj o && o.get("selected") instanceof UiValue.Bool b && b.value();
    }

    private static PropertyDescriptor prop(String name, ValueType type, UiValue def, String description) {
        return new PropertyDescriptor(name, type, def, description);
    }
}

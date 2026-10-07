package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.masonry.MButton;
import com.openmason.engine.ui.masonry.MCategoryButton;
import com.openmason.engine.ui.masonry.MDropdown;
import com.openmason.engine.ui.masonry.MSlider;
import com.openmason.engine.ui.masonry.MWidget;
import com.stonebreak.ui.settingsMenu.SettingsMenu;
import com.stonebreak.ui.settingsMenu.config.CategoryState;
import com.stonebreak.ui.settingsMenu.managers.StateManager;
import com.stonebreak.ui.settingsMenu.renderers.SkijaSettingsRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The settings screen's host contract ({@code stonebreak:screen.settings} 1, #299), next to the
 * value contract {@code stonebreak:settings}: the legacy {@link SettingsMenu} stays the controller
 * (widgets, labels, scroll easing, dropdowns, the UI-scale confirmation and its countdown, Apply's
 * side effects, where Back goes), root {@code settingsMenu} mirrors it and the actions call it.
 *
 * <p>Root {@code settingsMenu}: {@code titleY} and the scroll {@code offset} (device px), the six
 * {@code categories} ({@code name}, {@code selected}), the selected category's {@code rows}
 * ({@code id} = the setting, {@code kind} button / dropdown / slider, {@code label} as the widget
 * shows it, a slider's {@code fraction}, a dropdown's {@code open} and {@code items}
 * ({@code text}, {@code current})), the {@code scrollbar} thumb ({@code thumbTop} from the viewport
 * top, {@code thumbHeight}, device px) and the {@code confirm}ation ({@code shown}, {@code pending},
 * {@code countdown}).
 *
 * <p>Actions {@code stonebreak:screen.settings.<name>}: {@code press {target, fraction}}
 * ({@link SettingsMenu#press}), {@code drag {fraction}}, {@code scrollbar {x, y}} and
 * {@code scrollbar-drag {y}} (device px), {@code release}, {@code wheel {x, y, dy}},
 * {@code key {key, shift}}.
 */
public final class SettingsMenuContracts {

    public static final HostContract SCREEN = HostContract.of("stonebreak:screen.settings", 1);
    /** The most rows a category has, and the longest dropdown list (the resolutions). */
    public static final int MAX_ROWS = 8;
    public static final int MAX_ITEMS = 7;

    static final DataType.Obj ITEM = DataType.object("id", DataType.string(), "text", DataType.string(),
        "current", DataType.bool());
    static final DataType.Obj ROW = DataType.object("id", DataType.string(), "index", DataType.integer(),
        "kind", DataType.string(), "label", DataType.string(), "fraction", DataType.number(),
        "open", DataType.bool(), "items", DataType.list(ITEM, "id"));
    static final DataType.Obj CATEGORY = DataType.object("id", DataType.string(), "index", DataType.integer(),
        "name", DataType.string(), "selected", DataType.bool());
    static final DataType.Obj CONFIRM = DataType.object("shown", DataType.bool(), "pending", DataType.string(),
        "countdown", DataType.string());
    public static final DataType.Obj TYPE = DataType.object("titleY", DataType.number(), "offset", DataType.number(),
        "categories", DataType.list(CATEGORY, "id"), "rows", DataType.list(ROW, "id"),
        "scrollbar", DataType.bool(), "thumbTop", DataType.number(), "thumbHeight", DataType.number(),
        "confirm", CONFIRM);

    /** Each action's parameters (all numbers but {@code press}'s target). */
    static final Map<String, DataType.Obj> ACTIONS = Map.of(
        "press", DataType.object("target", DataType.string(), "fraction", DataType.number()),
        "drag", DataType.object("fraction", DataType.number()),
        "scrollbar", DataType.object("x", DataType.number(), "y", DataType.number()),
        "scrollbar-drag", DataType.object("y", DataType.number()),
        "release", DataType.object(),
        "wheel", DataType.object("x", DataType.number(), "y", DataType.number(), "dy", DataType.number()),
        "key", DataType.object("key", DataType.integer(), "shift", DataType.bool()));

    /** The game behind the screen. */
    public interface Services extends MenuWindow {
        /** The settings menu while its document shows (it lays out once a frame), or null. */
        default SettingsMenu settingsMenu() {
            return null;
        }

        /** Runs action {@code name} ({@link #perform}); null on success or why it refused. */
        default String settingsAction(String name, UiValue.Obj args) {
            return "no settings screen is showing";
        }
    }

    private final Services services;
    private final DataCell cell;
    private UiValue last;

    public SettingsMenuContracts(UiHost ui, Services services) {
        this.services = services;
        cell = ui.data().register("settingsMenu", new DataCell(TYPE, value(null, null)), SCREEN);
        ACTIONS.forEach((name, params) -> ui.actions().register(ActionSpec.of(SCREEN.id() + "." + name, SCREEN,
            params, DataType.ANY).withReentrancy(ActionSpec.Reentrancy.PARALLEL), (args, ctx) -> {
                try {
                    String problem = services.settingsAction(name, args);
                    poll(false); // the document sees the result before the next frame
                    return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
                        : CompletableFuture.failedFuture(new IllegalStateException(problem));
                } catch (RuntimeException e) {
                    return CompletableFuture.failedFuture(e);
                }
            }));
    }

    /** Action {@code name} on {@code menu} by the legacy handlers' rules: null on success, else why not. */
    public static String perform(SettingsMenu menu, String name, UiValue.Obj args) {
        if (menu == null) {
            return "no settings screen is showing";
        }
        switch (name) {
            case "press" -> {
                return menu.press(str(args, "target"), (float) num(args, "fraction")) ? null : "nothing to press there";
            }
            case "drag" -> menu.drag((float) num(args, "fraction"));
            case "scrollbar" -> {
                return menu.scrollbarPress((float) num(args, "x"), (float) num(args, "y")) ? null : "not on the scrollbar";
            }
            case "scrollbar-drag" -> menu.scrollbarDrag((float) num(args, "y"));
            case "release" -> menu.release();
            case "wheel" -> menu.wheel((float) num(args, "x"), (float) num(args, "y"), (float) num(args, "dy"));
            case "key" -> menu.key((int) num(args, "key"), args != null && args.get("shift") instanceof UiValue.Bool b
                && b.value());
            default -> throw new IllegalArgumentException("no settings action " + name);
        }
        return null;
    }

    /** UI thread, once per frame: lays the menu out (its scroll easing steps) and republishes what changed. */
    public void poll() {
        poll(true);
    }

    private void poll(boolean frame) {
        SettingsMenu menu = services.settingsMenu();
        SkijaSettingsRenderer.Frame f = null;
        if (menu != null) {
            if (frame) {
                menu.tick();
            }
            int[] w = services.menuWindow();
            // an action's republish must not step the easing a second time this frame
            f = frame ? menu.layout(w[0], w[1]) : lastFrame;
            lastFrame = f;
        }
        UiValue v = value(menu, f);
        if (!v.equals(last)) {
            last = v;
            cell.set(v);
        }
    }

    private SkijaSettingsRenderer.Frame lastFrame;

    static UiValue.Obj value(SettingsMenu menu, SkijaSettingsRenderer.Frame f) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        StateManager st = menu == null ? null : menu.getStateManager();
        m.put("titleY", UiValue.of(f == null ? 0f : f.titleY()));
        m.put("offset", UiValue.of(menu == null ? 0f : menu.getScrollContainer().getScrollOffset()));
        List<UiValue> categories = new ArrayList<>();
        List<UiValue> rows = new ArrayList<>();
        if (st != null) {
            for (MCategoryButton<CategoryState> b : st.getCategoryButtons()) {
                categories.add(obj("id", UiValue.of(b.tag().name()), "index", UiValue.of(b.tag().getIndex()),
                    "name", UiValue.of(b.text()), "selected", UiValue.of(b.tag() == st.getSelectedCategory())));
            }
            CategoryState.SettingType[] settings = st.getSelectedCategory().getSettings();
            for (int i = 0; i < settings.length; i++) {
                rows.add(row(settings[i], i, st.widget(settings[i])));
            }
        }
        m.put("categories", new UiValue.Arr(categories));
        m.put("rows", new UiValue.Arr(rows));
        float[] thumb = menu == null ? null : menu.getScrollContainer().thumbBounds();
        float top = menu == null ? 0f : menu.getScrollContainer().getContainerY();
        m.put("scrollbar", UiValue.of(thumb != null));
        m.put("thumbTop", UiValue.of(thumb == null ? 0f : thumb[1] - top));
        m.put("thumbHeight", UiValue.of(thumb == null ? 0f : thumb[3]));
        boolean confirm = st != null && st.isUiScaleConfirmActive();
        m.put("confirm", obj("shown", UiValue.of(confirm),
            "pending", UiValue.of(confirm ? st.uiScalePendingText() : ""),
            "countdown", UiValue.of(confirm ? st.uiScaleCountdownText() : "")));
        return new UiValue.Obj(m);
    }

    private static UiValue row(CategoryState.SettingType type, int index, MWidget w) {
        String kind = w instanceof MSlider ? "slider" : w instanceof MDropdown ? "dropdown" : "button";
        String label = w instanceof MSlider s ? s.displayLabel() : w instanceof MButton b ? b.text() : "";
        List<UiValue> items = new ArrayList<>();
        boolean open = false;
        if (w instanceof MDropdown d) {
            open = d.isOpen();
            String[] texts = d.items();
            for (int k = 0; k < texts.length; k++) {
                items.add(obj("id", UiValue.of("item" + k), "text", UiValue.of(texts[k]),
                    "current", UiValue.of(k == d.selectedIndex())));
            }
        }
        return obj("id", UiValue.of(type.name()), "index", UiValue.of(index), "kind", UiValue.of(kind),
            "label", UiValue.of(label), "fraction", UiValue.of(w instanceof MSlider s ? s.normalized() : 0f),
            "open", UiValue.of(open), "items", new UiValue.Arr(items));
    }

    private static UiValue.Obj obj(Object... kv) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (UiValue) kv[i + 1]);
        }
        return new UiValue.Obj(m);
    }

    private static String str(UiValue.Obj args, String k) {
        return args != null && args.get(k) instanceof UiValue.Str s ? s.value() : "";
    }

    private static double num(UiValue.Obj args, String k) {
        return args != null && args.get(k) instanceof UiValue.Num n ? n.value() : 0;
    }
}

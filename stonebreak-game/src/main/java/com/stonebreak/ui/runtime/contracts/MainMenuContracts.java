package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.ui.LegacyUiClock;
import com.stonebreak.ui.MainMenu;
import com.stonebreak.ui.mainMenu.MainMenuStage;
import com.stonebreak.ui.mainMenu.SkijaMainMenuRenderer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The main menu's host contract ({@code stonebreak:screen.main-menu} 1, #299): root {@code mainMenu}
 * with the splash line and the title animation as {@link MainMenuStage} computes it ({@code titleX},
 * {@code titleY}, {@code shakeX}, {@code shakeY} in device pixels, {@code titleScale},
 * {@code titleRotation} in degrees, {@code splashScale} the splash's beat, {@code splashX}/{@code splashY}
 * the splash's pivot in device pixels), republished every frame it
 * moves; actions {@code .singleplayer}, {@code .multiplayer}, {@code .settings}, {@code .quit} and
 * {@code .title} (the easter-egg click). The legacy {@link MainMenu} stays the controller.
 */
public final class MainMenuContracts {

    public static final HostContract MENU = HostContract.of("stonebreak:screen.main-menu", 1);
    public static final List<String> CHOICES = List.of("singleplayer", "multiplayer", "settings", "quit");

    public static final DataType.Obj TYPE = DataType.object("splash", DataType.string(), "splashScale", DataType.number(),
        "titleX", DataType.number(), "titleY", DataType.number(), "titleScale", DataType.number(),
        "titleRotation", DataType.number(), "shakeX", DataType.number(), "shakeY", DataType.number(),
        "splashX", DataType.number(), "splashY", DataType.number());

    /** The game behind the menu; every method returns null on success or why it refused. */
    public interface Services extends MenuWindow {
        /** The showing main menu, or null. */
        default MainMenu mainMenu() {
            return null;
        }

        /** Button {@code index} of {@link #CHOICES}. */
        default String mainMenuChoose(int index) {
            return "no main menu is showing";
        }

        default String mainMenuTitle() {
            return "no main menu is showing";
        }
    }

    private final Services services;
    private final DataCell cell;
    private UiValue last;

    public MainMenuContracts(UiHost ui, Services services) {
        this.services = services;
        cell = ui.data().register("mainMenu", new DataCell(TYPE, value(null, 0, new int[]{1920, 1080}, 1f)), MENU);
        for (int i = 0; i < CHOICES.size(); i++) {
            int index = i;
            action(ui, CHOICES.get(i), () -> services.mainMenuChoose(index));
        }
        action(ui, "title", services::mainMenuTitle);
    }

    /** UI thread, once per frame: the splash beat and the title motion change continuously. */
    public void poll() {
        MainMenu menu = services.mainMenu();
        UiValue v = value(menu, LegacyUiClock.millis(), services.menuWindow(), services.menuScale());
        if (!v.equals(last)) {
            last = v;
            cell.set(v);
        }
    }

    static UiValue.Obj value(MainMenu menu, long millis, int[] window, float scale) {
        MainMenuStage s = menu == null ? null : menu.getStage();
        Map<String, UiValue> m = new LinkedHashMap<>();
        String splash = menu == null ? null : menu.getCurrentSplashText();
        m.put("splash", UiValue.of(splash == null ? "" : splash));
        m.put("splashScale", SettingsContract.exact(SkijaMainMenuRenderer.splashPulse(millis)));
        m.put("titleX", SettingsContract.exact(s == null ? 0f : s.getTitleOffsetX()));
        m.put("titleY", SettingsContract.exact(s == null ? 0f : s.getTitleOffsetY()));
        m.put("titleScale", SettingsContract.exact(s == null ? 1f : s.getTitleScale()));
        m.put("titleRotation", SettingsContract.exact(s == null ? 0f : s.getTitleRotationDeg()));
        m.put("shakeX", SettingsContract.exact(s == null ? 0f : s.getScreenShakeX()));
        m.put("shakeY", SettingsContract.exact(s == null ? 0f : s.getScreenShakeY()));
        // the splash pivot, as the legacy renderer computes it (device px, exact)
        float[] anchor = SkijaMainMenuRenderer.splashAnchor(window[0], window[1], scale);
        m.put("splashX", SettingsContract.exact(anchor[0]));
        m.put("splashY", SettingsContract.exact(anchor[1]));
        return new UiValue.Obj(m);
    }

    private void action(UiHost ui, String name, java.util.function.Supplier<String> rule) {
        ui.actions().register(ActionSpec.of(MENU.id() + "." + name, MENU, null, DataType.ANY), (args, ctx) -> {
            String problem = rule.get();
            poll();
            return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
                : CompletableFuture.failedFuture(new IllegalStateException(problem));
        });
    }
}

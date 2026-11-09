package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.ui.MainMenu;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The main menu's host contract ({@code stonebreak:screen.main-menu} 3): root {@code mainMenu} with
 * <ul>
 *   <li>{@code splash} — the splash line;</li>
 *   <li>{@code fade} — how black the veil over the live world backdrop is, 0..1 (the showcase's dip
 *       to black; 1 while its world loads);</li>
 *   <li>{@code reveal} — how visible the menu itself is, 0..1 (it waits, briefly, for the world);</li>
 *   <li>{@code loading} — how visible the "Loading main menu" screen is while it waits, 0..1, with its
 *       {@code progress} (0..1) and {@code percent} text, styled as the world loading screen;</li>
 *   <li>{@code staticBackdrop} — show the static dirt backdrop instead (the live one is off or failed);</li>
 *   <li>{@code seedLine} — the corner line naming the showcase seed ("" with no live backdrop, and
 *       over the preview of a saved world);</li>
 *   <li>{@code canResume} — there is a saved world to resume: the RESUME entry shows;</li>
 *   <li>{@code exit} — how far the menu has slid off for Resume, 0..1 (0 otherwise).</li>
 * </ul>
 * Actions {@code .singleplayer}, {@code .multiplayer}, {@code .settings}, {@code .quit}, {@code .resume}.
 * Version 2 dropped the title animation and its {@code .title} easter-egg action with the dirt/space
 * stage; version 3 added Resume ({@code canResume}, {@code exit}, {@code .resume}).
 * {@link MainMenu} stays the controller; the backdrop is {@code ui.mainMenu.showcase.MenuShowcase}.
 */
public final class MainMenuContracts {

    public static final HostContract MENU = HostContract.of("stonebreak:screen.main-menu", 3);
    /** In {@link MainMenu#choose} order: appended, so the earlier entries keep their indices. */
    public static final List<String> CHOICES = List.of("singleplayer", "multiplayer", "settings", "quit", "resume");

    public static final DataType.Obj TYPE = DataType.object("splash", DataType.string(), "fade", DataType.number(),
        "reveal", DataType.number(), "staticBackdrop", DataType.bool(), "seedLine", DataType.string(),
        "loading", DataType.number(), "progress", DataType.number(), "percent", DataType.string(),
        "canResume", DataType.bool(), "exit", DataType.number());

    /** What the backdrop shows this frame, and the Resume entry's state. */
    public record Backdrop(float fade, float reveal, boolean staticBackdrop, String seedLine, float progress,
                           boolean canResume, float exit) {
        /** No live world: the static backdrop, the menu shown at once. */
        public static final Backdrop STATIC = new Backdrop(0f, 1f, true, "", 1f, false, 0f);

        /** Without Resume (no saved world, the menu at rest). */
        public Backdrop(float fade, float reveal, boolean staticBackdrop, String seedLine, float progress) {
            this(fade, reveal, staticBackdrop, seedLine, progress, false, 0f);
        }

        /** This backdrop with the Resume entry's state. */
        public Backdrop withResume(boolean canResume, float exit) {
            return new Backdrop(fade, reveal, staticBackdrop, seedLine, progress, canResume, exit);
        }

        /** The loading screen shows while the menu waits, and fades out as the menu fades in. */
        public float loading() {
            return staticBackdrop ? 0f : 1f - clamp(reveal);
        }
    }

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

        /** The live backdrop's state this frame. */
        default Backdrop mainMenuBackdrop() {
            return Backdrop.STATIC;
        }
    }

    private final Services services;
    private final DataCell cell;
    private UiValue last;

    public MainMenuContracts(UiHost ui, Services services) {
        this.services = services;
        cell = ui.data().register("mainMenu", new DataCell(TYPE, value(null, Backdrop.STATIC)), MENU);
        for (int i = 0; i < CHOICES.size(); i++) {
            int index = i;
            action(ui, CHOICES.get(i), () -> services.mainMenuChoose(index));
        }
    }

    /** UI thread, once per frame: the veil and the reveal move while the backdrop fades. */
    public void poll() {
        MainMenu menu = services.mainMenu();
        Backdrop backdrop = menu == null ? Backdrop.STATIC : services.mainMenuBackdrop();
        UiValue v = value(menu, backdrop == null ? Backdrop.STATIC : backdrop);
        if (!v.equals(last)) {
            last = v;
            cell.set(v);
        }
    }

    static UiValue.Obj value(MainMenu menu, Backdrop backdrop) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        String splash = menu == null ? null : menu.getCurrentSplashText();
        m.put("splash", UiValue.of(splash == null ? "" : splash));
        m.put("fade", SettingsContract.exact(clamp(backdrop.fade())));
        m.put("reveal", SettingsContract.exact(clamp(backdrop.reveal())));
        m.put("staticBackdrop", UiValue.of(backdrop.staticBackdrop()));
        m.put("seedLine", UiValue.of(backdrop.seedLine() == null ? "" : backdrop.seedLine()));
        float progress = clamp(backdrop.progress());
        m.put("loading", SettingsContract.exact(backdrop.loading()));
        m.put("progress", SettingsContract.exact(progress));
        m.put("percent", UiValue.of(Math.round(progress * 100f) + "%"));
        m.put("canResume", UiValue.of(backdrop.canResume()));
        m.put("exit", SettingsContract.exact(clamp(backdrop.exit())));
        return new UiValue.Obj(m);
    }

    private static float clamp(float v) {
        return Float.isFinite(v) ? Math.max(0f, Math.min(1f, v)) : 0f;
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

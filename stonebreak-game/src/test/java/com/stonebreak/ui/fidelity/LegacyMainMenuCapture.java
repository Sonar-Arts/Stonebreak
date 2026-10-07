package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.MainMenu;
import com.stonebreak.ui.mainMenu.SkijaMainMenuRenderer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The legacy side of the main menu's fidelity gate (#299): {@link SkijaMainMenuRenderer} on a
 * {@link LegacyUiRaster} with a pinned splash ({@value #SPLASH}) and space clock ({@value #SPACE_TIME} s),
 * its title animation driven in fixed 1/60 s steps:
 *
 * <ul>
 *   <li>{@code idle}: the dirt, nothing moving; {@code idle-hover-<button>}.</li>
 *   <li>{@code shake}: one title click, 0.1 s later (the title jitters).</li>
 *   <li>{@code reveal}: two clicks, 0.2 s after the slam's impact (shockwave ring, dirt half gone, screen shake).</li>
 *   <li>{@code space}: two clicks, 1.6 s later (space, the title tilting, the shake's last offset).</li>
 * </ul>
 * Not a test class.
 */
public final class LegacyMainMenuCapture implements MigrationGate.Renderer {

    public static final String SPLASH = "Now with more dirt!";
    public static final double SPACE_TIME = 12.5;
    public static final List<String> BUTTONS = List.of("singleplayer", "multiplayer", "settings", "quit");
    private static final float STEP = 1f / 60f;

    /** A main menu in the variant's animation state. */
    public static MainMenu menu(SkijaUIBackend backend, String variant, int w, int h, float scale) {
        MainMenu menu = new MainMenu(backend);
        menu.setSplashText(SPLASH);
        menu.renderer().setTimeSource(() -> SPACE_TIME);
        String base = variant.split("-")[0];
        switch (base) {
            case "idle" -> { }
            case "shake" -> {
                menu.clickTitle(w, h);
                steps(menu, 6, w, h, scale);
            }
            case "reveal", "space" -> {
                menu.clickTitle(w, h);
                steps(menu, 24, w, h, scale); // the shake settles: armed
                menu.clickTitle(w, h);
                steps(menu, base.equals("reveal") ? 28 : 96, w, h, scale);
            }
            default -> throw new IllegalArgumentException("main menu variant: idle|shake|reveal|space, not " + variant);
        }
        return menu;
    }

    private static void steps(MainMenu menu, int n, int w, int h, float scale) {
        for (int i = 0; i < n; i++) {
            menu.getStage().update(STEP, w, h, scale);
        }
    }

    /**
     * Idle cases: nothing moving. During the easter egg the legacy hit tests ignore the shake and
     * the title's motion (the document's follow them), so only still cases compare input.
     */
    static boolean still(String variant) {
        return variant.startsWith("idle");
    }

    static String hover(String variant) {
        int i = variant.indexOf("-hover-");
        return i < 0 ? "" : variant.substring(i + "-hover-".length());
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        float s = c.viewport().uiScale();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, s)) {
            MainMenu menu = menu(raster.backend(), c.variant(), w, h, s);
            try {
                Map<String, float[]> drawn = new LinkedHashMap<>();
                menu.renderer().setLayoutSink(drawn::put);
                menu.renderer().render(menu, w, h);
                menu.renderer().setLayoutSink(null);
                String hover = hover(c.variant());
                if (!hover.isEmpty()) {
                    float[] r = drawn.get(hover);
                    menu.handleMouseMove(r[0] + r[2] / 2f, r[1] + r[3] / 2f, w, h);
                }
                raster.reset();
                menu.renderer().render(menu, w, h);
                if (!still(c.variant())) {
                    return new MigrationGate.Capture(raster.capture(), drawn);
                }
                Map<String, float[]> hits = new LinkedHashMap<>();
                Map<String, String> actions = new LinkedHashMap<>();
                for (String b : BUTTONS) {
                    hits.put(b, LegacyDeathCapture.probeRegion((x, y) -> {
                        menu.handleMouseMove(x, y, w, h);
                        return menu.getSelectedButton() == BUTTONS.indexOf(b);
                    }, drawn.get(b), w, h));
                    actions.put(b, "stonebreak:screen.main-menu." + b);
                }
                hits.put("logo", LegacyDeathCapture.probeRegion((x, y) -> {
                    var logo = SkijaMainMenuRenderer.computeLogoRect(w, h, s);
                    return x >= logo.getLeft() && x <= logo.getRight() && y >= logo.getTop() && y <= logo.getBottom();
                }, drawn.get("logo"), w, h));
                actions.put("logo", "stonebreak:screen.main-menu.title");
                return new MigrationGate.Capture(raster.capture(), drawn, hits, actions);
            } finally {
                menu.dispose();
            }
        }
    }
}

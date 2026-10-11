package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.MainMenu;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.MenuStageProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the main menu's fidelity gate (#299): the shipped {@code main_menu} document on
 * a {@link DocumentStage}, bound to a {@link MainMenu} with a pinned splash ({@value #SPLASH}) whose title
 * animation is driven in fixed 1/60 s steps, its scene drawn by a {@link MenuStageProvider} over that
 * menu's stage with a pinned space clock ({@value #SPACE_TIME} s):
 *
 * <ul>
 *   <li>{@code idle}: the dirt, nothing moving; {@code idle-hover-<button>}.</li>
 *   <li>{@code shake}: one title click, 0.1 s later (the title jitters).</li>
 *   <li>{@code reveal}: two clicks, 0.2 s after the slam's impact (shockwave ring, dirt half gone, screen shake).</li>
 *   <li>{@code space}: two clicks, 1.6 s later (space, the title tilting, the shake's last offset).</li>
 * </ul>
 * Not a test class.
 */
public final class DocumentMainMenuCapture implements MigrationGate.Renderer {

    public static final String SPLASH = "Now with more dirt!";
    public static final double SPACE_TIME = 12.5;
    public static final List<String> BUTTONS = List.of("singleplayer", "multiplayer", "settings", "quit");
    private static final float STEP = 1f / 60f;

    /** A main menu in the variant's animation state. */
    static MainMenu menu(String variant, int w, int h, float scale) {
        MainMenu menu = new MainMenu();
        menu.setSplashText(SPLASH);
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
     * Idle cases: nothing moving. During the easter egg the legacy hit tests ignored the shake and
     * the title's motion (the document's follow them), so only still cases compare input.
     */
    static boolean still(String variant) {
        return variant.startsWith("idle");
    }

    static String hover(String variant) {
        int i = variant.indexOf("-hover-");
        return i < 0 ? "" : variant.substring(i + "-hover-".length());
    }

    static DocumentStage stage(FidelityCase c, MainMenu[] menuOut) throws Exception {
        int w = c.viewport().width();
        int h = c.viewport().height();
        float s = c.viewport().uiScale();
        RecordingServices services = new RecordingServices();
        services.window = new int[]{w, h};
        UiRaster probe = new UiRaster(w, h, s); // the case's UI scale while the title animates
        try {
            services.mainMenu = menu(c.variant(), w, h, s);
        } finally {
            probe.close();
        }
        menuOut[0] = services.mainMenu;
        MainMenu menu = services.mainMenu;
        return new DocumentStage(GameUiDocuments.readScreen(MainMenu.DOCUMENT_ID), w, h, s, services,
            MultiplayerSession.Mode.MENU, Map.of(MenuStageProvider.ID,
                new MenuStageProvider(menu::getStage, () -> SPACE_TIME)));
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        MainMenu[] menu = new MainMenu[1];
        try (DocumentStage stage = stage(c, menu)) {
            String hover = hover(c.variant());
            if (!hover.isEmpty()) {
                stage.hover(hover);
            }
            stage.paint();
            List<String> parts = new ArrayList<>(List.of("logo"));
            parts.addAll(BUTTONS);
            Map<String, float[]> rects = stage.layoutRects(parts);
            if (!still(c.variant())) {
                return new MigrationGate.Capture(stage.raster.capture(), rects); // hits follow the motion
            }
            Map<String, float[]> clickable = new LinkedHashMap<>(rects);
            return new MigrationGate.Capture(stage.raster.capture(), rects, stage.hits(clickable),
                stage.actions(clickable));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture " + c.id(), e);
        }
    }
}

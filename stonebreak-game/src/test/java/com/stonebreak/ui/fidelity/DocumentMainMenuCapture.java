package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.MainMenu;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.contracts.MainMenuContracts;
import com.stonebreak.ui.runtime.providers.DirtBackdropProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The candidate side of the main menu's gate: the shipped {@code main_menu} document (the live-world
 * redesign, contract {@code stonebreak:screen.main-menu} 3) on a {@link DocumentStage}, with a pinned
 * splash ({@value #SPLASH}) and the backdrop state each variant names:
 *
 * <ul>
 *   <li>{@code live}: the world shows through (the raster's checkerboard stands in for it), the menu revealed;
 *       {@code live-hover-<entry>} with the entry's hover transition run to its end.</li>
 *   <li>{@code loading}: the load wait — the "Loading main menu" screen at 40 % over the black veil, the
 *       menu not yet revealed.</li>
 *   <li>{@code static}: the live backdrop off or failed — the dirt backdrop, the menu shown.</li>
 *   <li>{@code live-resume}: a saved world to resume — the RESUME entry shows above the others;
 *       {@code live-resume-exit} a fifth of the way through Resume's slide-off.</li>
 * </ul>
 * Not a test class.
 */
public final class DocumentMainMenuCapture implements MigrationGate.Renderer {

    public static final String SPLASH = "Now with more dirt!";
    public static final String SEED_LINE = "Seed 1854228654";
    public static final List<String> ENTRIES = List.of("singleplayer", "multiplayer", "settings", "quit");
    /** Frames run after a hover so its 0.14 s transition has finished (the stage steps 16 ms). */
    private static final int HOVER_SETTLES = 12;

    static MainMenuContracts.Backdrop backdrop(String variant) {
        MainMenuContracts.Backdrop base = baseBackdrop(variant);
        return resumable(variant) ? base.withResume(true, variant.contains("-exit") ? 0.2f : 0f) : base;
    }

    /** The variant shows the RESUME entry (a saved world to resume). */
    static boolean resumable(String variant) {
        return variant.contains("-resume");
    }

    private static MainMenuContracts.Backdrop baseBackdrop(String variant) {
        return switch (variant.split("-")[0]) {
            case "live" -> new MainMenuContracts.Backdrop(0f, 1f, false, SEED_LINE, 1f);
            case "loading" -> new MainMenuContracts.Backdrop(1f, 0f, false, SEED_LINE, 0.4f);
            case "static" -> MainMenuContracts.Backdrop.STATIC;
            default -> throw new IllegalArgumentException("main menu variant: live|loading|static, not " + variant);
        };
    }

    static String hover(String variant) {
        int i = variant.indexOf("-hover-");
        return i < 0 ? "" : variant.substring(i + "-hover-".length());
    }

    static DocumentStage stage(FidelityCase c) throws Exception {
        int w = c.viewport().width();
        int h = c.viewport().height();
        float s = c.viewport().uiScale();
        RecordingServices services = new RecordingServices();
        services.window = new int[]{w, h};
        MainMenu menu = new MainMenu();
        menu.setSplashText(SPLASH);
        services.mainMenu = menu;
        services.mainMenuBackdrop = backdrop(c.variant());
        return new DocumentStage(GameUiDocuments.readScreen(MainMenu.DOCUMENT_ID), w, h, s, services,
            MultiplayerSession.Mode.MENU, Map.of(DirtBackdropProvider.ID, new DirtBackdropProvider()));
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        try (DocumentStage stage = stage(c)) {
            String hover = hover(c.variant());
            if (!hover.isEmpty()) {
                stage.hover(hover);
                for (int i = 0; i < HOVER_SETTLES; i++) {
                    stage.settle();
                }
            }
            stage.paint();
            List<String> parts = new ArrayList<>(List.of("logo"));
            if (resumable(c.variant())) {
                parts.add("resume");
            }
            parts.addAll(ENTRIES);
            Map<String, float[]> rects = stage.layoutRects(parts);
            Map<String, float[]> clickable = new LinkedHashMap<>(rects);
            clickable.remove("logo");
            return new MigrationGate.Capture(stage.raster.capture(), rects, stage.hits(clickable),
                stage.actions(clickable));
        } catch (Exception e) {
            throw new IllegalStateException("could not capture " + c.id(), e);
        }
    }
}

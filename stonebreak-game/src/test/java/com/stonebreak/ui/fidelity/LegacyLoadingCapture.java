package com.stonebreak.ui.fidelity;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.ui.LoadingScreen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The legacy side of the loading screen's fidelity gate (#299): {@link LoadingScreen}'s renderer on a
 * {@link LegacyUiRaster} (with the menu logo), at a reported stage, reporting the logo and bar rects
 * it drew. The screen takes no input. Variants are the stage reported last: {@code first} (shown, no
 * report yet), {@code caves} ("Generating Caves"), {@code meshing} ("Meshing Chunk", a full bar) and
 * {@code density} ("Calculating Terrain Density", an alias that maps to the second stage). Not a test class.
 */
public final class LegacyLoadingCapture implements MigrationGate.Renderer {

    /** The stage reported for a variant, or null for none. */
    public static String stage(String variant) {
        return switch (variant) {
            case "first" -> null;
            case "caves" -> "Generating Caves";
            case "meshing" -> "Meshing Chunk";
            case "density" -> "Calculating Terrain Density";
            default -> throw new IllegalArgumentException("loading variant: first|caves|meshing|density, not " + variant);
        };
    }

    /** A loading screen showing the variant's progress (never enters LOADING: no game here). */
    public static LoadingScreen screen(com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend backend, String variant) {
        LoadingScreen screen = new LoadingScreen(backend);
        screen.showProgress();
        String stage = stage(variant);
        if (stage != null) {
            screen.updateProgress(stage);
        }
        return screen;
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            LoadingScreen screen = screen(raster.backend(), c.variant());
            Map<String, float[]> drawn = new LinkedHashMap<>();
            screen.renderer().setLayoutSink(drawn::put);
            screen.render(w, h);
            return new MigrationGate.Capture(raster.capture(), drawn);
        }
    }
}

package com.stonebreak.core.bootstrap;

import com.stonebreak.config.Settings;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.components.CrosshairRenderer;

/**
 * Applies saved crosshair settings (style, size, thickness, gap, opacity,
 * color, outline) to the active {@link CrosshairRenderer}. Extracted from
 * {@code Game.initializeCrosshairSettings()}.
 */
public final class CrosshairConfigurator {

    private CrosshairConfigurator() {
    }

    /**
     * Applies crosshair settings from {@link Settings} to the renderer's crosshair component.
     */
    public static void apply(Renderer renderer) {
        if (renderer == null || renderer.getUIRenderer() == null) {
            System.err.println("Warning: Renderer or UIRenderer not available during crosshair initialization");
            return;
        }

        CrosshairRenderer crosshairRenderer = renderer.getUIRenderer().getCrosshairRenderer();
        if (crosshairRenderer == null) {
            System.err.println("Warning: CrosshairRenderer not available during initialization");
            return;
        }

        Settings settings = Settings.getInstance();

        try {
            CrosshairRenderer.CrosshairStyle styleEnum =
                CrosshairRenderer.CrosshairStyle.valueOf(settings.getCrosshairStyle());
            crosshairRenderer.setStyle(styleEnum);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid crosshair style in settings: " + settings.getCrosshairStyle() + ", using default");
            crosshairRenderer.setStyle(CrosshairRenderer.CrosshairStyle.SIMPLE_CROSS);
        }

        crosshairRenderer.setSize(settings.getCrosshairSize());
        crosshairRenderer.setThickness(settings.getCrosshairThickness());
        crosshairRenderer.setGap(settings.getCrosshairGap());
        crosshairRenderer.setOpacity(settings.getCrosshairOpacity());
        crosshairRenderer.setColor(settings.getCrosshairColorR(), settings.getCrosshairColorG(), settings.getCrosshairColorB());
        crosshairRenderer.setOutline(settings.getCrosshairOutline());

        System.out.println("Crosshair settings initialized: style=" + settings.getCrosshairStyle() +
                           ", size=" + settings.getCrosshairSize());
    }
}

package com.stonebreak.core.bootstrap;

import com.stonebreak.config.Settings;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.ui.crosshair.CrosshairSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies saved crosshair settings (style, size, thickness, gap, opacity,
 * color, outline) to the applied {@link CrosshairSettings}. Extracted from
 * {@code Game.initializeCrosshairSettings()}.
 */
public final class CrosshairConfigurator {

    private static final Logger logger = LoggerFactory.getLogger(CrosshairConfigurator.class);

    private CrosshairConfigurator() {
    }

    /**
     * Applies crosshair settings from {@link Settings} to the UI renderer's crosshair configuration.
     */
    public static void apply(Renderer renderer) {
        if (renderer == null || renderer.getUIRenderer() == null) {
            logger.warn("Renderer or UIRenderer not available during crosshair initialization");
            return;
        }

        CrosshairSettings crosshair = renderer.getUIRenderer().getCrosshairSettings();
        Settings settings = Settings.getInstance();

        try {
            CrosshairSettings.CrosshairStyle styleEnum =
                CrosshairSettings.CrosshairStyle.valueOf(settings.getCrosshairStyle());
            crosshair.setStyle(styleEnum);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid crosshair style in settings: {}, using default", settings.getCrosshairStyle());
            crosshair.setStyle(CrosshairSettings.CrosshairStyle.SIMPLE_CROSS);
        }

        crosshair.setSize(settings.getCrosshairSize());
        crosshair.setThickness(settings.getCrosshairThickness());
        crosshair.setGap(settings.getCrosshairGap());
        crosshair.setOpacity(settings.getCrosshairOpacity());
        crosshair.setColor(settings.getCrosshairColorR(), settings.getCrosshairColorG(), settings.getCrosshairColorB());
        crosshair.setOutline(settings.getCrosshairOutline());

        logger.debug("Crosshair settings initialized: style={}, size={}",
                settings.getCrosshairStyle(), settings.getCrosshairSize());
    }
}

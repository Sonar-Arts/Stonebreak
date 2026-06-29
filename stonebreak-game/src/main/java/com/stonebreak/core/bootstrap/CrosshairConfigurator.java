package com.stonebreak.core.bootstrap;

import com.stonebreak.config.Settings;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.UI.components.MCrosshairRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies saved crosshair settings (style, size, thickness, gap, opacity,
 * color, outline) to the active {@link MCrosshairRenderer}. Extracted from
 * {@code Game.initializeCrosshairSettings()}.
 */
public final class CrosshairConfigurator {

    private static final Logger logger = LoggerFactory.getLogger(CrosshairConfigurator.class);

    private CrosshairConfigurator() {
    }

    /**
     * Applies crosshair settings from {@link Settings} to the renderer's crosshair component.
     */
    public static void apply(Renderer renderer) {
        if (renderer == null || renderer.getUIRenderer() == null) {
            logger.warn("Renderer or UIRenderer not available during crosshair initialization");
            return;
        }

        MCrosshairRenderer crosshairRenderer = renderer.getUIRenderer().getMCrosshairRenderer();
        if (crosshairRenderer == null) {
            logger.warn("MCrosshairRenderer not available during initialization");
            return;
        }

        Settings settings = Settings.getInstance();

        try {
            MCrosshairRenderer.CrosshairStyle styleEnum =
                MCrosshairRenderer.CrosshairStyle.valueOf(settings.getCrosshairStyle());
            crosshairRenderer.setStyle(styleEnum);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid crosshair style in settings: {}, using default", settings.getCrosshairStyle());
            crosshairRenderer.setStyle(MCrosshairRenderer.CrosshairStyle.SIMPLE_CROSS);
        }

        crosshairRenderer.setSize(settings.getCrosshairSize());
        crosshairRenderer.setThickness(settings.getCrosshairThickness());
        crosshairRenderer.setGap(settings.getCrosshairGap());
        crosshairRenderer.setOpacity(settings.getCrosshairOpacity());
        crosshairRenderer.setColor(settings.getCrosshairColorR(), settings.getCrosshairColorG(), settings.getCrosshairColorB());
        crosshairRenderer.setOutline(settings.getCrosshairOutline());

        logger.debug("Crosshair settings initialized: style={}, size={}",
                settings.getCrosshairStyle(), settings.getCrosshairSize());
    }
}

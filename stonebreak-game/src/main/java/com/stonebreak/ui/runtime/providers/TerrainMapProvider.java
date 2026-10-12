package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.stonebreak.core.Game;
import com.stonebreak.ui.terrainMapper.TerrainMapperLayout;
import com.stonebreak.ui.terrainMapper.TerrainMapperScreen;
import com.stonebreak.ui.terrainMapper.managers.TerrainMapperStateManager;
import com.stonebreak.ui.terrainMapper.renderers.TerrainMapRenderer;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Typeface;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@value #ID}: the terrain mapper's live map (#301, ledger hard visual 11) filling the element, drawn by
 * {@link TerrainMapRenderer#paint} from the screen's state: the cached noise raster at the
 * current pan/zoom/mode/seed, the spawn waypoint, the hover crosshair and the 2 px frame. No params: the map
 * is game state. Pure Skia. Without a game (the editor preview) it shows the empty map: background and frame.
 */
public final class TerrainMapProvider implements UiPaintHost.UiDrawProvider, AutoCloseable {

    public static final String ID = "stonebreak:terrain-map";
    public static final int VERSION = 1;

    private final Supplier<TerrainMapperStateManager> state;
    private final Supplier<Typeface> typeface;
    private final Map<Float, Font> fonts = new HashMap<>();
    private TerrainMapperStateManager painted;
    private TerrainMapRenderer renderer;

    /**
     * @param state the screen to draw; null from it shows the empty map
     * @param typeface the face of the map's status line; null from it draws no text
     */
    public TerrainMapProvider(Supplier<TerrainMapperStateManager> state, Supplier<Typeface> typeface) {
        this.state = state;
        this.typeface = typeface;
    }

    /** The game's terrain mapper, or null. */
    public static TerrainMapperStateManager gameState() {
        Game game = Game.getInstance();
        TerrainMapperScreen screen = game == null ? null : game.getTerrainMapperScreen();
        return screen == null ? null : screen.getStateManager();
    }

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        TerrainMapperStateManager s = state.get();
        TerrainMapperLayout.Rect r = new TerrainMapperLayout.Rect(rect.x(), rect.y(), rect.width(), rect.height());
        if (s == null) {
            MPainter.fillRect(canvas, r.x(), r.y(), r.width(), r.height(), 0xFF0E0E0E);
            MPainter.strokeRect(canvas, r.x(), r.y(), r.width(), r.height(), MStyle.PANEL_BORDER, 2f);
            return;
        }
        if (s != painted) {
            painted = s;
            renderer = new TerrainMapRenderer(s);
        }
        renderer.paint(canvas, r, this::font);
    }

    /** The status line's font at an unscaled size, as the legacy {@code MFonts.get} gave it. */
    private Font font(float size) {
        Font cached = fonts.get(size);
        if (cached != null) {
            return cached;
        }
        Typeface face = typeface == null ? null : typeface.get();
        if (face == null) {
            return null;
        }
        Font font = new Font(face, size);
        fonts.put(size, font);
        return font;
    }

    @Override
    public void close() {
        fonts.values().forEach(Font::close);
        fonts.clear();
    }
}

package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.stonebreak.core.Game;
import com.stonebreak.ui.MainMenu;
import com.stonebreak.ui.mainMenu.MainMenuBackdrop;
import com.stonebreak.ui.mainMenu.MainMenuStage;
import io.github.humbleui.skija.Canvas;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * {@value #ID}: the main menu's scene behind its title and buttons (#299; ledger hard visual 7): dirt
 * with its tint, the space scene, the shockwave reveal and rings, as the game's {@link MainMenuStage}
 * says, drawn by the same {@link MainMenuBackdrop} as the legacy menu. The element is the whole window
 * (inside the screen-shake box: the scene shakes with everything else). No params: the stage is game
 * state, not document state. Pure Skia; without a game (editor preview) it shows the idle dirt.
 */
public final class MenuStageProvider implements UiPaintHost.UiDrawProvider, AutoCloseable {

    public static final String ID = "stonebreak:menu-stage";
    public static final int VERSION = 1;

    private final MainMenuBackdrop backdrop = new MainMenuBackdrop();
    private final Supplier<MainMenuStage> stage;
    private final DoubleSupplier time;

    /** The game's main menu stage and elapsed time. */
    public MenuStageProvider() {
        this(MenuStageProvider::gameStage, MenuStageProvider::gameTime);
    }

    public MenuStageProvider(Supplier<MainMenuStage> stage, DoubleSupplier time) {
        this.stage = stage;
        this.time = time;
    }

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        int saved = canvas.save();
        try {
            canvas.translate(rect.x(), rect.y());
            backdrop.paint(canvas, Math.round(rect.width()), Math.round(rect.height()), stage.get(), scale,
                (float) time.getAsDouble());
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    private static MainMenuStage gameStage() {
        try {
            Game game = Game.getInstance();
            MainMenu menu = game == null ? null : game.getMainMenu();
            return menu == null ? null : menu.getStage();
        } catch (RuntimeException | LinkageError e) {
            return null; // no game (editor preview): the idle dirt
        }
    }

    private static double gameTime() {
        try {
            Game game = Game.getInstance();
            return game == null ? 0 : game.getTotalTimeElapsed();
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    @Override
    public void close() {
        backdrop.close();
    }
}

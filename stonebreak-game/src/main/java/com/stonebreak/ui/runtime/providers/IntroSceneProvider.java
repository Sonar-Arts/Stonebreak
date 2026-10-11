package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import com.stonebreak.core.Game;
import com.stonebreak.ui.startupIntro.IntroScene;
import com.stonebreak.ui.startupIntro.SonarArtsIntroScreen;
import io.github.humbleui.skija.Canvas;

import java.util.function.Supplier;

/**
 * The startup intro's procedural layers (#301, ledger hard visual 8): {@value #OCEAN_ID} draws the ocean
 * bands and caustics, {@value #SONAR_ID} the sonar rings, bubbles and submarine, both from the game's
 * {@link IntroScene} with the legacy scanline primitives, at the window's origin (the element is the
 * whole window). The document stacks its logo between the two. No params: the scene is game state.
 * Pure Skia. Without a game (the editor preview) both show one deterministic demo scene: seeded, stepped
 * at 60 Hz to {@value #PREVIEW_SECONDS} s at the element's size.
 */
public final class IntroSceneProvider implements UiPaintHost.UiDrawProvider {

    public static final String OCEAN_ID = "stonebreak:intro-ocean";
    public static final String SONAR_ID = "stonebreak:intro-sonar";
    public static final int VERSION = 1;
    static final float PREVIEW_SECONDS = 2.5f;

    /** The editor preview's scene, shared by both layers so they agree; rebuilt on a size change. */
    private static IntroScene preview;

    private final boolean ocean;
    private final Supplier<IntroScene> scene;

    private IntroSceneProvider(boolean ocean, Supplier<IntroScene> scene) {
        this.ocean = ocean;
        this.scene = scene;
    }

    /** @param scene the scene to draw; null from it shows the demo scene */
    public static IntroSceneProvider ocean(Supplier<IntroScene> scene) {
        return new IntroSceneProvider(true, scene);
    }

    public static IntroSceneProvider sonar(Supplier<IntroScene> scene) {
        return new IntroSceneProvider(false, scene);
    }

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        IntroScene s = scene.get();
        if (s == null) {
            s = preview(Math.round(rect.width()), Math.round(rect.height()));
        }
        int saved = canvas.save();
        try {
            canvas.translate(rect.x(), rect.y());
            if (ocean) {
                s.paintOcean(canvas);
            } else {
                s.paintSonar(canvas);
            }
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    static synchronized IntroScene preview(int width, int height) {
        if (preview == null || preview.width() != width || preview.height() != height) {
            IntroScene demo = new IntroScene(new java.util.SplittableRandom(301), v -> { });
            demo.enter(width, height);
            for (int i = 0; i < Math.round(PREVIEW_SECONDS * 60); i++) {
                demo.update(1f / 60f);
            }
            preview = demo;
        }
        return preview;
    }

    /** The game's intro scene, or null (no game: the editor preview). */
    static IntroScene gameScene() {
        try {
            Game game = Game.getInstance();
            SonarArtsIntroScreen intro = game == null ? null : game.getStartupIntroScreen();
            return intro == null ? null : intro.scene();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}

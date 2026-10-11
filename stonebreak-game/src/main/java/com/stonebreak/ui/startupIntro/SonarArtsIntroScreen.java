package com.stonebreak.ui.startupIntro;

import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.startupIntro.render.IntroPainter;
import com.stonebreak.ui.startupIntro.render.SonarLogoRenderer;
import io.github.humbleui.skija.Canvas;

import java.util.concurrent.ThreadLocalRandom;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;

/**
 * Sonar Arts intro screen: ocean + green sonar pulses + submarine + retro
 * logo reveal, then a fade-to-black hand-off into the main menu. Direct port
 * of the submarine/sonar slice of TheMythalProphecy's StartupAnimationState
 * with the flash transition and airship/sky portions removed.
 *
 * <p>The simulation and timeline are {@link IntroScene}; since #301 the shipped document
 * {@code ui/documents/intro.sbui} ({@link IntroDocument}, the STARTUP_INTRO state screen) shows it,
 * and {@link #render} is the legacy fallback (not shipped, refused, {@code -Dstonebreak.ui.legacy=intro}).
 */
public final class SonarArtsIntroScreen {

    /** The shipped document: {@code ui/documents/intro.sbui}. */
    public static final String DOCUMENT_ID = "intro";

    private final SkijaUIBackend backend;
    private final SonarLogoRenderer logoRenderer = new SonarLogoRenderer();
    private final IntroScene scene;

    private boolean prevEsc;
    private boolean prevSpace;
    private boolean prevEnter;

    private static final String SONAR_SOUND_NAME = "StartupSonar";
    private static final String SONAR_SOUND_RESOURCE = "/sounds/StartupSonar.wav";
    private boolean sonarSoundRegistered;

    public SonarArtsIntroScreen(SkijaUIBackend backend) {
        this.backend = backend;
        this.scene = new IntroScene(ThreadLocalRandom.current(), this::playSonarPing);
        registerSonarSound();
    }

    private void registerSonarSound() {
        if (sonarSoundRegistered) return;
        SoundSystem sound = SoundSystem.getInstance();
        if (sound == null) return;
        try {
            com.stonebreak.audio.GameSoundLoader.load(sound, SONAR_SOUND_NAME, SONAR_SOUND_RESOURCE);
            // Only treat as registered if the buffer actually loaded, so we don't spam
            // "Sound not found" on every ping when the resource/codec failed.
            sonarSoundRegistered = sound.isSoundLoaded(SONAR_SOUND_NAME);
        } catch (Throwable t) {
            // Intro sound is cosmetic — if the audio device is unavailable or the resource
            // is missing, the intro still plays silently rather than crashing.
            System.err.println("[SonarIntro] Failed to register sonar sound: " + t.getMessage());
        }
    }

    private void playSonarPing(double volume) {
        if (!sonarSoundRegistered || volume <= 0) return;
        SoundSystem sound = SoundSystem.getInstance();
        if (sound != null) sound.playSoundWithVolume(SONAR_SOUND_NAME, (float) volume);
    }

    /** The intro's simulation and timeline (also what the intro document and its providers show). */
    public IntroScene scene() {
        return scene;
    }

    public void update(float deltaTime) {
        if (scene.update(deltaTime)) {
            finish();
        }
    }

    /** Sizes the scene to the window (first frame, resize); every painter calls it before drawing. */
    public void enterIfNeeded(int width, int height) {
        scene.enter(width, height);
    }

    public void render(int width, int height) {
        enterIfNeeded(width, height);
        if (!backend.isAvailable()) return;

        backend.beginFrame(width, height, 1f);
        try {
            Canvas canvas = backend.getCanvas();
            paintLegacy(canvas, scene, logoRenderer, width, height);
        } finally {
            backend.endFrame();
        }
    }

    /** The legacy frame: ocean, logo, rings, bubbles, submarine, then the fade to black. */
    public static void paintLegacy(Canvas canvas, IntroScene scene, SonarLogoRenderer logo, int width, int height) {
        IntroPainter painter = new IntroPainter(canvas);
        scene.paintOcean(canvas);
        logo.draw(painter, width, height, scene.reveal());
        scene.paintSonar(canvas);

        if (scene.phase() == IntroScene.Phase.FADE_TO_BLACK) {
            int color = IntroConfig.withAlpha(0xFF000000, scene.fade());
            painter.drawRectangle(0, 0, width, height, color);
        } else if (scene.phase() == IntroScene.Phase.COMPLETE) {
            painter.drawRectangle(0, 0, width, height, 0xFF000000);
        }
    }

    public void handleInput(long window) {
        boolean esc = com.stonebreak.input.PolledKeys.isDown(window, GLFW_KEY_ESCAPE);
        boolean space = com.stonebreak.input.PolledKeys.isDown(window, GLFW_KEY_SPACE);
        boolean enter = com.stonebreak.input.PolledKeys.isDown(window, GLFW_KEY_ENTER);

        boolean skipPressed = (esc && !prevEsc) || (space && !prevSpace) || (enter && !prevEnter);
        if (skipPressed) skipToMainMenu();

        prevEsc = esc;
        prevSpace = space;
        prevEnter = enter;
    }

    public void skipToMainMenu() {
        if (scene.phase() == IntroScene.Phase.COMPLETE) return;
        finish();
    }

    private void finish() {
        scene.complete();
        Game.getInstance().setState(GameState.MAIN_MENU);
    }

    public boolean isComplete() { return scene.phase() == IntroScene.Phase.COMPLETE; }

    public void dispose() {
        logoRenderer.close();
    }
}

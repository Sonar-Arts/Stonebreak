package com.stonebreak.ui.startupIntro;

import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Sonar Arts intro screen: ocean + green sonar pulses + submarine + retro
 * logo reveal, then a fade-to-black hand-off into the main menu. Direct port
 * of the submarine/sonar slice of TheMythalProphecy's StartupAnimationState
 * with the flash transition and airship/sky portions removed.
 *
 * <p>The simulation and timeline are {@link IntroScene}; the shipped document
 * {@code ui/documents/intro.sbui} ({@link IntroDocument}, the STARTUP_INTRO state screen) shows it.
 */
public final class SonarArtsIntroScreen {

    /** The shipped document: {@code ui/documents/intro.sbui}. */
    public static final String DOCUMENT_ID = "intro";

    private final IntroScene scene;

    private static final String SONAR_SOUND_NAME = "StartupSonar";
    private static final String SONAR_SOUND_RESOURCE = "/sounds/StartupSonar.wav";
    private boolean sonarSoundRegistered;

    public SonarArtsIntroScreen() {
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

    /** Sizes the scene to the window (first frame, resize); the document calls it before each paint. */
    public void enterIfNeeded(int width, int height) {
        scene.enter(width, height);
    }

    public void skipToMainMenu() {
        if (scene.phase() == IntroScene.Phase.COMPLETE) return;
        finish();
    }

    private void finish() {
        scene.complete();
        // The terrain model's first-launch setup (or an update) gets its own screen; otherwise straight on.
        boolean setup = com.stonebreak.world.generation.diffusion.tgmpipe.ModelSetup.getInstance().shouldShowScreen();
        Game.getInstance().setState(setup ? GameState.MODEL_SETUP : GameState.MAIN_MENU);
    }

    public boolean isComplete() { return scene.phase() == IntroScene.Phase.COMPLETE; }
}

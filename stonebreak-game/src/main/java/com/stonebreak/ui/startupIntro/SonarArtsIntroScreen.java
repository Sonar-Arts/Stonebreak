package com.stonebreak.ui.startupIntro;

import com.stonebreak.audio.SoundSystem;
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.startupIntro.entities.Bubble;
import com.stonebreak.ui.startupIntro.entities.SonarSystem;
import com.stonebreak.ui.startupIntro.entities.Submarine;
import com.stonebreak.ui.startupIntro.render.IntroPainter;
import com.stonebreak.ui.startupIntro.render.OceanBackgroundRenderer;
import com.stonebreak.ui.startupIntro.render.SonarLogoRenderer;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import com.stonebreak.ui.startupIntro.tween.FloatTween;
import com.stonebreak.ui.startupIntro.tween.TweenEngine;
import io.github.humbleui.skija.Canvas;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetKey;

/**
 * Sonar Arts intro screen: ocean + green sonar pulses + submarine + retro
 * logo reveal, then a fade-to-black hand-off into the main menu. Direct port
 * of the submarine/sonar slice of TheMythalProphecy's StartupAnimationState
 * with the flash transition and airship/sky portions removed.
 */
public final class SonarArtsIntroScreen {

    private enum Phase { SUBMARINE_WITH_SONAR, HOLD, FADE_TO_BLACK, COMPLETE }

    private final SkijaUIBackend backend;
    private final SonarLogoRenderer logoRenderer = new SonarLogoRenderer();
    private final TweenEngine tweenEngine = new TweenEngine();
    private final List<Bubble> bubbles = new ArrayList<>();

    private SonarSystem sonarSystem;
    private Submarine submarine;
    private OceanBackgroundRenderer oceanRenderer;

    private Phase phase = Phase.SUBMARINE_WITH_SONAR;
    private float phaseElapsed;
    private float totalElapsed;
    private float sonarSpawnTimer;
    private float logoRevealAmount;
    private FloatTween fadeToBlack;

    private int lastWidth;
    private int lastHeight;
    private boolean entered;

    private boolean prevEsc;
    private boolean prevSpace;
    private boolean prevEnter;

    private static final String SONAR_SOUND_NAME = "StartupSonar";
    private static final String SONAR_SOUND_RESOURCE = "/sounds/StartupSonar.wav";
    private static final float SONAR_SOUND_VOLUME = 0.5f;
    private boolean sonarSoundRegistered;

    public SonarArtsIntroScreen(SkijaUIBackend backend) {
        this.backend = backend;
        registerSonarSound();
    }

    private void registerSonarSound() {
        if (sonarSoundRegistered) return;
        SoundSystem sound = SoundSystem.getInstance();
        if (sound == null) return;
        try {
            sound.loadSound(SONAR_SOUND_NAME, SONAR_SOUND_RESOURCE);
            sonarSoundRegistered = true;
        } catch (Throwable t) {
            System.err.println("[SonarIntro] Failed to register sonar sound: " + t.getMessage());
        }
    }

    private void playSonarPing(float volume) {
        if (!sonarSoundRegistered || volume <= 0f) return;
        SoundSystem sound = SoundSystem.getInstance();
        if (sound != null) sound.playSoundWithVolume(SONAR_SOUND_NAME, volume);
    }

    /**
     * Volume to use for the next ping. During FADE_TO_BLACK we scale by
     * {@code 1 - fadeProgress} so the audio fades alongside the visual.
     */
    private float currentPingVolume() {
        float v = SONAR_SOUND_VOLUME;
        if (phase == Phase.FADE_TO_BLACK && fadeToBlack != null) {
            v *= Math.max(0f, 1f - fadeToBlack.current());
        }
        return v;
    }

    private void enterIfNeeded(int width, int height) {
        if (entered && width == lastWidth && height == lastHeight) return;
        lastWidth = width;
        lastHeight = height;
        IntroConfig.initializeScale(width, height);
        sonarSystem = new SonarSystem(width, height);
        submarine = new Submarine(width, height);
        oceanRenderer = new OceanBackgroundRenderer(width, height);

        if (!entered) {
            for (int i = 0; i < 15; i++) spawnBubble(true);
            entered = true;
            // Match C#: first ring + ping at t=0, then every 0.6s.
            sonarSystem.spawnRing();
            playSonarPing(SONAR_SOUND_VOLUME);
        }
    }

    public void update(float deltaTime) {
        if (phase == Phase.COMPLETE) return;
        if (sonarSystem == null) return;

        totalElapsed += deltaTime;
        phaseElapsed += deltaTime;
        tweenEngine.update(deltaTime);

        // Drive ring spawning + ping audio off the same external timer in
        // every phase so the ping cadence keeps going through HOLD and
        // FADE_TO_BLACK and audibly fades out with the visual fade.
        sonarSystem.update(deltaTime, false);
        sonarSpawnTimer += deltaTime;
        if (sonarSpawnTimer >= IntroConfig.SONAR_SPAWN_INTERVAL) {
            sonarSpawnTimer = 0f;
            sonarSystem.spawnRing();
            playSonarPing(currentPingVolume());
        }

        switch (phase) {
            case SUBMARINE_WITH_SONAR -> {
                if (ThreadLocalRandom.current().nextFloat() < 0.15f) spawnBubble(false);
                if (phaseElapsed >= IntroConfig.SUBMARINE_DURATION) {
                    phase = Phase.HOLD;
                    phaseElapsed = 0f;
                }
            }
            case HOLD -> {
                if (ThreadLocalRandom.current().nextFloat() < 0.10f) spawnBubble(false);
                if (phaseElapsed >= IntroConfig.HOLD_DURATION) {
                    phase = Phase.FADE_TO_BLACK;
                    phaseElapsed = 0f;
                    fadeToBlack = tweenEngine.tweenFloat(0f, 1f,
                            IntroConfig.FADE_OUT_DURATION, EasingType.EaseInQuad);
                }
            }
            case FADE_TO_BLACK -> {
                if (phaseElapsed >= IntroConfig.FADE_OUT_DURATION) {
                    finish();
                }
            }
            default -> {}
        }

        updateBubbles(deltaTime);

        // Reveal amount tracks the largest current ring radius and never decreases.
        float current = sonarSystem.largestRadius();
        float target = Math.max(0f, Math.min(1f, current / IntroConfig.s(150f)));
        if (target > logoRevealAmount) logoRevealAmount = target;
    }

    private void updateBubbles(float deltaTime) {
        Iterator<Bubble> it = bubbles.iterator();
        while (it.hasNext()) {
            Bubble b = it.next();
            b.update(deltaTime, totalElapsed);
            if (b.isExpired()) it.remove();
        }
    }

    private void spawnBubble(boolean randomY) {
        float cx = lastWidth * 0.5f;
        float cy = lastHeight * 0.5f;
        float x = cx + (ThreadLocalRandom.current().nextFloat() - 0.5f) * IntroConfig.s(120);
        float y = randomY
                ? ThreadLocalRandom.current().nextFloat() * lastHeight
                : cy - IntroConfig.s(20);
        bubbles.add(new Bubble(x, y));
    }

    public void render(int width, int height) {
        enterIfNeeded(width, height);
        if (!backend.isAvailable()) return;

        backend.beginFrame(width, height, 1f);
        try {
            Canvas canvas = backend.getCanvas();
            IntroPainter painter = new IntroPainter(canvas);

            oceanRenderer.draw(painter, totalElapsed);
            logoRenderer.draw(painter, width, height, logoRevealAmount);
            sonarSystem.draw(painter);
            for (Bubble b : bubbles) b.draw(painter);
            submarine.draw(painter);

            if (phase == Phase.FADE_TO_BLACK && fadeToBlack != null) {
                int color = IntroConfig.withAlpha(0xFF000000, fadeToBlack.current());
                painter.drawRectangle(0, 0, width, height, color);
            } else if (phase == Phase.COMPLETE) {
                painter.drawRectangle(0, 0, width, height, 0xFF000000);
            }
        } finally {
            backend.endFrame();
        }
    }

    public void handleInput(long window) {
        boolean esc = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;
        boolean space = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        boolean enter = glfwGetKey(window, GLFW_KEY_ENTER) == GLFW_PRESS;

        boolean skipPressed = (esc && !prevEsc) || (space && !prevSpace) || (enter && !prevEnter);
        if (skipPressed) skipToMainMenu();

        prevEsc = esc;
        prevSpace = space;
        prevEnter = enter;
    }

    public void skipToMainMenu() {
        if (phase == Phase.COMPLETE) return;
        finish();
    }

    private void finish() {
        phase = Phase.COMPLETE;
        Game.getInstance().setState(GameState.MAIN_MENU);
    }

    public boolean isComplete() { return phase == Phase.COMPLETE; }

    public void dispose() {
        logoRenderer.close();
        tweenEngine.clear();
    }
}

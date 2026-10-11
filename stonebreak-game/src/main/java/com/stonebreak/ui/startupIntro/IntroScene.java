package com.stonebreak.ui.startupIntro;

import com.openmason.engine.util.easing.EasingType;
import com.stonebreak.ui.startupIntro.entities.Bubble;
import com.stonebreak.ui.startupIntro.entities.SonarSystem;
import com.stonebreak.ui.startupIntro.entities.Submarine;
import com.stonebreak.ui.startupIntro.render.IntroPainter;
import com.stonebreak.ui.startupIntro.render.OceanBackgroundRenderer;
import com.stonebreak.ui.startupIntro.tween.FloatTween;
import com.stonebreak.ui.startupIntro.tween.TweenEngine;
import io.github.humbleui.skija.Canvas;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.random.RandomGenerator;

/**
 * The Sonar Arts intro's simulation and timeline (#301): ocean time, sonar rings and their pings,
 * bubbles, the logo reveal and the phases (submarine 1.8 s, hold 1.2 s, fade 1.0 s). Owned by
 * {@link SonarArtsIntroScreen}; the intro document and its draw providers show it. Deterministic for a given random source and sequence of frame steps
 * (the game uses {@code ThreadLocalRandom}; fixtures a seeded generator).
 */
public final class IntroScene {

    /** Where the intro is in its timeline. */
    public enum Phase {
        SUBMARINE_WITH_SONAR("submarine"), HOLD("hold"), FADE_TO_BLACK("fade"), COMPLETE("complete");

        private final String wire;

        Phase(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private static final float SONAR_SOUND_VOLUME = 0.5f;

    private final RandomGenerator random;
    private final DoubleConsumer ping;
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

    /**
     * @param ping plays the sonar ping at a volume (0..0.5): once per ring, faded with the fade to black
     */
    public IntroScene(RandomGenerator random, DoubleConsumer ping) {
        this.random = random;
        this.ping = ping;
    }

    /**
     * Sizes the scene to the window. The first call seeds 15 bubbles and the first ring and ping; a
     * resize rebuilds the rings, submarine and ocean for the new size (the rings restart), keeping the
     * bubbles and the timeline.
     */
    public void enter(int width, int height) {
        if (entered && width == lastWidth && height == lastHeight) return;
        lastWidth = width;
        lastHeight = height;
        IntroConfig.initializeScale(width, height);
        sonarSystem = new SonarSystem(width, height, random);
        submarine = new Submarine(width, height);
        oceanRenderer = new OceanBackgroundRenderer(width, height);

        if (!entered) {
            for (int i = 0; i < 15; i++) spawnBubble(true);
            entered = true;
            // Match C#: first ring + ping at t=0, then every 0.6s.
            sonarSystem.spawnRing();
            ping.accept(SONAR_SOUND_VOLUME);
        }
    }

    public boolean entered() {
        return entered;
    }

    /**
     * Advances one frame. @return true when the timeline just reached its end (the caller hands over
     * to the main menu)
     */
    public boolean update(float deltaTime) {
        if (phase == Phase.COMPLETE) return false;
        if (sonarSystem == null) return false;

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
            float v = SONAR_SOUND_VOLUME;
            if (phase == Phase.FADE_TO_BLACK && fadeToBlack != null) {
                v *= Math.max(0f, 1f - fadeToBlack.current());
            }
            if (v > 0f) ping.accept(v);
        }

        boolean finished = false;
        switch (phase) {
            case SUBMARINE_WITH_SONAR -> {
                if (random.nextFloat() < 0.15f) spawnBubble(false);
                if (phaseElapsed >= IntroConfig.SUBMARINE_DURATION) {
                    phase = Phase.HOLD;
                    phaseElapsed = 0f;
                }
            }
            case HOLD -> {
                if (random.nextFloat() < 0.10f) spawnBubble(false);
                if (phaseElapsed >= IntroConfig.HOLD_DURATION) {
                    phase = Phase.FADE_TO_BLACK;
                    phaseElapsed = 0f;
                    fadeToBlack = tweenEngine.tweenFloat(0f, 1f,
                            IntroConfig.FADE_OUT_DURATION, EasingType.EaseInQuad);
                }
            }
            case FADE_TO_BLACK -> {
                if (phaseElapsed >= IntroConfig.FADE_OUT_DURATION) {
                    phase = Phase.COMPLETE;
                    finished = true;
                }
            }
            default -> {}
        }

        updateBubbles(deltaTime);

        // Reveal amount tracks the largest current ring radius and never decreases.
        float current = sonarSystem.largestRadius();
        float target = Math.max(0f, Math.min(1f, current / IntroConfig.s(150f)));
        if (target > logoRevealAmount) logoRevealAmount = target;
        return finished;
    }

    /** Skipped or handed over: the scene stops and paints black. */
    public void complete() {
        phase = Phase.COMPLETE;
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
        float x = cx + (random.nextFloat() - 0.5f) * IntroConfig.s(120);
        float y = randomY
                ? random.nextFloat() * lastHeight
                : cy - IntroConfig.s(20);
        bubbles.add(new Bubble(x, y, random));
    }

    // ── state ───────────────────────────────────────────────────────────────

    public Phase phase() {
        return phase;
    }

    /** Seconds of intro time: what the intro's UI clock reads. */
    public float time() {
        return totalElapsed;
    }

    /** Logo opacity 0..1: grows with the largest ring, never shrinks. */
    public float reveal() {
        return logoRevealAmount;
    }

    /** Black over the scene, 0..1 (eased in over the last second; 1 once complete). */
    public float fade() {
        if (phase == Phase.COMPLETE) return 1f;
        return phase == Phase.FADE_TO_BLACK && fadeToBlack != null ? fadeToBlack.current() : 0f;
    }

    public int width() {
        return lastWidth;
    }

    public int height() {
        return lastHeight;
    }

    // ── painting (the scene providers' draw calls) ────────────────────────────────

    /** The deep-ocean bands and caustic shafts. */
    public void paintOcean(Canvas canvas) {
        if (oceanRenderer != null) oceanRenderer.draw(new IntroPainter(canvas), totalElapsed);
    }

    /** Above the logo: the sonar rings, the bubbles, then the submarine. */
    public void paintSonar(Canvas canvas) {
        if (sonarSystem == null) return;
        IntroPainter painter = new IntroPainter(canvas);
        sonarSystem.draw(painter);
        for (Bubble b : bubbles) b.draw(painter);
        submarine.draw(painter);
    }
}

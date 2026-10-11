package com.stonebreak.ui.startupIntro;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Deterministic intro frames (#301): a seeded {@link IntroScene} entered at the window size and stepped
 * at 60 Hz to a named moment, so the legacy renderer and the intro document show the same scene.
 * Not a test class.
 */
public final class IntroFixtures {

    private IntroFixtures() {
    }

    public static final long SEED = 301;
    public static final float DT = 1f / 60f;

    /** Moments of the timeline: start, rings out, the logo revealed, hold, mid fade, complete. */
    public static final List<String> VARIANTS = List.of("t0", "t0-4", "t1-2", "t2-4", "t3-5", "complete");

    /** Frames of {@link #DT} to step for {@code variant}. */
    static int frames(String variant) {
        return switch (variant) {
            case "t0" -> 0;
            case "t0-4" -> 24;
            case "t1-2" -> 72;
            case "t2-4" -> 144;
            case "t3-5" -> 210;
            case "complete" -> 240;
            default -> throw new IllegalArgumentException("intro variant " + variant);
        };
    }

    /** The scene of {@code variant} on a {@code w × h} window; pings are counted in {@code pings[0]}. */
    public static IntroScene scene(String variant, int w, int h, int[] pings) {
        IntroScene scene = new IntroScene(new SplittableRandom(SEED), v -> pings[0]++);
        scene.enter(w, h);
        int n = frames(variant);
        for (int i = 0; i < n; i++) {
            scene.update(DT);
        }
        return scene;
    }

    public static IntroScene scene(String variant, int w, int h) {
        return scene(variant, w, h, new int[1]);
    }

    /** Where SonarLogoRenderer draws the logo (shown once the reveal starts): the gate's geometry oracle. */
    public static Map<String, float[]> logoRect(IntroScene scene, int w, int h) {
        Map<String, float[]> out = new LinkedHashMap<>();
        out.put("window", new float[]{0f, 0f, w, h});
        if (scene.reveal() > 0f) {
            float scale = IntroConfig.scale();
            float sw = 1200 * scale;
            float sh = 324 * scale;
            out.put("logo", new float[]{(w - sw) / 2f, h * 0.18f, sw, sh});
        }
        return out;
    }
}

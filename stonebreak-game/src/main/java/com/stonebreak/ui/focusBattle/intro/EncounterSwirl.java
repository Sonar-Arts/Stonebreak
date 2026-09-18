package com.stonebreak.ui.focusBattle.intro;

import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.RuntimeEffect;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The classic JRPG encounter transition: the field freezes, flashes, then twists and zooms into
 * itself with a radial blur until it burns out to white, at which point the battle scene takes over.
 *
 * <p>Works on a still of the last field frame (see {@link FrameGrab}). The distortion is one SkSL
 * shader; if the runtime effect cannot be built (or no still is available) it degrades to stacked
 * rotated copies, which reads the same at a glance. Pure timeline + Skija: no GL, no wall clock, so
 * it is testable on a CPU raster surface.
 */
public final class EncounterSwirl implements AutoCloseable {

    /** Opening double flash. */
    static final float FLASH_SECONDS = 0.14f;
    /** Twist + zoom run from the end of the flash to here. */
    static final float TWIST_END_SECONDS = 1.45f;
    /** The burn to white starts here... */
    static final float WHITEOUT_START_SECONDS = 1.10f;
    /** ...and the transition is over (fully white) here. */
    public static final float TOTAL_SECONDS = 1.60f;

    private static final float MAX_TWIST_RADIANS = (float) (Math.PI * 2.6);
    private static final float MAX_ZOOM = 2.6f;
    private static final int FALLBACK_COPIES = 9;

    private static final String SKSL = """
            uniform shader image;
            uniform float2 size;
            uniform float twist;
            uniform float zoom;
            uniform float blur;
            uniform float white;
            uniform float dark;
            half4 main(float2 p) {
                float2 c = size * 0.5;
                float2 d = p - c;
                float r = length(d) / (0.5 * length(size));
                float falloff = 1.0 - smoothstep(0.0, 1.0, r);
                half4 acc = half4(0.0);
                for (int i = 0; i < 10; i++) {
                    float k = float(i) / 9.0;
                    float ang = twist * falloff * (1.0 + 0.30 * blur * k);
                    float s = 1.0 / (zoom * (1.0 + 0.45 * blur * k));
                    float cs = cos(ang);
                    float sn = sin(ang);
                    float2 q = float2(d.x * cs - d.y * sn, d.x * sn + d.y * cs) * s + c;
                    acc += image.eval(clamp(q, float2(0.5), size - float2(0.5)));
                }
                acc /= 10.0;
                half3 rgb = acc.rgb * half(1.0 - dark * smoothstep(0.25, 1.0, r));
                rgb = mix(rgb, half3(1.0), half(white));
                return half4(rgb, 1.0);
            }
            """;

    private Image still;
    private RuntimeEffect effect;
    private boolean effectBroken;
    private float time = TOTAL_SECONDS;

    /**
     * Starts the transition over {@code stillOrNull} (top-down rows, window sized). This object takes
     * ownership of the image. A null still plays the same timing over black.
     */
    public void begin(Image stillOrNull) {
        disposeStill();
        still = stillOrNull;
        time = 0f;
    }

    public void update(float dt) {
        if (dt > 0f && Float.isFinite(dt)) {
            time = Math.min(TOTAL_SECONDS, time + dt);
            if (TOTAL_SECONDS - time < 1.0e-4f) {
                time = TOTAL_SECONDS; // accumulated float steps must still land exactly on the end
            }
        }
    }

    /** True from {@link #begin} until the white-out completes. */
    public boolean active() {
        return time < TOTAL_SECONDS;
    }

    public float time() {
        return time;
    }

    // ─── Timeline (package-visible for tests) ──────────────────────────────────

    /** 0..1 progress of the twist/zoom section, eased so it starts gently and whips at the end. */
    float twistProgress() {
        float t = (time - FLASH_SECONDS) / (TWIST_END_SECONDS - FLASH_SECONDS);
        return EasingFunctions.apply(clamp01(t), EasingType.EaseInCubic);
    }

    /** 0..1 burn to white; exactly 1 at {@link #TOTAL_SECONDS}. */
    float whiteout() {
        float t = (time - WHITEOUT_START_SECONDS) / (TOTAL_SECONDS - WHITEOUT_START_SECONDS);
        return EasingFunctions.apply(clamp01(t), EasingType.EaseInQuad);
    }

    /** Opening flash alpha: a sharp double pulse, zero once the twist begins. */
    float flash() {
        if (time >= FLASH_SECONDS) {
            return 0f;
        }
        float t = time / FLASH_SECONDS;
        return 0.8f * (float) Math.abs(Math.sin(t * Math.PI * 2.0));
    }

    // ─── Painting ──────────────────────────────────────────────────────────────

    /** Covers the whole window. Call inside an open Skija frame. */
    public void paint(Canvas canvas, int width, int height) {
        if (canvas == null || width <= 0 || height <= 0) {
            return;
        }
        float progress = twistProgress();
        float white = whiteout();
        if (still == null) {
            fill(canvas, width, height, 0xFF000000);
        } else if (!paintWithShader(canvas, width, height, progress, white)) {
            paintWithCopies(canvas, width, height, progress);
        }
        if (still == null || effectBroken) {
            fill(canvas, width, height, argb(white, 0xFFFFFF));
        }
        float flash = flash();
        if (flash > 0f) {
            fill(canvas, width, height, argb(flash, 0xFFFFFF));
        }
    }

    private boolean paintWithShader(Canvas canvas, int width, int height, float progress, float white) {
        if (effectBroken) {
            return false;
        }
        try {
            if (effect == null) {
                effect = RuntimeEffect.makeForShader(SKSL);
            }
            ByteBuffer uniforms = ByteBuffer.allocate(7 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            uniforms.putFloat(still.getWidth()).putFloat(still.getHeight())
                    .putFloat(progress * MAX_TWIST_RADIANS)
                    .putFloat(1f + progress * (MAX_ZOOM - 1f))
                    .putFloat(progress)
                    .putFloat(white)
                    .putFloat(0.55f * progress);
            if (effect.getUniformSize() != uniforms.capacity()) {
                throw new IllegalStateException("unexpected uniform block size " + effect.getUniformSize());
            }
            try (Shader source = still.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null);
                 Data data = Data.makeFromBytes(uniforms.array());
                 Shader shader = effect.makeShader(data, new Shader[]{source});
                 Paint paint = new Paint().setShader(shader)) {
                canvas.save();
                // The still is window sized; scale covers a resize between the grab and now.
                canvas.scale(width / (float) still.getWidth(), height / (float) still.getHeight());
                canvas.drawRect(Rect.makeXYWH(0, 0, still.getWidth(), still.getHeight()), paint);
                canvas.restore();
            }
            return true;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            effectBroken = true;
            System.err.println("[battle] encounter swirl shader unavailable, using the copy fallback: " + e);
            return false;
        }
    }

    /** No-shader fallback: stacked copies, each a little more rotated and zoomed, read as a swirl blur. */
    private void paintWithCopies(Canvas canvas, int width, int height, float progress) {
        fill(canvas, width, height, 0xFF000000);
        float cx = width * 0.5f;
        float cy = height * 0.5f;
        for (int i = 0; i < FALLBACK_COPIES; i++) {
            float k = i / (float) (FALLBACK_COPIES - 1);
            float degrees = (float) Math.toDegrees(progress * MAX_TWIST_RADIANS * 0.35f) * (0.4f + 0.6f * k);
            float scale = (1f + progress * (MAX_ZOOM - 1f)) * (1f + 0.35f * progress * k);
            try (Paint paint = new Paint().setAlphaf(i == 0 ? 1f : 1f / (i + 1f))) {
                canvas.save();
                canvas.translate(cx, cy);
                canvas.rotate(degrees);
                canvas.scale(scale, scale);
                canvas.translate(-cx, -cy);
                canvas.drawImageRect(still, Rect.makeXYWH(0, 0, width, height), paint);
                canvas.restore();
            }
        }
    }

    private static void fill(Canvas canvas, int width, int height, int color) {
        if ((color >>> 24) == 0) {
            return;
        }
        try (Paint paint = new Paint().setColor(color)) {
            canvas.drawRect(Rect.makeXYWH(0, 0, width, height), paint);
        }
    }

    private static int argb(float alpha, int rgb) {
        return (Math.round(clamp01(alpha) * 255f) << 24) | (rgb & 0xFFFFFF);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(1f, v);
    }

    boolean usedFallback() {
        return effectBroken;
    }

    private void disposeStill() {
        if (still != null) {
            still.close();
            still = null;
        }
    }

    @Override
    public void close() {
        disposeStill();
        if (effect != null) {
            effect.close();
            effect = null;
        }
        time = TOTAL_SECONDS;
    }
}

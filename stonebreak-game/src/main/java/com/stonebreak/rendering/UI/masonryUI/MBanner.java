package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.types.Rect;

/**
 * A transient name plate: the move being executed, the zone just entered, a quest update. Its bounds
 * are the slot it sits in; {@link #show} drops it in from above, it holds for as long as the caller
 * says ({@link #hold}) and never less than the minimum hold (so a plate for something instant still
 * reads), then fades out and is gone.
 *
 * <p>Drawn as a {@link MPainter#hudFrame HUD frame} with the accent as its hairline, so the context
 * (a side, a faction, a severity) reads from the colour before the words do.
 *
 * <p>Deterministic: the timeline advances only through {@link #update}, and one large step lands
 * where many small ones would.
 */
public class MBanner extends MWidget {

    public static final float DEFAULT_IN_SECONDS = 0.12f;
    public static final float DEFAULT_MIN_HOLD_SECONDS = 0.9f;
    public static final float DEFAULT_OUT_SECONDS = 0.25f;

    private static final float PAD_X = 30f;
    private static final float DROP_GAP = 8f;

    private String text;
    private int accent;
    private float fontSize = MStyle.FONT_ITEM;
    private int textColor = MStyle.TEXT_PRIMARY;
    private float inSeconds = DEFAULT_IN_SECONDS;
    private float minHold = DEFAULT_MIN_HOLD_SECONDS;
    private float outSeconds = DEFAULT_OUT_SECONDS;

    /** Seconds since {@link #show}. */
    private float age;
    /** Seconds into the fade; negative while dropping or holding. */
    private float fadeAge = -1f;
    private boolean held;
    /** {@link #age} at which the hold was last released: the fade can start no earlier. */
    private float releasedAt;

    // ─────────────────────────────────────────────── Fluent config

    public MBanner fontSize(float v) { if (v > 0f) this.fontSize = v; return this; }
    public MBanner textColor(int c) { this.textColor = c; return this; }
    public MBanner inSeconds(float v) { this.inSeconds = sane(v, DEFAULT_IN_SECONDS); return this; }
    public MBanner minHold(float v) { this.minHold = sane(v, DEFAULT_MIN_HOLD_SECONDS); return this; }
    public MBanner outSeconds(float v) { this.outSeconds = sane(v, DEFAULT_OUT_SECONDS); return this; }

    @Override public MBanner scale(float scale) { super.scale(scale); return this; }
    @Override public MBanner scaleText(boolean v) { super.scaleText(v); return this; }
    @Override public MBanner position(float x, float y) { super.position(x, y); return this; }
    @Override public MBanner size(float width, float height) { super.size(width, height); return this; }
    @Override public MBanner bounds(float x, float y, float width, float height) {
        super.bounds(x, y, width, height); return this;
    }

    // ─────────────────────────────────────────────── Timeline

    /**
     * Starts (or restarts) the plate with {@code text}; {@code accent} (0 = none) tints its hairline.
     * An empty text hides it. The hold is released: call {@link #hold hold(true)} to keep it up.
     */
    public MBanner show(String text, int accent) {
        if (text == null || text.isEmpty()) {
            hide();
            return this;
        }
        this.text = text;
        this.accent = accent;
        this.age = 0f;
        this.fadeAge = -1f;
        this.held = false;
        this.releasedAt = 0f;
        return this;
    }

    /**
     * While true the plate stays up past its minimum hold (the action is still running, the player is
     * still in the zone). Releasing it lets the fade begin. Has no effect once the fade has begun.
     */
    public MBanner hold(boolean keep) {
        if (held && !keep) releasedAt = age;
        held = keep;
        return this;
    }

    /** Removes the plate at once, no fade. */
    public void hide() {
        text = null;
        accent = 0;
        age = 0f;
        fadeAge = -1f;
        held = false;
        releasedAt = 0f;
    }

    public void update(float dt) {
        if (text == null || !(dt > 0f) || Float.isInfinite(dt)) return;
        age += dt;
        if (fadeAge >= 0f) {
            fadeAge += dt;
        } else if (!held) {
            // The fade began when both the minimum hold and the caller's hold were over, which may
            // be part-way through this step.
            float fadeStart = Math.max(minHold, releasedAt);
            if (age >= fadeStart) fadeAge = age - fadeStart;
        }
        if (fadeAge >= outSeconds) hide();
    }

    // ─────────────────────────────────────────────── State

    /** True from {@link #show} until the fade has finished. */
    public boolean active() { return text != null; }

    /** True when there is something to draw (active and not fully transparent). */
    public boolean visible() { return text != null && alpha() > 0f; }

    public String text() { return text; }
    public int accent() { return accent; }
    public boolean fading() { return fadeAge >= 0f; }

    /** 0 = above its slot, 1 = seated (eased). */
    public float dropProgress() {
        if (text == null) return 0f;
        return EasingFunctions.apply(inSeconds <= 0f ? 1f : Math.min(1f, age / inSeconds), EasingType.EaseOutCubic);
    }

    public float alpha() {
        if (text == null) return 0f;
        float in = inSeconds <= 0f ? 1f : Math.min(1f, age / inSeconds);
        float out = fadeAge < 0f ? 1f : (outSeconds <= 0f ? 0f : 1f - Math.min(1f, fadeAge / outSeconds));
        return in * out;
    }

    /** Where the plate is drawn right now {@code {x, y, w, h}}: its slot, lifted while it drops in. */
    public float[] plateRect() {
        float lift = (1f - dropProgress()) * (height + DROP_GAP * textScale());
        return new float[]{x, y - lift, width, height};
    }

    // ─────────────────────────────────────────────── Render

    @Override
    public void render(MasonryUI ui) {
        if (ui == null || !visible() || !(width > 0f) || !(height > 0f)) return;
        Canvas canvas = ui.canvas();
        if (canvas == null) return;
        float s = textScale();
        float alpha = alpha();
        float[] r = plateRect();
        MPainter.hudFrame(canvas, r[0], r[1], r[2], r[3], accent, alpha);

        float size = Math.min(fontSize * s, r[3] * 0.62f);
        Font font = ui.fonts().fit(text, size, r[2] - 2f * Math.min(PAD_X * s, r[2] * 0.2f), 0.6f);
        if (font == null) return;
        // Fitting stops at 60% of the size; a name longer than that is cut by the frame, never spilt.
        canvas.save();
        try {
            canvas.clipRect(Rect.makeXYWH(r[0] + 2f, r[1] + 2f, Math.max(0f, r[2] - 4f), Math.max(0f, r[3] - 4f)));
            MPainter.drawText(canvas, text, r[0] + r[2] / 2f, MPainter.baselineFor(r[1] + r[3] / 2f, font.getSize()),
                    font, MColor.fade(textColor, alpha), MPainter.Align.CENTER);
        } finally {
            canvas.restore();
        }
    }

    private static float sane(float v, float fallback) {
        return v >= 0f && v < 1.0e4f ? v : fallback;
    }
}

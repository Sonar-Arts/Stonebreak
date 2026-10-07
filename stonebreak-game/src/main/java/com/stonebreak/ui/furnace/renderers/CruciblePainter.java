package com.stonebreak.ui.furnace.renderers;

import com.openmason.engine.ui.masonry.MPainter;
import com.stonebreak.ui.furnace.core.FurnaceLayout;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;

/**
 * The furnace's crucible art (ledger hard visual 9): chutes, heat-ramp bowl, lava clipped to a
 * 36-gon, bubbles, bézier flame tongues and the cook/fuel progress rings. One painter for the
 * legacy screen ({@link FurnaceRenderCoordinator}) and the UI document's
 * {@code stonebreak:furnace-crucible} draw provider, so both draw the same pixels from the same
 * {@link FurnaceLayout.Slots} geometry and values.
 */
public final class CruciblePainter {

    private static final int PROGRESS_FILL = 0xFFE87D1C;   // orange smelt-fill

    private CruciblePainter() {
    }

    /**
     * Chutes to the three slots, then the crucible: dark bowl with rising lava (= fuel left),
     * animated flames and bubbles. Drawn before the slot frames, which sit on the chutes' ends.
     *
     * @param fuelRatio fuel left in the burning unit, 0..1
     * @param lit       whether the crucible glows (the legacy screen: cooking, or fuel left)
     * @param t         animation time, seconds
     */
    public static void paintBowl(Canvas canvas, FurnaceLayout.Slots s, float fuelRatio, boolean lit, float t) {
        float cx = s.crucibleCenterX;
        float cy = s.crucibleCenterY;
        float r  = s.crucibleRadius;

        // Chutes (drawn under the disk so chrome overlaps cleanly)
        int chuteColor = 0xA04A4A4A;
        float ct = 6f;
        MPainter.fillRoundedRect(canvas, cx - ct / 2f, s.ingredientY + s.slotSize,
                ct, cy - (s.ingredientY + s.slotSize), 2f, chuteColor);
        MPainter.fillRoundedRect(canvas, cx - ct / 2f, cy,
                ct, s.fuelY - cy, 2f, chuteColor);
        MPainter.fillRoundedRect(canvas, cx, cy - ct / 2f,
                s.outputX - cx, ct, 2f, chuteColor);

        // Outer pulsing glow (only when lit). Two soft halos.
        if (lit) {
            float pulse = 0.85f + 0.15f * (float) Math.sin(t * 4.0);
            int glowOuter = withAlpha(heatColor(fuelRatio), (int)(60 * pulse));
            int glowInner = withAlpha(heatColor(fuelRatio), (int)(110 * pulse));
            try (Paint p = new Paint().setColor(glowOuter)) {
                canvas.drawCircle(cx, cy, r + 9f, p);
            }
            try (Paint p = new Paint().setColor(glowInner)) {
                canvas.drawCircle(cx, cy, r + 5f, p);
            }
        }

        // Outer dark rim
        try (Paint p = new Paint().setColor(0xFF1A1A1A)) {
            canvas.drawCircle(cx, cy, r + 3f, p);
        }
        // Rim highlight (subtle metallic bevel)
        try (Paint p = new Paint().setColor(0xFF3A3A3A).setMode(PaintMode.STROKE).setStrokeWidth(1.5f)) {
            canvas.drawCircle(cx, cy, r + 2f, p);
        }
        // Dark bowl interior (visible when fuel low)
        try (Paint p = new Paint().setColor(0xFF0A0405)) {
            canvas.drawCircle(cx, cy, r, p);
        }

        // Lava fill — clip to a polygon-approximated crucible circle, then
        // draw a rectangle from the bottom up to (1 - fuelRatio)*diameter from
        // the top, with a wavy top edge.
        if (lit) {
            int save = canvas.save();
            try (PathBuilder pb = new PathBuilder()) {
                int steps = 36;
                float clipR = r - 1f;
                pb.moveTo(cx + clipR, cy);
                for (int i = 1; i <= steps; i++) {
                    double a = 2 * Math.PI * i / steps;
                    pb.lineTo((float)(cx + clipR * Math.cos(a)),
                              (float)(cy + clipR * Math.sin(a)));
                }
                try (Path circle = pb.build()) {
                    canvas.clipPath(circle, true);
                }
            }
            float lavaTop = cy + r - (2f * r) * Math.max(fuelRatio, 0.05f);
            drawLava(canvas, cx, cy, r, lavaTop, fuelRatio, t);
            drawBubbles(canvas, cx, cy, r, lavaTop, t);
            drawFlameTongues(canvas, cx, lavaTop, r, fuelRatio, t);
            canvas.restoreToCount(save);
        }

        // Inner highlight (top-left) — sits on the rim, makes it feel rounded.
        try (Paint p = new Paint().setColor(0x28FFFFFF)) {
            canvas.drawCircle(cx - r * 0.30f, cy - r * 0.30f, r * 0.30f, p);
        }
    }

    private static void drawLava(Canvas canvas, float cx, float cy, float r,
                          float lavaTop, float fuelRatio, float t) {
        int hot  = heatColor(Math.min(1f, fuelRatio + 0.20f));
        int dim  = heatColor(Math.max(0f, fuelRatio - 0.30f));

        // Main lava body — polygon with wavy top, flat sides, flat bottom.
        try (PathBuilder pb = new PathBuilder()) {
            float amp  = Math.min(3f, r * 0.10f);
            float freq = 0.45f;
            float step = 1.5f;
            pb.moveTo(cx - r, cy + r + 2f);
            for (float x = -r; x <= r; x += step) {
                float y = lavaTop + (float) Math.sin(x * freq + t * 3.2) * amp
                                  + (float) Math.sin(x * freq * 2.3 - t * 1.7) * amp * 0.5f;
                pb.lineTo(cx + x, y);
            }
            pb.lineTo(cx + r, cy + r + 2f);
            pb.lineTo(cx - r, cy + r + 2f);
            try (Path lava = pb.build();
                 Paint p = new Paint().setColor(dim).setAntiAlias(true)) {
                canvas.drawPath(lava, p);
            }
        }
        // Hotter inner highlight band just under the surface.
        try (PathBuilder pb = new PathBuilder()) {
            float amp = Math.min(2.5f, r * 0.08f);
            float freq = 0.45f;
            float step = 1.5f;
            float bandH = Math.min(6f, r * 0.30f);
            pb.moveTo(cx - r, lavaTop + bandH);
            for (float x = -r; x <= r; x += step) {
                float y = lavaTop + (float) Math.sin(x * freq + t * 3.2) * amp;
                pb.lineTo(cx + x, y);
            }
            pb.lineTo(cx + r, lavaTop + bandH);
            pb.lineTo(cx - r, lavaTop + bandH);
            try (Path band = pb.build();
                 Paint p = new Paint().setColor(withAlpha(hot, 170)).setAntiAlias(true)) {
                canvas.drawPath(band, p);
            }
        }
    }

    private static void drawBubbles(Canvas canvas, float cx, float cy, float r,
                             float lavaTop, float t) {
        // Three drifting bubbles with deterministic phases so they don't all sync.
        for (int i = 0; i < 3; i++) {
            float phase = i * 1.7f;
            float cycle = (float) ((t * 0.9 + phase) % 1.6);
            if (cycle > 1.2f) continue; // brief gap before each bubble re-emerges
            float lifeT = cycle / 1.2f;  // 0..1 over the bubble's visible life
            float bx = cx + (float) Math.sin(phase * 3.1 + t * 0.7) * r * 0.55f;
            float by = lavaTop + (cy + r - lavaTop) * (1f - lifeT) * 0.8f + 2f;
            float br = 1.5f + 1.5f * (1f - lifeT);
            int alpha = (int) (220 * (1f - lifeT));
            try (Paint p = new Paint().setColor(withAlpha(0xFFFFE2A8, alpha))) {
                canvas.drawCircle(bx, by, br, p);
            }
        }
    }

    private static void drawFlameTongues(Canvas canvas, float cx, float lavaTop, float r,
                                  float fuelRatio, float t) {
        // 5 little flame tongues licking up from the lava surface. Each flame
        // is an approximated bezier — left edge + right edge, both built as
        // short line segments since Skija's PathBuilder is moveTo/lineTo-only.
        int flameCore = heatColor(Math.min(1f, fuelRatio + 0.35f));
        int flameTip  = withAlpha(0xFFFFE2A8, 200);
        float maxH = Math.min(r * 0.65f, 14f);
        int n = 5;
        for (int i = 0; i < n; i++) {
            float xi = -r * 0.75f + (i + 0.5f) * (1.5f * r / n);
            float wob = (float) Math.sin(t * 6.0 + i * 1.3) * 0.5f
                      + (float) Math.sin(t * 11.0 + i * 2.7) * 0.25f;
            float h = maxH * (0.55f + 0.35f * (float) Math.sin(t * 5.5 + i * 1.9));
            h *= 0.7f + 0.3f * Math.max(0.1f, fuelRatio);
            float baseY = lavaTop + (float) Math.sin(xi * 0.45f + t * 3.2) * 1.5f;
            float tipX  = cx + xi + wob * 2f;
            float tipY  = baseY - h;
            float halfW = 2.2f;

            float baseLX = cx + xi - halfW;
            float baseRX = cx + xi + halfW;
            float ctlLX  = cx + xi - halfW * 1.4f;
            float ctlRX  = cx + xi + halfW * 1.4f;
            float ctlY   = baseY - h * 0.5f;

            try (PathBuilder pb = new PathBuilder()) {
                pb.moveTo(baseLX, baseY);
                int steps = 8;
                // Left edge: base → tip via quadratic bezier (baseLX,baseY) (ctlLX,ctlY) (tipX,tipY)
                for (int k = 1; k <= steps; k++) {
                    float u = k / (float) steps;
                    float omu = 1f - u;
                    float px = omu * omu * baseLX + 2f * omu * u * ctlLX + u * u * tipX;
                    float py = omu * omu * baseY  + 2f * omu * u * ctlY  + u * u * tipY;
                    pb.lineTo(px, py);
                }
                // Right edge: tip → base via (tipX,tipY) (ctlRX,ctlY) (baseRX,baseY)
                for (int k = 1; k <= steps; k++) {
                    float u = k / (float) steps;
                    float omu = 1f - u;
                    float px = omu * omu * tipX + 2f * omu * u * ctlRX + u * u * baseRX;
                    float py = omu * omu * tipY + 2f * omu * u * ctlY  + u * u * baseY;
                    pb.lineTo(px, py);
                }
                pb.lineTo(baseLX, baseY);
                try (Path flame = pb.build();
                     Paint p = new Paint().setColor(flameCore).setAntiAlias(true)) {
                    canvas.drawPath(flame, p);
                }
            }
            try (Paint p = new Paint().setColor(flameTip)) {
                canvas.drawCircle(tipX, tipY + 0.5f, 1.2f, p);
            }
        }
    }

    /**
     * Two concentric rings: outer = cook progress (full 360°), inner = fuel-left. Drawn after the
     * slot frames.
     */
    public static void paintRings(Canvas canvas, FurnaceLayout.Slots s, float cookRatio, float fuelRatio) {
        float cx = s.crucibleCenterX;
        float cy = s.crucibleCenterY;
        float outerR  = s.crucibleRadius + 8f;
        float innerR  = s.crucibleRadius + 4f;
        float strokeW = 3f;

        // Track (dim background full circle) for the cook ring
        try (Paint p = new Paint()
                .setColor(0x40000000)
                .setMode(PaintMode.STROKE)
                .setStrokeWidth(strokeW)) {
            canvas.drawCircle(cx, cy, outerR, p);
        }
        // Cook progress sweep — full 360° from 12 o'clock clockwise
        if (cookRatio > 0f) {
            float sweep = 360f * Math.min(cookRatio, 1f);
            try (Paint p = new Paint()
                    .setColor(PROGRESS_FILL)
                    .setMode(PaintMode.STROKE)
                    .setStrokeWidth(strokeW)) {
                canvas.drawArc(cx - outerR, cy - outerR, cx + outerR, cy + outerR,
                        -90f, sweep, false, p);
            }
        }
        // Fuel ring — inner concentric, full 360° driven by remaining-fuel-in-unit
        if (fuelRatio > 0f) {
            float sweep = 360f * Math.min(fuelRatio, 1f);
            try (Paint p = new Paint()
                    .setColor(0xFFFFC04A)
                    .setMode(PaintMode.STROKE)
                    .setStrokeWidth(2f)) {
                canvas.drawArc(cx - innerR, cy - innerR, cx + innerR, cy + innerR,
                        -90f, sweep, false, p);
            }
        }
    }

    private static int withAlpha(int argb, int alpha) {
        if (alpha < 0) alpha = 0;
        if (alpha > 255) alpha = 255;
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private static int heatColor(float heat) {
        // Heat-only ramp: dark-red → orange → near-white.
        float h = Math.max(heat, 0.15f); // floor so a lit furnace always glows a little
        if (h >= 1f) h = 1f;
        // Lerp dark-red (0x50, 0x10, 0x05) → orange (0xE8, 0x7D, 0x1C) → near-white (0xFF, 0xE8, 0xB0).
        int r, g, b;
        if (h < 0.5f) {
            float t = h / 0.5f;
            r = (int) (0x50 + t * (0xE8 - 0x50));
            g = (int) (0x10 + t * (0x7D - 0x10));
            b = (int) (0x05 + t * (0x1C - 0x05));
        } else {
            float t = (h - 0.5f) / 0.5f;
            r = (int) (0xE8 + t * (0xFF - 0xE8));
            g = (int) (0x7D + t * (0xE8 - 0x7D));
            b = (int) (0x1C + t * (0xB0 - 0x1C));
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}

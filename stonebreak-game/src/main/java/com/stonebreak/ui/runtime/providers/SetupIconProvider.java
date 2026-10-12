package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;

/**
 * {@value #ID}: a model-setup step's status disc, centred in the element, its radius half the element's height:
 * done (green, a tick), failed (red, a cross), running (a ring with a turning blue arc at {@code angle} degrees),
 * skipped (a faint ring with a dash), else pending (a faint ring). Params {@code status}, {@code angle}. Pure Skia
 * (Daedalus; the legacy ModelSetupScreen's icons).
 */
public final class SetupIconProvider implements UiPaintHost.UiDrawProvider {

    public static final String ID = "stonebreak:setup-icon";
    public static final int VERSION = 1;

    private static final int COLOR_BG = 0xFF0D1015;
    private static final int COLOR_FAINT = 0xFF5A6470;
    private static final int COLOR_DONE = 0xFF4CC36A;
    private static final int COLOR_RUNNING = 0xFF5B9BF0;
    private static final int COLOR_FAILED = 0xFFE05252;
    private static final int COLOR_BAR_BG = 0xFF26303C;

    @Override
    public void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        String status = "pending";
        float angle = 0f;
        if (element.prop("params") instanceof UiValue.Obj p) {
            if (p.get("status") instanceof UiValue.Str s) status = s.value();
            if (p.get("angle") instanceof UiValue.Num n) angle = (float) n.value();
        }
        float r = rect.height() / 2f;
        float cx = rect.x() + rect.width() / 2f;
        float cy = rect.y() + r;
        float s = scale;
        switch (status) {
            case "done" -> {
                MPainter.fillCircle(canvas, cx, cy, r, COLOR_DONE);
                stroke(canvas, COLOR_BG, 2.6f * s, cx - r * 0.45f, cy + r * 0.02f, cx - r * 0.1f, cy + r * 0.38f,
                    cx + r * 0.48f, cy - r * 0.35f);
            }
            case "failed" -> {
                MPainter.fillCircle(canvas, cx, cy, r, COLOR_FAILED);
                float d = r * 0.38f;
                stroke(canvas, COLOR_BG, 2.6f * s, cx - d, cy - d, cx + d, cy + d);
                stroke(canvas, COLOR_BG, 2.6f * s, cx + d, cy - d, cx - d, cy + d);
            }
            case "running" -> {
                MPainter.strokeCircle(canvas, cx, cy, r, COLOR_BAR_BG, 3f * s);
                try (Paint p = new Paint().setColor(COLOR_RUNNING).setMode(PaintMode.STROKE).setStrokeWidth(3f * s)
                        .setStrokeCap(PaintStrokeCap.ROUND).setAntiAlias(true)) {
                    canvas.drawArc(cx - r, cy - r, cx + r, cy + r, angle, 100f, false, p);
                }
            }
            case "skipped" -> {
                MPainter.strokeCircle(canvas, cx, cy, r, COLOR_FAINT, 1.5f * s);
                stroke(canvas, COLOR_FAINT, 2f * s, cx - r * 0.4f, cy, cx + r * 0.4f, cy);
            }
            default -> MPainter.strokeCircle(canvas, cx, cy, r, COLOR_FAINT, 1.5f * s);
        }
    }

    private static void stroke(Canvas canvas, int color, float width, float... xy) {
        try (PathBuilder pb = new PathBuilder()) {
            pb.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) pb.lineTo(xy[i], xy[i + 1]);
            try (Path path = pb.build(); Paint p = new Paint().setColor(color).setMode(PaintMode.STROKE)
                    .setStrokeWidth(width).setStrokeCap(PaintStrokeCap.ROUND).setAntiAlias(true)) {
                canvas.drawPath(path, p);
            }
        }
    }
}

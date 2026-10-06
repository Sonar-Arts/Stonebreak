package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.UiSpriteSheet.Frame;
import com.openmason.engine.format.omui.UiSpriteSheet.Sampling;
import com.openmason.engine.format.omui.UiSpriteSheet.ScaleMode;
import com.openmason.engine.format.omui.UiSpriteSheet.Sprite;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.style.ComputedStyle;
import com.openmason.engine.ui.runtime.style.StyleValues;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import java.util.List;

/**
 * Draws one resolved image region into an element rect (#294): picks the animation frame, the
 * fill mode and the sampling, lays the pieces out with {@link SpriteSlices}, and applies the
 * sprite's tint and opacity. Every source rect is drawn with Skia's strict constraint, and tiled
 * pieces repeat their own cached sub-image, so filtering never bleeds in neighbouring sprites of
 * the sheet — a sheet needs no gutters.
 *
 * <p>Precedence: an element's {@code -sb-image-scale} beats the sprite's {@code scale}; a sprite's
 * {@code sampling} beats the element's {@code -sb-sampling} (the sheet author knows whether the
 * art is pixel art). {@code nine-slice} on a sprite without a usable slice draws stretched.
 */
public final class SpritePainter {

    private SpritePainter() {
    }

    /**
     * @param time          the document's UI clock (seconds)
     * @param reducedMotion hold animated sprites on their still region
     * @return UI time of this drawing's next frame change, {@code +∞} for a still image
     */
    public static double draw(Canvas canvas, UiImage.Region region, UiRect r, ComputedStyle s, float scale,
                              double time, boolean reducedMotion) {
        MTexture texture = region.texture();
        Image img = texture.image();
        Sprite sp = region.sprite();
        if (img == null || r.width() <= 0 || r.height() <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        int sx = sp.x();
        int sy = sp.y();
        double next = Double.POSITIVE_INFINITY;
        if (sp.animated() && !reducedMotion) {
            List<Frame> frames = sp.frames();
            Frame f = frames.get(SpriteFrames.frameAt(frames, sp.loop(), time));
            sx = f.x();
            sy = f.y();
            next = SpriteFrames.nextChange(frames, sp.loop(), time);
        }
        ScaleMode mode = scaleMode(s, sp);
        if (mode == ScaleMode.NINE_SLICE && (sp.slice().isNone() || !region.sliceUsable())) {
            mode = ScaleMode.STRETCH;
        }
        boolean nearest = sp.sampling() != null ? sp.sampling() == Sampling.NEAREST
            : !"linear".equals(s.keyword("-sb-sampling", "nearest"));
        SamplingMode sampling = nearest ? SamplingMode.DEFAULT : SamplingMode.LINEAR;
        float kx = (float) (sp.layoutWidth() * scale / sp.w());
        float ky = (float) (sp.layoutHeight() * scale / sp.h());
        List<SpriteSlices.Patch> patches = SpriteSlices.layout(sx, sy, sp.w(), sp.h(), sp.slice(), sp.edges(),
            sp.center(), mode, sp.pivotX(), sp.pivotY(), r, kx, ky, nearest);
        int tint = sp.tint() == null ? 0xFFFFFFFF : StyleValues.color(UiValue.of(sp.tint()), 0xFFFFFFFF);
        try (Paint paint = new Paint()) {
            ColorFilter filter = tint == 0xFFFFFFFF ? null : ColorFilter.makeBlend(tint, BlendMode.MODULATE);
            try {
                if (filter != null) {
                    paint.setColorFilter(filter);
                }
                if (sp.opacity() < 1) {
                    paint.setAlphaf((float) sp.opacity());
                }
                for (SpriteSlices.Patch p : patches) {
                    patch(canvas, texture, img, p, sampling, paint);
                }
            } finally {
                if (filter != null) {
                    filter.close();
                }
            }
        }
        return next;
    }

    /** The fill mode {@code s} asks for, else the sprite's own. */
    static ScaleMode scaleMode(ComputedStyle s, Sprite sp) {
        UiValue v = s.get("-sb-image-scale");
        ScaleMode styled = v instanceof UiValue.Str str ? ScaleMode.fromWire(str.value()) : null;
        return styled != null ? styled : sp.effectiveScale();
    }

    private static void patch(Canvas canvas, MTexture texture, Image img, SpriteSlices.Patch p, SamplingMode sampling,
                              Paint paint) {
        Rect dst = Rect.makeXYWH(p.dx(), p.dy(), p.dw(), p.dh());
        if (!p.tiled()) {
            canvas.drawImageRect(img, Rect.makeXYWH(p.sx(), p.sy(), p.sw(), p.sh()), dst, sampling, paint, true);
            return;
        }
        Image sub = texture.region(p.sx(), p.sy(), p.sw(), p.sh());
        if (sub == null) {
            return;
        }
        Matrix33 local = Matrix33.makeTranslate(p.dx(), p.dy()).makeConcat(Matrix33.makeScale(p.tileKx(), p.tileKy()));
        try (Shader shader = sub.makeShader(p.tileX() ? FilterTileMode.REPEAT : FilterTileMode.CLAMP,
            p.tileY() ? FilterTileMode.REPEAT : FilterTileMode.CLAMP, sampling, local)) {
            paint.setShader(shader);
            canvas.drawRect(dst, paint);
        } finally {
            paint.setShader(null);
        }
    }
}

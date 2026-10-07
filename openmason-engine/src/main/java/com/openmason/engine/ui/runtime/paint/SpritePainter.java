package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiSpriteSheet.Fill;
import com.openmason.engine.format.omui.UiSpriteSheet.Frame;
import com.openmason.engine.format.omui.UiSpriteSheet.Sampling;
import com.openmason.engine.format.omui.UiSpriteSheet.ScaleMode;
import com.openmason.engine.format.omui.UiSpriteSheet.Slice;
import com.openmason.engine.format.omui.UiSpriteSheet.Sprite;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.runtime.UiElement;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p>A steady frame allocates no native objects: paints (with their tint filter), tiled-patch
 * shaders and patch layouts are cached per paint thread.
 */
public final class SpritePainter {

    /** Patch layouts kept per paint thread; a steady frame re-lays nothing out. */
    private static final int LAYOUT_CACHE = 512;

    private static final ThreadLocal<Caches> CACHES = ThreadLocal.withInitial(Caches::new);
    private static final Map<String, Integer> TINTS = new ConcurrentHashMap<>();
    private static final Map<UiElement, Start> STARTS = new WeakHashMap<>();

    private SpritePainter() {
    }

    /** Draws without per-element timing: every sprite follows the document clock (tests, previews of one image). */
    public static double draw(Canvas canvas, UiImage.Region region, UiRect r, ComputedStyle s, float scale,
                              double time, boolean reducedMotion) {
        return draw(canvas, null, region, r, s, scale, time, reducedMotion);
    }

    /**
     * @param owner         the element showing the sprite: a play-once sprite starts when this
     *                      element starts showing it (a new slot flash, a furnace flame lighting),
     *                      not when the document was created; null = document time
     * @param time          the document's UI clock (seconds)
     * @param reducedMotion hold animated sprites on their still region
     * @return UI time of this drawing's next frame change, {@code +∞} for a still image
     */
    public static double draw(Canvas canvas, UiElement owner, UiImage.Region region, UiRect r, ComputedStyle s,
                              float scale, double time, boolean reducedMotion) {
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
            double start = startTime(owner, sp, time);
            double local = time - start;
            Frame f = frames.get(SpriteFrames.frameAt(frames, sp.loop(), local));
            sx = f.x();
            sy = f.y();
            next = start + SpriteFrames.nextChange(frames, sp.loop(), local);
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
        Caches caches = CACHES.get();
        List<SpriteSlices.Patch> patches = caches.layout(new LayoutKey(sx, sy, sp.w(), sp.h(), sp.slice(), sp.edges(),
            sp.center(), mode, sp.pivotX(), sp.pivotY(), r.x(), r.y(), r.width(), r.height(), kx, ky, nearest));
        Paint paint = caches.paint(tint(sp.tint()), (float) Math.min(1, sp.opacity()));
        for (SpriteSlices.Patch p : patches) {
            patch(canvas, texture, img, p, sampling, paint, caches);
        }
        return next;
    }

    /** The fill mode {@code s} asks for, else the sprite's own. */
    static ScaleMode scaleMode(ComputedStyle s, Sprite sp) {
        UiValue v = s.get("-sb-image-scale");
        ScaleMode styled = v instanceof UiValue.Str str ? ScaleMode.fromWire(str.value()) : null;
        return styled != null ? styled : sp.effectiveScale();
    }

    /**
     * When {@code owner}'s current showing of {@code sp} began. Looping sprites stay on the
     * document clock (every copy animates in step); a play-once sprite restarts per element and
     * whenever the element switches to a different sprite.
     */
    static double startTime(UiElement owner, Sprite sp, double time) {
        if (owner == null || sp.loop() != LoopMode.ONCE) {
            return 0;
        }
        synchronized (STARTS) {
            Start st = STARTS.get(owner);
            if (st == null || !st.sprite().equals(sp) || time < st.at()) {
                st = new Start(sp, time);
                STARTS.put(owner, st);
            }
            return st.at();
        }
    }

    private static int tint(String tint) {
        if (tint == null) {
            return 0xFFFFFFFF;
        }
        return TINTS.computeIfAbsent(tint, t -> StyleValues.color(UiValue.of(t), 0xFFFFFFFF));
    }

    private static void patch(Canvas canvas, MTexture texture, Image img, SpriteSlices.Patch p, SamplingMode sampling,
                              Paint paint, Caches caches) {
        Rect dst = Rect.makeXYWH(p.dx(), p.dy(), p.dw(), p.dh());
        if (!p.tiled()) {
            canvas.drawImageRect(img, Rect.makeXYWH(p.sx(), p.sy(), p.sw(), p.sh()), dst, sampling, paint, true);
            return;
        }
        Image sub = texture.region(p.sx(), p.sy(), p.sw(), p.sh());
        if (sub == null) {
            return;
        }
        // the shader's matrix is scale-only (cached per sub-image); the patch origin is a canvas translate
        Shader shader = caches.shader(sub, p.tileX(), p.tileY(), sampling, p.tileKx(), p.tileKy());
        int saved = canvas.save();
        try {
            canvas.translate(p.dx(), p.dy());
            paint.setShader(shader);
            canvas.drawRect(Rect.makeXYWH(0, 0, p.dw(), p.dh()), paint);
        } finally {
            paint.setShader(null);
            canvas.restoreToCount(saved);
        }
    }

    private record Start(Sprite sprite, double at) {
    }

    private record LayoutKey(int sx, int sy, int w, int h, Slice slice, Fill edges, Fill center, ScaleMode mode,
                             double pivotX, double pivotY, float x, float y, float width, float height, float kx,
                             float ky, boolean nearest) {
    }

    private record ShaderKey(boolean tileX, boolean tileY, SamplingMode sampling, float kx, float ky) {
    }

    /**
     * Native objects a sprite draw reuses, per paint thread: one {@link Paint} per tint/opacity
     * (with its colour filter), tiled-patch shaders per sub-image, and patch layouts. Shaders key
     * on the sub-image weakly, so a released texture's shaders go with it.
     */
    private static final class Caches {
        final Map<Long, Paint> paints = new HashMap<>();
        final Map<Image, Map<ShaderKey, Shader>> shaders = new WeakHashMap<>();
        final Map<LayoutKey, List<SpriteSlices.Patch>> layouts = new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<LayoutKey, List<SpriteSlices.Patch>> eldest) {
                return size() > LAYOUT_CACHE;
            }
        };

        List<SpriteSlices.Patch> layout(LayoutKey k) {
            List<SpriteSlices.Patch> cached = layouts.get(k);
            if (cached == null) {
                cached = List.copyOf(SpriteSlices.layout(k.sx(), k.sy(), k.w(), k.h(), k.slice(), k.edges(), k.center(),
                    k.mode(), k.pivotX(), k.pivotY(), new UiRect(k.x(), k.y(), k.width(), k.height()), k.kx(), k.ky(),
                    k.nearest()));
                layouts.put(k, cached);
            }
            return cached;
        }

        Paint paint(int tint, float opacity) {
            long key = ((long) tint << 32) | (Float.floatToIntBits(opacity) & 0xFFFFFFFFL);
            Paint paint = paints.get(key);
            if (paint == null) {
                paint = new Paint();
                if (tint != 0xFFFFFFFF) {
                    try (ColorFilter filter = ColorFilter.makeBlend(tint, BlendMode.MODULATE)) {
                        paint.setColorFilter(filter); // the paint keeps its own reference
                    }
                }
                if (opacity < 1) {
                    paint.setAlphaf(opacity);
                }
                paints.put(key, paint);
            }
            return paint;
        }

        Shader shader(Image sub, boolean tileX, boolean tileY, SamplingMode sampling, float kx, float ky) {
            ShaderKey key = new ShaderKey(tileX, tileY, sampling, kx, ky);
            return shaders.computeIfAbsent(sub, i -> new HashMap<>()).computeIfAbsent(key, k ->
                sub.makeShader(tileX ? FilterTileMode.REPEAT : FilterTileMode.CLAMP,
                    tileY ? FilterTileMode.REPEAT : FilterTileMode.CLAMP, sampling, Matrix33.makeScale(kx, ky)));
        }
    }
}

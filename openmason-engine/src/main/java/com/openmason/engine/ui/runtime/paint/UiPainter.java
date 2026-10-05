package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MItemSlot;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.layout.PaintOrder;
import com.openmason.engine.ui.runtime.style.ComputedStyle;
import com.openmason.engine.ui.runtime.style.StyleValues;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.FilterTileMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.Rect;

import java.util.List;

/**
 * Paints a runtime element tree with Masonry (#287): the geometry is exactly the rects layout
 * produced and hit testing uses, in the same {@link PaintOrder}, so what a player sees is what
 * they click.
 *
 * <p>Per element, bottom to top: {@code background-color} (rounded by {@code border-radius}),
 * {@code background-image}, the widget's own look, {@code border-*}; then children, clipped by
 * {@code overflow: hidden} and scroll containers; then a scroll container's scrollbar.
 * {@code opacity} fades the element with its subtree; {@code -sb-tint} multiplies the element's
 * own drawing; {@code visibility: hidden} skips the element but not visible descendants.
 *
 * <p>Widget looks: {@code Button} is the Masonry stone surface (hover/active → highlight fill,
 * disabled → disabled fill) unless a background is styled; {@code Label} draws house-style
 * shadowed text on the {@link MasonryContentMeasurer} baseline with {@code text-align};
 * {@code Image} draws its {@code source}; {@code ItemSlot} draws the Masonry slot frame and
 * then its host provider; {@code DrawProvider} calls its provider. Images honour
 * {@code -sb-image-scale} (stretch, integer, tile; nine-slice draws as stretch until slice
 * insets exist) and {@code -sb-sampling} (nearest by default: pixel art).
 *
 * <p>The caller owns the frame: {@code masonry.beginFrame(...)} before, {@code endFrame()} after.
 */
public final class UiPainter {

    private final UiPaintHost host;
    private final MasonryContentMeasurer text;
    private final MItemSlot slotFrame = new MItemSlot();

    public UiPainter(UiPaintHost host, MasonryContentMeasurer text) {
        this.host = host == null ? UiPaintHost.NONE : host;
        this.text = text;
    }

    /** Paints {@code ui} (already updated) into {@code masonry}'s open frame. */
    public void paint(UiDocumentInstance ui, MasonryUI masonry) {
        Canvas canvas = masonry.canvas();
        if (canvas == null) {
            return;
        }
        float scale = ui.metrics().scale();
        PaintOrder order = ui.paintOrder();
        for (PaintOrder.Entry e : order.entries()) {
            paintSubtree(ui, masonry, canvas, order, e.root(), scale);
        }
    }

    private void paintSubtree(UiDocumentInstance ui, MasonryUI masonry, Canvas canvas, PaintOrder order, UiElement el,
                              float scale) {
        ComputedStyle s = el.computedStyle();
        if (s.collapsed()) {
            return;
        }
        float opacity = (float) s.number("opacity", 1);
        if (opacity <= 0f) {
            return;
        }
        int saved = canvas.getSaveCount();
        if (opacity < 1f) {
            try (Paint p = new Paint().setAlphaf(opacity)) {
                canvas.saveLayer(null, p);
            }
        }
        if (!s.hidden()) {
            paintSelf(ui, masonry, canvas, el, scale);
        }
        List<UiElement> children = el.children();
        if (!children.isEmpty()) {
            boolean clip = el.clipsChildren();
            if (clip) {
                canvas.save();
                UiRect r = el.rect();
                canvas.clipRect(Rect.makeXYWH(r.x(), r.y(), r.width(), r.height()));
            }
            for (UiElement c : children) {
                if (!order.isLifted(c)) {
                    paintSubtree(ui, masonry, canvas, order, c, scale);
                }
            }
            if (clip) {
                canvas.restore();
            }
        }
        if (el.isScrollContainer() && !s.hidden()) {
            scrollbars(canvas, el, scale);
        }
        canvas.restoreToCount(saved);
    }

    private void paintSelf(UiDocumentInstance ui, MasonryUI masonry, Canvas canvas, UiElement el, float scale) {
        ComputedStyle s = el.computedStyle();
        UiRect r = el.rect();
        if (r.width() <= 0 || r.height() <= 0) {
            return;
        }
        int saved = canvas.getSaveCount();
        UiValue tintValue = s.get("-sb-tint");
        if (tintValue != null) {
            int tint = StyleValues.color(tintValue, 0xFFFFFFFF);
            if (tint != 0xFFFFFFFF) {
                try (ColorFilter f = ColorFilter.makeBlend(tint, BlendMode.MODULATE);
                     Paint p = new Paint().setColorFilter(f)) {
                    canvas.saveLayer(null, p);
                }
            }
        }
        float radius = (float) s.number("border-radius", 0) * scale;
        UiValue bg = s.get("background-color");
        boolean styledBackground = bg != null || assetRef(s.get("background-image")) != null;
        if (bg != null) {
            MPainter.fillRoundedRect(canvas, r.x(), r.y(), r.width(), r.height(), radius, StyleValues.color(bg, 0));
        }
        MTexture bgImage = host.texture(assetRef(s.get("background-image")));
        if (bgImage != null) {
            image(canvas, bgImage, r, s, scale);
        }
        switch (el.type()) {
            case "Button" -> {
                if (!styledBackground) {
                    int fill = !el.isEnabledInHierarchy() ? MStyle.BUTTON_FILL_DIS
                        : el.hasState(UiElement.HOVER) || el.hasState(UiElement.ACTIVE) ? MStyle.BUTTON_FILL_HI
                        : MStyle.BUTTON_FILL;
                    MPainter.stoneSurface(canvas, r.x(), r.y(), r.width(), r.height(), MStyle.BUTTON_RADIUS * scale,
                        fill, MStyle.BUTTON_BORDER, MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW,
                        MStyle.BUTTON_DROP_SHADOW, MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
                }
            }
            case "Label" -> label(canvas, el, r, s, scale);
            case "Image" -> {
                MTexture t = host.texture(assetRef(el.prop("source")));
                if (t != null) {
                    image(canvas, t, r, s, scale);
                }
            }
            case "ItemSlot" -> {
                slotFrame.bounds(r.x(), r.y(), r.width(), r.height());
                slotFrame.setHovered(el.hasState(UiElement.HOVER));
                slotFrame.render(masonry);
                provider(canvas, el, r, scale);
            }
            case "DrawProvider" -> provider(canvas, el, r, scale);
            default -> {
            }
        }
        border(canvas, r, s, radius, scale);
        canvas.restoreToCount(saved);
    }

    private void label(Canvas canvas, UiElement el, UiRect r, ComputedStyle s, float scale) {
        if (text == null) {
            return;
        }
        Font font = text.font(el, scale);
        String str = el.text("text");
        if (font == null || str.isEmpty()) {
            return;
        }
        int color = s.color("color", MStyle.TEXT_PRIMARY);
        float baseline = r.y() + text.baseline(el, r.width(), r.height(), scale);
        switch (s.keyword("text-align", "left")) {
            case "center" -> MPainter.drawText(canvas, str, r.x() + r.width() / 2f, baseline, font, color,
                MPainter.Align.CENTER);
            case "right" -> MPainter.drawText(canvas, str, r.right(), baseline, font, color, MPainter.Align.RIGHT);
            default -> MPainter.drawText(canvas, str, r.x(), baseline, font, color, MPainter.Align.LEFT);
        }
    }

    private void provider(Canvas canvas, UiElement el, UiRect r, float scale) {
        String id = el.prop("provider") instanceof UiValue.Str str ? str.value() : null;
        UiPaintHost.UiDrawProvider p = host.drawProvider(id);
        if (p != null) {
            int saved = canvas.save();
            try {
                p.draw(canvas, el, r, scale);
            } finally {
                canvas.restoreToCount(saved);
            }
        }
    }

    private static void image(Canvas canvas, MTexture texture, UiRect r, ComputedStyle s, float scale) {
        Image img = texture.image();
        if (img == null) {
            return;
        }
        SamplingMode sampling = "linear".equals(s.keyword("-sb-sampling", "nearest"))
            ? SamplingMode.LINEAR : SamplingMode.DEFAULT;
        Rect src = Rect.makeWH(img.getWidth(), img.getHeight());
        switch (s.keyword("-sb-image-scale", "stretch")) {
            case "integer" -> {
                float k = Math.max(1, (float) Math.floor(Math.min(r.width() / img.getWidth(), r.height() / img.getHeight())));
                float w = img.getWidth() * k;
                float h = img.getHeight() * k;
                float x = Math.round(r.x() + (r.width() - w) / 2f);
                float y = Math.round(r.y() + (r.height() - h) / 2f);
                try (Paint p = new Paint()) {
                    canvas.drawImageRect(img, src, Rect.makeXYWH(x, y, w, h), sampling, p, true);
                }
            }
            case "tile" -> {
                Matrix33 local = Matrix33.makeTranslate(r.x(), r.y()).makeConcat(Matrix33.makeScale(scale, scale));
                try (Shader shader = img.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, sampling, local);
                     Paint p = new Paint().setShader(shader)) {
                    canvas.drawRect(Rect.makeXYWH(r.x(), r.y(), r.width(), r.height()), p);
                }
            }
            default -> {
                try (Paint p = new Paint()) {
                    canvas.drawImageRect(img, src, Rect.makeXYWH(r.x(), r.y(), r.width(), r.height()), sampling, p, true);
                }
            }
        }
    }

    private static void border(Canvas canvas, UiRect r, ComputedStyle s, float radius, float scale) {
        UiValue colorValue = s.get("border-color");
        if (colorValue == null) {
            return;
        }
        int color = StyleValues.color(colorValue, 0);
        float l = px(s, "border-left-width", scale);
        float t = px(s, "border-top-width", scale);
        float rt = px(s, "border-right-width", scale);
        float b = px(s, "border-bottom-width", scale);
        if (l <= 0 && t <= 0 && rt <= 0 && b <= 0) {
            return;
        }
        if (l == t && t == rt && rt == b) {
            MPainter.strokeRoundedRect(canvas, r.x(), r.y(), r.width(), r.height(), radius, color, l);
            return;
        }
        MPainter.fillRect(canvas, r.x(), r.y(), r.width(), t, color);
        MPainter.fillRect(canvas, r.x(), r.bottom() - b, r.width(), b, color);
        MPainter.fillRect(canvas, r.x(), r.y(), l, r.height(), color);
        MPainter.fillRect(canvas, r.right() - rt, r.y(), rt, r.height(), color);
    }

    private static void scrollbars(Canvas canvas, UiElement el, float scale) {
        UiRect r = el.rect();
        float thickness = 4f * scale;
        if (el.maxScrollY() > 0) {
            float content = r.height() + el.maxScrollY();
            float h = Math.max(thickness * 2, r.height() * r.height() / content);
            float y = r.y() + (r.height() - h) * (el.scrollY() / el.maxScrollY());
            MPainter.fillRect(canvas, r.right() - thickness, r.y(), thickness, r.height(), MStyle.SCROLLBAR_TRACK);
            MPainter.fillRect(canvas, r.right() - thickness, y, thickness, h, MStyle.SCROLLBAR_THUMB);
        }
        if (el.maxScrollX() > 0) {
            float content = r.width() + el.maxScrollX();
            float w = Math.max(thickness * 2, r.width() * r.width() / content);
            float x = r.x() + (r.width() - w) * (el.scrollX() / el.maxScrollX());
            MPainter.fillRect(canvas, r.x(), r.bottom() - thickness, r.width(), thickness, MStyle.SCROLLBAR_TRACK);
            MPainter.fillRect(canvas, x, r.bottom() - thickness, w, thickness, MStyle.SCROLLBAR_THUMB);
        }
    }

    private static float px(ComputedStyle s, String property, float scale) {
        StyleValues.Length l = s.length(property);
        return l.kind() == StyleValues.Length.Kind.POINTS ? l.value() * scale : 0;
    }

    private static String assetRef(UiValue v) {
        return v instanceof UiValue.Str str && !"none".equals(str.value()) ? str.value() : null;
    }
}

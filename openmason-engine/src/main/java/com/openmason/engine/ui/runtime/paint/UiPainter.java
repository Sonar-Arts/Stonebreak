package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MItemSlot;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.MSymbol;
import com.openmason.engine.ui.masonry.MTooltip;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.TextLineMetrics;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiTexts;
import com.openmason.engine.ui.runtime.input.TextFieldController;
import com.openmason.engine.ui.runtime.input.TextFieldGeometry;
import com.openmason.engine.ui.runtime.input.TooltipController;
import com.openmason.engine.ui.runtime.input.UiInputRouter;
import com.openmason.engine.ui.runtime.widget.InputProps;
import com.openmason.engine.ui.text.TextBoundaries;
import com.openmason.engine.ui.runtime.layout.PaintOrder;
import com.openmason.engine.ui.runtime.layout.ScrollbarGeometry;
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
 * <p>Interaction (#288): a {@code Button} also highlights for keyboard/controller focus
 * ({@code :focus-visible}); any other element with {@code :focus-visible} gets an accent focus
 * ring. A {@code TextField} draws the legacy {@code MTextField} look with its value (masked for
 * passwords), placeholder, selection, IME preedit (underlined) and caret, scrolled to keep the
 * caret visible. A {@code status} (or an {@code :invalid} field) is drawn as a symbol, never as
 * colour alone. The router's tooltip paints last, above every layer.
 *
 * <p>The caller owns the frame: {@code masonry.beginFrame(...)} before, {@code endFrame()} after.
 */
public final class UiPainter {

    private final UiPaintHost host;
    private final MasonryContentMeasurer text;
    private final MItemSlot slotFrame = new MItemSlot();
    private final CanvasPainter canvasPainter;
    private UiInputRouter input;

    private static final int FIELD_FILL = 0xFF1F1F1F;
    private static final int FIELD_FILL_ACTIVE = 0xFF2A2A2A;
    private static final int FIELD_BORDER = 0xFF0F0F0F;
    private static final int FIELD_SELECTION = 0x606A82C8;
    private static final int STATUS_SUCCESS = 0xFF32C832;
    private static final int STATUS_ERROR = 0xFFC83232;
    private static final int STATUS_WARNING = 0xFFE0A030;
    private static final int STATUS_INFO = 0xFF6A82C8;

    public UiPainter(UiPaintHost host, MasonryContentMeasurer text) {
        this.host = host == null ? UiPaintHost.NONE : host;
        this.text = text;
        this.canvasPainter = new CanvasPainter(this.host, text);
    }

    /** Paints {@code ui} (already updated) into {@code masonry}'s open frame, without interaction state. */
    public void paint(UiDocumentInstance ui, MasonryUI masonry) {
        paint(ui, masonry, null);
    }

    /** Paints {@code ui} with {@code router}'s editing state (carets, selections) and tooltip. */
    public void paint(UiDocumentInstance ui, MasonryUI masonry, UiInputRouter router) {
        Canvas canvas = masonry.canvas();
        if (canvas == null) {
            return;
        }
        input = router;
        try {
            float scale = ui.metrics().scale();
            PaintOrder order = ui.paintOrder();
            for (PaintOrder.Entry e : order.entries()) {
                paintSubtree(ui, masonry, canvas, order, e.root(), scale);
            }
            if (router != null) {
                tooltip(masonry, router.tooltips().current(), ui, scale);
            }
        } finally {
            input = null;
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
                        : el.hasState(UiElement.HOVER) || el.hasState(UiElement.ACTIVE)
                        || el.hasState(UiElement.FOCUS_VISIBLE) ? MStyle.BUTTON_FILL_HI
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
            case "TextField" -> textField(canvas, el, r, s, scale, styledBackground);
            case "Canvas" -> canvasPainter.paint(canvas, ui.canvas(el.key()), r, scale);
            default -> {
            }
        }
        border(canvas, r, s, radius, scale);
        if (el.hasState(UiElement.FOCUS_VISIBLE) && !"Button".equals(el.type()) && !"TextField".equals(el.type())) {
            float g = 2f * scale;
            MPainter.strokeRoundedRect(canvas, r.x() - g, r.y() - g, r.width() + 2 * g, r.height() + 2 * g,
                radius + g, MStyle.TEXT_ACCENT, g);
        }
        status(canvas, el, r, scale);
        canvas.restoreToCount(saved);
    }

    // ── text fields (#288) ──────────────────────────────────────────────────

    private void textField(Canvas canvas, UiElement el, UiRect r, ComputedStyle s, float scale,
                           boolean styledBackground) {
        boolean focused = el.hasState(UiElement.FOCUS);
        if (!styledBackground) {
            MPainter.fillRoundedRect(canvas, r.x(), r.y(), r.width(), r.height(), 3f * scale,
                focused ? FIELD_FILL_ACTIVE : FIELD_FILL);
            MPainter.strokeRect(canvas, r.x() + 0.5f, r.y() + 0.5f, r.width() - 1f, r.height() - 1f,
                focused ? MStyle.SLIDER_FILL : FIELD_BORDER, (focused ? 1.5f : 1f) * scale);
        }
        if (text == null) {
            return;
        }
        Font font = text.font(el, scale);
        if (font == null) {
            return;
        }
        TextLineMetrics m = text.textLine(el, scale);
        TextFieldController tf = input == null ? null : input.existingTextField(el);
        boolean multiline = el.prop("multiline") instanceof UiValue.Bool b && b.value();
        TextFieldGeometry g = TextFieldGeometry.of(el, m, scale, multiline);
        boolean password = el.prop("password") instanceof UiValue.Bool b && b.value();
        String value = el.text("text");
        String shown = tf != null ? tf.displayText() : password ? "*".repeat(TextBoundaries.count(value)) : value;
        float scroll = tf != null ? tf.scrollX(g, m) : 0;
        int color = !el.isEnabledInHierarchy() ? MStyle.TEXT_DISABLED : s.color("color", MStyle.TEXT_PRIMARY);
        int saved = canvas.save();
        canvas.clipRect(Rect.makeXYWH(g.left(), r.y(), g.width(), r.height()));
        boolean composing = tf != null && tf.model().isComposing();
        if (shown.isEmpty() && !composing) {
            String placeholder = UiTexts.placeholder(el);
            if (!placeholder.isEmpty()) {
                MPainter.drawString(canvas, placeholder, g.left(), g.baseline(0), font, MStyle.TEXT_DISABLED);
            }
        } else {
            String[] lines = shown.split("\n", -1);
            int selStart = -1;
            int selEnd = -1;
            if (tf != null && focused && tf.model().hasSelection()) {
                selStart = tf.displayIndex(tf.model().selectionStart());
                selEnd = tf.displayIndex(tf.model().selectionEnd());
            }
            int lineStart = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                float x0 = g.left() - scroll;
                float top = g.top() + i * g.lineHeight();
                int a = Math.max(selStart, lineStart) - lineStart;
                int b = Math.min(selEnd, lineStart + line.length()) - lineStart;
                if (selStart >= 0 && b > a) {
                    float sx = x0 + m.measure().advance(line, a);
                    float ex = x0 + m.measure().advance(line, b);
                    MPainter.fillRect(canvas, sx, top, ex - sx, g.lineHeight(), FIELD_SELECTION);
                }
                MPainter.drawString(canvas, line, x0, g.baseline(i), font, color);
                if (composing) {
                    int ps = tf.displayIndex(tf.model().compositionStart()) - lineStart;
                    int pe = ps + tf.model().preedit().length();
                    int ua = Math.max(ps, 0);
                    int ub = Math.min(pe, line.length());
                    if (ub > ua) {
                        float ux = x0 + m.measure().advance(line, ua);
                        float uw = m.measure().advance(line, ub) - (ux - x0);
                        MPainter.fillRect(canvas, ux, g.baseline(i) + 2f * scale, uw, Math.max(1f, scale), color);
                    }
                }
                lineStart += line.length() + 1;
            }
            if (tf != null && input != null && tf.caretVisible(input.time(), input.settings().caretBlinkInterval(),
                el.owner().preferences().reducedMotion())) {
                int caret = tf.displayCaret();
                int line = 0;
                int start = 0;
                for (int i = 0; i < lines.length; i++) {
                    if (caret <= start + lines[i].length()) {
                        line = i;
                        break;
                    }
                    start += lines[i].length() + 1;
                }
                float cx = g.left() - scroll + m.measure().advance(lines[line], caret - start);
                float top = g.top() + line * g.lineHeight();
                MPainter.fillRect(canvas, Math.round(cx), top + scale, Math.max(1f, 1.5f * scale),
                    g.lineHeight() - 2 * scale, MStyle.TEXT_PRIMARY);
            }
        }
        canvas.restoreToCount(saved);
    }

    // ── status and tooltips (#288) ──────────────────────────────────────────

    private static void status(Canvas canvas, UiElement el, UiRect r, float scale) {
        String status = el.hasState(UiElement.INVALID) ? "error"
            : el.prop(InputProps.STATUS) instanceof UiValue.Str st ? st.value() : "none";
        MSymbol symbol;
        int color;
        switch (status) {
            case "success" -> {
                symbol = MSymbol.CHECK;
                color = STATUS_SUCCESS;
            }
            case "error" -> {
                symbol = MSymbol.CROSS;
                color = STATUS_ERROR;
            }
            case "warning" -> {
                symbol = MSymbol.WARNING;
                color = STATUS_WARNING;
            }
            case "info" -> {
                symbol = MSymbol.INFO;
                color = STATUS_INFO;
            }
            case "busy" -> {
                symbol = MSymbol.GEAR;
                color = MStyle.TEXT_PRIMARY;
            }
            default -> {
                return;
            }
        }
        float size = Math.min(16f * scale, r.height() - 4f * scale);
        if (size <= 2) {
            return;
        }
        float x = r.right() - size - 6f * scale;
        float y = r.y() + (r.height() - size) / 2f;
        symbol.draw(canvas, x, y, size, size, color);
    }

    private static void tooltip(MasonryUI masonry, TooltipController.Tooltip t, UiDocumentInstance ui, float scale) {
        if (t == null) {
            return;
        }
        float w = ui.metrics().viewportWidth();
        float h = ui.metrics().viewportHeight();
        if (t.fromFocus()) {
            MTooltip.draw(masonry, t.text(), t.x(), t.y() + 4f * scale, (int) w, (int) h);
        } else {
            MTooltip.draw(masonry, t.text(), t.x() + 15f * scale, t.y() + 15f * scale, (int) w, (int) h);
        }
    }

    private void label(Canvas canvas, UiElement el, UiRect r, ComputedStyle s, float scale) {
        if (text == null) {
            return;
        }
        Font font = text.font(el, scale);
        String str = UiTexts.label(el);
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
        for (ScrollbarGeometry bar : new ScrollbarGeometry[]{ScrollbarGeometry.vertical(el, scale),
            ScrollbarGeometry.horizontal(el, scale)}) {
            if (bar != null) {
                UiRect t = bar.track();
                UiRect th = bar.thumb();
                MPainter.fillRect(canvas, t.x(), t.y(), t.width(), t.height(), MStyle.SCROLLBAR_TRACK);
                MPainter.fillRect(canvas, th.x(), th.y(), th.width(), th.height(), MStyle.SCROLLBAR_THUMB);
            }
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

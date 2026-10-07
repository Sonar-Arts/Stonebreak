package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MItemSlot;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MStyle;
import com.openmason.engine.ui.masonry.MSymbol;
import com.openmason.engine.ui.masonry.MTooltip;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.TextLineMetrics;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic;
import com.openmason.engine.ui.runtime.input.UiCoordinates;
import com.openmason.engine.ui.runtime.input.UiTransform;
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
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Matrix33;
import io.github.humbleui.skija.Paint;
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
 * <p>{@code -sb-surface} (any element, {@code ui-masonry}) paints a house surface after the
 * background and replaces a {@code Button}'s state-driven look; {@code -sb-text-effect} picks a
 * label's shadow, none, or the layered title.
 *
 * <p>Widget looks: {@code Button} is the Masonry stone surface (hover/active → highlight fill,
 * disabled → disabled fill) unless a background is styled; {@code Label} draws house-style
 * shadowed text on the {@link MasonryContentMeasurer} baseline with {@code text-align};
 * {@code Image} draws its {@code source}; {@code ItemSlot} draws the Masonry slot frame (with the
 * selected-hotbar ring when its {@code stack} record is {@code selected}) and
 * then its host provider; {@code DrawProvider} calls its provider. Images and
 * {@code background-image} are whole textures, sprite regions or skins
 * ({@code <sheet>#<name>}, #294) drawn by {@link SpritePainter}: {@code -sb-image-scale}
 * (stretch, integer, tile, nine-slice), {@code -sb-sampling} (nearest by default: pixel art),
 * the sprite's tint, opacity and animation frames on the document's UI clock.
 *
 * <p>Interaction (#288): a {@code Button} also highlights for keyboard/controller focus
 * ({@code :focus-visible}); any other element with {@code :focus-visible} gets an accent focus
 * ring. A {@code TextField} draws the legacy {@code MTextField} look with its value (masked for
 * passwords), placeholder, selection, IME preedit (underlined) and caret, scrolled to keep the
 * caret visible. A {@code status} (or an {@code :invalid} field) is drawn as a symbol, never as
 * colour alone. The router's tooltip paints last, above every layer.
 *
 * <p>Layers: overlays ({@code -sb-layer}) paint after lower layers and keep their ancestors'
 * transforms and opacity; the router's tooltip paints above every authored layer; the
 * pointer-anchored cursor layer ({@code -sb-anchor: pointer}) paints last, only while the
 * pointer is over the frame. Host draw providers get their GL phase from {@link #prepare}
 * before the frame; a provider id the host does not know paints a placeholder and reports
 * {@code MISSING_DRAW_PROVIDER} instead of leaving the element silently empty.
 *
 * <p>The caller owns the frame: {@code masonry.beginFrame(...)} before, {@code endFrame()} after.
 */
public final class UiPainter {

    private final UiPaintHost host;
    private final MasonryContentMeasurer text;
    private final MItemSlot slotFrame = new MItemSlot();
    private final CanvasPainter canvasPainter;
    private UiInputRouter input;
    /** Provider problems already reported ({@code key|id}), so a broken slot does not report every frame. */
    private final java.util.Set<String> providerReports = new java.util.HashSet<>();

    private static final int PLACEHOLDER_FILL = 0x66FF00FF;
    private static final int PLACEHOLDER_EDGE = 0xFFFF00FF;

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
        ui.beginAnimationFrame();
        try {
            float scale = ui.metrics().scale();
            PaintOrder order = ui.paintOrder();
            for (PaintOrder.Entry e : order.entries()) {
                if (!e.cursor()) {
                    paintEntry(ui, masonry, canvas, order, e, scale);
                }
            }
            if (router != null) {
                tooltip(masonry, router.tooltips().current(), ui, scale);
            }
            if (ui.pointerInside()) {
                for (PaintOrder.Entry e : order.entries()) {
                    if (e.cursor()) {
                        paintEntry(ui, masonry, canvas, order, e, scale);
                    }
                }
            }
        } finally {
            input = null;
        }
    }

    private void paintEntry(UiDocumentInstance ui, MasonryUI masonry, Canvas canvas, PaintOrder order,
                            PaintOrder.Entry e, float scale) {
        // A lifted overlay still turns, scales (#295) and fades with its ancestors; the cursor
        // layer follows only the pointer.
        float alpha = e.cursor() ? 1f : ancestorOpacity(e.root());
        if (alpha <= 0f) {
            return;
        }
        int saved = canvas.getSaveCount();
        if (alpha < 1f) {
            try (Paint p = new Paint().setAlphaf(alpha)) {
                canvas.saveLayer(null, p);
            }
        }
        UiTransform outer = e.cursor() ? null : UiCoordinates.ancestorTransform(e.root());
        if (outer != null) {
            canvas.save();
            canvas.concat(matrix(outer));
        }
        paintSubtree(ui, masonry, canvas, order, e.root(), scale);
        canvas.restoreToCount(saved);
    }

    /** Product of the ancestors' {@code opacity} (1 for the tree root). */
    static float ancestorOpacity(UiElement el) {
        float a = 1f;
        for (UiElement p = el.parent(); p != null && a > 0f; p = p.parent()) {
            a *= (float) p.computedStyle().number("opacity", 1);
        }
        return a;
    }

    // ── host providers' GL phase (C1) ───────────────────────────────────────

    /**
     * Runs {@code UiDrawProvider.prepare} for every provider-backed element that will paint in
     * the next frame of {@code ui} (already laid out): not collapsed, not faded to 0, not hidden,
     * inside its clips and the viewport. Call outside the Masonry frame, on the GL thread.
     */
    public void prepare(UiDocumentInstance ui) {
        float scale = ui.metrics().scale();
        UiRect viewport = new UiRect(0, 0, ui.metrics().viewportWidth(), ui.metrics().viewportHeight());
        PaintOrder order = ui.paintOrder();
        for (PaintOrder.Entry e : order.entries()) {
            if (e.cursor() ? !ui.pointerInside() : ancestorOpacity(e.root()) <= 0f) {
                continue;
            }
            prepareSubtree(ui, order, e.root(), viewport, scale);
        }
    }

    private void prepareSubtree(UiDocumentInstance ui, PaintOrder order, UiElement el, UiRect clip, float scale) {
        ComputedStyle s = el.computedStyle();
        if (s.collapsed() || s.number("opacity", 1) <= 0) {
            return;
        }
        UiRect bounds = el.paintBounds();
        if (!s.hidden() && overlaps(bounds, clip)) {
            String id = providerId(el);
            UiPaintHost.UiDrawProvider p = id == null ? null : host.drawProvider(id);
            UiRect r = el.rect();
            if (p != null && r.width() > 0 && r.height() > 0) {
                try {
                    p.prepare(el, r, scale);
                } catch (RuntimeException ex) {
                    reportProvider(ui, el, id, UiRuntimeDiagnostic.Code.DRAW_PROVIDER_FAILED,
                        "draw provider '" + id + "' failed to prepare: " + ex);
                }
            }
        }
        UiRect childClip = el.clipsChildren() ? intersect(clip, bounds) : clip;
        if (childClip == null) {
            return;
        }
        for (UiElement c : el.children()) {
            if (!order.isLifted(c)) {
                prepareSubtree(ui, order, c, childClip, scale);
            }
        }
    }

    private static boolean overlaps(UiRect a, UiRect b) {
        return a.width() > 0 && a.height() > 0 && a.x() < b.right() && b.x() < a.right()
            && a.y() < b.bottom() && b.y() < a.bottom();
    }

    /** Intersection, or null when empty. */
    private static UiRect intersect(UiRect a, UiRect b) {
        float x = Math.max(a.x(), b.x());
        float y = Math.max(a.y(), b.y());
        float w = Math.min(a.right(), b.right()) - x;
        float h = Math.min(a.bottom(), b.bottom()) - y;
        return w <= 0 || h <= 0 ? null : new UiRect(x, y, w, h);
    }

    /** A slot record ({@code stack} prop) flagged {@code selected}: the selected hotbar slot's ring. */
    private static boolean selectedSlot(UiElement el) {
        return el.prop("stack") instanceof UiValue.Obj o && o.get("selected") instanceof UiValue.Bool b && b.value();
    }

    private static String providerId(UiElement el) {
        if (!"ItemSlot".equals(el.type()) && !"DrawProvider".equals(el.type())) {
            return null;
        }
        return el.prop("provider") instanceof UiValue.Str str && !str.value().isEmpty() ? str.value() : null;
    }

    private void reportProvider(UiDocumentInstance ui, UiElement el, String id, UiRuntimeDiagnostic.Code code,
                                String message) {
        if (providerReports.add(code + "|" + el.key() + "|" + id)) {
            ui.reportDiagnostic(UiRuntimeDiagnostic.error(code, el.key(), message));
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
        if (el.isTransformed()) {
            canvas.save();
            canvas.concat(matrix(el.localTransform())); // scale/rotate about the centre, with the subtree
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

    /** Skia's row-major 3×3 of a 2D affine transform. */
    static Matrix33 matrix(UiTransform t) {
        return new Matrix33(t.a(), t.c(), t.tx(), t.b(), t.d(), t.ty(), 0, 0, 1);
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
        UiImage bgImage = host.image(el, assetRef(s.get("background-image")));
        if (bgImage != null) {
            image(ui, canvas, el, bgImage, r, s, scale);
        }
        String surface = s.keyword("-sb-surface", "auto");
        if (!"auto".equals(surface)) {
            surface(canvas, surface, r);
        }
        switch (el.type()) {
            case "Button" -> {
                if (!styledBackground && "auto".equals(surface)) {
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
                UiImage img = host.image(el, assetRef(el.prop("source")));
                if (img != null) {
                    image(ui, canvas, el, img, r, s, scale);
                }
            }
            case "ItemSlot" -> {
                slotFrame.bounds(r.x(), r.y(), r.width(), r.height());
                slotFrame.setHovered(el.hasState(UiElement.HOVER));
                slotFrame.hotbarSelected(selectedSlot(el));
                slotFrame.render(masonry);
                provider(ui, canvas, el, r, scale);
            }
            case "DrawProvider" -> provider(ui, canvas, el, r, scale);
            case "TextField" -> textField(canvas, el, r, s, scale, styledBackground);
            case "Canvas" -> canvasPainter.paint(canvas, ui.canvas(el.key()), r, scale, ui);
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
        if (text.usesTextLayout(el)) {
            lines(canvas, el, r, s, scale, color, baseline);
            return;
        }
        String effect = s.keyword("-sb-text-effect", "shadow");
        switch (s.keyword("text-align", "left")) {
            case "center" -> labelText(canvas, effect, str, r.x() + r.width() / 2f, baseline, font, color,
                MPainter.Align.CENTER);
            case "right" -> labelText(canvas, effect, str, r.right(), baseline, font, color, MPainter.Align.RIGHT);
            default -> labelText(canvas, effect, str, r.x(), baseline, font, color, MPainter.Align.LEFT);
        }
    }

    /** One run of label text in its {@code -sb-text-effect}: the house shadow, none, or the title stack. */
    private static void labelText(Canvas canvas, String effect, String str, float x, float baseline, Font font, int color,
                             MPainter.Align align) {
        switch (effect) {
            case "none" -> MPainter.drawTextPlain(canvas, str, x, baseline, font, color, align);
            case "title" -> MPainter.drawTitleText(canvas, str, x, baseline, font, color, align);
            default -> MPainter.drawText(canvas, str, x, baseline, font, color, align);
        }
    }

    /**
     * The {@code -sb-surface} house surfaces, at the legacy painters' radii in device px (the stone
     * screens never scaled them): what lets a document reproduce a Masonry panel or a button that
     * does not follow its pseudo-states.
     */
    private static void surface(Canvas canvas, String surface, UiRect r) {
        switch (surface) {
            case "panel" -> MPainter.panel(canvas, r.x(), r.y(), r.width(), r.height());
            case "container" -> MPainter.containerPanel(canvas, r.x(), r.y(), r.width(), r.height());
            case "hud" -> MPainter.hudFrame(canvas, r.x(), r.y(), r.width(), r.height());
            case "button", "button-hover", "button-disabled" -> {
                int fill = switch (surface) {
                    case "button-hover" -> MStyle.BUTTON_FILL_HI;
                    case "button-disabled" -> MStyle.BUTTON_FILL_DIS;
                    default -> MStyle.BUTTON_FILL;
                };
                MPainter.stoneSurface(canvas, r.x(), r.y(), r.width(), r.height(), MStyle.BUTTON_RADIUS,
                    fill, MStyle.BUTTON_BORDER, MStyle.BUTTON_HIGHLIGHT, MStyle.BUTTON_SHADOW,
                    MStyle.BUTTON_DROP_SHADOW, MStyle.BUTTON_NOISE_DARK, MStyle.BUTTON_NOISE_LIGHT);
            }
            default -> {
            }
        }
    }

    /** Wrapped, truncated or rich label text ({@code ui-text}): the measurer's cached lines. */
    private void lines(Canvas canvas, UiElement el, UiRect r, ComputedStyle s, float scale, int color,
                       float baseline) {
        TextLayout.Result layout = text.labelLayout(el, r.width(), scale);
        Font base = text.font(el, scale);
        float lineHeight = (float) Math.ceil(base.getMetrics().getDescent() - base.getMetrics().getAscent());
        String align = s.keyword("text-align", "left");
        String effect = s.keyword("-sb-text-effect", "shadow");
        int alpha = color >>> 24;
        for (int i = 0; i < layout.lineCount(); i++) {
            TextLayout.Line line = layout.lines().get(i);
            float y = baseline + i * lineHeight;
            float x = switch (align) {
                case "center" -> r.x() + (r.width() - line.width()) / 2f;
                case "right" -> r.right() - line.width();
                default -> r.x();
            };
            for (TextLayout.Run run : line.runs()) {
                TextLayout.Span st = run.style();
                // A markup colour keeps the element's alpha, so fading the label fades every run.
                int c = st.hasColor()
                    ? (((st.color() >>> 24) * alpha / 255) << 24) | (st.color() & 0xFFFFFF)
                    : color;
                Font f = text.font(el, scale, st.bold(), st.italic());
                labelText(canvas, effect, run.text(), x + run.x(), y, f, c, MPainter.Align.LEFT);
                if (st.underline() && !run.text().isBlank()) {
                    float t = Math.max(1f, f.getSize() * 0.07f);
                    MPainter.fillRect(canvas, x + run.x(), y + Math.max(1f, f.getSize() * 0.1f), run.width(), t, c);
                }
            }
        }
    }

    private void provider(UiDocumentInstance ui, Canvas canvas, UiElement el, UiRect r, float scale) {
        String id = providerId(el);
        if (id == null) {
            return; // an empty slot: nothing to draw
        }
        if (canvas.quickReject(Rect.makeXYWH(r.x(), r.y(), r.width(), r.height()))) {
            return; // clipped away: prepare skipped it too, so never draw a stale texture
        }
        UiPaintHost.UiDrawProvider p = host.drawProvider(id);
        if (p == null) {
            reportProvider(ui, el, id, UiRuntimeDiagnostic.Code.MISSING_DRAW_PROVIDER,
                "no draw provider '" + id + "' is registered with this host");
            placeholder(canvas, r, scale);
            return;
        }
        int saved = canvas.save();
        try {
            p.draw(canvas, el, r, scale);
        } catch (RuntimeException ex) {
            canvas.restoreToCount(saved);
            reportProvider(ui, el, id, UiRuntimeDiagnostic.Code.DRAW_PROVIDER_FAILED,
                "draw provider '" + id + "' failed: " + ex);
            placeholder(canvas, r, scale);
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    /** Loud stand-in for a provider that is missing or broken: never a silently empty element. */
    private static void placeholder(Canvas canvas, UiRect r, float scale) {
        float inset = Math.min(2f * scale, Math.min(r.width(), r.height()) / 4f);
        MPainter.fillRect(canvas, r.x() + inset, r.y() + inset, r.width() - 2 * inset, r.height() - 2 * inset,
            PLACEHOLDER_FILL);
        MPainter.strokeRect(canvas, r.x() + inset, r.y() + inset, r.width() - 2 * inset, r.height() - 2 * inset,
            PLACEHOLDER_EDGE, Math.max(1f, scale));
        float size = Math.min(r.width(), r.height()) * 0.5f;
        if (size > 4) {
            MSymbol.WARNING.draw(canvas, r.x() + (r.width() - size) / 2f, r.y() + (r.height() - size) / 2f, size, size,
                PLACEHOLDER_EDGE);
        }
    }

    /** A texture, sprite region or skin (#294); animated sprites schedule their next repaint. */
    private static void image(UiDocumentInstance ui, Canvas canvas, UiElement el, UiImage img, UiRect r,
                              ComputedStyle s, float scale) {
        UiImage.Region region = img.region(el);
        if (region == null) {
            return;
        }
        double next = SpritePainter.draw(canvas, el, region, r, s, scale, ui.clock(), ui.preferences().reducedMotion());
        // The area on screen: transformed with the element and its ancestors, not the layout rect.
        ui.noteAnimation(el.paintBounds(), next);
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

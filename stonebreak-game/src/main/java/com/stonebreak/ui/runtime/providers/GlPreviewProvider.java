package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.ui.rendering.GlStateSnapshot;
import com.openmason.engine.ui.rendering.GlTextureImages;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiPaintHost;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.types.Rect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_COLOR_CLEAR_VALUE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glColorMask;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGetFloatv;
import static org.lwjgl.opengl.GL11.glViewport;

/**
 * Base for providers that render live GL content (3D model previews) into a document element:
 * every frame {@link #prepare} renders into an offscreen target owned per element, and
 * {@link #draw} paints that texture through the document's canvas. This replaces the legacy
 * "raw GL after the Skija flush" previews (ledger Hard visual #1), so documents can draw frames,
 * labels and tooltips over a preview and it fades, clips and transforms with its element.
 *
 * <p>The target is cleared to transparent with a fresh depth buffer before
 * {@link #render}; the whole prepare runs inside a {@link GlStateSnapshot}, so subclasses may
 * change any GL state. Targets of elements that stop being prepared (hidden, removed, document
 * closed) are freed after {@link #EVICT_AFTER_NANOS}; {@link #close} frees all. GL thread only.
 */
public abstract class GlPreviewProvider implements UiPaintHost.UiDrawProvider, AutoCloseable {

    /** An element's target survives this long without a prepare (a frame or two of hiding). */
    static final long EVICT_AFTER_NANOS = 2_000_000_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(GlPreviewProvider.class);

    private static final class Slot {
        final GlRenderTarget target = new GlRenderTarget();
        long lastPrepared;
        boolean rendered;
    }

    private final Map<String, Slot> slots = new HashMap<>();
    private final Paint paint = new Paint();
    private final LongSupplier clock;
    private boolean failed;

    protected GlPreviewProvider() {
        this(System::nanoTime);
    }

    protected GlPreviewProvider(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Renders the preview into the bound target: viewport {@code (0, 0, width, height)} is set,
     * colour is cleared to transparent and depth to 1, depth test is enabled.
     *
     * @return false when there was nothing to show (the element then paints nothing)
     */
    protected abstract boolean render(UiElement element, int width, int height);

    @Override
    public final void prepare(UiElement element, UiRect rect, float scale) {
        if (failed) {
            return;
        }
        long now = clock.getAsLong();
        evict(now);
        int w = Math.round(rect.width());
        int h = Math.round(rect.height());
        if (w <= 0 || h <= 0) {
            return;
        }
        Slot slot = slots.computeIfAbsent(element.key(), k -> new Slot());
        slot.lastPrepared = now;
        GlStateSnapshot saved = GlStateSnapshot.capture();
        float[] clear = new float[4];
        glGetFloatv(GL_COLOR_CLEAR_VALUE, clear);
        try {
            slot.target.ensure(w, h);
            glViewport(0, 0, w, h);
            glDisable(GL_SCISSOR_TEST);
            glColorMask(true, true, true, true);
            glDepthMask(true);
            glClearColor(0f, 0f, 0f, 0f);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glEnable(GL_DEPTH_TEST);
            slot.rendered = render(element, w, h);
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("[ui] {} preview failed; disabled for this session", getClass().getSimpleName(), e);
        } finally {
            glClearColor(clear[0], clear[1], clear[2], clear[3]);
            saved.restore();
        }
    }

    @Override
    public final void draw(Canvas canvas, UiElement element, UiRect rect, float scale) {
        Slot slot = slots.get(element.key());
        if (slot == null || !slot.rendered || !slot.target.allocated()) {
            return;
        }
        GlRenderTarget t = slot.target;
        Image image = GlTextureImages.borrow(canvas, t.texture(), t.width(), t.height(), true);
        if (image == null) {
            return;
        }
        Rect dst = Rect.makeXYWH(Math.round(rect.x()), Math.round(rect.y()), t.width(), t.height());
        paint.reset();
        canvas.drawImageRect(image, Rect.makeWH(t.width(), t.height()), dst, SamplingMode.DEFAULT, paint, true);
    }

    /** Live per-element targets (tests and diagnostics). */
    public int liveTargets() {
        return slots.size();
    }

    private void evict(long now) {
        for (Iterator<Slot> it = slots.values().iterator(); it.hasNext(); ) {
            Slot s = it.next();
            if (now - s.lastPrepared > EVICT_AFTER_NANOS) {
                s.target.close();
                it.remove();
            }
        }
    }

    @Override
    public void close() {
        for (Slot s : slots.values()) {
            s.target.close();
        }
        slots.clear();
        paint.close();
    }
}

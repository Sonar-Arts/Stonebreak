package com.openmason.engine.ui.rendering;

import com.openmason.engine.ui.masonry.MButton;
import com.openmason.engine.ui.masonry.MClipboard;
import com.openmason.engine.ui.masonry.MasonryEnvironment;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.masonry.textures.MTextureCache;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** GL-free parts of the #286 render contract: targets, raster path, services, ownership. */
class MasonryRenderingContractTest {

    private static final Typeface FONT = loadFont();

    @AfterEach
    void resetEnvironment() {
        MasonryEnvironment.reset();
    }

    @Test
    void targetsValidateAndDescribeTheirPolicy() {
        UiRenderTarget game = UiRenderTarget.gameWindow(800, 600, 2f);
        assertEquals(0, game.framebufferId());
        assertEquals(UiRenderTarget.Origin.BOTTOM_LEFT, game.origin());
        assertEquals(GlStatePolicy.RESET_TO_BASELINE, game.policy());

        UiRenderTarget preview = UiRenderTarget.offscreen(7, 64, 32, Float.NaN);
        assertEquals(UiRenderTarget.Origin.TOP_LEFT, preview.origin());
        assertEquals(GlStatePolicy.RESTORE, preview.policy());
        assertEquals(1f, preview.pixelRatio(), "a bad pixel ratio falls back to 1");
        assertTrue(preview.sameSurface(preview.withSize(64, 32, 3f)), "the ratio alone never rebuilds a surface");
        assertFalse(preview.sameSurface(preview.withSize(65, 32, 1f)));

        assertThrows(IllegalArgumentException.class, () -> UiRenderTarget.offscreen(1, 0, 10, 1f));
        assertThrows(IllegalArgumentException.class, () -> UiRenderTarget.offscreen(-1, 10, 10, 1f));
    }

    @Test
    void framebufferAllocationRoundsUpAndSelectsTheLogicalArea() {
        assertEquals(32, OffscreenFramebuffer.roundUp(1));
        assertEquals(32, OffscreenFramebuffer.roundUp(32));
        assertEquals(64, OffscreenFramebuffer.roundUp(33));
    }

    @Test
    void pointerMappingInvertsTheDisplayTransform() {
        PreviewMapping m = new PreviewMapping(100, 40, 1.5f);
        assertEquals(20f, m.canvasX(130f), 1e-6);
        assertEquals(10f, m.canvasY(55f), 1e-6, "no Y inversion: the shown image is top-down");
        assertEquals(130f, m.screenX(m.canvasX(130f)), 1e-4);
        assertThrows(IllegalArgumentException.class, () -> new PreviewMapping(0, 0, 0));
    }

    @Test
    void rasterPixelsAreTopDownPremultipliedWhateverTheByteOrder() {
        RasterMasonryBackend raster = new RasterMasonryBackend(8, 8, FONT, false);
        try (Paint p = new Paint()) {
            raster.getCanvas().clear(0);
            p.setColor(0xFFFF0000);
            raster.getCanvas().drawRect(Rect.makeXYWH(0, 0, 8, 1), p);
            p.setColor(0x80FFFFFF);
            raster.getCanvas().drawRect(Rect.makeXYWH(0, 4, 8, 1), p);
        }
        int[] px = raster.premultipliedRgba();
        assertEquals(0xFF0000FF, px[0], "row 0 is the top, red first");
        assertEquals(0, px[7 * 8]);
        int half = px[4 * 8];
        for (int shift = 0; shift < 32; shift += 8) {
            assertTrue(Math.abs(((half >>> shift) & 0xFF) - 0x80) <= 1, Integer.toHexString(half));
        }
        assertEquals(0xFFFF0000, raster.colorAt(3, 0), "colorAt is unpremultiplied ARGB");
        assertTrue(raster.rowBytes() >= 32);
        raster.dispose();
    }

    @Test
    void rasterFramesNestAndResizeOnlyAtTheOuterFrame() {
        RasterMasonryBackend raster = new RasterMasonryBackend(FONT, false);
        assertFalse(raster.isAvailable());
        raster.beginFrame(40, 20, 1f);
        raster.beginFrame(99, 99, 1f);
        assertEquals(40, raster.width(), "a nested frame never reallocates under the outer one");
        raster.endFrame();
        raster.endFrame();
        raster.beginFrame(60, 30, 1f);
        assertEquals(60, raster.width());
        raster.endFrame();
        raster.dispose();
    }

    @Test
    void anUninitializedGpuBackendIgnoresFramesInsteadOfTouchingGl() {
        GpuMasonryBackend gpu = new GpuMasonryBackend();
        assertFalse(gpu.isAvailable());
        gpu.beginFrame(100, 100, 1f);
        gpu.endFrame();
        assertThrows(IllegalStateException.class, gpu::getCanvas);
        assertFalse(new MasonryUI(gpu).beginFrame(100, 100, 1f), "MasonryUI skips frames it cannot draw");
        gpu.dispose();
    }

    @Test
    void closingOneScreenLeavesTheSharedTypefaceAndOtherScreensWorking() {
        RasterMasonryBackend raster = new RasterMasonryBackend(160, 60, FONT, false);
        MasonryUI first = new MasonryUI(raster);
        MasonryUI second = new MasonryUI(raster);
        new MButton("One").bounds(4, 4, 120, 40).render(first);
        first.dispose();

        raster.getCanvas().clear(0);
        new MButton("Two").bounds(4, 4, 120, 40).render(second);
        int painted = 0;
        for (int v : raster.premultipliedRgba()) {
            if (v != 0) painted++;
        }
        assertTrue(painted > 2000, "the surviving screen still draws its body and text");
        assertNotNull(raster.typeface().getFamilyName(), "the shared typeface is still open");
        second.dispose();
        raster.dispose();
    }

    @Test
    void theTextureCacheOwnsItsTexturesAndRemembersFailures() {
        MTextureCache cache = new MTextureCache();
        AtomicInteger loads = new AtomicInteger();
        assertNull(cache.get("missing", k -> {
            loads.incrementAndGet();
            return null;
        }));
        for (int frame = 0; frame < 50; frame++) {
            assertNull(cache.get("missing", k -> {
                loads.incrementAndGet();
                return null;
            }));
        }
        assertEquals(1, loads.get(), "a failure is not retried per frame");

        assertNull(cache.forget("missing"), "forgetting a failure returns nothing to close");
        assertNull(cache.get("missing", k -> MTexture.loadFromOmtBytes(k, new byte[0])));
        assertEquals(0, cache.size());
        cache.disposeAll();
    }

    @Test
    void hostServicesDefaultToNeutralAndCanBeInstalled() {
        assertEquals(1f, MasonryEnvironment.uiScale());
        MClipboard.write("local");
        assertEquals("local", MClipboard.read(), "headless default is a process-local clipboard");

        MasonryEnvironment.installUiScale(() -> 1.5f);
        StringBuilder system = new StringBuilder();
        MasonryEnvironment.installClipboard(new MasonryEnvironment.ClipboardProvider() {
            @Override
            public String read() {
                throw new IllegalStateException("window gone");
            }

            @Override
            public void write(String text) {
                system.append(text);
            }
        });
        assertEquals(1.5f, MasonryEnvironment.uiScale());
        MClipboard.write("x");
        assertEquals("x", system.toString());
        assertEquals("", MClipboard.read(), "a failing host clipboard reads as empty, never throws");
    }

    private static Typeface loadFont() {
        try (InputStream in = MasonryRenderingContractTest.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
            return FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

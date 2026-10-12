package com.stonebreak.rendering.UI.backend.skija;

import com.openmason.engine.ui.masonry.MButton;
import com.openmason.engine.ui.masonry.MDropdown;
import com.openmason.engine.ui.masonry.MPainter;
import com.openmason.engine.ui.masonry.MToggle;
import com.openmason.engine.ui.masonry.MVitalBar;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.rendering.GlStateSnapshot;
import com.openmason.engine.ui.rendering.GlTextureImages;
import com.openmason.engine.ui.rendering.GpuMasonryBackend;
import com.openmason.engine.ui.rendering.MasonryBackend;
import com.openmason.engine.ui.rendering.OffscreenFramebuffer;
import com.openmason.engine.ui.rendering.RasterMasonryBackend;
import com.openmason.engine.ui.rendering.RasterTextureUpload;
import com.openmason.engine.ui.rendering.UiRenderTarget;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * #286 real-GL checks of the shared Masonry renderer: one scene drawn by the same widget code
 * into the game window, an offscreen preview framebuffer and the CPU raster reference path;
 * framebuffer orientation, premultiplied alpha, clip and overlay order; GL state around a
 * paint for both state policies; preview resize/close without leaks; GL context recreation.
 * Supersedes the #283 {@code RenderTargetSpikeTest}.
 *
 * <p>Needs a display: {@code -Dstonebreak.ui.gl=true}.
 */
@Tag("integration")
class MasonryRenderTargetGlTest {

    private static final int W = 256;
    private static final int H = 160;
    private static final int WIN_W = 320;
    private static final int WIN_H = 200;
    private static final int BG = 0xFF203040;

    private static long window;
    private static Typeface typeface;

    @BeforeAll
    static void openContext() throws Exception {
        assumeTrue(Boolean.getBoolean("stonebreak.ui.gl"), "needs a display: -Dstonebreak.ui.gl=true");
        window = createWindow();
        try (InputStream in = MasonryRenderTargetGlTest.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
            typeface = FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        }
    }

    @AfterAll
    static void closeContext() {
        if (window != NULL) {
            MTextureRegistry.disposeAll();
            GL.setCapabilities(null);
            glfwDestroyWindow(window);
            glfwTerminate();
            window = NULL;
        }
    }

    // ───────────────────────────── parity ─────────────────────────────

    @Test
    void gameWindowAndPreviewFramebufferRenderTheSamePixels() {
        int[] game = renderGameWindow();
        int[] preview = renderPreview(W, H);
        assertEquals(0, countDiff(game, preview, 0),
                "the game window and an offscreen preview share one drawing path: " + describeDiff(game, preview));
    }

    @Test
    void rasterReferenceMatchesTheGpuUpToAntiAliasing() {
        int[] gpu = renderPreview(W, H);
        RasterMasonryBackend raster = new RasterMasonryBackend(W, H, typeface, false);
        try {
            paintFrame(raster, W, H);
            int[] cpu = raster.premultipliedRgba();
            int coarse = countDiff(gpu, cpu, 24);
            System.out.println("[#286] raster vs GPU over " + W * H + " px: " + describeDiff(gpu, cpu)
                    + "; " + coarse + " differ by >24 levels");
            // Flat fills, stone surfaces and the texture agree; GPU vs CPU coverage differs on
            // anti-aliased edges and glyphs only.
            assertTrue(coarse <= W * H / 200, "more than 0.5% of pixels differ by >24 levels: " + describeDiff(gpu, cpu));
            assertEquals(gpu[(H - 20) * W + 20], cpu[(H - 20) * W + 20], "an untouched backdrop pixel agrees exactly");
        } finally {
            raster.dispose();
        }
    }

    @Test
    void theRasterUploadPathPresentsExactlyTheRasterPixels() {
        RasterMasonryBackend raster = new RasterMasonryBackend(W, H, typeface, false);
        RasterTextureUpload upload = new RasterTextureUpload();
        int callerTexture = glGenTextures();
        int fbo = glGenFramebuffers();
        try {
            paintFrame(raster, W, H);
            glBindTexture(GL_TEXTURE_2D, callerTexture);
            upload.upload(raster);
            assertEquals(callerTexture, glGetInteger(GL_TEXTURE_BINDING_2D), "the caller's binding survives");
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, upload.textureId(), 0);
            int[] shown = readFramebuffer(fbo, 0, 0, W, H);
            assertArrayEquals(raster.premultipliedRgba(), shown, "rows top-down, byte order honoured");
            assertEquals(1f, upload.uvMaxX(), "W is a whole number of allocation steps");
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glBindTexture(GL_TEXTURE_2D, 0);
            glDeleteFramebuffers(fbo);
            glDeleteTextures(callerTexture);
            upload.close();
            raster.dispose();
        }
    }

    // ─────────────────────── orientation, alpha, clip, overlays ───────────────────────

    @Test
    void previewRowsAreTopDownAndPremultiplied() {
        int[] px = paintPreview(64, 64, (ui, c) -> {
            c.clear(0x00000000);
            try (Paint p = new Paint()) {
                p.setColor(0xFFFF0000);
                c.drawRect(Rect.makeXYWH(0, 0, 64, 8), p);
                p.setColor(0x80FFFFFF);
                c.drawRect(Rect.makeXYWH(0, 32, 64, 8), p);
            }
        });
        assertEquals(0xFF0000FF, px[2 * 64 + 10], "row 0 of the texture is the canvas top");
        assertEquals(0x00000000, px[60 * 64 + 10], "the bottom stays transparent");
        int half = px[36 * 64 + 10];
        for (int shift = 0; shift < 32; shift += 8) {
            int channel = (half >>> shift) & 0xFF;
            assertTrue(Math.abs(channel - 0x80) <= 1, "50% white is stored premultiplied: " + Integer.toHexString(half));
        }
    }

    @Test
    void clipsHoldAndOverlaysPaintLast() {
        int[] px = paintPreview(64, 64, (ui, c) -> {
            c.clear(0x00000000);
            c.save();
            c.clipRect(Rect.makeXYWH(0, 0, 32, 32));
            fill(c, 0, 0, 64, 64, 0xFF00FF00);
            c.restore();
            ui.pushOverlay(() -> fill(c, 40, 40, 16, 16, 0xFF0000FF));
            fill(c, 36, 36, 24, 24, 0xFFFF0000);
            ui.renderOverlays();
        });
        assertEquals(0x00FF00FF, px[10 * 64 + 10], "inside the clip");
        assertEquals(0x00000000, px[10 * 64 + 50] & 0xFF, "outside the clip nothing painted");
        assertEquals(0x0000FFFF, px[48 * 64 + 48], "the overlay lands on top of a later widget");
        assertEquals(0xFF0000FF, px[37 * 64 + 37], "and the widget remains outside the overlay");
    }

    // ───────────────────────────── GL state ─────────────────────────────

    @Test
    void restorePolicyGivesTheCallerBackEveryBinding() {
        OffscreenFramebuffer callerTarget = new OffscreenFramebuffer();
        callerTarget.ensureSize(64, 64);
        int sampler = glGenSamplers();
        int callerTexture = glGenTextures();
        int program = trivialProgram();
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, callerTarget.framebufferId());
            glUseProgram(program);
            glViewport(3, 4, 50, 60);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, callerTexture);
            glBindSampler(1, sampler);
            glActiveTexture(GL_TEXTURE3);
            glEnable(GL_SCISSOR_TEST);
            glScissor(1, 2, 30, 40);
            glEnable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glBlendFuncSeparate(GL_ONE, GL_ZERO, GL_ONE, GL_ZERO);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            GlStateSnapshot before = GlStateSnapshot.capture();

            renderPreview(W, H);

            GlStateSnapshot after = GlStateSnapshot.capture();
            assertTrue(before.sameAs(after), "state changed: " + before.firstDifference(after));
            assertEquals(callerTarget.framebufferId(), after.drawFramebuffer(), "never framebuffer 0");
        } finally {
            resetCallerState();
            glDeleteProgram(program);
            glDeleteSamplers(sampler);
            glDeleteTextures(callerTexture);
            callerTarget.close();
        }
    }

    @Test
    void gameWindowPolicyResetsToTheBaselineTheGameExpects() {
        int sampler = glGenSamplers();
        try {
            glActiveTexture(GL_TEXTURE1);
            glBindSampler(1, sampler);
            glEnable(GL_DEPTH_TEST);
            renderGameWindow();
            assertEquals(0, glGetInteger(GL_FRAMEBUFFER_BINDING));
            assertEquals(GL_TEXTURE0, glGetInteger(GL_ACTIVE_TEXTURE));
            glActiveTexture(GL_TEXTURE1);
            assertEquals(0, glGetInteger(GL_SAMPLER_BINDING), "the terrain-smear sampler fix holds");
            assertFalse(glIsEnabled(GL_DEPTH_TEST));
            assertTrue(glIsEnabled(GL_BLEND));
        } finally {
            glDeleteSamplers(sampler);
            resetCallerState();
        }
    }

    @Test
    void restoreCoversElementBindingsAndTheRestOfTheConfiguration() {
        int vao = glGenVertexArrays();
        int[] ebo = {glGenBuffers(), glGenBuffers()};
        try {
            glBindVertexArray(vao);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo[0]);
            glBlendColor(0.1f, 0.2f, 0.3f, 0.4f);
            glClearColor(0.5f, 0.25f, 0.125f, 1f);
            glPolygonOffset(2f, 3f);
            glPixelStorei(GL_PACK_ROW_LENGTH, 7);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 2);
            glStencilFuncSeparate(GL_BACK, GL_LESS, 3, 0x0F);
            glDisable(GL_DITHER);
            GlStateSnapshot before = GlStateSnapshot.capture();

            renderPreview(W, H);
            GlStateSnapshot afterPaint = GlStateSnapshot.capture();
            assertTrue(before.sameAs(afterPaint), "paint changed state: " + before.firstDifference(afterPaint));
            // An element bind while the caller's VAO is still current (before Skia switches to its
            // own) lands in that VAO; restore must put the caller's buffer back into it.
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo[1]);
            before.restore();

            assertEquals(vao, glGetInteger(GL_VERTEX_ARRAY_BINDING));
            assertEquals(ebo[0], glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING), "the VAO's element buffer is back");
            GlStateSnapshot after = GlStateSnapshot.capture();
            assertTrue(before.sameAs(after), "state changed: " + before.firstDifference(after));
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            glBindVertexArray(0);
            glDeleteVertexArrays(vao);
            glDeleteBuffers(ebo[0]);
            glDeleteBuffers(ebo[1]);
            glBlendColor(0, 0, 0, 0);
            glClearColor(0, 0, 0, 0);
            glPolygonOffset(0, 0);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glStencilFuncSeparate(GL_BACK, GL_ALWAYS, 0, 0xFF);
            glEnable(GL_DITHER);
            resetCallerState();
        }
    }

    @Test
    void aBoundUnpackBufferNeverReachesUiTextureAllocation() {
        int pbo = glGenBuffers();
        RasterMasonryBackend raster = new RasterMasonryBackend(W, H, typeface, false);
        RasterTextureUpload upload = new RasterTextureUpload();
        try {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
            glBufferData(GL_PIXEL_UNPACK_BUFFER, 16, GL_STREAM_DRAW); // far too small for any texture
            int[] gpu = renderPreview(W, H);
            assertEquals(GL_NO_ERROR, glGetError(), "offscreen allocation with a caller PBO bound");
            assertEquals(BG >>> 24 & 0xFF, gpu[0] & 0xFF, "and the frame is real (opaque background)");

            raster.beginFrame(W, H, 1f);
            raster.getCanvas().clear(BG);
            raster.endFrame();
            upload.upload(raster);
            assertEquals(GL_NO_ERROR, glGetError(), "raster upload allocation with a caller PBO bound");
            assertEquals(pbo, glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING), "the caller's PBO is back");
        } finally {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            glDeleteBuffers(pbo);
            upload.close();
            raster.dispose();
            resetCallerState();
        }
    }

    @Test
    void aWarmContextPaintsFrameAfterFrameIdenticallyAndRestoresEachTime() {
        GpuMasonryBackend backend = previewBackend();
        OffscreenFramebuffer fbo = new OffscreenFramebuffer();
        try {
            GlStateSnapshot before = GlStateSnapshot.capture();
            int[] first = paintScene(backend, fbo, W, H);
            for (int frame = 0; frame < 5; frame++) {
                int[] again = paintScene(backend, fbo, W, H);
                assertEquals(0, countDiff(first, again, 0), "frame " + frame + ": " + describeDiff(first, again));
                GlStateSnapshot after = GlStateSnapshot.capture();
                assertTrue(before.sameAs(after), "frame " + frame + ": " + before.firstDifference(after));
            }
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            backend.releaseTargetSurface();
            fbo.close();
            backend.dispose();
            resetCallerState();
        }
    }

    // ───────────────────────── host GL textures (#282 C1b) ─────────────────────────

    @Test
    void aHostGlTextureDrawsThroughTheDocumentCanvasOnTheGpu() {
        int tex = stripedTexture(); // first uploaded rows red, last rows green
        try {
            int[] px = paintPreview(64, 64, (ui, canvas) -> {
                canvas.clear(0xFF000000);
                assertTrue(GlTextureImages.inGpuFrame());
                io.github.humbleui.skija.Image topDown = GlTextureImages.borrow(canvas, tex, 8, 8, false);
                assertSame(topDown, GlTextureImages.borrow(canvas, tex, 8, 8, false), "one wrap per frame");
                io.github.humbleui.skija.Image flipped = GlTextureImages.borrow(canvas, tex, 8, 8, true);
                canvas.drawImageRect(topDown, Rect.makeXYWH(8, 8, 8, 8));
                canvas.drawImageRect(flipped, Rect.makeXYWH(32, 8, 8, 8));
            });
            assertFalse(GlTextureImages.inGpuFrame(), "the frame ended");
            assertEquals(0xFF0000FF, px[9 * 64 + 9], "texture row 0 at the top");
            assertEquals(0x00FF00FF, px[15 * 64 + 9]);
            assertEquals(0x00FF00FF, px[9 * 64 + 33], "bottom-left origin flips it");
            assertEquals(0xFF0000FF, px[15 * 64 + 33]);
            assertTrue(glIsTexture(tex), "the texture stays the caller's");
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            glDeleteTextures(tex);
            resetCallerState();
        }
    }

    @Test
    void aHostGlTextureReadsBackOntoTheRasterPath() {
        int tex = stripedTexture();
        RasterMasonryBackend raster = new RasterMasonryBackend(64, 64, typeface, false);
        try {
            raster.beginFrame(64, 64, 1f);
            Canvas canvas = raster.getCanvas();
            canvas.clear(0xFF000000);
            io.github.humbleui.skija.Image img = GlTextureImages.borrow(canvas, tex, 8, 8, false);
            assertNotNull(img, "read back without a GPU frame");
            canvas.drawImageRect(img, Rect.makeXYWH(8, 8, 8, 8));
            raster.endFrame();
            assertEquals(0xFFFF0000, raster.colorAt(9, 9));
            assertEquals(0xFF00FF00, raster.colorAt(9, 15));
            assertTrue(img.isClosed(), "closed when the raster frame ended");
        } finally {
            raster.dispose();
            glDeleteTextures(tex);
            resetCallerState();
        }
    }

    /** 8x8 RGBA8: rows 0-3 (first uploaded) opaque red, rows 4-7 opaque green. */
    private static int stripedTexture() {
        ByteBuffer data = BufferUtils.createByteBuffer(8 * 8 * 4);
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                data.put((byte) (row < 4 ? 255 : 0)).put((byte) (row < 4 ? 0 : 255)).put((byte) 0).put((byte) 255);
            }
        }
        data.flip();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 8, 8, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
        glBindTexture(GL_TEXTURE_2D, 0);
        return tex;
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    @Test
    void resizingAndClosingPreviewsReleasesTheirGlObjects() {
        GpuMasonryBackend backend = previewBackend();
        OffscreenFramebuffer fbo = new OffscreenFramebuffer();
        List<int[]> names = new ArrayList<>();
        MasonryUI ui = new MasonryUI(backend);
        try {
            int[][] sizes = {{100, 80}, {101, 81}, {300, 200}, {40, 400}, {300, 200}, {64, 64}, {500, 33}};
            for (int round = 0; round < 3; round++) {
                for (int[] s : sizes) {
                    if (fbo.ensureSize(s[0], s[1])) {
                        backend.releaseTargetSurface();
                        names.add(new int[] {fbo.framebufferId(), fbo.textureId()});
                    }
                    backend.setTarget(fbo.target(1f));
                    backend.beginFrame();
                    try {
                        backend.getCanvas().clear(BG);
                        new MButton("Resize").bounds(4, 4, 90, 28).render(ui);
                    } finally {
                        backend.endFrame();
                    }
                    assertEquals(GL_NO_ERROR, glGetError(), "size " + s[0] + "x" + s[1]);
                }
            }
        } finally {
            ui.dispose();
            backend.releaseTargetSurface();
            fbo.close();
            backend.dispose();
        }
        for (int[] n : names) {
            assertFalse(glIsFramebuffer(n[0]), "framebuffer " + n[0] + " leaked");
            assertFalse(glIsTexture(n[1]), "texture " + n[1] + " leaked");
        }
        assertEquals(GL_NO_ERROR, glGetError());
    }

    @Test
    void closingOneScreenNeverInvalidatesAnother() {
        GpuMasonryBackend backend = previewBackend();
        OffscreenFramebuffer fbo = new OffscreenFramebuffer();
        fbo.ensureSize(W, H);
        backend.setTarget(fbo.target(1f));
        MasonryUI first = new MasonryUI(backend);
        MasonryUI second = new MasonryUI(backend);
        try {
            drawLabel(backend, first);
            first.dispose();
            int[] px = drawLabel(backend, second);
            int painted = 0;
            for (int v : px) {
                if (v != 0) painted++;
            }
            assertTrue(painted > 100, "text still renders through the shared typeface");
            MTexture heart = MTextureRegistry.get("/ui/shared/stonebreak/ui/hud/heart_full.sbt");
            assertNotNull(heart.image(), "shared textures belong to the cache, not to a screen");
        } finally {
            second.dispose();
            backend.releaseTargetSurface();
            fbo.close();
            backend.dispose();
        }
    }

    @Test
    void aRecreatedGlContextRendersTheSameFrame() {
        long previous = glfwGetCurrentContext();
        long first = createWindow();
        GpuMasonryBackend backend = previewBackend();
        OffscreenFramebuffer fbo = new OffscreenFramebuffer();
        int[] before = paintScene(backend, fbo, W, H);
        fbo.close();
        backend.abandonContext();                 // the context is about to disappear
        destroyWindow(first);

        long second = createWindow();
        try {
            backend.initialize(UiRenderTarget.offscreen(0, W, H, 1f));
            OffscreenFramebuffer again = new OffscreenFramebuffer();
            int[] after = paintScene(backend, again, W, H);
            assertEquals(0, countDiff(before, after, 0), describeDiff(before, after));
            backend.releaseTargetSurface();
            again.close();
            backend.dispose();
        } finally {
            destroyWindow(second);
            glfwMakeContextCurrent(previous);
            GL.createCapabilities();
        }
    }

    // ───────────────────────────── scene ─────────────────────────────

    /** The authored fixture: widgets, an SBT texture at integer scale, a clip, an overlay. */
    private static void paintScene(MasonryUI ui, Canvas c) {
        c.clear(BG);
        new MButton("Resume Game").bounds(10, 10, 170, 40).render(ui);
        new MToggle("Wireframes", true).bounds(10, 58, 160, 24).render(ui);
        new MVitalBar().label("HP").value(70).max(100).fillColor(0xFF3366FF).bounds(10, 90, 160, 20).render(ui);
        MDropdown dropdown = new MDropdown("Quality", new String[] {"Low", "High"}).itemHeight(20)
                .bounds(186, 10, 64, 24);
        dropdown.open();
        dropdown.render(ui);
        MTexture heart = MTextureRegistry.get("/ui/shared/stonebreak/ui/hud/heart_full.sbt");
        c.save();
        c.clipRect(Rect.makeXYWH(186, 90, 40, 40));
        MPainter.drawImage(c, heart.image(), 186, 90, heart.width() * 2f, heart.height() * 2f);
        c.restore();
        ui.renderOverlays();
    }

    private static void paintFrame(MasonryBackend backend, int w, int h) {
        MasonryUI ui = new MasonryUI(backend);
        try {
            assertTrue(ui.beginFrame(w, h, 1f));
            try {
                paintScene(ui, backend.getCanvas());
            } finally {
                ui.endFrame();
            }
        } finally {
            ui.dispose();
        }
    }

    private static int[] renderGameWindow() {
        SkijaUIBackend game = new SkijaUIBackend() {
            @Override
            public Typeface getMinecraftTypeface() {
                return typeface;
            }
        };
        game.initialize(UiRenderTarget.gameWindow(WIN_W, WIN_H, 1f));
        try {
            paintFrame(game, WIN_W, WIN_H);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            return readTopDown(0, WIN_W, WIN_H, true, W, H);
        } finally {
            game.dispose();
        }
    }

    private static int[] renderPreview(int w, int h) {
        GpuMasonryBackend backend = previewBackend();
        OffscreenFramebuffer fbo = new OffscreenFramebuffer();
        try {
            return paintScene(backend, fbo, w, h);
        } finally {
            backend.releaseTargetSurface();
            fbo.close();
            backend.dispose();
        }
    }

    private static int[] paintScene(GpuMasonryBackend backend, OffscreenFramebuffer fbo, int w, int h) {
        fbo.ensureSize(w, h);
        backend.setTarget(fbo.target(1f));
        paintFrame(backend, fbo.allocatedWidth(), fbo.allocatedHeight());
        return readTexture(fbo, w, h);
    }

    private interface Painter {
        void paint(MasonryUI ui, Canvas canvas);
    }

    private static int[] paintPreview(int w, int h, Painter painter) {
        GpuMasonryBackend backend = previewBackend();
        OffscreenFramebuffer fbo = new OffscreenFramebuffer();
        MasonryUI ui = new MasonryUI(backend);
        try {
            fbo.ensureSize(w, h);
            backend.setTarget(fbo.target(1f));
            ui.beginFrame(fbo.allocatedWidth(), fbo.allocatedHeight(), 1f);
            try {
                painter.paint(ui, backend.getCanvas());
            } finally {
                ui.endFrame();
            }
            return readTexture(fbo, w, h);
        } finally {
            ui.dispose();
            backend.releaseTargetSurface();
            fbo.close();
            backend.dispose();
        }
    }

    private static int[] drawLabel(GpuMasonryBackend backend, MasonryUI ui) {
        ui.beginFrame(W, H, 1f);
        try {
            backend.getCanvas().clear(0);
            new MButton("Shared font").bounds(10, 10, 170, 40).render(ui);
        } finally {
            ui.endFrame();
        }
        return readFramebuffer(backend.target().framebufferId(), 0, 0, 180, 50);
    }

    private static GpuMasonryBackend previewBackend() {
        GpuMasonryBackend backend = new GpuMasonryBackend() {
            @Override
            public Typeface typeface() {
                return typeface;
            }
        };
        backend.initialize(UiRenderTarget.offscreen(0, 1, 1, 1f));
        return backend;
    }

    // ───────────────────────────── GL helpers ─────────────────────────────

    private static int[] readTexture(OffscreenFramebuffer fbo, int w, int h) {
        return readFramebuffer(fbo.framebufferId(), 0, 0, w, h);
    }

    /** {@code w x h} at GL (x, y), as 0xRRGGBBAA in GL row order. */
    private static int[] readFramebuffer(int framebuffer, int x, int y, int w, int h) {
        int previous = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, previous);
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            out[i] = (buf.get(i * 4) & 0xFF) << 24 | (buf.get(i * 4 + 1) & 0xFF) << 16
                    | (buf.get(i * 4 + 2) & 0xFF) << 8 | (buf.get(i * 4 + 3) & 0xFF);
        }
        return out;
    }

    /** Top-left {@code w x h} of a bottom-left-origin framebuffer, rows flipped to top-down. */
    private static int[] readTopDown(int framebuffer, int fbW, int fbH, boolean bottomLeft, int w, int h) {
        int[] raw = readFramebuffer(framebuffer, 0, bottomLeft ? fbH - h : 0, w, h);
        if (!bottomLeft) {
            return raw;
        }
        int[] out = new int[w * h];
        for (int row = 0; row < h; row++) {
            System.arraycopy(raw, (h - 1 - row) * w, out, row * w, w);
        }
        return out;
    }

    private static void fill(Canvas c, float x, float y, float w, float h, int argb) {
        try (Paint p = new Paint()) {
            p.setColor(argb);
            c.drawRect(Rect.makeXYWH(x, y, w, h), p);
        }
    }

    private static int countDiff(int[] a, int[] b, int tolerance) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (channelDelta(a[i], b[i]) > tolerance) n++;
        }
        return n;
    }

    private static int channelDelta(int p, int q) {
        int max = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            max = Math.max(max, Math.abs(((p >>> shift) & 0xFF) - ((q >>> shift) & 0xFF)));
        }
        return max;
    }

    private static String describeDiff(int[] a, int[] b) {
        int n = 0;
        int max = 0;
        for (int i = 0; i < a.length; i++) {
            int d = channelDelta(a[i], b[i]);
            if (d > 0) {
                n++;
                max = Math.max(max, d);
            }
        }
        return n + " px differ, max channel delta " + max;
    }

    private static int trivialProgram() {
        int vs = shader(GL_VERTEX_SHADER, "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }\n");
        int fs = shader(GL_FRAGMENT_SHADER, "#version 330 core\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n");
        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        glDeleteShader(vs);
        glDeleteShader(fs);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        return program;
    }

    private static int shader(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        return s;
    }

    private static void resetCallerState() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glUseProgram(0);
        glViewport(0, 0, WIN_W, WIN_H);
        for (int unit = 0; unit < 4; unit++) {
            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindSampler(unit, 0);
        }
        glActiveTexture(GL_TEXTURE0);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    }

    private static long createWindow() {
        assertTrue(glfwInit(), "glfwInit");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_STENCIL_BITS, 8);
        long w = glfwCreateWindow(WIN_W, WIN_H, "masonry-render-target", NULL, NULL);
        assertNotEquals(NULL, w, "hidden window");
        glfwMakeContextCurrent(w);
        GL.createCapabilities();
        return w;
    }

    private static void destroyWindow(long w) {
        glfwMakeContextCurrent(NULL);
        GL.setCapabilities(null);
        glfwDestroyWindow(w);
    }
}

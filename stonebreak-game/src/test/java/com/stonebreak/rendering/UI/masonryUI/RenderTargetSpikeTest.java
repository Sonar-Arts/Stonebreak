package com.stonebreak.rendering.UI.masonryUI;

import com.stonebreak.rendering.UI.backend.skija.SkiaContext;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.textures.MTexture;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * #283 render-target spike. One Masonry widget ({@link MButton}) and one asset
 * (an SBT through {@link MTextureRegistry}) are drawn under a transform, a
 * clip and 50% alpha to (A) the default framebuffer and (B) an offscreen FBO
 * texture, which is then composited back exactly the way the Open Mason tool
 * shows viewport textures in ImGui ({@code ImGui.image(tex, w, h, 0, 1, 1, 0)}
 * with ImGui's GL3 blend state). Checks pixel parity, target origin,
 * premultiplied alpha, the GL state Skia leaves behind, and pointer mapping at
 * a 1.5x display scale. Writes {@code target/render-target-spike.md}.
 *
 * <p>Needs a display: {@code -Dstonebreak.ui.gl=true}.
 */
@Tag("integration")
class RenderTargetSpikeTest {

    private static final int W = 256, H = 192;          // widget/texture size
    private static final int WIN_W = 512, WIN_H = 384;  // window (default framebuffer)
    private static final int BG = 0xFF203040;

    private final StringBuilder report = new StringBuilder();
    private Canvas current;
    /** Canvas scale of the scene; 1.25 puts nearest-sampled asset texels on non-integer pixel spans. */
    private float sceneScale = 1.25f;
    private String assetNote = "";

    @Test
    void spike() throws Exception {
        assumeTrue(Boolean.getBoolean("stonebreak.ui.gl"), "needs a display: -Dstonebreak.ui.gl=true");
        assertTrue(glfwInit(), "glfwInit");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_STENCIL_BITS, 8);
        long window = glfwCreateWindow(WIN_W, WIN_H, "render-target-spike", NULL, NULL);
        assertNotEquals(NULL, window, "hidden window");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        DirectContext skia = DirectContext.makeGL();
        Fbo fbo = Fbo.create(W, H);
        Compositor quad = new Compositor();
        try {
            line("# Render-target spike — Masonry to framebuffer vs offscreen texture");
            line("");
            line("- GL: " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION));
            line("- Widget: `MButton` (clipped by the canvas clip), asset: `SB_Full_Health_Icon.sbt` at 50% alpha,"
                + " canvas transform translate(12, 10) scale(1.25). Texture " + W + "x" + H + ", window "
                + WIN_W + "x" + WIN_H + ".");
            line("");

            MasonryUI ui = new MasonryUI(new SwitchableBackend());
            MButton button = new MButton("Resume Game");
            button.bounds(10, 10, 170, 40);
            MTexture heart = MTextureRegistry.get("/ui/HUD/Health Icon/SB_Full_Health_Icon.sbt");

            line("## Pixel parity");
            line("");
            line("| Scene scale | Comparison | Differing pixels (of " + W * H + ") |");
            line("| --- | --- | --- |");
            int[] direct = null;
            for (float scale : new float[]{1f, 1.25f}) {
                sceneScale = scale;
                // A — default framebuffer
                direct = crop(render(skia, 0, WIN_W, WIN_H, SurfaceOrigin.BOTTOM_LEFT, ui, button, heart, true),
                    WIN_W, 0, 0, W, H);
                // B — offscreen texture, GL's natural bottom-left origin
                int[] offscreen = render(skia, fbo.fbo, W, H, SurfaceOrigin.BOTTOM_LEFT, ui, button, heart, true);
                // B' — top-left origin: Skia writes rows top-down into the texture
                int[] topLeftRaw = renderRaw(skia, fbo.fbo, W, H, SurfaceOrigin.TOP_LEFT, ui, button, heart, true);
                int diffAB = countDiff(direct, offscreen, 0);
                int topLeftAsIs = countDiff(direct, topLeftRaw, 0);
                int topLeftFlipped = countDiff(direct, flipRows(topLeftRaw, W, H), 0);
                String sc = String.format(Locale.ROOT, "%.2f", scale);
                line("| " + sc + " | A default framebuffer vs B offscreen FBO (BOTTOM_LEFT), both read top-down | " + diffAB + " |");
                line("| " + sc + " | A vs FBO with TOP_LEFT origin, texture rows read as-is | " + topLeftAsIs + " |");
                line("| " + sc + " | A vs FBO with TOP_LEFT origin, rows flipped | " + topLeftFlipped + " |");
                if (scale == 1f) {
                    assertEquals(0, diffAB, "integer scale: framebuffer and offscreen texture must match exactly");
                    assertEquals(0, topLeftAsIs, "TOP_LEFT stores rows top-down");
                } else {
                    // Only nearest-sampled asset texels whose boundary falls on a pixel centre may differ.
                    int[] assetRect = {Math.round(12 + 40 * scale), Math.round(10 + 60 * scale),
                        Math.round(12 + 88 * scale), Math.round(10 + 108 * scale)};
                    assertEquals(0, diffOutside(direct, offscreen, W, assetRect),
                        "non-asset pixels must match exactly; " + describeDiff(direct, offscreen, W));
                    assetNote = describeDiff(direct, offscreen, W);
                }
            }
            sceneScale = 1.25f;

            // C — composite B's texture like ImGui.image(tex, w, h, 0,1,1,0) at 1:1
            int[] texturePixels = render(skia, fbo.fbo, W, H, SurfaceOrigin.BOTTOM_LEFT, ui, button, heart, true);
            int[] composited = composite(quad, fbo.texture, 200, 150, W, H, BlendMode.IMGUI_STRAIGHT, BG);
            int diffImgui = countDiff(texturePixels, crop(composited, WIN_W, 200, 150, W, H), 0);
            line("| 1.25 | B shown through an ImGui-equivalent quad (uv0=(0,1), uv1=(1,0)) vs B itself, opaque | " + diffImgui + " |");
            line("");
            line("At 1.25 every difference lies inside the asset's destination rect: " + assetNote + "."
                + " A " + heart.width() + "x" + heart.height() + " SBT drawn nearest-sampled into 48 px at 1.25x"
                + " (" + String.format(Locale.ROOT, "%.2f", 60f / heart.width()) + " px per texel) puts some pixel centres exactly on texel"
                + " boundaries, and the default framebuffer (height " + WIN_H + ", flipped) and the texture"
                + " (height " + H + ") round those ties differently. Fidelity rule for #286: integer"
                + " asset scales (or pixel-snapped destination rects) are target-independent; anything else"
                + " needs a ±1-texel tolerance at boundaries.");
            line("");
            assertEquals(0, diffImgui, "ImGui-style composition of an opaque target must be lossless");

            alphaSection(skia, fbo, quad, ui, button, heart);
            stateSection(skia, fbo, ui, button, heart);
            pointerSection(skia, fbo, quad, ui, button, heart, texturePixels);

        } finally {
            // Written even when an assertion fails: the partial report is the diagnostic.
            Path out = Path.of("target", "render-target-spike.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report);
            quad.dispose();
            fbo.dispose();
            skia.close();
            GL.setCapabilities(null);
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    // ───────────────────────────── sections ─────────────────────────────

    /** Transparent target over a coloured viewport: Skia writes premultiplied alpha. */
    private void alphaSection(DirectContext skia, Fbo fbo, Compositor quad, MasonryUI ui, MButton button,
                              MTexture heart) {
        // Reference: the same target painted over an opaque backdrop (isolates blending from sampling).
        int[] expected = render(skia, fbo.fbo, W, H, SurfaceOrigin.BOTTOM_LEFT, ui, button, heart, true);
        render(skia, fbo.fbo, W, H, SurfaceOrigin.BOTTOM_LEFT, ui, button, heart, false);
        int[] straight = crop(composite(quad, fbo.texture, 0, 0, W, H, BlendMode.IMGUI_STRAIGHT, BG), WIN_W, 0, 0, W, H);
        render(skia, fbo.fbo, W, H, SurfaceOrigin.BOTTOM_LEFT, ui, button, heart, false);
        int[] premul = crop(composite(quad, fbo.texture, 0, 0, W, H, BlendMode.PREMULTIPLIED, BG), WIN_W, 0, 0, W, H);
        int maxStraight = maxChannelDelta(expected, straight);
        int maxPremul = maxChannelDelta(expected, premul);
        line("## Alpha and colour (transparent offscreen target composited over the viewport colour)");
        line("");
        line("| Blend used to composite | Pixels off by >1 level | Max channel error (levels of 255) |");
        line("| --- | --- | --- |");
        line("| ImGui GL3 default (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) | " + countDiff(expected, straight, 1) + " | "
            + maxStraight + " |");
        line("| Premultiplied (ONE, ONE_MINUS_SRC_ALPHA) | " + countDiff(expected, premul, 1) + " | " + maxPremul + " |");
        line("");
        line("Skia surfaces are premultiplied, so a translucent UI texture shown with ImGui's default blend is"
            + " darkened at every partially transparent pixel. The preview must switch blend for that draw"
            + " (ImDrawList callback) or render onto an opaque backdrop.");
        line("");
        assertTrue(maxPremul <= 2, "premultiplied composition should match within rounding");
        assertTrue(maxStraight > maxPremul, "straight-alpha composition is expected to darken edges");
    }

    /** What Skia leaves bound, and what SkiaContext.restoreGLDefaults puts back. */
    private void stateSection(DirectContext skia, Fbo fbo, MasonryUI ui, MButton button, MTexture heart)
        throws Exception {
        Fbo callerTarget = Fbo.create(64, 64);
        int sampler = glGenSamplers();
        glSamplerParameteri(sampler, GL_TEXTURE_WRAP_S, GL_REPEAT);
        int callerTex = glGenTextures();
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, callerTarget.fbo);
            glViewport(3, 4, 50, 60);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, callerTex);
            glBindSampler(1, sampler);
            glActiveTexture(GL_TEXTURE3);
            glEnable(GL_SCISSOR_TEST);
            glScissor(1, 2, 30, 40);
            glEnable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            Map<String, String> before = snapshot();

            skia.resetGLAll();
            paint(skia, fbo.fbo, W, H, SurfaceOrigin.BOTTOM_LEFT, ui, button, heart, true);
            Map<String, String> afterSkia = snapshot();

            Method restore = SkiaContext.class.getDeclaredMethod("restoreGLDefaults");
            restore.setAccessible(true);
            restore.invoke(null);
            Map<String, String> afterRestore = snapshot();

            line("## GL state around a Skia paint into an offscreen target");
            line("");
            line("| State | Caller set | After Skia flush | After `SkiaContext.restoreGLDefaults()` |");
            line("| --- | --- | --- | --- |");
            for (String key : before.keySet()) {
                line("| " + key + " | " + before.get(key) + " | " + afterSkia.get(key) + " | " + afterRestore.get(key) + " |");
            }
            line("");
            line("Today's restore targets one owner: it binds FBO 0 (clobbering a caller's offscreen target)"
                + " and never restores the viewport or caller texture/sampler bindings. An offscreen preview needs"
                + " save/restore of the caller's state instead of a reset to game defaults.");
            line("");
            assertEquals("0", afterRestore.get("sampler on unit 1"), "the sampler fix must hold");
        } finally {
            glDeleteSamplers(sampler);
            glDeleteTextures(callerTex);
            callerTarget.dispose();
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, WIN_W, WIN_H);
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_DEPTH_TEST);
        }
    }

    /**
     * The preview shows the texture at a display scale and offset. A pointer at
     * window (mx, my) maps to widget space as ((mx - ix) / scale, (my - iy) / scale)
     * — no Y inversion, because the uv flip already happened in the quad.
     */
    private void pointerSection(DirectContext skia, Fbo fbo, Compositor quad, MasonryUI ui, MButton button,
                                MTexture heart, int[] direct) {
        float scale = 1.5f;
        int ix = 20, iy = 40;
        int dw = Math.round(W * scale), dh = Math.round(H * scale);
        render(skia, fbo.fbo, W, H, SurfaceOrigin.BOTTOM_LEFT, ui, button, heart, true);
        int[] shown = composite(quad, fbo.texture, ix, iy, dw, dh, BlendMode.IMGUI_STRAIGHT, BG);
        int samples = 0, colourAgree = 0, hitAgree = 0;
        for (int my = iy; my < iy + dh; my += 7) {
            for (int mx = ix; mx < ix + dw; mx += 7) {
                float wx = (mx + 0.5f - ix) / scale;
                float wy = (my + 0.5f - iy) / scale;
                int px = Math.min(W - 1, (int) wx), py = Math.min(H - 1, (int) wy);
                samples++;
                if (shown[my * WIN_W + mx] == direct[py * W + px]) {
                    colourAgree++;
                }
                // Undo the scene transform (translate 12,10 scale 1.25) and clip to hit-test the button.
                float bx = (wx - 12) / 1.25f, by = (wy - 10) / 1.25f;
                boolean inClip = bx >= 0 && bx < 150 && by >= 0 && by < 120;
                boolean hit = inClip && button.contains(bx, by);
                boolean drawnAsButton = isButtonPixel(direct[py * W + px]);
                if (hit == drawnAsButton) {
                    hitAgree++;
                }
            }
        }
        line("## Pointer mapping (texture shown at 1.5x, offset " + ix + "," + iy + ")");
        line("");
        line(String.format(Locale.ROOT, "- %d pointer samples: shown colour equals widget-space colour at the mapped"
            + " point for **%d (%.1f%%)**; widget hit-test agrees with the drawn button for **%d (%.1f%%)**"
            + " (disagreement = anti-aliased edge texels and the label glyphs).", samples, colourAgree,
            100.0 * colourAgree / samples, hitAgree, 100.0 * hitAgree / samples));
        line("");
        assertTrue(colourAgree >= samples * 0.98, "pointer→texel mapping must hold");
        assertTrue(hitAgree >= samples * 0.95, "hit-testing must agree with the drawn geometry");
    }

    private static boolean isButtonPixel(int argb) {
        // The stone button face is grey (R≈G≈B, mid luminance); the backdrop is BG, the heart is red.
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return argb != BG && Math.abs(r - g) < 24 && Math.abs(g - b) < 24 && r > 40;
    }

    // ───────────────────────────── drawing ─────────────────────────────

    private void drawScene(Canvas c, MasonryUI ui, MButton button, MTexture heart, boolean opaque) {
        current = c;
        c.clear(opaque ? BG : 0x00000000);
        c.save();
        c.translate(12, 10);
        c.scale(sceneScale, sceneScale);
        c.clipRect(Rect.makeXYWH(0, 0, 150, 120));
        button.render(ui);
        try (Paint half = new Paint()) {
            half.setAlphaf(0.5f);
            c.drawImageRect(heart.image(), Rect.makeWH(heart.width(), heart.height()),
                Rect.makeXYWH(40, 60, 48, 48), SamplingMode.DEFAULT, half, true);
        }
        c.restore();
    }

    private void paint(DirectContext skia, int fb, int w, int h, SurfaceOrigin origin, MasonryUI ui,
                       MButton button, MTexture heart, boolean opaque) {
        try (BackendRenderTarget rt = BackendRenderTarget.makeGL(w, h, 0, 8, fb, FramebufferFormat.GR_GL_RGBA8);
             Surface surface = Surface.wrapBackendRenderTarget(skia, rt, origin, ColorType.RGBA_8888,
                 ColorSpace.getSRGB())) {
            drawScene(surface.getCanvas(), ui, button, heart, opaque);
            skia.flushAndSubmit(surface);
        }
    }

    /** Paints, then reads the target back as top-down ARGB (GL rows are bottom-up). */
    private int[] render(DirectContext skia, int fb, int w, int h, SurfaceOrigin origin, MasonryUI ui,
                         MButton button, MTexture heart, boolean opaque) {
        return flipRows(renderRaw(skia, fb, w, h, origin, ui, button, heart, opaque), w, h);
    }

    /** Paints and returns rows in GL memory order (row 0 = bottom of the buffer). */
    private int[] renderRaw(DirectContext skia, int fb, int w, int h, SurfaceOrigin origin, MasonryUI ui,
                            MButton button, MTexture heart, boolean opaque) {
        skia.resetGLAll();
        paint(skia, fb, w, h, origin, ui, button, heart, opaque);
        skia.resetGLAll();
        return readGl(fb, w, h);
    }

    private static int[] readGl(int fb, int w, int h) {
        glBindFramebuffer(GL_FRAMEBUFFER, fb);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        int[] px = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int r = buf.get() & 0xFF, g = buf.get() & 0xFF, b = buf.get() & 0xFF, a = buf.get() & 0xFF;
            px[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return px;
    }

    private int[] composite(Compositor quad, int texture, int x, int y, int w, int h, BlendMode mode, int bg) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, WIN_W, WIN_H);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_DEPTH_TEST);
        glClearColor(((bg >> 16) & 0xFF) / 255f, ((bg >> 8) & 0xFF) / 255f, (bg & 0xFF) / 255f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
        quad.draw(texture, x, y, w, h, mode);
        return flipRows(readGl(0, WIN_W, WIN_H), WIN_W, WIN_H);
    }

    // ───────────────────────────── pixels ─────────────────────────────

    private static int[] flipRows(int[] px, int w, int h) {
        int[] out = new int[px.length];
        for (int row = 0; row < h; row++) {
            System.arraycopy(px, row * w, out, (h - 1 - row) * w, w);
        }
        return out;
    }

    private static int[] crop(int[] px, int stride, int x, int y, int w, int h) {
        int[] out = new int[w * h];
        for (int row = 0; row < h; row++) {
            System.arraycopy(px, (y + row) * stride + x, out, row * w, w);
        }
        return out;
    }

    private static String describeDiff(int[] a, int[] b, int w) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1, n = 0;
        int[] hist = new int[256];
        int alphaOnly = 0;
        for (int i = 0; i < a.length; i++) {
            int d = channelDelta(a[i], b[i]);
            if (d == 0) {
                continue;
            }
            n++;
            hist[d]++;
            if (channelDelta(a[i] | 0xFF000000, b[i] | 0xFF000000) == 0) {
                alphaOnly++;
            }
            minX = Math.min(minX, i % w);
            maxX = Math.max(maxX, i % w);
            minY = Math.min(minY, i / w);
            maxY = Math.max(maxY, i / w);
        }
        if (n == 0) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(n).append(" px, bbox (").append(minX).append(',').append(minY).append(")-(").append(maxX)
            .append(',').append(maxY).append("), alpha-only ").append(alphaOnly).append(", error histogram");
        for (int d = 1; d < 256; d++) {
            if (hist[d] > 0) {
                sb.append(' ').append(d).append(':').append(hist[d]);
            }
        }
        return sb.toString();
    }

    private static int diffOutside(int[] a, int[] b, int w, int[] rect) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            int x = i % w, y = i / w;
            boolean inside = x >= rect[0] - 1 && x <= rect[2] && y >= rect[1] - 1 && y <= rect[3];
            if (!inside && a[i] != b[i]) {
                n++;
            }
        }
        return n;
    }

    private static int countDiff(int[] a, int[] b, int tolerance) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (channelDelta(a[i], b[i]) > tolerance) {
                n++;
            }
        }
        return n;
    }

    private static int maxChannelDelta(int[] a, int[] b) {
        int max = 0;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, channelDelta(a[i], b[i]));
        }
        return max;
    }

    private static int channelDelta(int p, int q) {
        int max = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            max = Math.max(max, Math.abs(((p >> shift) & 0xFF) - ((q >> shift) & 0xFF)));
        }
        return max;
    }

    private static Map<String, String> snapshot() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("framebuffer", String.valueOf(glGetInteger(GL_FRAMEBUFFER_BINDING)));
        IntBuffer vp = BufferUtils.createIntBuffer(4);
        glGetIntegerv(GL_VIEWPORT, vp);
        s.put("viewport", vp.get(0) + "," + vp.get(1) + " " + vp.get(2) + "x" + vp.get(3));
        s.put("program", String.valueOf(glGetInteger(GL_CURRENT_PROGRAM)));
        s.put("active texture unit", String.valueOf(glGetInteger(GL_ACTIVE_TEXTURE) - GL_TEXTURE0));
        glActiveTexture(GL_TEXTURE1);
        s.put("texture on unit 1", String.valueOf(glGetInteger(GL_TEXTURE_BINDING_2D)));
        s.put("sampler on unit 1", String.valueOf(glGetInteger(GL_SAMPLER_BINDING)));
        s.put("scissor test", String.valueOf(glIsEnabled(GL_SCISSOR_TEST)));
        s.put("depth test", String.valueOf(glIsEnabled(GL_DEPTH_TEST)));
        s.put("blend", String.valueOf(glIsEnabled(GL_BLEND)));
        s.put("unpack alignment", String.valueOf(glGetInteger(GL_UNPACK_ALIGNMENT)));
        return s;
    }

    private void line(String s) {
        report.append(s).append('\n');
    }

    // ───────────────────────────── GL helpers ─────────────────────────────

    private enum BlendMode { IMGUI_STRAIGHT, PREMULTIPLIED }

    private record Fbo(int fbo, int texture, int stencil) {
        static Fbo create(int w, int h) {
            int tex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glBindTexture(GL_TEXTURE_2D, 0);
            int rb = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, rb);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_STENCIL_INDEX8, w, h);
            glBindRenderbuffer(GL_RENDERBUFFER, 0);
            int fb = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fb);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, rb);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("FBO incomplete: 0x" + Integer.toHexString(status));
            }
            return new Fbo(fb, tex, rb);
        }

        void dispose() {
            glDeleteFramebuffers(fbo);
            glDeleteTextures(texture);
            glDeleteRenderbuffers(stencil);
        }
    }

    /** A textured quad drawn exactly like ImGui's GL3 backend draws an image item. */
    private static final class Compositor {
        private final int program;
        private final int vao;
        private final int vbo;

        Compositor() {
            int vs = shader(GL_VERTEX_SHADER, """
                #version 330 core
                layout(location = 0) in vec2 aPos;
                layout(location = 1) in vec2 aUV;
                out vec2 vUV;
                void main() { vUV = aUV; gl_Position = vec4(aPos, 0.0, 1.0); }
                """);
            int fs = shader(GL_FRAGMENT_SHADER, """
                #version 330 core
                in vec2 vUV;
                uniform sampler2D uTex;
                out vec4 color;
                void main() { color = texture(uTex, vUV); }
                """);
            program = glCreateProgram();
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
                throw new IllegalStateException(glGetProgramInfoLog(program));
            }
            glDeleteShader(vs);
            glDeleteShader(fs);
            vao = glGenVertexArrays();
            vbo = glGenBuffers();
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, 16 * Float.BYTES, GL_DYNAMIC_DRAW);
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8);
            glEnableVertexAttribArray(0);
            glEnableVertexAttribArray(1);
            glBindVertexArray(0);
        }

        /** Pixel rect in top-down window coordinates; uv0 = (0,1) top-left, uv1 = (1,0). */
        void draw(int texture, int x, int y, int w, int h, BlendMode mode) {
            float x0 = x * 2f / WIN_W - 1f, x1 = (x + w) * 2f / WIN_W - 1f;
            float y0 = 1f - y * 2f / WIN_H, y1 = 1f - (y + h) * 2f / WIN_H;
            FloatBuffer v = BufferUtils.createFloatBuffer(16).put(new float[]{
                x0, y0, 0f, 1f,
                x1, y0, 1f, 1f,
                x0, y1, 0f, 0f,
                x1, y1, 1f, 0f}).flip();
            glUseProgram(program);
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, v);
            glActiveTexture(GL_TEXTURE0);
            glBindSampler(0, 0);
            glBindTexture(GL_TEXTURE_2D, texture);
            glUniform1i(glGetUniformLocation(program, "uTex"), 0);
            glEnable(GL_BLEND);
            glBlendEquation(GL_FUNC_ADD);
            if (mode == BlendMode.IMGUI_STRAIGHT) {
                glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            } else {
                glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            }
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glBindVertexArray(0);
            glUseProgram(0);
        }

        void dispose() {
            glDeleteProgram(program);
            glDeleteVertexArrays(vao);
            glDeleteBuffers(vbo);
        }

        private static int shader(int type, String src) {
            int s = glCreateShader(type);
            glShaderSource(s, src);
            glCompileShader(s);
            if (glGetShaderi(s, GL_COMPILE_STATUS) != GL_TRUE) {
                throw new IllegalStateException(glGetShaderInfoLog(s));
            }
            return s;
        }
    }

    /** Masonry backend whose canvas is whichever target the spike is painting. */
    private final class SwitchableBackend extends SkijaUIBackend {
        private final Typeface typeface = loadTypeface();

        @Override
        public Canvas getCanvas() {
            return current;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Typeface getMinecraftTypeface() {
            return typeface;
        }

        private static Typeface loadTypeface() {
            try (InputStream in = RenderTargetSpikeTest.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
                return FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
            } catch (IOException | NullPointerException e) {
                throw new IllegalStateException("could not load the game typeface", e);
            }
        }
    }
}

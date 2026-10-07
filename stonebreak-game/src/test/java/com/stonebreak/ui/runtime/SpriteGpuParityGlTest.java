package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiSpriteSheet;
import com.openmason.engine.format.omui.UiSpriteSheet.Fill;
import com.openmason.engine.format.omui.UiSpriteSheet.Frame;
import com.openmason.engine.format.omui.UiSpriteSheet.Sampling;
import com.openmason.engine.format.omui.UiSpriteSheet.ScaleMode;
import com.openmason.engine.format.omui.UiSpriteSheet.Skin;
import com.openmason.engine.format.omui.UiSpriteSheet.Slice;
import com.openmason.engine.format.omui.UiSpriteSheet.Sprite;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.SpriteSheetCodec;
import com.openmason.engine.ui.fidelity.FidelityImage;
import com.openmason.engine.ui.fidelity.PixelComparator;
import com.openmason.engine.ui.fidelity.PixelReport;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.rendering.GpuMasonryBackend;
import com.openmason.engine.ui.rendering.MasonryBackend;
import com.openmason.engine.ui.rendering.OffscreenFramebuffer;
import com.openmason.engine.ui.rendering.RasterMasonryBackend;
import com.openmason.engine.ui.rendering.UiRenderTarget;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * #328: sprites and nine-slices on the GPU Skia backend. Every other sprite gate (the #294
 * goldens, the #296 baselines and {@code MigrationGate}) runs on the CPU raster backend, so a
 * GPU-only fault — sampler or wrap state, texel bleeding at a fractional scale, cached
 * shaders/Paints — would pass them all and still show in the game and the editor preview.
 *
 * <p>One sprite showcase (whole texture, regions, stretched and tiled nine-slices with a hollow
 * centre, integer pixel art, linear filtering, tint + opacity, an animated frame, and a
 * {@code stone.sprites.json}-style skinned button in its normal and hover states), hosted by
 * {@link GameUiDocuments} exactly as the game hosts documents, is painted at 0.75x, 1x, 1.25x
 * and 2x into the game window (framebuffer 0), the editor's offscreen preview framebuffer, and
 * the CPU raster reference. The window and the framebuffer must agree exactly; the GPU and the
 * raster within {@link PixelTolerance#RASTER_DRIFT}. On a failure the three captures and the
 * diff are written to {@code target/ui-fidelity/sprite-gpu/}.
 *
 * <p>The fixture deliberately puts nearest-sampled texel edges exactly on pixel centres (the
 * whole texture stretched 4.5x at 2x, the hover skin at 3.75x, tiled bars at 0.75x). Each target
 * breaks such a tie its own way; only {@code SpritePainter.NEAREST_TIE_BIAS} makes them agree.
 *
 * <p>Needs a display: {@code -Dstonebreak.ui.gl=true}.
 */
@Tag("integration")
class SpriteGpuParityGlTest {

    /** Logical layout size; the framebuffer is this times the UI scale. */
    private static final int LW = 240;
    private static final int LH = 176;
    private static final float MAX_SCALE = 2f;
    private static final int WIN_W = Math.round(LW * MAX_SCALE);
    private static final int WIN_H = Math.round(LH * MAX_SCALE);
    private static final int BG = 0xFF203040;

    private static final String TEXTURE_ID = "t:ui/textures/sprite_parity";
    private static final String SHEET_ID = "t:ui/sprites/sprite_parity";
    private static final String TEXTURE_ENTRY = "assets/sprite_parity.omt";
    private static final String SHEET_ENTRY = "assets/sprite_parity.sprites.json";

    /** The 12x12 frame's corners, edge band and centre: a slice of 3 cuts at the colour boundaries. */
    private static final int TL = 0xFFFF0000;
    private static final int TR = 0xFF00FF00;
    private static final int BL = 0xFFFFFF00;
    private static final int BR = 0xFFFF00FF;
    private static final int EDGE = 0xFF0000FF;
    private static final int CENTER = 0xFF808080;

    private static final Path FAILURES = Path.of("target", "ui-fidelity", "sprite-gpu");

    private static long window;
    private static Typeface typeface;

    @BeforeAll
    static void openContext() throws Exception {
        assumeTrue(Boolean.getBoolean("stonebreak.ui.gl"), "needs a display: -Dstonebreak.ui.gl=true");
        assertTrue(glfwInit(), "glfwInit");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_STENCIL_BITS, 8);
        window = glfwCreateWindow(WIN_W, WIN_H, "sprite-gpu-parity", NULL, NULL);
        assertNotEquals(NULL, window, "hidden window");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        int[] fbw = new int[1];
        int[] fbh = new int[1];
        glfwGetFramebufferSize(window, fbw, fbh);
        assumeTrue(fbw[0] == WIN_W && fbh[0] == WIN_H,
            "the window framebuffer is " + fbw[0] + "x" + fbh[0] + ", not " + WIN_W + "x" + WIN_H + " (display scaling)");
        try (InputStream in = SpriteGpuParityGlTest.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
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

    @TestFactory
    Stream<DynamicTest> spritesPaintAlikeOnTheGpuTargetsAndTheRasterReference() {
        return Stream.of(0.75f, 1f, 1.25f, 2f)
            .map(scale -> DynamicTest.dynamicTest(scale + "x", () -> paintAtScale(scale)));
    }

    private static void paintAtScale(float scale) throws Exception {
        int w = Math.round(LW * scale);
        int h = Math.round(LH * scale);
        String tag = "x" + String.valueOf(scale).replace('.', '_');

        SkijaUIBackend game = new SkijaUIBackend() {
            @Override
            public Typeface getMinecraftTypeface() {
                return typeface;
            }
        };
        game.initialize(UiRenderTarget.gameWindow(WIN_W, WIN_H, 1f));
        GpuMasonryBackend preview = new GpuMasonryBackend() {
            @Override
            public Typeface typeface() {
                return typeface;
            }
        };
        preview.initialize(UiRenderTarget.offscreen(0, 1, 1, 1f));
        OffscreenFramebuffer fbo = new OffscreenFramebuffer();
        fbo.ensureSize(w, h);
        preview.setTarget(fbo.target(1f));
        RasterMasonryBackend raster = new RasterMasonryBackend(w, h, typeface, false);

        OmuiArchive doc = showcase();
        UiDocumentView gameView = GameUiDocuments.open(doc, () -> typeface, Map.of());
        UiDocumentView previewView = GameUiDocuments.open(doc, () -> typeface, Map.of());
        UiDocumentView rasterView = GameUiDocuments.open(doc, () -> typeface, Map.of());
        List<UiDocumentView> views = List.of(gameView, previewView, rasterView);
        MasonryUI gameUi = new MasonryUI(game);
        MasonryUI previewUi = new MasonryUI(preview);
        MasonryUI rasterUi = new MasonryUI(raster);
        try {
            for (UiDocumentView v : views) {
                v.frame(0.15); // blink shows its second frame
            }
            List<String> failures = new ArrayList<>();
            int[] normal = null;
            for (String state : List.of("normal", "hover")) {
                if (state.equals("hover")) {
                    UiRect r = rasterView.instance().find("stone").rect();
                    assertFalse(r.isEmpty(), "the stone button was laid out");
                    for (UiDocumentView v : views) {
                        v.pointerMove(r.x() + r.width() / 2, r.y() + r.height() / 2);
                    }
                }
                paint(game, gameUi, gameView, WIN_W, WIN_H, w, h, scale);
                int[] onWindow = argb(readTopDown(0, WIN_W, WIN_H, true, w, h));
                paint(preview, previewUi, previewView, fbo.allocatedWidth(), fbo.allocatedHeight(), w, h, scale);
                int[] offscreen = argb(readTopDown(fbo.framebufferId(), w, h, false, w, h));
                paint(raster, rasterUi, rasterView, w, h, w, h, scale);
                int[] cpu = rasterPixels(raster, w, h);
                assertEquals(GL_NO_ERROR, glGetError(), state);

                String name = tag + "_" + state;
                FidelityImage reference = new FidelityImage(w, h, cpu);
                assertSpritesPainted(reference, scale, name);
                compare(failures, name, "fbo", "window", new FidelityImage(w, h, offscreen),
                    new FidelityImage(w, h, onWindow), PixelTolerance.EXACT);
                compare(failures, name, "raster", "gpu", reference, new FidelityImage(w, h, offscreen),
                    PixelTolerance.RASTER_DRIFT);
                if (normal == null) {
                    normal = cpu;
                } else {
                    assertNotEquals(0, PixelComparator.compare(new FidelityImage(w, h, normal), reference,
                        PixelTolerance.EXACT).mismatched(), name + ": hovering swaps the stone skin's region");
                }
            }
            assertTrue(failures.isEmpty(), String.join("\n", failures)
                + "\n(captures and diffs in " + FAILURES.toAbsolutePath() + ")");
            for (UiDocumentView v : views) {
                assertTrue(v.instance().diagnostics().isEmpty(), v.instance().diagnostics()::toString);
            }
        } finally {
            gameUi.dispose();
            previewUi.dispose();
            rasterUi.dispose();
            for (UiDocumentView v : views) {
                v.close();
            }
            preview.releaseTargetSurface();
            fbo.close();
            preview.dispose();
            game.dispose();
            raster.dispose();
        }
    }

    // ───────────────────────────── fixture ─────────────────────────────

    /**
     * A 28x16 texture. Left 12x12: the pixel-art frame with a translucent glaze over its right
     * half (a second OMT layer at 75 %). Right 16x16: two 16x8 stone buttons (normal, lighter
     * hover) with a dark 3x2 border band and per-texel noise, so tiling and filtering show.
     */
    private static byte[] texture() {
        return omt(28, 16,
            new Layer("base", 1f, png(28, 16, SpriteGpuParityGlTest::basePixel)),
            new Layer("glaze", 0.75f, png(28, 16, (x, y) -> x >= 6 && x < 12 && y < 12 ? 0x9020C040 : 0)));
    }

    private static int basePixel(int x, int y) {
        if (x < 12) {
            return y < 12 ? framePixel(x, y) : 0;
        }
        int lx = x - 12;
        int ly = y % 8;
        boolean hover = y >= 8;
        boolean border = lx < 3 || lx >= 13 || ly < 2 || ly >= 6;
        int grey = (border ? 48 : 112) + ((lx * 37 + ly * 91 + (hover ? 53 : 0)) % 7) * 10 + (hover ? 40 : 0);
        return 0xFF000000 | grey << 16 | grey << 8 | Math.min(255, grey + (hover ? 30 : 0));
    }

    private static int framePixel(int x, int y) {
        boolean left = x < 3;
        boolean right = x >= 9;
        boolean top = y < 3;
        boolean bottom = y >= 9;
        if (top && left) return TL;
        if (top && right) return TR;
        if (bottom && left) return BL;
        if (bottom && right) return BR;
        return left || right || top || bottom ? EDGE : CENTER;
    }

    private static UiSpriteSheet sheet() {
        Sprite panel = Sprite.of("panel", 0, 0, 12, 12).withSlice(new Slice(3, 3, 3, 3), Fill.STRETCH, Fill.STRETCH);
        Sprite hollow = Sprite.of("hollow", 0, 0, 12, 12).withSlice(new Slice(3, 3, 3, 3), Fill.TILE, Fill.HIDDEN);
        Sprite pixel = Sprite.of("pixel", 0, 0, 6, 6).withLook(ScaleMode.INTEGER, null, null, 1);
        Sprite smooth = Sprite.of("smooth", 0, 0, 12, 12).withLook(null, Sampling.LINEAR, null, 1);
        Sprite tinted = Sprite.of("tinted", 3, 3, 6, 6).withLook(null, null, "#80C0FF", 0.6);
        Sprite blink = Sprite.of("blink", 0, 0, 3, 3).withLogicalSize(12, 12)
            .withFrames(List.of(new Frame(0, 0, 0.1), new Frame(9, 0, 0.1), new Frame(9, 9, 0.1)), LoopMode.PING_PONG);
        // stone.sprites.json's button: tiled edges, hidden centre, linear, tint + opacity, 2x logical size
        Sprite button = Sprite.of("button", 12, 0, 16, 8).withLogicalSize(32, 16).withPivot(0, 1)
            .withSlice(new Slice(3, 2, 3, 2), Fill.TILE, Fill.HIDDEN)
            .withLook(ScaleMode.NINE_SLICE, Sampling.LINEAR, "#FF8000C0", 0.5);
        Sprite buttonHover = Sprite.of("button_hover", 12, 8, 16, 8);
        Sprite bar = Sprite.of("bar", 12, 0, 16, 8).withSlice(new Slice(3, 2, 3, 2), Fill.TILE, Fill.TILE);
        Skin stone = new Skin("stone", "button", "button_hover", null, null, null, Map.of());
        return new UiSpriteSheet(TEXTURE_ID, 28, 16,
            List.of(panel, hollow, pixel, smooth, tinted, blink, button, buttonHover, bar), List.of(stone), Map.of());
    }

    private static OmuiArchive showcase() {
        List<UiNode> row = new ArrayList<>();
        int i = 0;
        for (int[] size : new int[][]{{12, 12}, {40, 24}, {90, 36}}) {
            row.add(box("panel" + i, size[0], size[1], "background-image", SHEET_ID + "#panel"));
            row.add(box("hollow" + i++, size[0] + 7, size[1] + 5, "background-image", SHEET_ID + "#hollow"));
        }
        row.add(image("pixel", SHEET_ID + "#pixel", 31, 31));
        row.add(box("smooth", 37, 29, "background-image", SHEET_ID + "#smooth"));
        row.add(box("tinted", 24, 24, "background-color", "#404040", "background-image", SHEET_ID + "#tinted"));
        row.add(image("blink", SHEET_ID + "#blink", 0, 0));
        row.add(image("whole", TEXTURE_ID, 36, 36));
        row.add(box("stone", 64, 24, "background-image", SHEET_ID + "#stone"));
        row.add(box("bar", 101, 15, "background-image", SHEET_ID + "#bar"));
        UiNode wrap = node("row", "Box", Map.of(), style("flex-direction", "row", "flex-wrap", "wrap",
            "column-gap", 6, "row-gap", 6, "padding-left", 6, "padding-top", 6, "align-items", "flex-start"), row);
        UiNode root = node("root", "Box", Map.of(), style("width", "100%", "height", "100%",
            "background-color", "#182028"), List.of(wrap));

        UiBytes tex = UiBytes.copyOf(texture());
        UiBytes sheet = UiBytes.copyOf(SpriteSheetCodec.write(sheet()));
        UiDependency texRow = UiDependency.embedded(TEXTURE_ID, UiDependency.Kind.TEXTURE, tex, TEXTURE_ENTRY, null);
        UiDependency s = UiDependency.embedded(SHEET_ID, UiDependency.Kind.SPRITES, sheet, SHEET_ENTRY, null);
        UiDependency sheetRow = new UiDependency(s.id(), s.kind(), s.version(), s.sha256(), s.size(), s.mode(),
            s.entry(), s.sourceHint(), List.of(TEXTURE_ID), false, null, null, Map.of());

        UiManifest m = UiManifest.create("t:ui/sprite_gpu_parity", UiManifest.DocumentKind.SCREEN, "sprite parity");
        List<String> requires = new ArrayList<>(m.requires());
        requires.add(UiFeatures.SPRITES);
        m = new UiManifest(m.schemaVersion(), m.documentId(), m.kind(), m.displayName(), m.uiApi(),
            m.layoutSemantics(), requires, m.hostApis(), m.providers(), m.unknown());
        return OmuiArchive.of(m, new UiDocument(root, List.of(), null, null, Map.of()))
            .withDependencies(new OmuiArchive.UiDependencies(List.of(sheetRow, texRow), Map.of()))
            .withAsset(TEXTURE_ENTRY, tex)
            .withAsset(SHEET_ENTRY, sheet);
    }

    private static UiNode box(String id, int w, int h, Object... kv) {
        Map<String, UiValue> st = style(kv);
        st.put("width", UiValue.of(w));
        st.put("height", UiValue.of(h));
        return node(id, "Box", Map.of(), st, List.of());
    }

    /** An {@code Image}; {@code w == 0} keeps the sprite's natural (logical) size. */
    private static UiNode image(String id, String source, int w, int h) {
        Map<String, UiValue> st = w == 0 ? Map.of() : style("width", w, "height", h);
        return node(id, "Image", Map.of("source", UiValue.of(source)), st, List.of());
    }

    private static UiNode node(String id, String type, Map<String, UiValue> props, Map<String, UiValue> style,
                               List<UiNode> children) {
        return new UiNode(id, id, type, 1, List.of(), props, style, null, List.of(), null, children, Map.of());
    }

    private static Map<String, UiValue> style(Object... kv) {
        Map<String, UiValue> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            out.put((String) kv[i], v instanceof Number n ? UiValue.of(n.doubleValue()) : UiValue.of((String) v));
        }
        return out;
    }

    private record Layer(String name, float opacity, byte[] png) {
    }

    /** A straight-alpha PNG whose pixel {@code (x, y)} is {@code argb.applyAsInt(x, y)}. */
    private static byte[] png(int w, int h, IntBinaryOperator argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb.applyAsInt(x, y));
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An OMT archive (the Texture Editor's save format) with {@code layers} bottom to top. */
    private static byte[] omt(int w, int h, Layer... layers) {
        StringBuilder manifest = new StringBuilder("{\"version\":\"1.0\",\"canvasSize\":{\"width\":" + w
            + ",\"height\":" + h + "},\"layers\":[");
        for (int i = 0; i < layers.length; i++) {
            manifest.append(i == 0 ? "" : ",").append("{\"name\":\"").append(layers[i].name())
                .append("\",\"visible\":true,\"opacity\":").append(layers[i].opacity())
                .append(",\"dataFile\":\"layer_").append(i).append(".png\"}");
        }
        manifest.append("],\"activeLayerIndex\":0}");
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int i = 0; i < layers.length; i++) {
                zip.putNextEntry(new ZipEntry("layer_" + i + ".png"));
                zip.write(layers[i].png());
                zip.closeEntry();
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ───────────────────────────── checks ─────────────────────────────

    /**
     * Guards against two equally blank captures agreeing: the frame's unglazed texels (its left
     * corners, edge band and centre; the glaze layer covers the right half) show unfiltered.
     */
    private static void assertSpritesPainted(FidelityImage img, float scale, String name) {
        for (int colour : new int[]{TL, BL, EDGE, CENTER}) {
            int found = 0;
            for (int p : img.argb()) {
                if (p == colour) {
                    found++;
                }
            }
            assertTrue(found >= Math.max(1, Math.round(scale * scale)),
                name + ": no " + Integer.toHexString(colour) + " texels painted");
        }
    }

    /** Compares and, on a mismatch, records it in {@code failures} and writes both captures and the diff. */
    private static void compare(List<String> failures, String name, String expectedLabel, String actualLabel,
                                FidelityImage expected, FidelityImage actual, PixelTolerance tolerance) {
        PixelReport report = PixelComparator.compare(expected, actual, tolerance);
        if (!report.passed()) {
            String stem = name + "_" + actualLabel + "_vs_" + expectedLabel;
            expected.write(FAILURES.resolve(stem + "." + expectedLabel + ".png"));
            actual.write(FAILURES.resolve(stem + "." + actualLabel + ".png"));
            if (report.diff() != null) {
                report.diff().write(FAILURES.resolve(stem + ".diff.png"));
            }
            failures.add(name + ": " + actualLabel + " vs " + expectedLabel + ": " + report.summary());
        }
    }

    // ───────────────────────────── painting and readback ─────────────────────────────

    private static void paint(MasonryBackend backend, MasonryUI ui, UiDocumentView view, int frameW, int frameH,
                              int w, int h, float scale) {
        assertTrue(ui.beginFrame(frameW, frameH, 1f));
        try {
            backend.getCanvas().clear(BG);
            view.render(ui, w, h, scale, 1f);
        } finally {
            ui.endFrame();
        }
    }

    /** Unpremultiplied ARGB, rows top-down. */
    private static int[] rasterPixels(RasterMasonryBackend raster, int w, int h) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = raster.colorAt(x, y);
            }
        }
        return out;
    }

    /** 0xRRGGBBAA to 0xAARRGGBB; the backdrop is opaque, so premultiplied equals straight. */
    private static int[] argb(int[] rgba) {
        for (int i = 0; i < rgba.length; i++) {
            rgba[i] = rgba[i] >>> 8 | rgba[i] << 24;
        }
        return rgba;
    }

    /** Top-left {@code w x h} of a framebuffer as 0xRRGGBBAA, rows top-down. */
    private static int[] readTopDown(int framebuffer, int fbW, int fbH, boolean bottomLeft, int w, int h) {
        int previous = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glReadPixels(0, bottomLeft ? fbH - h : 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, previous);
        int[] raw = new int[w * h];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (buf.get(i * 4) & 0xFF) << 24 | (buf.get(i * 4 + 1) & 0xFF) << 16
                | (buf.get(i * 4 + 2) & 0xFF) << 8 | (buf.get(i * 4 + 3) & 0xFF);
        }
        if (!bottomLeft) {
            return raw;
        }
        int[] out = new int[w * h];
        for (int row = 0; row < h; row++) {
            System.arraycopy(raw, (h - 1 - row) * w, out, row * w, w);
        }
        return out;
    }
}

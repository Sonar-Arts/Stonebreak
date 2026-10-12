package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.masonry.MasonryUI;
import com.openmason.engine.ui.rendering.GpuMasonryBackend;
import com.openmason.engine.ui.rendering.MasonryBackend;
import com.openmason.engine.ui.rendering.OffscreenFramebuffer;
import com.openmason.engine.ui.rendering.UiRenderTarget;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * #287 AC: pseudo-state rules behave identically in preview and game. One document, hosted by
 * {@link GameUiDocuments} exactly as the game and the Open Mason preview host it, is painted
 * into the game window (framebuffer 0) and into the editor's offscreen preview framebuffer
 * after the same pointer/focus/enabled changes; the pixels must match exactly.
 *
 * <p>Needs a display: {@code -Dstonebreak.ui.gl=true}.
 */
class UiDocumentGlTest {

    private static final int W = 300;
    private static final int H = 200;
    private static final int BG = 0xFF203040;

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
        window = glfwCreateWindow(W, H, "ui-document-gl", NULL, NULL);
        assertTrue(window != NULL, "hidden window");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        try (InputStream in = UiDocumentGlTest.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
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

    private static UiNode node(String id, String type, Map<String, UiValue> props, Map<String, UiValue> style,
                               List<UiNode> children) {
        return new UiNode(id, id, type, 1, List.of(), props, style, null, List.of(), null, children, Map.of());
    }

    private static OmuiArchive document() {
        UiNode label = node("go_text", "Label", Map.of("text", UiValue.of("Resume Game")),
            Map.of("flex-grow", UiValue.of(1), "text-align", UiValue.of("center")), List.of());
        UiNode go = node("go", "Button", Map.of(), Map.of("width", UiValue.of(200), "height", UiValue.of(44)),
            List.of(label));
        UiNode stop = node("stop", "Button", Map.of(), Map.of("width", UiValue.of(200), "height", UiValue.of(44)),
            List.of());
        UiNode root = node("root", "Box", Map.of(), Map.of("width", UiValue.of("100%"), "height", UiValue.of("100%"),
            "align-items", UiValue.of("center"), "justify-content", UiValue.of("center"), "row-gap", UiValue.of(12)),
            List.of(go, stop));
        UiStyleSheet sheet = new UiStyleSheet("s", Map.of(), List.of(), List.of(
            new UiStyleSheet.StyleRule("Button:focus", Map.of("border-color", UiValue.of("#FFCC55"),
                "border-left-width", UiValue.of(3), "border-top-width", UiValue.of(3),
                "border-right-width", UiValue.of(3), "border-bottom-width", UiValue.of(3)), List.of(), Map.of()),
            new UiStyleSheet.StyleRule("Label:disabled", Map.of("color", UiValue.of("#787878")), List.of(), Map.of())),
            Map.of());
        return OmuiArchive.of(UiManifest.create("t:ui/gl_states", UiManifest.DocumentKind.SCREEN, "gl"),
            new UiDocument(root, List.of("s"), null, null, Map.of())).withStyle(sheet);
    }

    /** Hover the first button, focus the second, then disable the first. */
    private static final List<Consumer<UiDocumentView>> STEPS = List.of(
        v -> { },
        v -> v.pointerMove(W / 2f, H / 2f - 20),
        v -> {
            v.pointerLeave();
            v.focus(v.instance().find("stop"));
        },
        v -> v.instance().find("go").setEnabled(false));

    @Test
    void gameWindowAndPreviewFramebufferPaintEveryStateIdentically() throws Exception {
        SkijaUIBackend game = new SkijaUIBackend() {
            @Override
            public Typeface getMinecraftTypeface() {
                return typeface;
            }
        };
        game.initialize(UiRenderTarget.gameWindow(W, H, 1f));
        GpuMasonryBackend preview = new GpuMasonryBackend() {
            @Override
            public Typeface typeface() {
                return typeface;
            }
        };
        preview.initialize(UiRenderTarget.offscreen(0, 1, 1, 1f));
        OffscreenFramebuffer fbo = new OffscreenFramebuffer();
        fbo.ensureSize(W, H);
        preview.setTarget(fbo.target(1f));
        UiDocumentView gameView = GameUiDocuments.open(document(), () -> typeface, Map.of());
        UiDocumentView previewView = GameUiDocuments.open(document(), () -> typeface, Map.of());
        MasonryUI gameUi = new MasonryUI(game);
        MasonryUI previewUi = new MasonryUI(preview);
        try {
            int[] previous = null;
            int index = 0;
            for (Consumer<UiDocumentView> step : STEPS) {
                index++;
                step.accept(gameView);
                step.accept(previewView);
                paint(game, gameUi, gameView, W, H);
                int[] a = readTopDown(0, W, H, true);
                paint(preview, previewUi, previewView, fbo.allocatedWidth(), fbo.allocatedHeight());
                int[] b = readTopDown(fbo.framebufferId(), W, H, false);
                assertEquals(0, countDiff(a, b), "step " + index + ": game window and preview differ: " + where(a, b));
                if (previous != null) {
                    assertNotEquals(0, countDiff(previous, a), "each state change is visible");
                }
                previous = a;
            }
            UiElement stop = gameView.instance().find("stop");
            assertNotNull(stop);
            assertTrue(stop.hasState(UiElement.FOCUS));
        } finally {
            gameUi.dispose();
            previewUi.dispose();
            gameView.close();
            previewView.close();
            preview.releaseTargetSurface();
            fbo.close();
            preview.dispose();
            game.dispose();
        }
    }

    private static void paint(MasonryBackend backend, MasonryUI ui, UiDocumentView view, int fw, int fh) {
        assertTrue(ui.beginFrame(fw, fh, 1f));
        try {
            backend.getCanvas().clear(BG);
            view.render(ui, W, H, 1f, 1f);
        } finally {
            ui.endFrame();
        }
    }

    private static int[] readTopDown(int framebuffer, int w, int h, boolean bottomLeft) {
        int previous = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
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

    private static String where(int[] a, int[] b) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < a.length && shown < 8; i++) {
            if (a[i] != b[i]) {
                sb.append(String.format(" (%d,%d) %08x vs %08x", i % W, i / W, a[i], b[i]));
                shown++;
            }
        }
        return sb.toString();
    }

    private static int countDiff(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                n++;
            }
        }
        return n;
    }
}

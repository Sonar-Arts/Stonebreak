package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.rendering.GlStateSnapshot;
import com.openmason.engine.ui.rendering.RasterMasonryBackend;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Real-GL checks of the game's GL-backed draw providers: block icons render once per block and
 * size into the atlas and paint upright inside the element; previews render per frame into
 * per-element targets that are evicted when no longer prepared; neither phase leaves GL state
 * changed (a past Skia sampler leak smeared terrain). The cube renderer itself is replaced by a
 * painter that fills its viewport, so the test needs no world.
 *
 * <p>Needs a display: {@code -Dstonebreak.ui.gl=true}.
 */
@Tag("integration")
class ItemProvidersGlTest {

    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BG = 0xFF000000;

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
        window = glfwCreateWindow(64, 64, "item-providers", NULL, NULL);
        assertNotEquals(NULL, window, "hidden window");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        try (InputStream in = ItemProvidersGlTest.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
            typeface = FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        }
    }

    @AfterAll
    static void closeContext() {
        if (window != NULL) {
            GL.setCapabilities(null);
            glfwDestroyWindow(window);
            glfwTerminate();
            window = NULL;
        }
    }

    private static UiElement element(String type, Map<String, UiValue> props) {
        UiNode node = new UiNode("el", null, type, 1, List.of(), props, Map.of(), null, List.of(), null,
            List.of(), Map.of());
        UiManifest m = UiManifest.create("stonebreak:ui/test_gl_icons", UiManifest.DocumentKind.SCREEN, "Icons");
        OmuiArchive doc = OmuiArchive.of(m, new UiDocument(UiNode.of("root", "Box", List.of(node)), List.of(),
            null, null, Map.of()));
        return UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic()).find("el");
    }

    /** Odd caller state the providers must give back untouched. */
    private static void dirtyCallerState() {
        glClearColor(0.25f, 0.5f, 0.75f, 1f);
        glViewport(3, 4, 50, 40);
        glEnable(GL_SCISSOR_TEST);
        glScissor(1, 2, 30, 20);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        glDisable(GL_DEPTH_TEST);
        glActiveTexture(GL_TEXTURE3);
    }

    private static void resetCallerState() {
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glActiveTexture(GL_TEXTURE0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private static float[] clearColor() {
        float[] c = new float[4];
        glGetFloatv(GL_COLOR_CLEAR_VALUE, c);
        return c;
    }

    /** Paints {@code el} through {@code provider.draw} on a CPU raster frame (GL readback path). */
    private static RasterMasonryBackend paint(UiPaintDraw draw) {
        RasterMasonryBackend raster = new RasterMasonryBackend(64, 64, typeface, false);
        raster.beginFrame(64, 64, 1f);
        Canvas canvas = raster.getCanvas();
        canvas.clear(BG);
        draw.paint(canvas);
        raster.endFrame();
        return raster;
    }

    @FunctionalInterface
    private interface UiPaintDraw {
        void paint(Canvas canvas);
    }

    @Test
    void blockIconsRenderOncePerSizeAndPaintUprightInTheElement() {
        List<String> painted = new ArrayList<>();
        // fills the bottom half of the viewport red, the top half green: catches a y flip
        ItemIconAtlas atlas = new ItemIconAtlas((type, x, y, size) -> {
            painted.add(type.name() + "@" + size);
            glScissor(x, y, size, size / 2);
            glClearColor(1f, 0f, 0f, 1f);
            glClear(GL_COLOR_BUFFER_BIT);
            glScissor(x, y + size / 2, size, size - size / 2);
            glClearColor(0f, 1f, 0f, 1f);
            glClear(GL_COLOR_BUFFER_BIT);
        });
        ItemIconProvider provider = new ItemIconProvider(atlas, () -> typeface);
        UiElement icon = element("DrawProvider", Map.of("provider", UiValue.of(ItemIconProvider.ID),
            "item", UiValue.of("stonebreak:dirt")));
        UiRect rect = new UiRect(8, 8, 32, 32);
        try {
            dirtyCallerState();
            GlStateSnapshot before = GlStateSnapshot.capture();
            float[] clearBefore = clearColor();
            provider.prepare(icon, rect, 1f);
            provider.prepare(icon, rect, 1f);
            assertEquals(List.of("DIRT@32"), painted, "rendered once, then cached");
            assertTrue(before.sameAs(GlStateSnapshot.capture()), before.firstDifference(GlStateSnapshot.capture()));
            assertEquals(java.util.Arrays.toString(clearBefore), java.util.Arrays.toString(clearColor()));
            assertEquals(GL_NO_ERROR, glGetError());

            RasterMasonryBackend raster = paint(canvas -> provider.draw(canvas, icon, rect, 1f));
            try {
                assertEquals(GREEN, raster.colorAt(20, 10), "viewport top is the image top");
                assertEquals(RED, raster.colorAt(20, 37));
                assertEquals(BG, raster.colorAt(4, 4), "nothing outside the element");
                assertEquals(BG, raster.colorAt(44, 20));
            } finally {
                raster.dispose();
            }

            provider.prepare(icon, new UiRect(0, 0, 48, 48), 1f);
            assertEquals(List.of("DIRT@32", "DIRT@48"), painted, "a new size renders, never resamples");
            assertEquals(2, atlas.cachedIcons());
        } finally {
            atlas.close();
            resetCallerState();
        }
    }

    @Test
    void emptyAndUnknownSlotsNeverTouchGl() {
        ItemIconAtlas atlas = new ItemIconAtlas((type, x, y, size) -> {
            throw new AssertionError("nothing to render");
        });
        ItemIconProvider provider = new ItemIconProvider(atlas, () -> typeface);
        provider.prepare(element("ItemSlot", Map.of()), new UiRect(0, 0, 40, 40), 1f);
        provider.prepare(element("ItemSlot", Map.of("item", UiValue.of("stonebreak:nothing"))),
            new UiRect(0, 0, 40, 40), 1f);
        assertEquals(0, atlas.cachedIcons());
        atlas.close();
    }

    @Test
    void previewsRenderEveryFrameAndAreEvictedWhenNoLongerPrepared() {
        AtomicLong now = new AtomicLong(1_000L);
        int[] frames = {0};
        GlPreviewProvider preview = new GlPreviewProvider(now::get) {
            @Override
            protected boolean render(UiElement element, int width, int height) {
                frames[0]++;
                glClearColor(0f, 1f, 0f, 1f);
                glClear(GL_COLOR_BUFFER_BIT); // fills the target (no scissor)
                return true;
            }
        };
        UiElement a = element("DrawProvider", Map.of("provider", UiValue.of(EntityPreviewProvider.ID)));
        UiRect rect = new UiRect(16, 16, 24, 24);
        try {
            dirtyCallerState();
            GlStateSnapshot before = GlStateSnapshot.capture();
            preview.prepare(a, rect, 1f);
            preview.prepare(a, rect, 1f);
            assertEquals(2, frames[0], "live content renders every frame");
            assertTrue(before.sameAs(GlStateSnapshot.capture()), before.firstDifference(GlStateSnapshot.capture()));
            assertEquals(GL_NO_ERROR, glGetError());

            RasterMasonryBackend raster = paint(canvas -> preview.draw(canvas, a, rect, 1f));
            try {
                assertEquals(GREEN, raster.colorAt(20, 20));
                assertEquals(GREEN, raster.colorAt(39, 39));
                assertEquals(BG, raster.colorAt(41, 20));
            } finally {
                raster.dispose();
            }

            assertEquals(1, preview.liveTargets());
            now.addAndGet(GlPreviewProvider.EVICT_AFTER_NANOS + 1);
            UiElement b = element("DrawProvider", Map.of("provider", UiValue.of(EntityPreviewProvider.ID)));
            preview.prepare(b, rect, 1f); // same key in a new document: a fresh slot replaces the stale one
            assertEquals(1, preview.liveTargets());
        } finally {
            preview.close();
            resetCallerState();
        }
        assertEquals(0, preview.liveTargets());
    }
}

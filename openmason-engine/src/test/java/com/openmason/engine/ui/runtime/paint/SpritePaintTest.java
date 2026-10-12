package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.format.omt.OMTReader;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiSpriteSheet;
import com.openmason.engine.format.omui.UiSpriteSheet.Frame;
import com.openmason.engine.format.omui.UiSpriteSheet.Skin;
import com.openmason.engine.format.omui.UiSpriteSheet.Sprite;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.ProjectFolder;
import com.openmason.engine.ui.assets.SpriteFixtures;
import com.openmason.engine.ui.assets.export.ExportMode;
import com.openmason.engine.ui.assets.export.ExportPlanner;
import com.openmason.engine.ui.assets.export.UiExportService;
import com.openmason.engine.ui.masonry.textures.MTexture;
import com.openmason.engine.ui.masonry.textures.MTextureCache;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiPreferences;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.ImageInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.assets.SpriteFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprites painted through the real resolution chain (#294): the editor resolves a project, the
 * game an exported SBUI with no project, and both must paint the same pixels.
 */
@Tag("integration")
class SpritePaintTest {

    @TempDir
    Path tmp;

    private final MTextureCache cache = new MTextureCache();

    private ProjectAssetSource project(byte[] texture, byte[] sheet) throws Exception {
        ProjectFolder folder = new ProjectFolder(tmp.resolve("project"));
        folder.write(TEXTURE_HINT, UiBytes.copyOf(texture));
        folder.write(SHEET_HINT, UiBytes.copyOf(sheet));
        return new ProjectAssetSource(folder, "UI/");
    }

    /** The editor's host: the document's table over the project. */
    private RasterDocHost editor(OmuiArchive doc, ProjectAssetSource project, int w, int h) {
        ResolvedUiAssets assets = new ResolvedUiAssets(AssetResolver.forDocument(doc, List.of(project)),
                List.of(project), cache);
        return new RasterDocHost(doc, UiRuntimeContext.basic().withSource(assets), assets.paintHost(Map.of()), w, h);
    }

    /** The game's host: an exported SBUI's own table, no project at all. */
    private RasterDocHost game(SbuiArchive sbui, int w, int h) {
        ResolvedUiAssets assets = new ResolvedUiAssets(AssetResolver.forExport(sbui, List.of()), List.of(), cache);
        return new RasterDocHost(sbui.source(), UiRuntimeContext.basic().withSource(assets),
                assets.paintHost(Map.of()), w, h);
    }

    private static ResolvedUiAssets assets(RasterDocHost host) {
        return (ResolvedUiAssets) host.ui().context().source();
    }

    @Test
    void nineSlicePanelResizesWithIntactCorners() throws Exception {
        byte[] tex = frameTexture();
        byte[] sheet = sheetBytes(frameSheet());
        ProjectAssetSource project = project(tex, sheet);
        for (float scale : new float[]{1f, 2f, 1.25f}) {
            for (int[] size : new int[][]{{40, 20}, {100, 64}, {13, 9}}) {
                OmuiArchive doc = screen("t:ui/s", sharedRows(tex, sheet), SHEET_ID + "#panel", size[0], size[1]);
                try (RasterDocHost host = editor(doc, project, 320, 200).render(scale)) {
                    UiRect r = host.ui().find("panel").rect();
                    int corner = Math.max(1, Math.round(scale)) * 3; // snapped texels per corner
                    String at = size[0] + "x" + size[1] + " @" + scale;
                    int x0 = (int) r.x();
                    int y0 = (int) r.y();
                    int x1 = (int) r.right() - 1;
                    int y1 = (int) r.bottom() - 1;
                    assertEquals(TL, host.color(x0, y0), at);
                    assertEquals(TL, host.color(x0 + corner - 1, y0 + corner - 1), "whole corner " + at);
                    assertEquals(TR, host.color(x1, y0), at);
                    assertEquals(BL, host.color(x0, y1), at);
                    assertEquals(BR, host.color(x1, y1), at);
                    if (r.width() > 2 * corner + 2 && r.height() > 2 * corner + 2) {
                        assertEquals(EDGE, host.color((int) (r.x() + r.width() / 2), y0), "top edge " + at);
                        assertEquals(EDGE, host.color(x0, (int) (r.y() + r.height() / 2)), "left edge " + at);
                        assertEquals(CENTER, host.color((int) (r.x() + r.width() / 2), (int) (r.y() + r.height() / 2)),
                                "centre " + at);
                        assertEquals(EDGE, host.color(x0 + corner, y0), "the corner ends exactly " + at);
                    }
                    assertEquals(RasterDocHost.BACKDROP, host.color(x1 + 1, y0), "nothing bleeds outside " + at);
                }
            }
        }
    }

    @Test
    void belowTheMinimumSliceSizeTheCornersShrinkWithoutCorruption() throws Exception {
        byte[] tex = frameTexture();
        byte[] sheet = sheetBytes(frameSheet());
        OmuiArchive doc = screen("t:ui/s", sharedRows(tex, sheet), SHEET_ID + "#panel", 4, 4);
        try (RasterDocHost host = editor(doc, project(tex, sheet), 64, 64).render(1f)) {
            UiRect r = host.ui().find("panel").rect();
            assertEquals(TL, host.color((int) r.x(), (int) r.y()));
            assertEquals(BR, host.color((int) r.right() - 1, (int) r.bottom() - 1));
            for (int y = (int) r.y(); y < r.bottom(); y++) {
                for (int x = (int) r.x(); x < r.right(); x++) {
                    int c = host.color(x, y);
                    assertTrue(c == TL || c == TR || c == BL || c == BR, "only corner texels at (" + x + "," + y + ")");
                }
            }
        }
    }

    @Test
    void previewAndExportedGameMatchPixelForPixel() throws Exception {
        // Pixel art, a translucent layer stack, a nine-slice panel and an animated sprite in one screen.
        byte[] art = omt(12, 12,
                new Layer("base", true, 1f, png(12, 12, SpriteFixtures::framePixel)),
                new Layer("glaze", true, 0.5f, png(12, 12, (x, y) -> x < 6 ? 0x800000FF : 0x00000000)),
                new Layer("hidden", false, 1f, png(12, 12, (x, y) -> 0xFFFFFFFF)));
        UiSpriteSheet base = frameSheet();
        List<Sprite> sprites = new ArrayList<>(base.sprites());
        sprites.add(Sprite.of("blink", 0, 0, 3, 3).withFrames(List.of(new Frame(0, 0, 0.1), new Frame(9, 0, 0.1)),
                LoopMode.LOOP));
        byte[] sheet = sheetBytes(base.withSprites(sprites));
        ProjectAssetSource project = project(art, sheet);
        OmuiArchive doc = withChildren(screen("t:ui/s", sharedRows(art, sheet), SHEET_ID + "#panel", 60, 36),
                image("whole", TEXTURE_ID), image("anim", SHEET_ID + "#blink"));
        SbuiArchive sbui = UiExportService.export(doc, List.of(project), ExportPlanner.Request.of(ExportMode.COLLECT_ALL),
                null, List.of()).sbui();

        for (float scale : new float[]{1f, 1.5f, 2f}) {
            try (RasterDocHost editor = editor(doc, project, 200, 120); RasterDocHost game = game(sbui, 200, 120)) {
                editor.view.frame(0.15);
                game.view.frame(0.15);
                int[] a = editor.render(scale).pixels();
                int[] b = game.render(scale).pixels();
                assertArrayEquals(a, b, "editor preview and game paint identical pixels at " + scale + "x");
                UiRect anim = editor.ui().find("anim").rect();
                assertEquals(TR, editor.color((int) anim.x(), (int) anim.y()), "frame 2 shows at t=0.15");
            }
        }
    }

    @Test
    void layerCompositingIsStraightAlphaSourceOverLikeTheTextureEditorsFormat() throws Exception {
        byte[] art = omt(2, 1,
                new Layer("base", true, 1f, png(2, 1, (x, y) -> 0xFFFF0000)),
                new Layer("glaze", true, 0.5f, png(2, 1, (x, y) -> x == 0 ? 0x800000FF : 0xFF00FF00)));
        MTexture t = MTexture.loadFromOmtBytes("t", art);
        assertNotNull(t);
        try (Bitmap bm = new Bitmap()) {
            bm.allocPixels(new ImageInfo(2, 1, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL));
            assertTrue(t.image().readPixels(bm));
            byte[] px = bm.readPixels();
            // srcA = 128/255 * 0.5 = 0.251; red * 0.749 + blue * 0.251
            assertArrayEquals(new byte[]{(byte) 191, 0, 64, (byte) 255}, java.util.Arrays.copyOfRange(px, 0, 4));
            // an opaque layer at 50 %: half red, half green
            assertArrayEquals(new byte[]{(byte) 128, (byte) 128, 0, (byte) 255}, java.util.Arrays.copyOfRange(px, 4, 8));
        }
        assertNotNull(new OMTReader().read(art), "fixture is a real OMT");
        t.close();
    }

    @Test
    void skinsFollowTheControlState() throws Exception {
        byte[] tex = frameTexture();
        UiSpriteSheet sheet = frameSheet().withSprites(List.of(Sprite.of("idle", 0, 0, 3, 3),
                Sprite.of("hot", 9, 0, 3, 3), Sprite.of("off", 0, 9, 3, 3))).withSkins(List.of(
                new Skin("btn", "idle", "hot", null, "off", null, Map.of())));
        byte[] sheetBytes = sheetBytes(sheet);
        OmuiArchive doc = screen("t:ui/s", sharedRows(tex, sheetBytes), SHEET_ID + "#btn", 30, 30);
        try (RasterDocHost host = editor(doc, project(tex, sheetBytes), 100, 100).render(1f)) {
            UiElement el = host.ui().find("panel");
            int cx = (int) (el.rect().x() + 15);
            int cy = (int) (el.rect().y() + 15);
            assertEquals(TL, host.render(1f).color(cx, cy), "normal");
            host.view.pointerMove(cx, cy);
            assertEquals(TR, host.render(1f).color(cx, cy), "hover");
            host.ui().find("panel").setEnabled(false);
            assertEquals(BL, host.render(1f).color(cx, cy), "disabled beats hover");
            host.view.pointerLeave();
            host.ui().find("panel").setEnabled(true);
            host.ui().find("panel").setState(UiElement.ACTIVE, true);
            assertEquals(TL, host.render(1f).color(cx, cy), "no pressed region: falls back to normal");
        }
    }

    @Test
    void animatedSpritesFollowTheUiClockAndRepaintOnlyOnFrameChanges() throws Exception {
        byte[] tex = frameTexture();
        UiSpriteSheet sheet = frameSheet().withSprites(List.of(Sprite.of("blink", 0, 0, 3, 3)
                .withFrames(List.of(new Frame(0, 0, 0.1), new Frame(9, 0, 0.2)), LoopMode.LOOP)));
        byte[] sheetBytes = sheetBytes(sheet);
        OmuiArchive doc = screen("t:ui/s", sharedRows(tex, sheetBytes), SHEET_ID + "#blink", 12, 12);
        try (RasterDocHost host = editor(doc, project(tex, sheetBytes), 40, 40).render(1f)) {
            UiRect r = host.ui().find("panel").rect();
            assertEquals(TL, host.color((int) r.x(), (int) r.y()));
            assertTrue(host.ui().animating());
            host.ui().consumeDirtyRegion();
            host.view.frame(0.05);
            assertTrue(host.ui().consumeDirtyRegion().isEmpty(), "no repaint inside a frame");
            host.view.frame(0.06);
            UiRect dirty = host.ui().consumeDirtyRegion();
            assertFalse(dirty.isEmpty(), "the frame boundary dirties the sprite");
            assertTrue(dirty.contains(r.x() + 1, r.y() + 1));
            assertEquals(TR, host.render(1f).color((int) r.x(), (int) r.y()));
            host.view.frame(0.2);
            assertEquals(TL, host.render(1f).color((int) r.x(), (int) r.y()), "loops after 0.3 s");

            host.ui().setPreferences(new UiPreferences(true, 1f));
            host.view.frame(0.1);
            assertEquals(TL, host.render(1f).color((int) r.x(), (int) r.y()), "reduced motion holds the still");
            assertFalse(host.ui().animating());
        }
    }

    @Test
    void invalidRegionsDrawNothingAndAreReportedOnce() throws Exception {
        // The texture was cropped to 8x8 after the 12x12 regions were authored.
        byte[] cropped = omt(8, 8, new Layer("base", true, 1f, png(8, 8, SpriteFixtures::framePixel)));
        byte[] sheet = sheetBytes(frameSheet());
        OmuiArchive doc = withChildren(screen("t:ui/s", sharedRows(cropped, sheet), SHEET_ID + "#panel", 30, 30),
                image("dot", SHEET_ID + "#dot"), image("ghost", SHEET_ID + "#nope"));
        try (RasterDocHost host = editor(doc, project(cropped, sheet), 100, 100).render(1f)) {
            UiRect r = host.ui().find("panel").rect();
            for (int y = (int) r.y(); y < r.bottom(); y++) {
                assertEquals(RasterDocHost.BACKDROP, host.color((int) r.x() + 1, y), "the panel is not drawn");
            }
            UiRect dot = host.ui().find("dot").rect();
            assertEquals(CENTER, host.color((int) dot.x(), (int) dot.y()), "regions still inside keep drawing");
            host.render(1f).render(1f);
            List<com.openmason.engine.format.omui.UiDiagnostic> d = assets(host).spriteDiagnostics();
            assertEquals(1, d.stream().filter(x -> x.code() == Code.SPRITE_REGION_INVALID).count(), d::toString);
            assertTrue(d.stream().anyMatch(x -> x.code() == Code.TEXTURE_SIZE_CHANGED));
            assertTrue(d.stream().anyMatch(x -> x.code() == Code.UNKNOWN_SPRITE && x.message().contains("nope")));
        }
    }

    @Test
    void imagesMeasureAsTheSpritesLogicalSize() throws Exception {
        byte[] tex = frameTexture();
        UiSpriteSheet sheet = frameSheet().withSprites(List.of(Sprite.of("dot", 4, 4, 4, 4).withLogicalSize(8, 6)));
        byte[] sheetBytes = sheetBytes(sheet);
        OmuiArchive doc = withChildren(screen("t:ui/s", sharedRows(tex, sheetBytes), "none", 1, 1),
                image("icon", SHEET_ID + "#dot"));
        try (RasterDocHost host = editor(doc, project(tex, sheetBytes), 100, 100).render(2f)) {
            UiRect r = host.ui().find("icon").rect();
            assertEquals(16, r.width(), 1e-3);
            assertEquals(12, r.height(), 1e-3);
        }
    }

    @Test
    void savingTheTextureRefreshesAfterInvalidate() throws Exception {
        byte[] tex = frameTexture();
        byte[] sheet = sheetBytes(frameSheet());
        ProjectAssetSource project = project(tex, sheet);
        OmuiArchive doc = screen("t:ui/s", sharedRows(tex, sheet), SHEET_ID + "#panel", 30, 30);
        try (RasterDocHost host = editor(doc, project, 100, 100).render(1f)) {
            UiRect r = host.ui().find("panel").rect();
            assertEquals(TL, host.color((int) r.x(), (int) r.y()));
            // The Texture Editor saves a repainted texture over the shared file.
            project.folder().write(TEXTURE_HINT, UiBytes.copyOf(omt(12, 12, new Layer("base", true, 1f,
                    png(12, 12, (x, y) -> 0xFF00FFFF)))));
            assertEquals(TL, host.render(1f).color((int) r.x(), (int) r.y()), "resolved bytes are remembered");
            assets(host).invalidate();
            assertEquals(0xFF00FFFF, host.render(1f).color((int) r.x(), (int) r.y()), "the save shows after invalidate");
        }
    }

    @Test
    void animatedFramesNeverReReadOrReDecodeTheirSources() throws Exception {
        byte[] tex = frameTexture();
        UiSpriteSheet sheet = frameSheet().withSprites(List.of(Sprite.of("blink", 0, 0, 3, 3)
                .withFrames(List.of(new Frame(0, 0, 0.05), new Frame(9, 0, 0.05), new Frame(0, 9, 0.05)), LoopMode.LOOP)));
        byte[] sheetBytes = sheetBytes(sheet);
        ProjectAssetSource project = project(tex, sheetBytes);
        int[] finds = {0};
        com.openmason.engine.ui.assets.AssetSource counting = new com.openmason.engine.ui.assets.AssetSource() {
            @Override
            public String name() {
                return project.name();
            }

            @Override
            public com.openmason.engine.ui.assets.AssetOrigin origin() {
                return project.origin();
            }

            @Override
            public com.openmason.engine.ui.assets.ResolvedAsset find(String id, UiDependency.Kind kind, String hint)
                    throws java.io.IOException {
                finds[0]++;
                return project.find(id, kind, hint);
            }
        };
        OmuiArchive doc = screen("t:ui/s", sharedRows(tex, sheetBytes), SHEET_ID + "#blink", 12, 12);
        ResolvedUiAssets assets = new ResolvedUiAssets(AssetResolver.forDocument(doc, List.of(counting)),
                List.of(counting), cache);
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic().withSource(assets),
                assets.paintHost(Map.of()), 40, 40)) {
            host.render(1f);
            int afterFirst = finds[0];
            int textures = cache.size();
            UiImage.Region region = (UiImage.Region) assets.image(SHEET_ID + "#blink");
            for (int i = 0; i < 30; i++) {
                host.view.frame(0.02);
                host.render(1f);
            }
            assertEquals(afterFirst, finds[0], "frames never touch the sources again");
            assertEquals(textures, cache.size(), "one decoded texture serves every frame");
            assertSame(region, assets.image(SHEET_ID + "#blink"), "regions are resolved once per sheet revision");
            io.github.humbleui.skija.Image sub = region.texture().region(0, 0, 3, 3);
            assertSame(sub, region.texture().region(0, 0, 3, 3), "sub-images are cut once and owned by the texture");
            region.texture().close();
            assertTrue(sub.isClosed(), "closing the texture releases its sub-images");
        }
    }

    /** A fixed command buffer standing in for a Lua canvas. */
    private record Commands(float[] floats, String texture) implements com.openmason.engine.ui.runtime.UiCanvasCommands {
        @Override
        public int size() {
            return floats.length;
        }

        @Override
        public float get(int index) {
            return floats[index];
        }

        @Override
        public String string(int id) {
            return null;
        }

        @Override
        public String texture(int id) {
            return texture;
        }
    }

    @Test
    void canvasSpritesFollowTheClockReducedMotionAndStayInTheirRegion() throws Exception {
        byte[] tex = frameTexture();
        UiSpriteSheet sheet = frameSheet().withSprites(List.of(Sprite.of("dot", 4, 4, 4, 4),
                Sprite.of("blink", 0, 0, 3, 3).withFrames(List.of(new Frame(0, 0, 0.1), new Frame(9, 0, 0.1)),
                        LoopMode.LOOP)));
        byte[] sheetBytes = sheetBytes(sheet);
        UiNode blinkCanvas = new UiNode("cv", null, "Canvas", 1, List.of(), Map.of(),
                Map.of("width", UiValue.of(12), "height", UiValue.of(12)), null, List.of(), null, List.of(), Map.of());
        UiNode dotCanvas = new UiNode("dc", null, "Canvas", 1, List.of(), Map.of(),
                Map.of("width", UiValue.of(12), "height", UiValue.of(12)), null, List.of(), null, List.of(), Map.of());
        OmuiArchive doc = withChildren(screen("t:ui/s", sharedRows(tex, sheetBytes), "none", 1, 1), blinkCanvas,
                dotCanvas);
        try (RasterDocHost host = editor(doc, project(tex, sheetBytes), 60, 40)) {
            host.ui().attachCanvas("cv", new Commands(new float[]{5, 0, 0, 0, 12, 12, 0, 0, -1, -1, 1},
                    SHEET_ID + "#blink"));
            // asks for 40x40 texels of a 4x4 sprite: clamped to the sprite, never its neighbours
            host.ui().attachCanvas("dc", new Commands(new float[]{5, 0, 0, 0, 12, 12, 0, 0, 40, 40, 1},
                    SHEET_ID + "#dot"));
            host.render(1f);
            UiRect cv = host.ui().find("cv").rect();
            UiRect dc = host.ui().find("dc").rect();
            assertEquals(TL, host.color((int) cv.x(), (int) cv.y()));
            for (int y = 0; y < 12; y++) {
                for (int x = 0; x < 12; x++) {
                    assertEquals(CENTER, host.color((int) dc.x() + x, (int) dc.y() + y), "only the dot's texels");
                }
            }
            assertTrue(host.ui().animating(), "an animated canvas sprite schedules its next frame");
            host.ui().consumeDirtyRegion();
            host.view.frame(0.11);
            assertTrue(host.ui().consumeDirtyRegion().contains(cv.x() + 1, cv.y() + 1));
            assertEquals(TR, host.render(1f).color((int) cv.x(), (int) cv.y()));
            host.ui().setPreferences(new UiPreferences(true, 1f));
            assertEquals(TL, host.render(1f).color((int) cv.x(), (int) cv.y()), "reduced motion holds the still");
        }
    }

    @Test
    void layersNotSizedLikeTheCanvasAreScaledNotDroppedAndHiddenOnesLeaveATransparentTexture() {
        byte[] art = omt(4, 2, new Layer("small", true, 1f, png(2, 1, (x, y) -> x == 0 ? 0xFFFF0000 : 0xFF0000FF)));
        MTexture t = MTexture.decode("t", art);
        assertNotNull(t);
        assertEquals(4, t.width());
        try (Bitmap bm = new Bitmap()) {
            bm.allocPixels(new ImageInfo(4, 2, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL));
            assertTrue(t.image().readPixels(bm));
            byte[] px = bm.readPixels();
            assertEquals((byte) 255, px[0], "left half red");
            assertEquals((byte) 255, px[3 * 4 + 2], "right half blue");
        }
        t.close();
        byte[] hidden = omt(5, 3, new Layer("off", false, 1f, png(5, 3, (x, y) -> 0xFFFFFFFF)));
        MTexture empty = MTexture.decode("h", hidden);
        assertNotNull(empty, "a texture with nothing visible is transparent, not missing");
        assertArrayEquals(com.openmason.engine.ui.assets.TextureSizes.of(hidden), new int[]{empty.width(), empty.height()},
                "runtime decode and headless size checks agree");
        empty.close();
    }

    @Test
    void refreshReplacesOnlyChangedBytesAndReportsTheirStaleTextures() throws Exception {
        byte[] tex = frameTexture();
        byte[] sheet = sheetBytes(frameSheet());
        ProjectAssetSource project = project(tex, sheet);
        OmuiArchive doc = screen("t:ui/s", sharedRows(tex, sheet), SHEET_ID + "#panel", 30, 30);
        ResolvedUiAssets assets = new ResolvedUiAssets(AssetResolver.forDocument(doc, List.of(project)),
                List.of(project), cache);
        UiImage before = assets.image(SHEET_ID + "#panel");
        assertSame(before, assets.image(SHEET_ID + "#panel"), "resolved once per reference");
        assertSame(assets.image(TEXTURE_ID), assets.image(TEXTURE_ID), "whole textures too");
        assertFalse(assets.refresh().any(), "nothing changed on disk");
        assertSame(before, assets.image(SHEET_ID + "#panel"));

        byte[] repainted = omt(12, 12, new Layer("base", true, 1f, png(12, 12, (x, y) -> 0xFF00FFFF)));
        project.folder().write(TEXTURE_HINT, UiBytes.copyOf(repainted));
        ResolvedUiAssets.Refresh r = assets.refresh();
        assertEquals(java.util.Set.of(TEXTURE_ID), r.changed());
        assertEquals(java.util.Set.of("ui:" + UiBytes.copyOf(tex).sha256()), r.staleTextureKeys());
        UiImage.Region after = (UiImage.Region) assets.image(SHEET_ID + "#panel");
        assertEquals("ui:" + UiBytes.copyOf(repainted).sha256(), after.texture().resourcePath());
        int cached = cache.size();
        assets.forget(r.staleTextureKeys());
        assertEquals(cached - 1, cache.size(), "the superseded revision leaves the shared cache");
    }

    // ── documents ───────────────────────────────────────────────────────────

    private static UiNode image(String id, String source) {
        return new UiNode(id, null, "Image", 1, List.of(), Map.of("source", UiValue.of(source)), Map.of(), null,
                List.of(), null, List.of(), Map.of());
    }

    private static OmuiArchive withChildren(OmuiArchive doc, UiNode... extra) {
        UiNode root = doc.document().root();
        List<UiNode> kids = new ArrayList<>(root.children());
        kids.addAll(List.of(extra));
        UiNode newRoot = new UiNode(root.id(), root.name(), root.type(), root.typeVersion(), root.classes(),
                root.props(), Map.of("flex-direction", UiValue.of("row"), "align-items", UiValue.of("flex-start")), root.dataSource(), root.bindings(),
                root.instance(), kids, root.unknown());
        UiDocument d = doc.document();
        return doc.withDocument(new UiDocument(newRoot, d.styleSheets(), d.codeBehind(), d.component(), d.unknown()));
    }
}

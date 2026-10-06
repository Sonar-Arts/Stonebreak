package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiSpriteSheet;
import com.openmason.engine.format.omui.UiSpriteSheet.Fill;
import com.openmason.engine.format.omui.UiSpriteSheet.Frame;
import com.openmason.engine.format.omui.UiSpriteSheet.Sampling;
import com.openmason.engine.format.omui.UiSpriteSheet.Slice;
import com.openmason.engine.format.omui.UiSpriteSheet.Sprite;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.ProjectFolder;
import com.openmason.engine.ui.assets.SpriteFixtures;
import com.openmason.engine.ui.masonry.textures.MTextureCache;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.openmason.engine.ui.assets.SpriteFixtures.*;
import static com.openmason.engine.ui.runtime.UiDocs.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sprite golden images (#294): nine-slice panels at three sizes (stretched and tiled edges,
 * hollow centre), a translucent two-layer texture, pixel art at an integer scale, linear
 * filtering, tint and opacity, and an animated sprite on a fixed UI clock — at 1x and at the
 * fractional 1.25x where pixel-art snapping matters. Compared with committed PNGs in
 * {@code src/test/resources/ui/runtime/visual/}; regenerate with {@code -Dui.visual.write=true}
 * and review the diff. Same tolerance as {@link UiVisualFixtureTest}.
 */
class SpriteVisualFixtureTest {

    private static final boolean WRITE = Boolean.getBoolean("ui.visual.write");
    private static final Path DIR = Path.of("src/test/resources/ui/runtime/visual");

    @TempDir
    Path tmp;

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
    }

    private static byte[] texture() {
        // the 12x12 frame on the left, a translucent glaze over its right half (second layer, 75 %)
        return omt(12, 12,
            new Layer("base", true, 1f, png(12, 12, SpriteFixtures::framePixel)),
            new Layer("glaze", true, 0.75f, png(12, 12, (x, y) -> x >= 6 ? 0x9020C040 : 0x00000000)));
    }

    private static UiSpriteSheet sheet() {
        Sprite panel = Sprite.of("panel", 0, 0, 12, 12).withSlice(new Slice(3, 3, 3, 3), Fill.STRETCH, Fill.STRETCH);
        Sprite hollow = Sprite.of("hollow", 0, 0, 12, 12).withSlice(new Slice(3, 3, 3, 3), Fill.TILE, Fill.HIDDEN);
        Sprite pixel = Sprite.of("pixel", 0, 0, 6, 6).withLook(UiSpriteSheet.ScaleMode.INTEGER, null, null, 1);
        Sprite smooth = Sprite.of("smooth", 0, 0, 12, 12).withLook(null, Sampling.LINEAR, null, 1);
        Sprite tinted = Sprite.of("tinted", 3, 3, 6, 6).withLook(null, null, "#80C0FF", 0.6);
        Sprite blink = Sprite.of("blink", 0, 0, 3, 3).withLogicalSize(12, 12)
            .withFrames(List.of(new Frame(0, 0, 0.1), new Frame(9, 0, 0.1), new Frame(9, 9, 0.1)), LoopMode.PING_PONG);
        return new UiSpriteSheet(TEXTURE_ID, 12, 12, List.of(panel, hollow, pixel, smooth, tinted, blink), List.of(),
            Map.of());
    }

    private static OmuiArchive showcase(byte[] tex, byte[] sheet) {
        N row = box("row").style("flex-direction", "row").style("flex-wrap", "wrap").style("column-gap", 6)
            .style("row-gap", 6).style("padding-left", 6).style("padding-top", 6).style("align-items", "flex-start");
        int i = 0;
        for (int[] size : new int[][]{{12, 12}, {40, 24}, {90, 36}}) {
            row.kids(box("panel" + i).style("width", size[0]).style("height", size[1])
                .style("background-image", SHEET_ID + "#panel"));
            row.kids(box("hollow" + i++).style("width", size[0] + 7).style("height", size[1] + 5)
                .style("background-image", SHEET_ID + "#hollow"));
        }
        row.kids(node("pixel", "Image").prop("source", SHEET_ID + "#pixel").style("width", 31).style("height", 31));
        row.kids(box("smooth").style("width", 37).style("height", 29).style("background-image", SHEET_ID + "#smooth"));
        row.kids(box("tinted").style("width", 24).style("height", 24).style("background-color", "#404040")
            .style("background-image", SHEET_ID + "#tinted"));
        row.kids(node("blink", "Image").prop("source", SHEET_ID + "#blink"));
        row.kids(node("whole", "Image").prop("source", TEXTURE_ID).style("width", 36).style("height", 36));
        OmuiArchive doc = screen("t:ui/sprite_showcase", box("root").style("background-color", "#182028").kids(row))
            .withDependencies(new OmuiArchive.UiDependencies(sharedRows(tex, sheet), Map.of()));
        return declare(doc, List.of(UiFeatures.SPRITES));
    }

    @Test
    void showcaseAtOneX() throws IOException {
        compare("sprites_x1", 1f, 240, 140);
    }

    @Test
    void showcaseAtOnePointTwoFiveX() throws IOException {
        compare("sprites_x1_25", 1.25f, 300, 175);
    }

    private void compare(String name, float scale, int w, int h) throws IOException {
        byte[] tex = texture();
        byte[] sheet = sheetBytes(sheet());
        ProjectFolder folder = new ProjectFolder(tmp.resolve("p"));
        folder.write(TEXTURE_HINT, UiBytes.copyOf(tex));
        folder.write(SHEET_HINT, UiBytes.copyOf(sheet));
        ProjectAssetSource project = new ProjectAssetSource(folder, "UI/");
        OmuiArchive doc = showcase(tex, sheet);
        ResolvedUiAssets assets = new ResolvedUiAssets(AssetResolver.forDocument(doc, List.of(project)),
            List.of(project), new MTextureCache());
        int[] actual;
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic().withSource(assets),
            assets.paintHost(Map.of()), w, h)) {
            host.view.frame(0.15); // blink shows its second frame
            host.render(scale);
            assertTrue(assets.spriteDiagnostics().isEmpty(), assets.spriteDiagnostics()::toString);
            actual = host.pixels();
        }
        Path file = DIR.resolve(name + ".png");
        if (WRITE) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, w, h, actual, 0, w);
            Files.createDirectories(DIR);
            ImageIO.write(img, "png", file.toFile());
            return;
        }
        BufferedImage golden;
        try (InputStream in = SpriteVisualFixtureTest.class.getResourceAsStream("/ui/runtime/visual/" + name + ".png")) {
            assertNotNull(in, "missing fixture " + file + " (run with -Dui.visual.write=true)");
            golden = ImageIO.read(in);
        }
        assertTrue(golden.getWidth() == w && golden.getHeight() == h, name + " fixture size changed");
        int[] expected = golden.getRGB(0, 0, w, h, null, 0, w);
        int over = 0;
        for (int i = 0; i < expected.length; i++) {
            if (delta(expected[i], actual[i]) > 2) {
                over++;
            }
        }
        assertTrue(over <= expected.length / 1000, name + ": " + over + " pixels differ by more than 2 levels");
    }

    private static int delta(int a, int b) {
        int max = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            max = Math.max(max, Math.abs((a >>> shift & 0xFF) - (b >>> shift & 0xFF)));
        }
        return max;
    }
}

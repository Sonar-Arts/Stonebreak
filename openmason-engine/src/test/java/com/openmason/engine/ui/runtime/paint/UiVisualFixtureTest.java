package com.openmason.engine.ui.runtime.paint;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.ui.runtime.UiDocs.N;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.label;
import static com.openmason.engine.ui.runtime.UiDocs.node;
import static com.openmason.engine.ui.runtime.UiDocs.rule;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.UiDocs.sheet;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Visual fixtures (#287 validation): documents rendered on the raster reference path with the
 * pinned font, compared with committed PNGs in {@code src/test/resources/ui/runtime/visual/}.
 * They pin text measurement and placement (sizes, alignment, baselines) and scaled geometry
 * (the same screen at 1× and 1.5×). Regenerate after an intentional change with
 * {@code -Dui.visual.write=true}, then review the PNG diff.
 *
 * <p>Tolerance: a pixel may differ by 2 levels per channel (Skia point releases nudge glyph
 * coverage); at most 0.1% of pixels may exceed that.
 */
class UiVisualFixtureTest {

    private static final boolean WRITE = Boolean.getBoolean("ui.visual.write");
    private static final Path DIR = Path.of("src/test/resources/ui/runtime/visual");

    @BeforeEach
    void requireNative() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
    }

    /** A pause-menu-shaped screen: panel, title, stone buttons with centred labels, a disabled one. */
    static OmuiArchive menu() {
        N panel = box("panel").cls("panel").style("width", 300).style("height", 330).style("align-items", "center")
            .style("justify-content", "center").style("row-gap", 12);
        panel.kids(label("title", "GAME PAUSED").cls("title"));
        for (String text : new String[]{"Resume Game", "Statistics", "Settings", "Quit to Menu"}) {
            panel.kids(node(text.replace(' ', '_').toLowerCase(), "Button").cls("menu-button").kids(
                label(text.replace(' ', '_').toLowerCase() + "_text", text).cls("button-text")));
        }
        return screen("t:ui/visual_menu",
            box("root").style("width", "100%").style("height", "100%").style("justify-content", "center")
                .style("align-items", "center").style("background-color", "#203040").kids(panel),
            sheet("menu",
                rule(".panel", "background-color", "#4E4E4EF0", "border-color", "#1F1F1F", "border-left-width", 2,
                    "border-top-width", 2, "border-right-width", 2, "border-bottom-width", 2, "border-radius", 4),
                rule(".title", "font-size", 30, "color", "#FFDC64", "margin-bottom", 8),
                rule(".menu-button", "width", 220, "height", 44, "align-items", "stretch"),
                rule(".button-text", "flex-grow", 1, "text-align", "center")));
    }

    /** Sizes, horizontal alignment and a baseline row. */
    static OmuiArchive text() {
        N col = box("col").style("padding-left", 8).style("padding-top", 8).style("row-gap", 6)
            .style("align-items", "flex-start");
        for (int size : new int[]{12, 20, 36}) {
            col.kids(label("size" + size, "Stonebreak " + size + "px").style("font-size", size));
        }
        for (String align : new String[]{"left", "center", "right"}) {
            col.kids(box("frame_" + align).style("width", 240).style("height", 28).style("border-color", "#808080")
                .style("border-left-width", 1).style("border-top-width", 1).style("border-right-width", 1)
                .style("border-bottom-width", 1).kids(label("align_" + align, align).style("flex-grow", 1)
                    .style("text-align", align)));
        }
        col.kids(box("baseline").style("flex-direction", "row").style("align-items", "baseline").style("column-gap", 6)
            .kids(label("b1", "Base").style("font-size", 14), label("b2", "Line").style("font-size", 32),
                label("b3", "Up").style("font-size", 20).style("color", "#FFCC55")));
        return screen("t:ui/visual_text", box("root").style("background-color", "#101418").kids(col));
    }

    @Test
    void menuAtOneX() throws IOException {
        compare("menu_x1", menu(), 480, 400, 1f);
    }

    @Test
    void menuAtOneAndAHalfX() throws IOException {
        compare("menu_x1_5", menu(), 720, 600, 1.5f);
    }

    @Test
    void textMetrics() throws IOException {
        compare("text_metrics", text(), 320, 300, 1f);
    }

    private static void compare(String name, OmuiArchive doc, int w, int h, float scale) throws IOException {
        int[] actual;
        try (RasterDocHost host = new RasterDocHost(doc, UiRuntimeContext.basic(), w, h).render(scale)) {
            assertTrue(host.ui().diagnostics().isEmpty(), host.ui().diagnostics().toString());
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
        try (InputStream in = UiVisualFixtureTest.class.getResourceAsStream("/ui/runtime/visual/" + name + ".png")) {
            assertNotNull(in, "missing fixture " + file + " (run with -Dui.visual.write=true)");
            golden = ImageIO.read(in);
        }
        assertTrue(golden.getWidth() == w && golden.getHeight() == h, name + " fixture size changed");
        int[] expected = golden.getRGB(0, 0, w, h, null, 0, w);
        int over = 0;
        int worst = 0;
        for (int i = 0; i < expected.length; i++) {
            int d = delta(expected[i], actual[i]);
            worst = Math.max(worst, d);
            if (d > 2) {
                over++;
            }
        }
        assertTrue(over <= expected.length / 1000,
            name + ": " + over + " of " + expected.length + " pixels differ by more than 2 levels (worst " + worst + ")");
    }

    private static int delta(int a, int b) {
        int max = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            max = Math.max(max, Math.abs((a >>> shift & 0xFF) - (b >>> shift & 0xFF)));
        }
        return max;
    }
}

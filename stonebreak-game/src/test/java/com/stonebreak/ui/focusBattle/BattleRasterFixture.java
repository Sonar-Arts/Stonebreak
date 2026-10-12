package com.stonebreak.ui.focusBattle;

import com.openmason.engine.format.omui.UiValue;
import com.stonebreak.config.Settings;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.runtime.contracts.BattleHudContracts;
import com.openmason.engine.ui.masonry.MasonryUI;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Typeface;
import org.joml.Matrix4fc;

import java.io.InputStream;
import java.util.List;

/**
 * CPU-raster MasonryUI for the battle HUD's pixel tests — the same seam as the widget library's
 * {@code RasterUiFixture} (which is package-private to its own package): a backend subclass hands
 * out a bitmap canvas and the game's real typeface without ever calling {@code initialize()}, so no
 * OpenGL context is touched.
 *
 * <p>The background is snow-bright on purpose: the HUD is designed to be read over the arena's
 * snow, and translucent slate glass over a dark clear colour would hide differences a player sees.
 */
public final class BattleRasterFixture {

    /** Arena snow, the worst-case backdrop. */
    public static final int BACKGROUND = 0xFF91BAD4;

    private static final Typeface TYPEFACE = loadGameTypeface();

    public final int width;
    public final int height;
    public final Bitmap bitmap;
    public final Canvas canvas;
    public final MasonryUI ui;

    public BattleRasterFixture(int width, int height) {
        this.width = width;
        this.height = height;
        bitmap = new Bitmap();
        bitmap.allocPixels(ImageInfo.makeN32Premul(width, height));
        canvas = new Canvas(bitmap);
        canvas.clear(BACKGROUND);
        ui = new MasonryUI(new RasterBackend(canvas));
    }

    /** The battle HUD document's stack of {@code stonebreak:battle-hud} pieces, bottom to top. */
    private static final List<String> PIECES = List.of("underlays", "mode", "enemy", "party", "command", "overlays",
            "topmost");

    /**
     * Paints {@code screen}'s HUD as the game does (#301): the screen prepares the frame at {@code camera},
     * then every {@code stonebreak:battle-hud} piece is painted in the {@code battle_hud} document's stacking
     * order at the rect the {@code stonebreak:battle} contract publishes for it. The help strip is the
     * document's own text and is not drawn here. {@code uiScale} is the user's UI scale for this paint.
     */
    public BattleRasterFixture paintHud(FocusBattleScreen screen, float uiScale, Matrix4fc camera) {
        Settings settings = Settings.getInstance();
        float previous = settings.getUiScale();
        settings.setUiScale(uiScale);
        try {
            screen.prepareFrame(width, height, camera);
            UiValue.Obj model = (UiValue.Obj) BattleHudContracts.model(screen, width, height, null);
            boolean commandShown = model.get("commandShown") instanceof UiValue.Bool b && b.value();
            for (String piece : PIECES) {
                if (piece.equals("command") && !commandShown) continue;
                UiValue.Obj p = (UiValue.Obj) model.get(piece);
                UiValue.Obj params = (UiValue.Obj) p.get("params");
                float[] submenu = params.get("submenu") instanceof UiValue.Obj sub ? rect(sub) : null;
                screen.paintPiece(piece, ui, canvas, width, height, rect((UiValue.Obj) p.get("rect")), submenu);
            }
        } finally {
            settings.setUiScale(previous);
        }
        return this;
    }

    /**
     * The help strip the {@code stonebreak:battle} contract gives the document for {@code screen} this
     * frame ({@code shown}, {@code rect}, {@code text}, {@code color}, {@code size}, ...), its line fitted
     * with this fixture's game font. The document draws the strip itself.
     */
    public UiValue.Obj helpModel(FocusBattleScreen screen, float uiScale) {
        Settings settings = Settings.getInstance();
        float previous = settings.getUiScale();
        settings.setUiScale(uiScale);
        try {
            return (UiValue.Obj) ((UiValue.Obj) BattleHudContracts.model(screen, width, height, ui)).get("help");
        } finally {
            settings.setUiScale(previous);
        }
    }

    /** {@code color} (#RRGGBBAA) of a {@link #helpModel}: its alpha byte. */
    public static int helpAlpha(UiValue.Obj help) {
        String c = ((UiValue.Str) help.get("color")).value();
        return Integer.parseInt(c.substring(7, 9), 16);
    }

    private static float[] rect(UiValue.Obj r) {
        return new float[]{num(r, "x"), num(r, "y"), num(r, "w"), num(r, "h")};
    }

    private static float num(UiValue.Obj o, String k) {
        return o.get(k) instanceof UiValue.Num n ? (float) n.value() : 0f;
    }

    /** Pixels in the region that differ from the cleared background. */
    public int countPainted(int x0, int y0, int x1, int y1) {
        int painted = 0;
        for (int y = Math.max(0, y0); y < Math.min(height, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(width, x1); x++) {
                if (bitmap.getColor(x, y) != BACKGROUND) painted++;
            }
        }
        return painted;
    }

    public int countPainted(float[] rect) {
        return countPainted((int) rect[0], (int) rect[1], (int) Math.ceil(rect[0] + rect[2]),
                (int) Math.ceil(rect[1] + rect[3]));
    }

    /** Pixels in the region colored exactly {@code color} (probe interiors — AA blends edges). */
    public int countExactly(int color, int x0, int y0, int x1, int y1) {
        int count = 0;
        for (int y = Math.max(0, y0); y < Math.min(height, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(width, x1); x++) {
                if (bitmap.getColor(x, y) == color) count++;
            }
        }
        return count;
    }

    /** Pixels in the region where this fixture and {@code other} disagree. */
    public int diff(BattleRasterFixture other, int x0, int y0, int x1, int y1) {
        int differing = 0;
        for (int y = Math.max(0, y0); y < Math.min(height, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(width, x1); x++) {
                if (bitmap.getColor(x, y) != other.bitmap.getColor(x, y)) differing++;
            }
        }
        return differing;
    }

    public int diff(BattleRasterFixture other) {
        return diff(other, 0, 0, width, height);
    }

    public int diff(BattleRasterFixture other, float[] rect) {
        return diff(other, (int) rect[0], (int) rect[1], (int) Math.ceil(rect[0] + rect[2]),
                (int) Math.ceil(rect[1] + rect[3]));
    }

    private static Typeface loadGameTypeface() {
        try (InputStream in = BattleRasterFixture.class.getResourceAsStream("/fonts/Minecraft.ttf")) {
            if (in == null) throw new IllegalStateException("bundled game font missing: /fonts/Minecraft.ttf");
            return FontMgr.getDefault().makeFromData(Data.makeFromBytes(in.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("could not load the game typeface", e);
        }
    }

    /** The seam: raster canvas + classpath typeface, no GL, no initialize(). */
    private static final class RasterBackend extends SkijaUIBackend {
        private final Canvas canvas;

        RasterBackend(Canvas canvas) { this.canvas = canvas; }

        @Override public Canvas getCanvas() { return canvas; }
        @Override public boolean isAvailable() { return true; }
        @Override public Typeface getMinecraftTypeface() { return TYPEFACE; }
    }
}

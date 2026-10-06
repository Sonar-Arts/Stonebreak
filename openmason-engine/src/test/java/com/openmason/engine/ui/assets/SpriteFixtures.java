package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiFeatures;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiSpriteSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.SpriteSheetCodec;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Texture bytes, sprite sheets and sprite-using documents for the #294 tests. Not a test class. */
public final class SpriteFixtures {

    public static final String TEXTURE_ID = "t:ui/textures/frame";
    public static final String SHEET_ID = "t:ui/sprites/frame";
    public static final String TEXTURE_HINT = "textures/frame.omt";
    public static final String SHEET_HINT = "sprites/frame.sprites.json";

    private SpriteFixtures() {
    }

    /** A straight-alpha PNG whose pixel {@code (x, y)} is {@code argb.applyAsInt(x, y)}. */
    public static byte[] png(int w, int h, IntBinaryOperator argb) {
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

    /** One OMT layer: name, visibility, opacity and its PNG. */
    public record Layer(String name, boolean visible, float opacity, byte[] png) {
    }

    /** An OMT archive (the Texture Editor's save format) with {@code layers} bottom to top. */
    public static byte[] omt(int w, int h, Layer... layers) {
        StringBuilder manifest = new StringBuilder("{\"version\":\"1.0\",\"canvasSize\":{\"width\":" + w
                + ",\"height\":" + h + "},\"layers\":[");
        for (int i = 0; i < layers.length; i++) {
            manifest.append(i == 0 ? "" : ",").append("{\"name\":\"").append(layers[i].name()).append("\",\"visible\":")
                    .append(layers[i].visible()).append(",\"opacity\":").append(layers[i].opacity())
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

    /**
     * A 12x12 pixel-art frame: 3 px corners in four distinct colours, a blue edge band, a
     * grey centre. A slice of 3 on every side cuts exactly at the colour boundaries.
     */
    public static final int TL = 0xFFFF0000;
    public static final int TR = 0xFF00FF00;
    public static final int BL = 0xFFFFFF00;
    public static final int BR = 0xFFFF00FF;
    public static final int EDGE = 0xFF0000FF;
    public static final int CENTER = 0xFF808080;

    public static int framePixel(int x, int y) {
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

    public static byte[] frameTexture() {
        return omt(12, 12, new Layer("base", true, 1f, png(12, 12, SpriteFixtures::framePixel)));
    }

    /** The frame as a nine-slice sprite named {@code panel} plus a 4x4 {@code dot}, authored for 12x12. */
    public static UiSpriteSheet frameSheet() {
        UiSpriteSheet.Sprite panel = UiSpriteSheet.Sprite.of("panel", 0, 0, 12, 12)
                .withSlice(new UiSpriteSheet.Slice(3, 3, 3, 3), UiSpriteSheet.Fill.STRETCH, UiSpriteSheet.Fill.STRETCH);
        return new UiSpriteSheet(TEXTURE_ID, 12, 12, List.of(panel, UiSpriteSheet.Sprite.of("dot", 4, 4, 4, 4)),
                List.of(), Map.of());
    }

    public static byte[] sheetBytes(UiSpriteSheet sheet) {
        return SpriteSheetCodec.write(sheet);
    }

    /** Shared rows for the frame texture and a sheet that requires it, at their hints. */
    public static List<UiDependency> sharedRows(byte[] texture, byte[] sheet) {
        UiBytes t = UiBytes.copyOf(texture);
        UiBytes s = UiBytes.copyOf(sheet);
        UiDependency tex = UiDependency.shared(TEXTURE_ID, UiDependency.Kind.TEXTURE, t.sha256(), t.size(), TEXTURE_HINT);
        UiDependency sh = UiDependency.shared(SHEET_ID, UiDependency.Kind.SPRITES, s.sha256(), s.size(), SHEET_HINT);
        sh = UiAssetFixtures.with(sh, List.of(TEXTURE_ID), false, null);
        return List.of(sh, tex);
    }

    /** A screen whose root is a {@code w x h} box with {@code background-image: <ref>}. */
    public static OmuiArchive screen(String documentId, List<UiDependency> rows, String ref, double w, double h) {
        UiNode panel = new UiNode("panel", null, "Box", 1, List.of(), Map.of(),
                Map.of("background-image", UiValue.of(ref), "width", UiValue.of(w), "height", UiValue.of(h)), null,
                List.of(), null, List.of(), Map.of());
        UiNode root = new UiNode("root", null, "Box", 1, List.of(), Map.of(), Map.of(), null, List.of(), null,
                List.of(panel), Map.of());
        UiManifest m = UiManifest.create(documentId, UiManifest.DocumentKind.SCREEN, "");
        OmuiArchive doc = OmuiArchive.of(m, new UiDocument(root, List.of(), null, null, Map.of()))
                .withDependencies(new UiDependencies(new ArrayList<>(rows), Map.of()));
        return com.openmason.engine.ui.runtime.UiDocs.declare(doc, List.of(UiFeatures.SPRITES));
    }
}

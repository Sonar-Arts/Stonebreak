package com.openmason.engine.format.omui;

import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiSpriteSheet.Frame;
import com.openmason.engine.format.omui.UiSpriteSheet.Slice;
import com.openmason.engine.format.omui.UiSpriteSheet.Sprite;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Geometry checks of a sprite sheet against the texture it actually resolves to (#294). A
 * texture can be resized in the Texture Editor after its regions were authored; the regions are
 * then revalidated here instead of being sampled out of bounds.
 *
 * <p>What a runtime does with the result: a sprite whose region (or any frame) leaves the texture
 * is {@linkplain Check#drawable not drawn}; a sprite whose slice no longer fits its region is
 * drawn without slicing (stretched), never with overlapping or negative patches.
 */
public final class UiSpriteSheets {

    private UiSpriteSheets() {
    }

    /**
     * @param regionInvalid sprites whose rect or a frame lies outside the texture
     * @param sliceInvalid  sprites whose slice insets exceed their rect
     */
    public record Check(int textureWidth, int textureHeight, Set<String> regionInvalid, Set<String> sliceInvalid,
                        List<UiDiagnostic> diagnostics) {
        public Check {
            regionInvalid = Set.copyOf(regionInvalid);
            sliceInvalid = Set.copyOf(sliceInvalid);
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean drawable(String sprite) {
            return !regionInvalid.contains(sprite);
        }

        public boolean sliceUsable(String sprite) {
            return !sliceInvalid.contains(sprite) && !regionInvalid.contains(sprite);
        }

        public boolean ok() {
            return regionInvalid.isEmpty() && sliceInvalid.isEmpty();
        }
    }

    /**
     * Checks every region of {@code sheet} against a {@code texW × texH} texture.
     *
     * @param entry what the findings are about (the sheet's dependency id or file)
     */
    public static Check check(UiSpriteSheet sheet, int texW, int texH, String entry) {
        UiDiagnostics d = new UiDiagnostics();
        Set<String> region = new TreeSet<>();
        Set<String> slice = new TreeSet<>();
        if (sheet.width() != texW || sheet.height() != texH) {
            d.warning(Code.TEXTURE_SIZE_CHANGED, entry, "/width", "Texture '" + sheet.texture() + "' is now " + texW
                    + "x" + texH + "; the regions were authored for " + sheet.width() + "x" + sheet.height());
        }
        List<Sprite> sprites = sheet.sprites();
        for (int i = 0; i < sprites.size(); i++) {
            Sprite s = sprites.get(i);
            String ptr = "/sprites/" + i;
            if (!inside(s.x(), s.y(), s.w(), s.h(), texW, texH)) {
                region.add(s.name());
                d.error(Code.SPRITE_REGION_INVALID, entry, ptr, "Sprite '" + s.name() + "' (" + rect(s.x(), s.y(),
                        s.w(), s.h()) + ") lies outside the " + texW + "x" + texH + " texture; it is not drawn");
            }
            for (int f = 0; f < s.frames().size(); f++) {
                Frame fr = s.frames().get(f);
                if (!inside(fr.x(), fr.y(), s.w(), s.h(), texW, texH)) {
                    region.add(s.name());
                    d.error(Code.SPRITE_REGION_INVALID, entry, ptr + "/frames/" + f, "Frame " + f + " of '" + s.name()
                            + "' (" + rect(fr.x(), fr.y(), s.w(), s.h()) + ") lies outside the " + texW + "x" + texH
                            + " texture; the sprite is not drawn");
                }
            }
            String problem = sliceProblem(s);
            if (problem != null) {
                slice.add(s.name());
                d.error(Code.SPRITE_SLICE_INVALID, entry, ptr + "/slice", "Sprite '" + s.name() + "': " + problem
                        + "; it draws stretched until the slice is fixed");
            }
        }
        return new Check(texW, texH, region, slice, d.list());
    }

    /** @return why {@code s}'s insets cannot slice its rect, or null */
    public static String sliceProblem(Sprite s) {
        Slice sl = s.slice();
        if (sl.isNone()) {
            return null;
        }
        if (sl.left() + sl.right() > s.w()) {
            return "left + right insets (" + sl.left() + " + " + sl.right() + ") exceed the width " + s.w();
        }
        if (sl.top() + sl.bottom() > s.h()) {
            return "top + bottom insets (" + sl.top() + " + " + sl.bottom() + ") exceed the height " + s.h();
        }
        return null;
    }

    private static boolean inside(int x, int y, int w, int h, int texW, int texH) {
        return x >= 0 && y >= 0 && w > 0 && h > 0 && (long) x + w <= texW && (long) y + h <= texH;
    }

    private static String rect(int x, int y, int w, int h) {
        return x + "," + y + " " + w + "x" + h;
    }
}

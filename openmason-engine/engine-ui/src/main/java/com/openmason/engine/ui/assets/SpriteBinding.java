package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.UiDependency.Kind;
import com.openmason.engine.format.omui.UiSpriteSheet;

import java.util.List;
import java.util.function.Function;

/**
 * Which texture a sprite sheet draws from (#294). The binding is the sheet row's
 * {@code requires}: the one {@code texture} or {@code image} row it requires. Ids live in rows,
 * so a rename ({@code RelinkOperations.rename}) or an import remap ({@code -imported}) that
 * rewrites rows keeps every sheet bound without editing sheet bytes.
 *
 * <p>The sheet's own {@code texture} field records the id it was authored against. It is used
 * only when the row requires no texture at all and that id is a texture row of the same table
 * (a hand-made document); the binding then carries a warning, because embedding the sheet would
 * leave the texture behind.
 *
 * @param texture  the bound texture id, or null when unbound
 * @param problem  why the binding is missing or questionable, or null
 * @param fatal    true when there is no usable texture
 */
public record SpriteBinding(String texture, String problem, boolean fatal) {

    /**
     * @param sheetId the sprites row
     * @param rows    the table the sheet resolves through ({@code id → row}, null when absent)
     */
    public static SpriteBinding of(String sheetId, UiSpriteSheet sheet, Function<String, AssetRow> rows) {
        AssetRow row = rows.apply(sheetId);
        List<String> textures = row == null ? List.of() : row.requires().stream()
                .filter(id -> isTexture(rows.apply(id))).toList();
        if (textures.size() == 1) {
            return new SpriteBinding(textures.getFirst(), null, false);
        }
        if (textures.size() > 1) {
            return new SpriteBinding(null, "Sprite sheet '" + sheetId + "' requires several textures " + textures
                    + "; it must require exactly one", true);
        }
        if (sheet != null && isTexture(rows.apply(sheet.texture()))) {
            return new SpriteBinding(sheet.texture(), "Sprite sheet '" + sheetId + "' does not require its texture '"
                    + sheet.texture() + "'; embedding the sheet would leave it behind", false);
        }
        return new SpriteBinding(null, "Sprite sheet '" + sheetId + "' requires no texture"
                + (sheet == null ? "" : " and its authored texture '" + sheet.texture()
                + "' is not in the dependency table"), true);
    }

    private static boolean isTexture(AssetRow r) {
        return r != null && (r.kind() == Kind.TEXTURE || r.kind() == Kind.IMAGE);
    }
}

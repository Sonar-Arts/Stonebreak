package com.openmason.engine.ui.assets.export;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiSpriteRef;
import com.openmason.engine.format.omui.UiSpriteSheet;
import com.openmason.engine.format.omui.UiSpriteSheets;
import com.openmason.engine.format.omui.io.SpriteSheetCodec;
import com.openmason.engine.ui.assets.AssetRow;
import com.openmason.engine.ui.assets.DependencyRefs;
import com.openmason.engine.ui.assets.Resolution;
import com.openmason.engine.ui.assets.ResolvedAsset;
import com.openmason.engine.ui.assets.SpriteBinding;
import com.openmason.engine.ui.assets.TextureSizes;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Sprite checks before export (#294). Every sprite sheet the document lists must parse, be bound
 * to one texture of the document's table ({@link SpriteBinding}: at runtime everything resolves
 * through that one table) and fit that texture. Every {@code <sheet>#<name>} the document and the components it carries
 * use must name a region or skin that exists and lies inside the texture.
 *
 * <p>Errors (blocking): an unreadable sheet, an unlisted or unresolvable texture, a referenced
 * name the sheet lacks, a referenced region outside its texture or with a slice that no longer
 * fits. Warnings: a texture whose size changed since the regions were authored, problems in
 * regions nothing references, a sheet bound only through its authored texture id (its row does
 * not {@code require} it, so embedding the sheet would leave it behind).
 */
public final class SpriteChecks {

    private SpriteChecks() {
    }

    /** The export's sprite findings for an editor document (its own references only). */
    public static java.util.List<UiDiagnostic> check(OmuiArchive doc, Resolution resolution) {
        UiDiagnostics d = new UiDiagnostics();
        check(doc, java.util.List.of(), resolution, d);
        return d.list();
    }

    static void check(OmuiArchive doc, Collection<OmuiArchive> components, Resolution resolution, UiDiagnostics d) {
        Set<UiSpriteRef> used = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
        used.addAll(DependencyRefs.spriteRefs(doc));
        for (OmuiArchive c : components) {
            used.addAll(DependencyRefs.spriteRefs(c));
        }
        for (UiDependency row : doc.dependencies().entries()) {
            if (row.kind() != UiDependency.Kind.SPRITES) {
                continue;
            }
            ResolvedAsset asset = resolution.get(row.id());
            if (asset == null) {
                continue; // reported by resolution (missing / omitted)
            }
            UiDiagnostics parse = new UiDiagnostics();
            UiSpriteSheet sheet = SpriteSheetCodec.read(asset.bytes().toArray(), row.id(), parse);
            if (sheet == null || parse.hasErrors()) {
                d.error(Code.INVALID_VALUE, OmuiFormat.DEPENDENCIES, "", "Sprite sheet '" + row.id()
                        + "' cannot be read");
                parse.list().stream().filter(UiDiagnostic::isError).forEach(d::add);
                continue;
            }
            sheet(doc, row, sheet, used, resolution, d);
        }
        for (UiSpriteRef ref : used) {
            UiDependency row = doc.dependencies().find(ref.sheet());
            if (row == null || row.kind() != UiDependency.Kind.SPRITES) {
                d.error(Code.UNRESOLVED_REFERENCE, OmuiFormat.DEPENDENCIES, "", "'" + ref
                        + "' names a sprite sheet the document's dependency table does not list");
            }
        }
    }

    private static void sheet(OmuiArchive doc, UiDependency row, UiSpriteSheet sheet, Set<UiSpriteRef> used,
                              Resolution resolution, UiDiagnostics d) {
        SpriteBinding binding = SpriteBinding.of(row.id(), sheet, id -> {
            UiDependency r = doc.dependencies().find(id);
            return r == null ? null : AssetRow.of(r);
        });
        if (binding.fatal()) {
            d.error(Code.UNRESOLVED_REFERENCE, OmuiFormat.DEPENDENCIES, "", binding.problem());
            return;
        }
        if (binding.problem() != null) {
            d.warning(Code.UNRESOLVED_REFERENCE, OmuiFormat.DEPENDENCIES, "", binding.problem());
        }
        String texture = binding.texture();
        ResolvedAsset tex = resolution.get(texture);
        int[] size = tex == null ? null : TextureSizes.of(tex.bytes().toArray());
        if (size == null) {
            if (tex != null) {
                d.error(Code.INVALID_VALUE, OmuiFormat.DEPENDENCIES, "", "Texture '" + texture + "' of sprite sheet '"
                        + row.id() + "' is not a readable texture");
            }
            return;
        }
        UiSpriteSheets.Check check = UiSpriteSheets.check(sheet, size[0], size[1], row.id());
        Set<String> referenced = new TreeSet<>();
        for (UiSpriteRef ref : used) {
            if (!ref.sheet().equals(row.id())) {
                continue;
            }
            if (sheet.sprite(ref.name()).isPresent()) {
                referenced.add(ref.name());
            } else if (sheet.skin(ref.name()).isPresent()) {
                referenced.addAll(sheet.skin(ref.name()).get().regions());
            } else {
                d.error(Code.UNKNOWN_SPRITE, OmuiFormat.DEPENDENCIES, "", "'" + ref + "': sprite sheet '" + row.id()
                        + "' has no sprite or skin '" + ref.name() + "'");
            }
        }
        for (UiDiagnostic f : check.diagnostics()) {
            String sprite = spriteAt(sheet, f.pointer());
            boolean blocking = f.isError() && sprite != null && referenced.contains(sprite);
            if (blocking) {
                d.add(f);
            } else {
                d.warning(f.code(), f.entry(), f.pointer(), f.message()
                        + (f.isError() ? " (not referenced by this document)" : ""));
            }
        }
    }

    /** The sprite a check pointer ({@code /sprites/3/slice}) is about, or null. */
    private static String spriteAt(UiSpriteSheet sheet, String pointer) {
        if (!pointer.startsWith("/sprites/")) {
            return null;
        }
        String rest = pointer.substring("/sprites/".length());
        int slash = rest.indexOf('/');
        try {
            int i = Integer.parseInt(slash < 0 ? rest : rest.substring(0, slash));
            return i < sheet.sprites().size() ? sheet.sprites().get(i).name() : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

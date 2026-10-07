package com.openmason.engine.ui.assets.export;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
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
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Sprite checks before export (#294). Every sprite sheet the document lists must parse, be bound
 * to one texture of the document's table ({@link SpriteBinding}: at runtime everything resolves
 * through that one table) and fit that texture. Every {@code <sheet>#<name>} the document and the components it carries
 * use must name a region or skin that exists and lies inside the texture.
 *
 * <p>Errors (blocking): an unreadable sheet, an unlisted or unresolvable texture, a referenced
 * name the sheet lacks, a referenced region outside its texture or with a slice that no longer
 * fits. A component may embed its own sheet (and texture) for references the host's table does
 * not list; those resolve at runtime through the component's table and are checked the same way.
 * Warnings: a texture whose size changed since the regions were authored, problems in
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
        Set<UiSpriteRef> used = refSet();
        used.addAll(DependencyRefs.spriteRefs(doc));
        // A component's reference resolves the way the runtime resolves it: through the host's table
        // when that lists the sheet, else through a sheet the component embeds itself.
        Map<OmuiArchive, Set<UiSpriteRef>> ownUsed = new IdentityHashMap<>();
        for (OmuiArchive c : components) {
            for (UiSpriteRef ref : DependencyRefs.spriteRefs(c)) {
                if (isSheet(doc.dependencies().find(ref.sheet()))) {
                    used.add(ref);
                } else if (isSheet(c.dependencies().find(ref.sheet()))
                        && c.dependencies().find(ref.sheet()).mode() == UiDependency.Mode.EMBEDDED) {
                    ownUsed.computeIfAbsent(c, k -> refSet()).add(ref);
                } else {
                    d.error(Code.UNRESOLVED_REFERENCE, OmuiFormat.DEPENDENCIES, "", "'" + ref + "' (component "
                            + c.manifest().documentId() + ") names a sprite sheet neither the document's nor the"
                            + " component's dependency table lists");
                }
            }
        }
        Function<String, AssetRow> hostRows = id -> {
            UiDependency r = doc.dependencies().find(id);
            return r == null ? null : AssetRow.of(r);
        };
        Function<String, byte[]> hostBytes = id -> {
            ResolvedAsset a = resolution.get(id);
            return a == null ? null : a.bytes().toArray();
        };
        for (UiDependency row : doc.dependencies().entries()) {
            if (row.kind() != UiDependency.Kind.SPRITES) {
                continue;
            }
            ResolvedAsset asset = resolution.get(row.id());
            if (asset == null) {
                continue; // reported by resolution (missing / omitted)
            }
            UiSpriteSheet sheet = parse(row.id(), asset.bytes().toArray(), d);
            if (sheet != null) {
                sheet(row.id(), sheet, used, hostRows, hostBytes, d);
            }
        }
        for (UiSpriteRef ref : DependencyRefs.spriteRefs(doc)) {
            if (!isSheet(doc.dependencies().find(ref.sheet()))) {
                d.error(Code.UNRESOLVED_REFERENCE, OmuiFormat.DEPENDENCIES, "", "'" + ref
                        + "' names a sprite sheet the document's dependency table does not list");
            }
        }
        for (Map.Entry<OmuiArchive, Set<UiSpriteRef>> e : ownUsed.entrySet()) {
            component(doc, e.getKey(), e.getValue(), hostRows, hostBytes, d);
        }
    }

    /** Sheets a component embeds for its own references: checked against the textures it can reach. */
    private static void component(OmuiArchive doc, OmuiArchive c, Set<UiSpriteRef> used,
                                  Function<String, AssetRow> hostRows, Function<String, byte[]> hostBytes,
                                  UiDiagnostics d) {
        Function<String, AssetRow> rows = id -> {
            AssetRow host = hostRows.apply(id);
            if (host != null) {
                return host;
            }
            UiDependency r = c.dependencies().find(id);
            return r == null ? null : AssetRow.of(r);
        };
        Function<String, byte[]> bytes = id -> {
            if (doc.dependencies().find(id) == null) {
                UiDependency r = c.dependencies().find(id);
                if (r != null && r.mode() == UiDependency.Mode.EMBEDDED && r.entry() != null) {
                    UiBytes b = c.assets().get(r.entry());
                    return b != null && b.sha256().equals(r.sha256()) ? b.toArray() : null;
                }
            }
            return hostBytes.apply(id);
        };
        Set<String> sheets = new TreeSet<>();
        used.forEach(ref -> sheets.add(ref.sheet()));
        for (String id : sheets) {
            byte[] sheetBytes = bytes.apply(id);
            if (sheetBytes == null) {
                d.error(Code.MISSING_ENTRY, OmuiFormat.DEPENDENCIES, "", "Sprite sheet '" + id + "' embedded in component "
                        + c.manifest().documentId() + " is missing or does not match its recorded hash");
                continue;
            }
            UiSpriteSheet sheet = parse(id, sheetBytes, d);
            if (sheet != null) {
                sheet(id, sheet, used, rows, bytes, d);
            }
        }
    }

    private static UiSpriteSheet parse(String id, byte[] bytes, UiDiagnostics d) {
        UiDiagnostics parse = new UiDiagnostics();
        UiSpriteSheet sheet = SpriteSheetCodec.read(bytes, id, parse);
        if (sheet == null || parse.hasErrors()) {
            d.error(Code.INVALID_VALUE, OmuiFormat.DEPENDENCIES, "", "Sprite sheet '" + id + "' cannot be read");
            parse.list().stream().filter(UiDiagnostic::isError).forEach(d::add);
            return null;
        }
        return sheet;
    }

    private static boolean isSheet(UiDependency row) {
        return row != null && row.kind() == UiDependency.Kind.SPRITES;
    }

    private static Set<UiSpriteRef> refSet() {
        return new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
    }

    private static void sheet(String sheetId, UiSpriteSheet sheet, Set<UiSpriteRef> used,
                              Function<String, AssetRow> rows, Function<String, byte[]> textureBytes,
                              UiDiagnostics d) {
        SpriteBinding binding = SpriteBinding.of(sheetId, sheet, rows);
        if (binding.fatal()) {
            d.error(Code.UNRESOLVED_REFERENCE, OmuiFormat.DEPENDENCIES, "", binding.problem());
            return;
        }
        if (binding.problem() != null) {
            d.warning(Code.UNRESOLVED_REFERENCE, OmuiFormat.DEPENDENCIES, "", binding.problem());
        }
        String texture = binding.texture();
        byte[] tex = textureBytes.apply(texture);
        int[] size = tex == null ? null : TextureSizes.of(tex);
        if (size == null) {
            if (tex != null) {
                d.error(Code.INVALID_VALUE, OmuiFormat.DEPENDENCIES, "", "Texture '" + texture + "' of sprite sheet '"
                        + sheetId + "' is not a readable texture");
            }
            return;
        }
        UiSpriteSheets.Check check = UiSpriteSheets.check(sheet, size[0], size[1], sheetId);
        Set<String> referenced = new TreeSet<>();
        for (UiSpriteRef ref : used) {
            if (!ref.sheet().equals(sheetId)) {
                continue;
            }
            if (sheet.sprite(ref.name()).isPresent()) {
                referenced.add(ref.name());
            } else if (sheet.skin(ref.name()).isPresent()) {
                referenced.addAll(sheet.skin(ref.name()).get().regions());
            } else {
                d.error(Code.UNKNOWN_SPRITE, OmuiFormat.DEPENDENCIES, "", "'" + ref + "': sprite sheet '" + sheetId
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

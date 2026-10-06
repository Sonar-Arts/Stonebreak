package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostics;
import com.openmason.engine.format.omui.UiSpriteRef;
import com.openmason.engine.format.omui.UiSpriteSheet;
import com.openmason.engine.format.omui.io.SpriteSheetCodec;
import com.openmason.engine.format.sbt.SBTFormat;
import com.openmason.engine.format.sbt.SBTParser;
import com.openmason.engine.format.sbt.SBTSerializer;
import com.openmason.engine.ui.assets.AssetKinds;
import com.openmason.engine.ui.assets.AssetOrigin;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.AssetRow;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.ResolvedAsset;
import com.openmason.engine.ui.assets.SpriteBinding;
import com.openmason.engine.ui.assets.TextureSizes;
import com.openmason.engine.ui.assets.edit.AssetEdit;
import com.openmason.engine.ui.assets.edit.ProjectWrite;
import com.openmason.main.systems.project.ProjectLayout;
import com.openmason.main.systems.uiEditor.command.DocumentCommands;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.command.UiCommandException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * UI images for the editor (#294): what an image field can reference, project textures and
 * sprite sheets to add, which project file "Edit texture" opens, and the sprite-sheet commands.
 * Headless (no ImGui, no GL), so every rule here is unit-tested.
 *
 * <p>Pixels are never duplicated: textures stay the Texture Editor's OMT/SBT files, shared by
 * every document that lists them; sheets only hold region metadata.
 */
public final class UiImageAssets {

    private static final List<String> SKIPPED_DIRS = List.of("target", "build", "node_modules", "out");

    private UiImageAssets() {
    }

    // ── choices for an image field ──────────────────────────────────────────

    /**
     * One thing an image field can show.
     *
     * @param ref   the value to author ({@code id} or {@code sheet#name})
     * @param group what the picker groups it under (the sheet id for sprites)
     * @param skin  true for a skin (per-state regions)
     */
    public record ImageChoice(String ref, String group, String label, UiDependency.Kind kind, boolean skin) {
    }

    /** Textures and images of the table, then every sprite and skin of every sheet that resolves. */
    public static List<ImageChoice> choices(OmuiArchive doc, List<? extends AssetSource> sources) {
        List<ImageChoice> out = new ArrayList<>();
        for (UiDependency row : doc.dependencies().entries()) {
            if (row.kind() == UiDependency.Kind.TEXTURE || row.kind() == UiDependency.Kind.IMAGE) {
                out.add(new ImageChoice(row.id(), "Textures", row.id(), row.kind(), false));
            }
        }
        AssetResolver resolver = AssetResolver.forDocument(doc, sources);
        for (UiDependency row : doc.dependencies().entries()) {
            if (row.kind() != UiDependency.Kind.SPRITES) {
                continue;
            }
            UiSpriteSheet sheet = sheet(resolver, row.id());
            if (sheet == null) {
                continue;
            }
            for (UiSpriteSheet.Sprite s : sheet.sprites()) {
                out.add(new ImageChoice(new UiSpriteRef(row.id(), s.name()).toString(), row.id(), s.name(),
                        UiDependency.Kind.SPRITES, false));
            }
            for (UiSpriteSheet.Skin s : sheet.skins()) {
                out.add(new ImageChoice(new UiSpriteRef(row.id(), s.name()).toString(), row.id(), s.name(),
                        UiDependency.Kind.SPRITES, true));
            }
        }
        return out;
    }

    /** The parsed sheet {@code sheetId} resolves to, or null. */
    public static UiSpriteSheet sheet(AssetResolver resolver, String sheetId) {
        ResolvedAsset a = resolver.resolveOne(sheetId, new UiDiagnostics());
        if (a == null) {
            return null;
        }
        UiDiagnostics d = new UiDiagnostics();
        UiSpriteSheet sheet = SpriteSheetCodec.read(a.bytes().toArray(), sheetId, d);
        return d.hasErrors() ? null : sheet;
    }

    // ── project files ───────────────────────────────────────────────────────

    /** A texture, image or sprite sheet file in the project, with the id it gets when added. */
    public record ProjectImage(Path path, String relative, UiDependency.Kind kind, String id) {
    }

    /** Every {@code .omt}, {@code .sbt}, {@code .png} and {@code .sprites.json} in the project, by path. */
    public static List<ProjectImage> projectImages(UiProjectContext project) {
        Path root = project.root();
        if (root == null) {
            return List.of();
        }
        List<ProjectImage> out = new ArrayList<>();
        collect(project, root, root, out, 8);
        out.sort(Comparator.comparing(ProjectImage::relative));
        return out;
    }

    private static void collect(UiProjectContext project, Path root, Path dir, List<ProjectImage> out, int depth) {
        if (depth < 0) {
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    if (!name.startsWith(".") && !SKIPPED_DIRS.contains(name)) {
                        collect(project, root, p, out, depth - 1);
                    }
                    continue;
                }
                UiDependency.Kind kind = kindOf(name);
                if (kind != null) {
                    out.add(new ProjectImage(p, project.relative(p), kind, idFor(project, p, kind)));
                }
            }
        } catch (IOException ignored) {
            // unreadable folders are skipped; the picker lists what it can see
        }
    }

    /** The dependency kind a file name implies, or null for anything that is not a UI image. */
    public static UiDependency.Kind kindOf(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".sprites.json")) {
            return UiDependency.Kind.SPRITES;
        }
        if (n.endsWith(".omt") || n.endsWith(".sbt")) {
            return UiDependency.Kind.TEXTURE;
        }
        return n.endsWith(".png") ? UiDependency.Kind.IMAGE : null;
    }

    /**
     * The id a project file gets: its convention id under {@code UI/<ns>/...}, otherwise
     * {@code project:<path>} from its project-relative path (lowercased, other characters as
     * {@code _}); the row's hint keeps the real path either way.
     */
    public static String idFor(UiProjectContext project, Path file, UiDependency.Kind kind) {
        Path ui = project.uiDir();
        String conv = ui == null ? null : UiProjectContext.idFor(ui, file);
        if (conv != null && OmuiFormat.LOGICAL_ID.matcher(conv).matches()) {
            return conv;
        }
        String rel = project.relative(file);
        if (rel == null) {
            rel = file.getFileName().toString();
        }
        for (String ext : AssetKinds.extensions(kind)) {
            if (rel.toLowerCase(Locale.ROOT).endsWith(ext)) {
                rel = rel.substring(0, rel.length() - ext.length());
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : rel.toLowerCase(Locale.ROOT).split("/")) {
            String clean = seg.replaceAll("[^a-z0-9_.-]", "_").replaceAll("^[.]+|[.]+$", "_");
            if (!clean.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : "/").append(clean);
            }
        }
        return "project:" + (sb.isEmpty() ? "image" : sb);
    }

    // ── adding to a document ────────────────────────────────────────────────

    /** Adds a project texture or image as a shared row (nothing when the id is already listed). */
    public static UiCommand addImage(UiProjectContext project, ProjectImage file) throws IOException {
        return DocumentCommands.ensureDependency(project.sharedRow(file.id(), file.kind(), file.path()));
    }

    /**
     * Adds a sprite sheet and the texture it was authored against, the sheet row requiring the
     * texture row (so embedding and export carry both). The texture is found by its id: an
     * existing row, the project convention path, or a project image with that id.
     */
    public static UiCommand addSheet(UiProjectContext project, ProjectImage sheetFile) throws IOException,
            UiCommandException {
        UiSpriteSheet sheet = readSheet(sheetFile.path());
        String texId = sheet.texture();
        List<UiCommand> steps = new ArrayList<>();
        Path texFile = locateTexture(project, texId);
        if (texFile != null) {
            steps.add(DocumentCommands.ensureDependency(project.sharedRow(texId, kindOf(texFile.getFileName()
                    .toString()), texFile)));
        }
        UiDependency row = project.sharedRow(sheetFile.id(), UiDependency.Kind.SPRITES, sheetFile.path());
        UiDependency withTexture = new UiDependency(row.id(), row.kind(), row.version(), row.sha256(), row.size(),
                row.mode(), row.entry(), row.sourceHint(), List.of(texId), row.optional(), row.fallback(), row.license(),
                row.unknown());
        steps.add(DocumentCommands.ensureDependency(withTexture));
        steps.add(UiCommand.of("Check texture", ctx -> {
            if (ctx.doc().dependencies().find(texId) == null) {
                throw new UiCommandException("Sprite sheet " + sheetFile.relative() + " draws from '" + texId
                        + "', which is neither listed by this document nor found in the project");
            }
        }));
        return UiCommand.compound("Add sprite sheet " + sheetFile.id(), steps);
    }

    private static Path locateTexture(UiProjectContext project, String texId) {
        var source = project.projectSource();
        if (source == null) {
            return null;
        }
        for (UiDependency.Kind k : List.of(UiDependency.Kind.TEXTURE, UiDependency.Kind.IMAGE)) {
            for (String candidate : source.conventionCandidates(texId, k)) {
                Path p = project.root().resolve(candidate);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return projectImages(project).stream().filter(i -> i.id().equals(texId) && i.kind() != UiDependency.Kind.SPRITES)
                .map(ProjectImage::path).findFirst().orElse(null);
    }

    public static UiSpriteSheet readSheet(Path file) throws IOException {
        try {
            return SpriteSheetCodec.read(Files.readAllBytes(file), file.getFileName().toString());
        } catch (com.openmason.engine.format.omui.UiFormatException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ── sheet creation and saving ───────────────────────────────────────────

    /**
     * Creates an empty sprite sheet for texture row {@code textureId} at its convention path
     * ({@code UI/<ns>/<path>.sprites.json}, id {@code <texture id>_sprites}) and adds it to the
     * document, requiring the texture. An existing sheet file is reused, never overwritten.
     */
    public static UiCommand newSheet(OmuiArchive doc, UiProjectContext project, String textureId) throws IOException,
            UiCommandException {
        if (project.root() == null) {
            throw new UiCommandException("Open a project to create sprite sheets");
        }
        UiDependency tex = doc.dependencies().find(textureId);
        if (tex == null || (tex.kind() != UiDependency.Kind.TEXTURE && tex.kind() != UiDependency.Kind.IMAGE)) {
            throw new UiCommandException("'" + textureId + "' is not a texture of this document");
        }
        ResolvedAsset a = AssetResolver.forDocument(doc, project.sources()).resolveOne(textureId, new UiDiagnostics());
        int[] size = a == null ? null : TextureSizes.of(a.bytes().toArray());
        if (size == null) {
            throw new UiCommandException("Texture '" + textureId + "' does not resolve to a readable texture");
        }
        String sheetId = textureId + "_sprites";
        String rel = ProjectLayout.UI_DIR + "/" + AssetKinds.fileName(sheetId, UiDependency.Kind.SPRITES, null);
        Path file = project.root().resolve(rel);
        if (!Files.isRegularFile(file)) {
            UiSpriteSheet empty = new UiSpriteSheet(textureId, size[0], size[1], List.of(), List.of(), Map.of());
            Files.createDirectories(file.getParent());
            com.openmason.engine.format.omui.io.AtomicFiles.write(file, SpriteSheetCodec.write(empty));
        }
        UiDependency row = project.sharedRow(sheetId, UiDependency.Kind.SPRITES, file);
        UiDependency withTexture = new UiDependency(row.id(), row.kind(), row.version(), row.sha256(), row.size(),
                row.mode(), row.entry(), row.sourceHint(), List.of(textureId), row.optional(), row.fallback(),
                row.license(), row.unknown());
        return DocumentCommands.ensureDependency(withTexture);
    }

    /**
     * Saves an edited sheet as one undoable document step: a shared sheet is written to the
     * project file it resolves to (undo restores the old bytes); an embedded one replaces its
     * snapshot. Either way the row records the new hash and size.
     */
    public static UiCommand saveSheet(String sheetId, UiSpriteSheet sheet, UiProjectContext project) {
        return UiCommand.of("Edit sprite sheet " + sheetId, ctx -> {
            OmuiArchive doc = ctx.doc();
            UiDependency row = doc.dependencies().find(sheetId);
            if (row == null || row.kind() != UiDependency.Kind.SPRITES) {
                throw new UiCommandException("'" + sheetId + "' is not a sprite sheet of this document");
            }
            UiBytes next = UiBytes.copyOf(SpriteSheetCodec.write(sheet));
            List<ProjectWrite> writes = new ArrayList<>();
            OmuiArchive after;
            if (row.mode() == UiDependency.Mode.EMBEDDED) {
                after = doc.withAsset(row.entry(), next);
            } else {
                ResolvedAsset a = AssetResolver.forDocument(doc, project.sources()).resolveOne(sheetId,
                        new UiDiagnostics());
                if (a == null || a.origin() != AssetOrigin.PROJECT) {
                    throw new UiCommandException("Sprite sheet '" + sheetId + "' is not a project file"
                            + (a == null ? "" : " (it comes from " + a.describe() + ")") + "; embed it to edit it here");
                }
                writes.add(new ProjectWrite(a.location(), a.bytes(), next));
                after = doc;
            }
            UiDependency updated = new UiDependency(row.id(), row.kind(), row.version(), next.sha256(), next.size(),
                    row.mode(), row.entry(), row.sourceHint(), row.requires(), row.optional(), row.fallback(),
                    row.license(), row.unknown());
            List<UiDependency> rows = new ArrayList<>(doc.dependencies().entries().stream()
                    .map(r -> r.id().equals(sheetId) ? updated : r).toList());
            after = after.withDependencies(new OmuiArchive.UiDependencies(rows, doc.dependencies().unknown()));
            ctx.applyAssetEdit(new AssetEdit("Edit sprite sheet " + sheetId, doc, after, writes, List.of()));
        });
    }

    // ── "Edit texture" ──────────────────────────────────────────────────────

    /**
     * What "Edit texture" opens for an image reference.
     *
     * @param textureId the texture row (for a sprite, the sheet's bound texture)
     * @param file      the project file to edit, null when there is none (see {@code problem})
     * @param sbt       the file is an SBT: the Texture Editor edits its OMT source, then re-wraps it
     */
    public record EditableTexture(String textureId, Path file, boolean sbt, String problem) {
        public boolean ok() {
            return file != null;
        }
    }

    public static EditableTexture editableTexture(OmuiArchive doc, String ref, UiProjectContext project) {
        if (ref == null || ref.isBlank() || "none".equals(ref)) {
            return new EditableTexture(null, null, false, "No texture selected");
        }
        AssetResolver resolver = AssetResolver.forDocument(doc, project.sources());
        String texId = ref;
        UiSpriteRef sprite = UiSpriteRef.parse(ref);
        if (sprite != null) {
            UiSpriteSheet sheet = sheet(resolver, sprite.sheet());
            SpriteBinding b = SpriteBinding.of(sprite.sheet(), sheet, id -> {
                UiDependency r = doc.dependencies().find(id);
                return r == null ? null : AssetRow.of(r);
            });
            if (b.texture() == null) {
                return new EditableTexture(null, null, false, b.problem());
            }
            texId = b.texture();
        }
        UiDependency row = doc.dependencies().find(texId);
        if (row == null || (row.kind() != UiDependency.Kind.TEXTURE && row.kind() != UiDependency.Kind.IMAGE)) {
            return new EditableTexture(texId, null, false, "'" + texId + "' is not a texture of this document");
        }
        if (row.mode() == UiDependency.Mode.EMBEDDED) {
            return new EditableTexture(texId, null, false, "'" + texId + "' is an embedded snapshot; extract it to"
                    + " the project (UI Assets) to edit the shared texture");
        }
        ResolvedAsset a = resolver.resolveOne(texId, new UiDiagnostics());
        if (a == null) {
            return new EditableTexture(texId, null, false, "'" + texId + "' does not resolve");
        }
        if (a.origin() != AssetOrigin.PROJECT || project.root() == null) {
            return new EditableTexture(texId, null, false, "'" + texId + "' comes from " + a.describe()
                    + ", not from this project");
        }
        Path file = project.root().resolve(a.location());
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return new EditableTexture(texId, null, false, "'" + texId + "' is a flat PNG; the Texture Editor"
                    + " edits OMT/SBT textures (import the PNG into one to keep layers)");
        }
        return new EditableTexture(texId, file, name.endsWith(".sbt"), null);
    }

    /**
     * The layered source of an SBT: {@code <name>.omt} beside it. Written from the SBT's
     * embedded OMT when absent, so the Texture Editor keeps the SBT's layers; an existing
     * sibling is the author's source and is never overwritten.
     */
    public static Path omtSourceFor(Path sbt) throws IOException {
        String name = sbt.getFileName().toString();
        Path omt = sbt.resolveSibling(name.substring(0, name.length() - ".sbt".length()) + ".omt");
        if (!Files.isRegularFile(omt)) {
            byte[] bytes = new SBTParser().read(sbt).omtBytes();
            com.openmason.engine.format.omui.io.AtomicFiles.write(omt, bytes);
        }
        return omt;
    }

    /** Re-wraps {@code omt} into {@code sbt}, keeping the SBT's identity and metadata. */
    public static void rewrapSbt(Path sbt, Path omt) throws IOException {
        SBTFormat.Document m = new SBTParser().read(sbt).manifest();
        SBTFormat.ExportParameters p = new SBTFormat.ExportParameters();
        p.setTextureId(m.textureId());
        p.setTextureName(m.textureName());
        p.setTextureType(SBTFormat.TextureType.fromId(m.textureType()));
        p.setTexturePack(m.texturePack());
        p.setAuthor(m.author());
        p.setDescription(m.description());
        if (!new SBTSerializer().export(p, omt, sbt.toString())) {
            throw new IOException("Could not re-export " + sbt.getFileName() + " from " + omt.getFileName());
        }
    }
}

package com.openmason.engine.ui.assets.export;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiSpriteSheet;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiImporter;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.ProjectFolder;
import com.openmason.engine.ui.assets.Resolution;
import com.openmason.engine.ui.assets.SpriteFixtures;
import com.openmason.engine.ui.assets.UiAssetFixtures;
import com.openmason.engine.ui.assets.edit.AssetEdit;
import com.openmason.engine.ui.assets.edit.EmbedOperations;
import com.openmason.engine.ui.assets.edit.SbuiProjectImport;
import com.openmason.engine.ui.masonry.textures.MTextureCache;
import com.openmason.engine.ui.runtime.paint.ResolvedUiAssets;
import com.openmason.engine.ui.runtime.paint.UiImage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.openmason.engine.ui.assets.SpriteFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprite sheets through export, import and embedding (#294): invalid or missing regions are
 * reported before export, references survive OMUI/SBUI round trips and fresh-project imports,
 * and embedded snapshots stay put until refreshed.
 */
@Tag("integration")
class SpriteExportTest {

    @TempDir
    Path tmp;

    private ProjectAssetSource project(String name, byte[] texture, byte[] sheet) throws Exception {
        ProjectFolder folder = new ProjectFolder(tmp.resolve(name));
        folder.write(TEXTURE_HINT, UiBytes.copyOf(texture));
        folder.write(SHEET_HINT, UiBytes.copyOf(sheet));
        return new ProjectAssetSource(folder, "UI/");
    }

    private static OmuiArchive doc(byte[] texture, byte[] sheet, String ref) {
        return screen("t:ui/s", sharedRows(texture, sheet), ref, 40, 20);
    }

    private static boolean has(ExportPlan plan, Code code, boolean error) {
        return plan.diagnostics().stream().anyMatch(d -> d.code() == code && d.isError() == error);
    }

    @Test
    void validSpritesExportAndRoundTripThroughSbuiIntoAFreshProject() throws Exception {
        byte[] tex = frameTexture();
        byte[] sheet = sheetBytes(frameSheet());
        OmuiArchive doc = doc(tex, sheet, SHEET_ID + "#panel");
        ProjectAssetSource authoring = project("authoring", tex, sheet);

        UiExportService.Result result = UiExportService.export(doc, List.of(authoring),
                ExportPlanner.Request.of(ExportMode.COLLECT_ALL), null, List.of());
        assertFalse(result.plan().blocked(), result.plan().diagnostics()::toString);
        assertTrue(result.plan().diagnostics().stream().noneMatch(UiDiagnostic::isError));
        Path out = tmp.resolve("out/s.sbui");
        Files.createDirectories(out.getParent());
        UiExportService.save(result, out);

        SbuiArchive sbui = SbuiReader.read(out, SbuiReader.Options.RUNTIME).archive();
        assertEquals(doc.document(), sbui.source().document(), "the source tree, sprite refs included, is verbatim");

        // The game: sheet and texture resolve from the export alone, and the region draws.
        ResolvedUiAssets game = new ResolvedUiAssets(AssetResolver.forExport(sbui, List.of()), List.of(),
                new MTextureCache());
        assertInstanceOf(UiImage.Region.class, game.image(SHEET_ID + "#panel"));
        assertEquals(3, ((UiImage.Region) game.image(SHEET_ID + "#panel")).sprite().slice().left());
        assertTrue(game.spriteDiagnostics().isEmpty(), game.spriteDiagnostics()::toString);

        // Portable import opens with no project at all.
        OmuiArchive portable = SbuiImporter.importPortable(sbui).document();
        assertEquals(portable, OmuiReader.read(OmuiWriter.write(portable)).archive());
        Resolution r = AssetResolver.forDocument(portable, List.of()).resolveAll();
        assertTrue(r.complete(), r.diagnostics()::toString);
        assertEquals(List.of(TEXTURE_ID), portable.dependencies().find(SHEET_ID).requires());
    }

    @Test
    void missingAndInvalidRegionsBlockTheExport() throws Exception {
        byte[] tex = frameTexture();
        byte[] sheet = sheetBytes(frameSheet());

        ExportPlan unknown = ExportPlanner.plan(doc(tex, sheet, SHEET_ID + "#nope"),
                List.of(project("a", tex, sheet)), ExportPlanner.Request.of(ExportMode.SHARED));
        assertTrue(unknown.blocked());
        assertTrue(has(unknown, Code.UNKNOWN_SPRITE, true), unknown.diagnostics()::toString);

        // The texture was resized after the regions were authored.
        byte[] cropped = omt(8, 8, new Layer("base", true, 1f, png(8, 8, SpriteFixtures::framePixel)));
        ExportPlan resized = ExportPlanner.plan(doc(cropped, sheet, SHEET_ID + "#panel"),
                List.of(project("b", cropped, sheet)), ExportPlanner.Request.of(ExportMode.SHARED));
        assertTrue(resized.blocked(), "a referenced region outside the texture blocks");
        assertTrue(has(resized, Code.SPRITE_REGION_INVALID, true));
        assertTrue(has(resized, Code.TEXTURE_SIZE_CHANGED, false), "the size change is explained");
        UiFormatException e = assertThrows(UiFormatException.class, () -> UiExportService.export(
                doc(cropped, sheet, SHEET_ID + "#panel"), List.of(project("c", cropped, sheet)),
                ExportPlanner.Request.of(ExportMode.SHARED), null, List.of()));
        assertTrue(e.has(Code.SPRITE_REGION_INVALID));

        // Only the small dot is used: the out-of-bounds panel is a warning, not a block.
        ExportPlan dotOnly = ExportPlanner.plan(doc(cropped, sheet, SHEET_ID + "#dot"),
                List.of(project("d", cropped, sheet)), ExportPlanner.Request.of(ExportMode.SHARED));
        assertFalse(dotOnly.blocked(), dotOnly.diagnostics()::toString);
        assertTrue(has(dotOnly, Code.SPRITE_REGION_INVALID, false));
    }

    @Test
    void aSheetMustBeBoundToOneTextureOfTheTable() throws Exception {
        byte[] tex = frameTexture();
        byte[] sheet = sheetBytes(frameSheet());
        ProjectAssetSource p = project("p", tex, sheet);
        List<UiDependency> rows = sharedRows(tex, sheet);

        // No requires, but the authored texture is in the table: works, with a warning.
        UiDependency loose = UiAssetFixtures.with(rows.getFirst(), List.of(), false, null);
        ExportPlan warned = ExportPlanner.plan(screen("t:ui/s", List.of(loose, rows.get(1)), SHEET_ID + "#panel", 40, 20),
                List.of(p), ExportPlanner.Request.of(ExportMode.SHARED));
        assertFalse(warned.blocked(), warned.diagnostics()::toString);
        assertTrue(warned.diagnostics().stream().anyMatch(d -> !d.isError() && d.message().contains("embedding")));

        // The authored texture id is gone too: nothing to draw from.
        UiSpriteSheet foreign = frameSheet().withTexture("t:ui/textures/elsewhere", 12, 12);
        byte[] foreignBytes = sheetBytes(foreign);
        ProjectAssetSource q = project("q", tex, foreignBytes);
        List<UiDependency> qRows = sharedRows(tex, foreignBytes);
        ExportPlan blocked = ExportPlanner.plan(screen("t:ui/s", List.of(UiAssetFixtures.with(qRows.getFirst(),
                List.of(), false, null), qRows.get(1)), SHEET_ID + "#panel", 40, 20), List.of(q),
                ExportPlanner.Request.of(ExportMode.SHARED));
        assertTrue(blocked.blocked());
        assertTrue(blocked.diagnostics().stream().anyMatch(d -> d.isError() && d.message().contains("requires no texture")));
    }

    @Test
    void importRemapKeepsTheSheetBoundToItsTexture() throws Exception {
        byte[] tex = frameTexture();
        byte[] sheet = sheetBytes(frameSheet());
        SbuiArchive sbui = UiExportService.export(doc(tex, sheet, SHEET_ID + "#panel"),
                List.of(project("authoring", tex, sheet)), ExportPlanner.Request.of(ExportMode.COLLECT_ALL), null,
                List.of()).sbui();

        // The target project already has a different texture under the same id.
        ProjectFolder folder = new ProjectFolder(tmp.resolve("target"));
        folder.write(TEXTURE_HINT, UiBytes.copyOf(omt(4, 4, new Layer("x", true, 1f, png(4, 4, (x, y) -> 0xFF000000)))));
        ProjectAssetSource target = new ProjectAssetSource(folder, "UI/");
        SbuiProjectImport.Result imported = SbuiProjectImport.importIntoProject(sbui, target);
        OmuiArchive doc = imported.edit().apply(folder);
        assertEquals(TEXTURE_ID + "-imported", imported.remapped().get(TEXTURE_ID));
        assertEquals(List.of(TEXTURE_ID + "-imported"), doc.dependencies().find(SHEET_ID).requires());

        ResolvedUiAssets editor = new ResolvedUiAssets(AssetResolver.forDocument(doc, List.of(target)), List.of(target),
                new MTextureCache());
        UiImage.Region panel = (UiImage.Region) editor.image(SHEET_ID + "#panel");
        assertNotNull(panel, editor.spriteDiagnostics()::toString);
        assertEquals(12, panel.texture().width(), "bound to the imported 12x12 texture, not the project's 4x4");
        ExportPlan replan = ExportPlanner.plan(doc, List.of(target), ExportPlanner.Request.of(ExportMode.SHARED));
        assertTrue(replan.diagnostics().stream().noneMatch(UiDiagnostic::isError), replan.diagnostics()::toString);
    }

    @Test
    void embeddedSnapshotsStayStableUntilRefreshedWhileSharedOnesPropagate() throws Exception {
        byte[] tex = frameTexture();
        byte[] sheet = sheetBytes(frameSheet());
        ProjectAssetSource p = project("p", tex, sheet);
        OmuiArchive shared = doc(tex, sheet, SHEET_ID + "#panel");
        AssetEdit embed = EmbedOperations.embed(shared, SHEET_ID, List.of(p));
        OmuiArchive embedded = embed.after();
        assertEquals(UiDependency.Mode.EMBEDDED, embedded.dependencies().find(SHEET_ID).mode());
        assertEquals(UiDependency.Mode.EMBEDDED, embedded.dependencies().find(TEXTURE_ID).mode(),
                "embedding a sheet snapshots the texture it requires");

        // The Texture Editor saves a repainted texture over the shared file.
        byte[] repainted = omt(12, 12, new Layer("base", true, 1f, png(12, 12, (x, y) -> 0xFF00FFFF)));
        p.folder().write(TEXTURE_HINT, UiBytes.copyOf(repainted));

        UiImage.Region fromShared = (UiImage.Region) new ResolvedUiAssets(AssetResolver.forDocument(shared, List.of(p)),
                List.of(p), new MTextureCache()).image(SHEET_ID + "#panel");
        UiImage.Region fromSnapshot = (UiImage.Region) new ResolvedUiAssets(
                AssetResolver.forDocument(embedded, List.of(p)), List.of(p), new MTextureCache()).image(SHEET_ID + "#panel");
        assertNotEquals(fromShared.texture().resourcePath(), fromSnapshot.texture().resourcePath(),
                "the shared reference sees the save, the snapshot does not");
        assertEquals("ui:" + UiBytes.copyOf(tex).sha256(), fromSnapshot.texture().resourcePath());

        OmuiArchive refreshed = EmbedOperations.refresh(embedded, SHEET_ID, List.of(p)).after();
        UiImage.Region after = (UiImage.Region) new ResolvedUiAssets(AssetResolver.forDocument(refreshed, List.of()),
                List.of(), new MTextureCache()).image(SHEET_ID + "#panel");
        assertEquals("ui:" + UiBytes.copyOf(repainted).sha256(), after.texture().resourcePath(),
                "an explicit refresh picks the save up, texture included");
    }
}

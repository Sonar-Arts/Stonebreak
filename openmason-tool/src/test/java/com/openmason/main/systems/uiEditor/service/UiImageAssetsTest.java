package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiSpriteSheet;
import com.openmason.engine.format.omui.UiSpriteSheet.Skin;
import com.openmason.engine.format.omui.UiSpriteSheet.Slice;
import com.openmason.engine.format.omui.UiSpriteSheet.Sprite;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.omui.io.SpriteSheetCodec;
import com.openmason.engine.format.sbt.SBTFormat;
import com.openmason.engine.format.sbt.SBTParser;
import com.openmason.engine.format.sbt.SBTSerializer;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.paint.ResolvedUiAssets;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.io.TextureExporter;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiResources;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UI images in the editor (#294): Texture Editor files skin UI controls through sprite sheets,
 * edits to sheets are undoable document steps, "Edit texture" finds the right file (SBTs through
 * their layered OMT) and a texture save repaints every document that shares it.
 */
class UiImageAssetsTest {

    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;

    private static Typeface typeface;

    @TempDir
    Path project;

    @BeforeAll
    static void font() throws Exception {
        typeface = GameUiResources.loadTypeface();
    }

    @AfterAll
    static void release() {
        typeface.close();
    }

    /** A 6x3 texture saved by the Texture Editor's own writer: red, green, blue 2x3 bands. */
    private Path saveTexture(String relative, int left, int middle, int right) throws Exception {
        LayerManager layers = new LayerManager(6, 3);
        PixelCanvas c = layers.getActiveLayer().getCanvas();
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 6; x++) {
                int argb = x < 2 ? left : x < 4 ? middle : right;
                c.setPixel(x, y, PixelCanvas.packRGBA(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24));
            }
        }
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        assertTrue(new TextureExporter().exportToOMT(layers, file.toString()));
        return file;
    }

    private UiProjectContext ctx() {
        return new UiProjectContext(() -> project);
    }

    private static UiSpriteSheet bands() {
        return new UiSpriteSheet("test:ui/textures/bands", 6, 3, List.of(Sprite.of("left", 0, 0, 2, 3),
            Sprite.of("middle", 2, 0, 2, 3), Sprite.of("right", 4, 0, 2, 3)),
            List.of(new Skin("button", "left", "middle", null, "right", null, Map.of())), Map.of());
    }

    private UiEditorDocument screenWithSheet(UiProjectContext ctx, String sheetRel) throws Exception {
        UiDocumentService service = new UiDocumentService(ctx, new UiRecoveryService(project.resolve(".rec")));
        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/s", "S");
        UiImageAssets.ProjectImage sheet = UiImageAssets.projectImages(ctx).stream()
            .filter(p -> p.relative().equals(sheetRel)).findFirst().orElseThrow();
        assertTrue(doc.execute(UiImageAssets.addSheet(ctx, sheet)), doc.lastMessage());
        assertTrue(doc.execute(NodeCommands.create("Button", NodeLocation.endOf("root"), n -> n)));
        String button = doc.primary();
        assertTrue(doc.execute(NodeCommands.setStyle(List.of(button), Map.of("width", UiValue.of(60),
            "height", UiValue.of(30), "background-image", UiValue.of(sheet.id() + "#button")), "skin")), doc.lastMessage());
        return doc;
    }

    @Test
    void projectFilesGetConventionIdsAndSheetsBringTheirTexture() throws Exception {
        saveTexture("UI/test/ui/textures/bands.omt", RED, GREEN, BLUE);
        Path sheetFile = project.resolve("UI/test/ui/sprites/bands.sprites.json");
        Files.createDirectories(sheetFile.getParent());
        Files.write(sheetFile, SpriteSheetCodec.write(bands()));
        saveTexture("Textures/loose icon.omt", RED, RED, RED);
        UiProjectContext ctx = ctx();

        List<UiImageAssets.ProjectImage> images = UiImageAssets.projectImages(ctx);
        assertEquals(List.of("Textures/loose icon.omt", "UI/test/ui/sprites/bands.sprites.json",
            "UI/test/ui/textures/bands.omt"), images.stream().map(UiImageAssets.ProjectImage::relative).toList());
        assertEquals("project:textures/loose_icon", images.get(0).id(), "off-convention files get a project id");
        assertEquals("test:ui/sprites/bands", images.get(1).id());
        assertEquals(UiDependency.Kind.SPRITES, images.get(1).kind());

        UiEditorDocument doc = screenWithSheet(ctx, "UI/test/ui/sprites/bands.sprites.json");
        OmuiArchive a = doc.archive();
        assertEquals(List.of("test:ui/textures/bands"), a.dependencies().find("test:ui/sprites/bands").requires());
        assertNotNull(a.dependencies().find("test:ui/textures/bands"), "the texture row came along");
        assertTrue(a.manifest().requires().contains("ui-sprites"), "the editor declares the feature");

        List<String> refs = UiImageAssets.choices(a, ctx.sources()).stream().map(UiImageAssets.ImageChoice::ref).toList();
        assertEquals(List.of("test:ui/textures/bands", "test:ui/sprites/bands#left", "test:ui/sprites/bands#middle",
            "test:ui/sprites/bands#right", "test:ui/sprites/bands#button"), refs);
    }

    @Test
    void aTextureEditorSaveRepaintsEveryDocumentSharingTheTexture() throws Exception {
        Path tex = saveTexture("UI/test/ui/textures/bands.omt", RED, GREEN, BLUE);
        Path sheetFile = project.resolve("UI/test/ui/sprites/bands.sprites.json");
        Files.createDirectories(sheetFile.getParent());
        Files.write(sheetFile, SpriteSheetCodec.write(bands()));
        UiProjectContext ctx = ctx();
        UiEditorDocument first = screenWithSheet(ctx, "UI/test/ui/sprites/bands.sprites.json");
        UiEditorDocument second = screenWithSheet(ctx, "UI/test/ui/sprites/bands.sprites.json");

        try (UiDocumentView a = GameUiDocuments.open(first.archive(), ctx.sources(), () -> typeface, Map.of());
             UiDocumentView b = GameUiDocuments.open(second.archive(), ctx.sources(), () -> typeface, Map.of())) {
            assertEquals(RED, centre(a), "the skin's normal state is the red band");
            assertEquals(RED, centre(b));

            // Repaint the texture in the Texture Editor and save over the same file.
            saveTexture("UI/test/ui/textures/bands.omt", 0xFFFFFF00, GREEN, BLUE);
            for (UiDocumentView v : List.of(a, b)) {
                ((ResolvedUiAssets) v.instance().context().source()).invalidate(); // what textureSaved() does
            }
            assertEquals(0xFFFFFF00, centre(a), "every shared reference updates");
            assertEquals(0xFFFFFF00, centre(b));

            // The skin follows the control's state.
            String key = a.instance().root().children().getFirst().key();
            a.instance().find(key).setEnabled(false);
            assertEquals(BLUE, centre(a), "disabled state draws the right band");
        }
        assertTrue(Files.isRegularFile(tex));
    }

    private BufferedImage frame(UiDocumentView view) {
        return UiSnapshot.render(view, typeface, 200, 100, 1f);
    }

    private int centre(UiDocumentView view) {
        BufferedImage img = frame(view);
        UiRect r = view.instance().root().children().getFirst().rect();
        return img.getRGB((int) (r.x() + r.width() / 2), (int) (r.y() + r.height() / 2));
    }

    @Test
    void sheetEditsAreUndoableDocumentStepsThatWriteTheProjectFile() throws Exception {
        saveTexture("UI/test/ui/textures/bands.omt", RED, GREEN, BLUE);
        Path sheetFile = project.resolve("UI/test/ui/sprites/bands.sprites.json");
        Files.createDirectories(sheetFile.getParent());
        byte[] original = SpriteSheetCodec.write(bands());
        Files.write(sheetFile, original);
        UiProjectContext ctx = ctx();
        UiEditorDocument doc = screenWithSheet(ctx, "UI/test/ui/sprites/bands.sprites.json");
        doc.setProject(new com.openmason.engine.ui.assets.ProjectFolder(project));

        SpriteSheetDraft draft = new SpriteSheetDraft("test:ui/sprites/bands", bands());
        draft.setSprite("left", draft.sheet().sprite("left").orElseThrow().withSlice(new Slice(1, 1, 0, 1), null, null),
            null);
        assertTrue(draft.rename("left", "frame"));
        assertEquals("frame", draft.sheet().skin("button").orElseThrow().normal(), "skins follow a rename");
        assertTrue(doc.execute(UiImageAssets.saveSheet("test:ui/sprites/bands", draft.sheet(), ctx)), doc.lastMessage());

        UiSpriteSheet written = SpriteSheetCodec.read(Files.readAllBytes(sheetFile), "s");
        assertEquals(draft.sheet(), written);
        assertEquals(UiBytes.copyOf(Files.readAllBytes(sheetFile)).sha256(),
            doc.archive().dependencies().find("test:ui/sprites/bands").sha256(), "the row records the new hash");

        assertTrue(doc.undo());
        assertArrayEquals(original, Files.readAllBytes(sheetFile), "undo restores the file");
        assertTrue(doc.redo());
        assertEquals(draft.sheet(), SpriteSheetCodec.read(Files.readAllBytes(sheetFile), "s"));
    }

    @Test
    void newSheetsAreCreatedAtTheConventionPathForATexture() throws Exception {
        saveTexture("UI/test/ui/textures/bands.omt", RED, GREEN, BLUE);
        UiProjectContext ctx = ctx();
        UiDocumentService service = new UiDocumentService(ctx, new UiRecoveryService(project.resolve(".rec")));
        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/s", "S");
        UiImageAssets.ProjectImage tex = UiImageAssets.projectImages(ctx).getFirst();
        assertTrue(doc.execute(UiImageAssets.addImage(ctx, tex)));
        assertTrue(doc.execute(UiImageAssets.newSheet(doc.archive(), ctx, tex.id())));
        Path file = project.resolve("UI/test/ui/textures/bands_sprites.sprites.json");
        UiSpriteSheet created = SpriteSheetCodec.read(Files.readAllBytes(file), "s");
        assertEquals(6, created.width());
        assertEquals(3, created.height());
        assertEquals(List.of(tex.id()), doc.archive().dependencies().find(tex.id() + "_sprites").requires());
    }

    @Test
    void editTextureFindsTheProjectFileOrSaysWhyNot() throws Exception {
        Path tex = saveTexture("UI/test/ui/textures/bands.omt", RED, GREEN, BLUE);
        Path sheetFile = project.resolve("UI/test/ui/sprites/bands.sprites.json");
        Files.createDirectories(sheetFile.getParent());
        Files.write(sheetFile, SpriteSheetCodec.write(bands()));
        UiProjectContext ctx = ctx();
        UiEditorDocument doc = screenWithSheet(ctx, "UI/test/ui/sprites/bands.sprites.json");

        UiImageAssets.EditableTexture viaSprite = UiImageAssets.editableTexture(doc.archive(),
            "test:ui/sprites/bands#middle", ctx);
        assertTrue(viaSprite.ok(), viaSprite.problem());
        assertEquals(tex.toAbsolutePath().normalize(), viaSprite.file().toAbsolutePath().normalize());
        assertFalse(viaSprite.sbt());

        doc.setProject(new com.openmason.engine.ui.assets.ProjectFolder(project));
        assertTrue(doc.execute(com.openmason.main.systems.uiEditor.command.DocumentCommands.assetEdit(
            com.openmason.engine.ui.assets.edit.EmbedOperations.embed(doc.archive(), "test:ui/textures/bands",
                ctx.sources()))));
        UiImageAssets.EditableTexture embedded = UiImageAssets.editableTexture(doc.archive(), "test:ui/textures/bands", ctx);
        assertFalse(embedded.ok());
        assertTrue(embedded.problem().contains("embedded snapshot"), embedded.problem());
        assertFalse(UiImageAssets.editableTexture(doc.archive(), "none", ctx).ok());
    }

    @Test
    void sbtTexturesAreEditedThroughTheirLayeredSourceAndRewrapped() throws Exception {
        Path omt = saveTexture("work/bands.omt", RED, GREEN, BLUE);
        Path sbt = project.resolve("UI/test/ui/textures/bands.sbt");
        Files.createDirectories(sbt.getParent());
        SBTFormat.ExportParameters p = new SBTFormat.ExportParameters();
        p.setTextureId("stonebreak:bands");
        p.setTextureName("Bands");
        p.setTexturePack("core");
        p.setAuthor("tests");
        assertTrue(new SBTSerializer().export(p, omt, sbt.toString()));

        UiProjectContext ctx = ctx();
        UiDocumentService service = new UiDocumentService(ctx, new UiRecoveryService(project.resolve(".rec")));
        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/s", "S");
        UiImageAssets.ProjectImage img = UiImageAssets.projectImages(ctx).stream()
            .filter(i -> i.relative().endsWith(".sbt")).findFirst().orElseThrow();
        assertTrue(doc.execute(UiImageAssets.addImage(ctx, img)));
        UiImageAssets.EditableTexture t = UiImageAssets.editableTexture(doc.archive(), img.id(), ctx);
        assertTrue(t.ok() && t.sbt(), t.problem());

        TextureEditBridge bridge = new TextureEditBridge();
        Path[] opened = new Path[1];
        assertNull(bridge.edit(t, f -> {
            opened[0] = f;
            return null;
        }));
        assertEquals(sbt.resolveSibling("bands.omt"), opened[0], "the SBT's layers open as its sibling OMT");
        assertArrayEquals(new SBTParser().read(sbt).omtBytes(), Files.readAllBytes(opened[0]));

        // The Texture Editor saves the OMT; the SBT is re-wrapped with its identity kept.
        saveTexture("UI/test/ui/textures/bands.omt", BLUE, BLUE, BLUE);
        List<Path> changed = bridge.saved(opened[0]);
        assertEquals(List.of(opened[0], sbt), changed);
        SBTParser.Result rewrapped = new SBTParser().read(sbt);
        assertEquals("stonebreak:bands", rewrapped.manifest().textureId());
        assertEquals("tests", rewrapped.manifest().author());
        assertArrayEquals(Files.readAllBytes(opened[0]), rewrapped.omtBytes());
    }

    @Test
    void theSbtLinkSurvivesARestartAndAStaleSiblingIsRefreshed() throws Exception {
        Path omt = saveTexture("work/bands.omt", RED, GREEN, BLUE);
        Path sbt = project.resolve("UI/test/ui/textures/bands.sbt");
        Files.createDirectories(sbt.getParent());
        SBTFormat.ExportParameters p = new SBTFormat.ExportParameters();
        p.setTextureId("stonebreak:bands");
        p.setTextureName("Bands");
        p.setTexturePack("core");
        p.setAuthor("tests");
        assertTrue(new SBTSerializer().export(p, omt, sbt.toString()));
        Path sibling = UiImageAssets.omtSourceFor(sbt);

        // A new bridge (a tool restart, or the OMT opened straight from the Texture Editor) still re-wraps.
        saveTexture("UI/test/ui/textures/bands.omt", BLUE, BLUE, BLUE);
        TextureEditBridge fresh = new TextureEditBridge();
        assertEquals(sbt, fresh.sbtFor(sibling));
        assertEquals(List.of(sibling, sbt), fresh.saved(sibling));
        assertArrayEquals(Files.readAllBytes(sibling), new SBTParser().read(sbt).omtBytes());

        // The SBT changes after the sibling (a pull): opening must not hand out the stale layers.
        byte[] siblingBefore = Files.readAllBytes(sibling);
        assertTrue(new SBTSerializer().export(p, omt, sbt.toString()));
        Files.setLastModifiedTime(sbt, java.nio.file.attribute.FileTime.fromMillis(
            Files.getLastModifiedTime(sibling).toMillis() + 5_000));
        Path reopened = UiImageAssets.omtSourceFor(sbt);
        assertArrayEquals(new SBTParser().read(sbt).omtBytes(), Files.readAllBytes(reopened));
        assertArrayEquals(siblingBefore, Files.readAllBytes(sibling.resolveSibling("bands.omt.stale")),
            "the previous layers are kept, never silently lost");

        // An author's newer sibling is their source and stays as it is.
        saveTexture("UI/test/ui/textures/bands.omt", GREEN, GREEN, GREEN);
        byte[] authored = Files.readAllBytes(reopened);
        Files.setLastModifiedTime(reopened, java.nio.file.attribute.FileTime.fromMillis(
            Files.getLastModifiedTime(sbt).toMillis() + 5_000));
        assertArrayEquals(authored, Files.readAllBytes(UiImageAssets.omtSourceFor(sbt)));
    }

    @Test
    void draftUndoMergesDragsAndRemovalKeepsSkinsValid() {
        SpriteSheetDraft d = new SpriteSheetDraft("s", bands());
        Sprite left = d.sheet().sprite("left").orElseThrow();
        d.setSprite("left", left.withRect(0, 0, 1, 3), "drag");
        d.setSprite("left", left.withRect(0, 0, 2, 2), "drag");
        d.endInteraction();
        assertTrue(d.dirty());
        d.undo();
        assertEquals(left, d.sheet().sprite("left").orElseThrow(), "one drag = one undo step");
        assertFalse(d.dirty());

        d.remove("right");
        assertNull(d.sheet().skin("button").orElseThrow().disabled(), "a removed region leaves its skin state");
        d.remove("left");
        assertTrue(d.sheet().skin("button").isEmpty(), "a skin without its normal region goes too");
        assertFalse(d.rename("middle", "9bad"));
        assertEquals("middle_2", d.freshName("middle"));
    }
}

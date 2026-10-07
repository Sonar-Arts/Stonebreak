package com.openmason.engine.ui.assets.edit;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDependency.Mode;
import com.openmason.engine.format.omui.UiDiagnostic.Code;
import com.openmason.engine.format.omui.UiFormatException;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.assets.AssetResolver;
import com.openmason.engine.ui.assets.DependencyRefs;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.Resolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static com.openmason.engine.ui.assets.UiAssetFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Embed, refresh, extract, relink and rename as undoable commands (#285). */
class AssetEditOperationsTest {

    private static final String PANEL = UiSamples.PANEL_TEXTURE_ID;
    private static final String THEME = UiSamples.THEME_ID;
    private static final String BUTTON = UiSamples.BUTTON_ID;

    @TempDir
    Path tmp;
    ProjectAssetSource project;
    OmuiArchive pause;

    @BeforeEach
    void setUp() throws Exception {
        project = pauseProject(tmp);
        pause = UiSamples.pauseMenu();
    }

    @Test
    void embedSnapshotsTheDependencyAndItsRequiredClosure() throws Exception {
        // The theme sheet needs the panel texture: embedding the theme must take the panel along.
        OmuiArchive doc = withRow(pause, with(pause.dependencies().find(THEME), List.of(PANEL), false, null));

        AssetEdit edit = EmbedOperations.embed(doc, THEME, List.of(project));
        OmuiArchive after = edit.after();

        for (String id : List.of(THEME, PANEL)) {
            UiDependency row = after.dependencies().find(id);
            assertEquals(Mode.EMBEDDED, row.mode(), id);
            assertEquals(row.sha256(), after.assets().get(row.entry()).sha256());
            assertNotNull(row.sourceHint(), "hint kept for refresh/extract");
        }
        assertEquals("assets/stonebreak/ui/themes/stone.uss.json", after.dependencies().find(THEME).entry());
        assertEquals(Mode.SHARED, after.dependencies().find(UiSamples.COMMON_LUA_ID).mode(), "outside the closure");
        assertTrue(edit.writes().isEmpty());
        assertEquals(doc, edit.undo(project.folder()));
        roundTrips(after);
    }

    @Test
    void embedNeverOverwritesAnOrphanedEntryAndPreservesIt() throws Exception {
        UiBytes orphan = bytes("orphaned snapshot from an older version");
        OmuiArchive doc = pause.withAsset("assets/stonebreak/ui/textures/panel.sbt", orphan);

        OmuiArchive after = EmbedOperations.embed(doc, PANEL, List.of(project)).after();

        assertEquals(orphan, after.assets().get("assets/stonebreak/ui/textures/panel.sbt"));
        assertEquals("assets/stonebreak/ui/textures/panel-2.sbt", after.dependencies().find(PANEL).entry());
        roundTrips(after);
    }

    @Test
    void embedOfAMissingRequiredDependencyIsRefusedWithoutChanges() throws Exception {
        project.folder().delete(PANEL_HINT);
        UiFormatException e = assertThrows(UiFormatException.class,
                () -> EmbedOperations.embed(pause, PANEL, List.of(project)));
        assertTrue(e.has(Code.MISSING_ENTRY));
    }

    @Test
    void refreshPicksUpTheEditedOriginalOnlyWhenAsked() throws Exception {
        OmuiArchive embedded = EmbedOperations.embed(pause, PANEL, List.of(project)).after();
        UiBytes edited = bytes("repainted panel");
        project.folder().write(PANEL_HINT, edited);

        Resolution before = AssetResolver.forDocument(embedded, List.of(project)).resolveAll();
        assertEquals(UiBytes.copyOf(UiSamples.PANEL_TEXTURE_BYTES), before.get(PANEL).bytes(), "no implicit refresh");

        AssetEdit refresh = EmbedOperations.refresh(embedded, PANEL, List.of(project));
        UiDependency row = refresh.after().dependencies().find(PANEL);
        assertEquals(edited.sha256(), row.sha256());
        assertEquals(edited, refresh.after().assets().get(row.entry()));
        assertEquals(embedded, refresh.undo(project.folder()));

        AssetEdit again = EmbedOperations.refresh(refresh.after(), PANEL, List.of(project));
        assertFalse(again.changesDocument(), "unchanged original is a no-op");
    }

    @Test
    void extractWritesTheSnapshotAndUndoRemovesIt() throws Exception {
        UiBytes snapshot = pause.assets().get("assets/components/stone_button.omui");

        AssetEdit edit = ExtractOperations.extractToProject(pause, BUTTON, project, CollisionPolicy.FAIL);
        OmuiArchive after = edit.apply(project.folder());

        UiDependency row = after.dependencies().find(BUTTON);
        assertEquals(Mode.SHARED, row.mode());
        assertEquals("ui/components/stone_button.omui", row.sourceHint());
        assertEquals(snapshot, project.folder().read(row.sourceHint()));
        assertFalse(after.assets().containsKey("assets/components/stone_button.omui"), "snapshot moved out");
        assertEquals(snapshot, AssetResolver.forDocument(after, List.of(project)).resolveAll().get(BUTTON).bytes());

        assertEquals(pause, edit.undo(project.folder()));
        assertNull(project.folder().read(row.sourceHint()));
    }

    @Test
    void extractCollisionsAreExplicit() throws Exception {
        UiBytes theirs = bytes("someone else's stone button");
        project.folder().write("ui/components/stone_button.omui", theirs);
        UiBytes snapshot = pause.assets().get("assets/components/stone_button.omui");

        UiFormatException refused = assertThrows(UiFormatException.class,
                () -> ExtractOperations.extractToProject(pause, BUTTON, project, CollisionPolicy.FAIL));
        assertTrue(refused.has(Code.DUPLICATE_ID));

        AssetEdit keep = ExtractOperations.extractToProject(pause, BUTTON, project, CollisionPolicy.KEEP_PROJECT);
        assertTrue(keep.writes().isEmpty());
        assertEquals(theirs.sha256(), keep.after().dependencies().find(BUTTON).sha256());

        AssetEdit replace = ExtractOperations.extractToProject(pause, BUTTON, project, CollisionPolicy.REPLACE);
        replace.apply(project.folder());
        assertEquals(snapshot, project.folder().read("ui/components/stone_button.omui"));
        replace.undo(project.folder());
        assertEquals(theirs, project.folder().read("ui/components/stone_button.omui"), "undo restores their file");
    }

    @Test
    void undoRefusesToClobberALaterEdit() throws Exception {
        AssetEdit edit = ExtractOperations.extractToProject(pause, BUTTON, project, CollisionPolicy.FAIL);
        edit.apply(project.folder());
        project.folder().write("ui/components/stone_button.omui", bytes("edited after extract"));

        assertThrows(IOException.class, () -> edit.undo(project.folder()));
        assertEquals(bytes("edited after extract"), project.folder().read("ui/components/stone_button.omui"));
    }

    @Test
    void unresolvedReferencesSurviveSaveAndRelinkRecoversThem() throws Exception {
        project.folder().delete(PANEL_HINT);
        assertFalse(AssetResolver.forDocument(pause, List.of(project)).resolveAll().complete());

        OmuiArchive reopened = OmuiReader.read(OmuiWriter.write(pause)).archive();
        assertEquals(pause.dependencies(), reopened.dependencies(), "editor saves keep unresolved rows");

        UiBytes moved = bytes("panel, moved by the artist");
        project.folder().write("art/panel_v2.sbt", moved);
        AssetEdit relink = RelinkOperations.relink(reopened, PANEL, "art/panel_v2.sbt", project);

        Resolution r = AssetResolver.forDocument(relink.after(), List.of(project)).resolveAll();
        assertTrue(r.complete(), r.diagnostics()::toString);
        assertEquals(moved.sha256(), relink.after().dependencies().find(PANEL).sha256());
        assertThrows(UiFormatException.class, () -> RelinkOperations.relink(reopened, PANEL, "../escape.sbt", project));
        assertThrows(UiFormatException.class, () -> RelinkOperations.relink(reopened, BUTTON, "x.omui", project));
    }

    @Test
    void renameRemapsEveryReference() throws Exception {
        String renamed = "stonebreak:ui/textures/panel_dark";
        AssetEdit edit = RelinkOperations.rename(pause, PANEL, renamed);
        OmuiArchive after = edit.after();

        assertNull(after.dependencies().find(PANEL));
        assertNotNull(after.dependencies().find(renamed));
        assertEquals(UiValue.of(renamed), after.document().root().flatten().stream()
                .filter(n -> n.id().equals("panel")).findFirst().orElseThrow().style().get("background-image"));
        assertEquals(UiValue.of(renamed), after.document().root().flatten().stream()
                .filter(n -> n.id().equals("quit_icon")).findFirst().orElseThrow().props().get("source"));
        assertFalse(DependencyRefs.referenced(after).contains(PANEL));
        roundTrips(after);
    }

    @Test
    void renameReportsLuaThatMentionsTheOldId() throws Exception {
        OmuiArchive doc = pause.withScript("pause", "local t = \"" + PANEL + "\"\nreturn {}\n");
        AssetEdit edit = RelinkOperations.rename(doc, PANEL, "stonebreak:ui/textures/panel2");
        assertTrue(edit.diagnostics().stream().anyMatch(d -> d.entry().equals("scripts/pause.lua")));
        assertTrue(edit.after().scripts().get("pause").contains(PANEL), "Lua source is never rewritten");
    }

    private static void roundTrips(OmuiArchive doc) throws UiFormatException {
        assertEquals(doc, OmuiReader.read(OmuiWriter.write(doc)).archive());
    }
}

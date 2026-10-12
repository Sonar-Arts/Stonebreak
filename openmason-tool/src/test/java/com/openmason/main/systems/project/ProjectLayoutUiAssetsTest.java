package com.openmason.main.systems.project;

import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.ui.assets.ProjectAssetSource;
import com.openmason.engine.ui.assets.ResolvedAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The project's shared UI assets resolve by hint first, then under {@code UI/} by convention. */
class ProjectLayoutUiAssetsTest {

    @TempDir
    Path root;

    @Test
    void resolvesHintsAndConventionUnderTheUiFolder() throws Exception {
        ProjectAssetSource source = ProjectLayout.uiAssetSource(root);
        Files.createDirectories(root.resolve("UI/stonebreak/ui/textures"));
        Files.writeString(root.resolve("UI/stonebreak/ui/textures/panel.sbt"), "by convention");
        Files.createDirectories(root.resolve("art"));
        Files.writeString(root.resolve("art/button.sbt"), "by hint");

        ResolvedAsset panel = source.find("stonebreak:ui/textures/panel", UiDependency.Kind.TEXTURE, "missing.sbt");
        ResolvedAsset button = source.find("stonebreak:ui/textures/button", UiDependency.Kind.TEXTURE, "art/button.sbt");

        assertEquals("UI/stonebreak/ui/textures/panel.sbt", panel.location());
        assertEquals(UiBytes.utf8("by hint"), button.bytes());
        assertEquals("UI/stonebreak/ui/scripts/menu.lua",
                source.placementFor("stonebreak:ui/scripts/menu", UiDependency.Kind.SCRIPT, null));
        assertNull(ProjectLayout.uiAssetSource(null));
    }
}

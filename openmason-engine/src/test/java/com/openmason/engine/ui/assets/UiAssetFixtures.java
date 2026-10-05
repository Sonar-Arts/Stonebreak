package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiArchive.UiDependencies;
import com.openmason.engine.format.omui.UiBytes;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiSamples;
import com.openmason.engine.format.omui.UiValue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Projects and documents for the asset tests, built on the #284 {@link UiSamples}. */
public final class UiAssetFixtures {

    public static final String THEME_HINT = "ui/themes/stone.uss.json";
    public static final String LUA_HINT = "ui/scripts/common.lua";
    public static final String PANEL_HINT = "textures/ui/panel.sbt";
    public static final String CONVENTION_DIR = "UI/";

    private UiAssetFixtures() {
    }

    /** A project folder holding the pause menu's shared dependencies at their hints. */
    public static ProjectAssetSource pauseProject(Path root) throws IOException {
        ProjectFolder folder = new ProjectFolder(root);
        folder.write(THEME_HINT, UiBytes.copyOf(UiSamples.THEME_BYTES));
        folder.write(LUA_HINT, UiBytes.copyOf(UiSamples.COMMON_LUA_BYTES));
        folder.write(PANEL_HINT, UiBytes.copyOf(UiSamples.PANEL_TEXTURE_BYTES));
        return new ProjectAssetSource(folder, CONVENTION_DIR);
    }

    /** A minimal screen whose root shows {@code textureId} as background and in an Image. */
    public static OmuiArchive screenUsing(String documentId, String textureId, UiBytes textureBytes, String hint) {
        UiNode image = new UiNode("icon", null, "Image", 1, List.of(), Map.of("source", UiValue.of(textureId)),
                Map.of(), null, List.of(), null, List.of(), Map.of());
        UiNode root = new UiNode("root", null, "Box", 1, List.of(), Map.of(),
                Map.of("background-image", UiValue.of(textureId)), null, List.of(), null, List.of(image), Map.of());
        return OmuiArchive.of(UiManifest.create(documentId, UiManifest.DocumentKind.SCREEN, ""),
                        new UiDocument(root, List.of(), null, null, Map.of()))
                .withDependencies(new UiDependencies(List.of(UiDependency.shared(textureId, UiDependency.Kind.TEXTURE,
                        textureBytes.sha256(), textureBytes.size(), hint)), Map.of()));
    }

    /** {@code doc} with one row replaced (or added when absent). */
    public static OmuiArchive withRow(OmuiArchive doc, UiDependency row) {
        List<UiDependency> rows = new ArrayList<>(doc.dependencies().entries().stream()
                .filter(d -> !d.id().equals(row.id())).toList());
        rows.add(row);
        return doc.withDependencies(new UiDependencies(rows, doc.dependencies().unknown()));
    }

    /** {@code row} with different {@code requires}, optional flag and fallback. */
    public static UiDependency with(UiDependency row, List<String> requires, boolean optional, String fallback) {
        return new UiDependency(row.id(), row.kind(), row.version(), row.sha256(), row.size(), row.mode(), row.entry(),
                row.sourceHint(), requires, optional, fallback, row.license(), row.unknown());
    }

    public static UiBytes bytes(String text) {
        return UiBytes.utf8(text);
    }
}

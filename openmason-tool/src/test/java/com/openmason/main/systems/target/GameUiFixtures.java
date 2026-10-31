package com.openmason.main.systems.target;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.assets.AssetSource;
import com.openmason.engine.ui.assets.MountedAssetSource;
import com.openmason.engine.ui.assets.ResourceOpener;
import com.openmason.engine.ui.hosting.UiDocuments;
import com.openmason.engine.ui.masonry.textures.MTextureCache;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Opens UI documents the way the game does (#382: the game's {@code GameUiDocuments} is a thin
 * layer over the engine's {@link UiDocuments}), with the game's packaged {@code ui/shared/} root
 * and UI font read from this checkout's files — no game classes.
 */
public final class GameUiFixtures {

    /** The game's packaged UI root (its {@code GameUiAssets.RESOURCE_ROOT}). */
    public static final String PACKAGED_ROOT = "ui/shared/";
    /** The game's UI font (its {@code GameUiResources.TYPEFACE_RESOURCE}). */
    public static final String TYPEFACE = "fonts/Minecraft.ttf";

    private static final MTextureCache TEXTURES = new MTextureCache();

    private GameUiFixtures() {
    }

    private static Path tree() throws IOException {
        Path tree = TestGameTargets.checkoutTree();
        if (tree == null) {
            throw new IOException("Game resource tree " + TestGameTargets.SOURCE_TREE + " not found");
        }
        return tree;
    }

    /** The game's packaged UI asset source, over the checkout. */
    public static List<AssetSource> packaged() throws IOException {
        return List.of(MountedAssetSource.packaged(PACKAGED_ROOT, ResourceOpener.directory(tree())));
    }

    /** A new typeface (caller closes it): the game's UI font. */
    public static Typeface loadTypeface() throws IOException {
        byte[] bytes = Files.readAllBytes(tree().resolve(TYPEFACE));
        return FontMgr.getDefault().makeFromData(Data.makeFromBytes(bytes));
    }

    /** A shipped document as the game opens it: embedded rows, then the packaged root. */
    public static UiDocumentView open(SbuiArchive sbui, Supplier<Typeface> typeface) throws IOException {
        return UiDocuments.open(sbui, packaged(), TEXTURES, typeface, Map.of());
    }

    /** An authoring document: embedded rows, then {@code projectSources}, then the packaged root. */
    public static UiDocumentView open(OmuiArchive omui, List<AssetSource> projectSources, Supplier<Typeface> typeface)
            throws IOException {
        return UiDocuments.open(omui, UiDocuments.withFallback(projectSources, packaged()), TEXTURES, typeface,
                Map.of());
    }
}

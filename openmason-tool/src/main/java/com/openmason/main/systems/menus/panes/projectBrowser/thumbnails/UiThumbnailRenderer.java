package com.openmason.main.systems.menus.panes.projectBrowser.thumbnails;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import com.openmason.main.systems.project.ProjectLayout;
import com.openmason.main.systems.uiEditor.service.UiSnapshot;
import com.stonebreak.ui.runtime.GameUiResources;
import io.github.humbleui.skija.Typeface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Thumbnails of UI documents (#293): the document painted by the game's own runtime at a
 * 1280 x 720 logical frame, at half scale on the CPU, then fitted into the square. Cached by
 * path, size and modification time; documents that cannot run get the placeholder.
 */
public final class UiThumbnailRenderer {

    private static final Logger logger = LoggerFactory.getLogger(UiThumbnailRenderer.class);
    private static final Map<String, int[]> CACHE = new HashMap<>();
    private static Typeface typeface;
    private static boolean failed;

    private UiThumbnailRenderer() {
    }

    /** GL texture id of {@code entry}'s thumbnail, or 0 (placeholder). GL thread only. */
    public static int get(AssetEntry entry, int size) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(entry.path()).toMillis();
        } catch (Exception e) {
            return 0;
        }
        String key = entry.pathString() + "#" + size;
        int[] cached = CACHE.get(key);
        if (cached != null && cached[1] == (int) mtime) {
            return cached[0];
        }
        int tex = generate(entry.path(), size);
        if (cached != null && cached[0] > 0) {
            org.lwjgl.opengl.GL11.glDeleteTextures(cached[0]);
        }
        CACHE.put(key, new int[]{tex, (int) mtime});
        return tex;
    }

    private static int generate(Path file, int size) {
        if (failed) {
            return 0;
        }
        try {
            if (typeface == null) {
                typeface = GameUiResources.loadTypeface();
            }
            OmuiArchive doc = OmuiReader.read(file).archive();
            Path root = projectRoot(file);
            var sources = new java.util.ArrayList<com.openmason.engine.ui.assets.AssetSource>();
            if (root != null) {
                sources.add(ProjectLayout.uiAssetSource(root));
            }
            BufferedImage img = UiSnapshot.render(doc, sources, typeface, 640, 360, 0.5f);
            return ThumbnailGL.uploadFromImage(img, size);
        } catch (UnsatisfiedLinkError | ExceptionInInitializerError e) {
            failed = true;
            logger.warn("UI thumbnails unavailable: {}", e.toString());
            return 0;
        } catch (Exception e) {
            logger.debug("No thumbnail for {}: {}", file, e.getMessage());
            return 0;
        }
    }

    /** The folder above the {@code UI/} directory holding {@code file}, or null. */
    private static Path projectRoot(Path file) {
        for (Path p = file.toAbsolutePath().getParent(); p != null; p = p.getParent()) {
            if (p.getFileName() != null && ProjectLayout.UI_DIR.equals(p.getFileName().toString())) {
                return p.getParent();
            }
        }
        return null;
    }
}

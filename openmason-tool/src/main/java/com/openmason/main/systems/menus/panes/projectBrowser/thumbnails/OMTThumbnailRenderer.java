package com.openmason.main.systems.menus.panes.projectBrowser.thumbnails;

import com.openmason.engine.format.omt.OMTArchive;
import com.openmason.engine.format.omt.OMTReader;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates thumbnails for .OMT textures by compositing the archive's visible
 * layers — the canonical "what does this texture look like" preview.
 */
public class OMTThumbnailRenderer {

    private static final Logger logger = LoggerFactory.getLogger(OMTThumbnailRenderer.class);

    private final ThumbnailCache cache;
    private final OMTReader omtReader = new OMTReader();

    public OMTThumbnailRenderer(ThumbnailCache cache) {
        this.cache = cache;
    }

    public int getThumbnail(AssetEntry entry, int size) {
        if (entry == null) {
            return 0;
        }
        String key = ThumbnailCache.omtKey(entry.pathString(), size);
        long version = readMtime(entry.path());
        return cache.getOrCreate(key, version, () -> generate(entry, size));
    }

    private int generate(AssetEntry entry, int size) {
        Path file = entry.path();
        if (file == null || !Files.exists(file)) {
            return 0;
        }
        try (InputStream in = Files.newInputStream(file)) {
            OMTArchive archive = omtReader.read(in);

            List<byte[]> visible = new ArrayList<>();
            for (OMTArchive.Layer layer : archive.layers()) {
                if (layer.visible() && layer.pngBytes() != null && layer.pngBytes().length > 0) {
                    visible.add(layer.pngBytes());
                }
            }
            if (visible.isEmpty()) {
                return 0;
            }
            int w = archive.canvasSize().width();
            int h = archive.canvasSize().height();
            return ThumbnailGL.uploadComposite(visible, w, h, size);
        } catch (Exception e) {
            logger.warn("Failed to generate OMT thumbnail for {}: {}", entry.name(), e.getMessage());
            return 0;
        }
    }

    private static long readMtime(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }
}

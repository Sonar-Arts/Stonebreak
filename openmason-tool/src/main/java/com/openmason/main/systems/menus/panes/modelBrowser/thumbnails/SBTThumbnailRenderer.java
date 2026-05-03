package com.openmason.main.systems.menus.panes.modelBrowser.thumbnails;

import com.openmason.engine.format.omt.OMTArchive;
import com.openmason.engine.format.omt.OMTReader;
import com.openmason.engine.format.sbt.SBTFileManager;
import com.openmason.engine.format.sbt.SBTParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates thumbnails for .SBT textures by compositing the embedded OMT's
 * visible layers. SBTs are flat textures; the composite is the canonical
 * "what does this texture look like" preview.
 */
public class SBTThumbnailRenderer {

    private static final Logger logger = LoggerFactory.getLogger(SBTThumbnailRenderer.class);

    private final ModelBrowserThumbnailCache cache;
    private final SBTParser sbtParser = new SBTParser();
    private final OMTReader omtReader = new OMTReader();

    public SBTThumbnailRenderer(ModelBrowserThumbnailCache cache) {
        this.cache = cache;
    }

    public int getThumbnail(SBTFileManager.SBTFileEntry entry, int size) {
        if (entry == null) {
            return 0;
        }
        String key = ModelBrowserThumbnailCache.sbtKey(entry.getFilePathString(), size);
        long version = readMtime(entry.filePath());
        return cache.getOrCreate(key, version, () -> generate(entry, size));
    }

    private int generate(SBTFileManager.SBTFileEntry entry, int size) {
        Path file = entry.filePath();
        if (file == null || !Files.exists(file)) {
            return 0;
        }
        try (InputStream in = Files.newInputStream(file)) {
            SBTParser.Result result = sbtParser.read(in);
            OMTArchive archive = omtReader.read(result.omtBytes());

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
            logger.warn("Failed to generate SBT thumbnail for {}: {}", entry.name(), e.getMessage());
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

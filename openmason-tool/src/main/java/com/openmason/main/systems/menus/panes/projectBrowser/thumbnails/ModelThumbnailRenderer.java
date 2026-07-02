package com.openmason.main.systems.menus.panes.projectBrowser.thumbnails;

import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.omt.OMTArchive;
import com.openmason.engine.format.omt.OMTReader;
import com.openmason.main.systems.menus.panes.projectBrowser.ProjectAssetScanner.AssetEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates thumbnails for .OMO models by extracting the main face texture.
 *
 * <p>Resolution order, picking the first available source:
 * <ol>
 *   <li>The first per-face material PNG (face id 0 if present)</li>
 *   <li>Any material PNG in the model</li>
 *   <li>The first visible layer of the embedded texture.omt (default texture)</li>
 * </ol>
 */
public class ModelThumbnailRenderer {

    private static final Logger logger = LoggerFactory.getLogger(ModelThumbnailRenderer.class);

    private final ThumbnailCache cache;
    private final OMOReader omoReader = new OMOReader();
    private final OMTReader omtReader = new OMTReader();

    public ModelThumbnailRenderer(ThumbnailCache cache) {
        this.cache = cache;
    }

    /**
     * Returns a GL texture id for the OMO entry. Cached per-file with the
     * file's last-modified time as the cache version, so re-saved models
     * pick up new texture content automatically.
     */
    public int getThumbnail(AssetEntry entry, int size) {
        if (entry == null) {
            return 0;
        }
        String key = ThumbnailCache.omoKey(entry.pathString(), size);
        long version = readMtime(entry.path());
        return cache.getOrCreate(key, version, () -> generate(entry, size));
    }

    private int generate(AssetEntry entry, int size) {
        Path file = entry.path();
        if (file == null || !Files.exists(file)) {
            return 0;
        }
        try (InputStream in = Files.newInputStream(file)) {
            OMOReader.ReadResult result = omoReader.read(in);
            byte[] png = pickPrimaryPng(result);
            if (png == null) {
                return 0;
            }
            int id = ThumbnailGL.uploadFromPng(png, size);
            if (id > 0) {
                return id;
            }
            logger.debug("OMO thumbnail upload returned 0 for: {}", entry.name());
            return 0;
        } catch (Exception e) {
            logger.warn("Failed to generate OMO thumbnail for {}: {}", entry.name(), e.getMessage());
            return 0;
        }
    }

    /**
     * Picks the most representative PNG: prefer the material that face 0
     * (typically the front face) is mapped to, fall back to the first
     * available material PNG, then the default OMT's first visible layer.
     */
    private byte[] pickPrimaryPng(OMOReader.ReadResult result) throws Exception {
        if (result.materials() != null && !result.materials().isEmpty()) {
            int preferredMaterialId = -1;
            if (result.faceMappings() != null) {
                for (var mapping : result.faceMappings()) {
                    if (mapping.faceId() == 0) {
                        preferredMaterialId = mapping.materialId();
                        break;
                    }
                }
            }
            if (preferredMaterialId >= 0) {
                for (ParsedMaterialData m : result.materials()) {
                    if (m.materialId() == preferredMaterialId && m.texturePng() != null) {
                        return m.texturePng();
                    }
                }
            }
            for (ParsedMaterialData m : result.materials()) {
                if (m.texturePng() != null && m.texturePng().length > 0) {
                    return m.texturePng();
                }
            }
        }
        if (result.defaultTextureBytes() != null && result.defaultTextureBytes().length > 0) {
            OMTArchive archive = omtReader.read(result.defaultTextureBytes());
            for (OMTArchive.Layer layer : archive.layers()) {
                if (layer.visible() && layer.pngBytes() != null && layer.pngBytes().length > 0) {
                    return layer.pngBytes();
                }
            }
            return archive.layers().isEmpty() ? null : archive.layers().get(0).pngBytes();
        }
        return null;
    }

    private static long readMtime(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }
}

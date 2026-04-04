package com.stonebreak.rendering.sbo;

import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.sbo.SBOParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts texture images from SBO parse results.
 * Converts raw PNG bytes from SBO materials into BufferedImages
 * suitable for atlas integration or GPU upload.
 */
public class SBOTextureExtractor {

    private static final Logger logger = LoggerFactory.getLogger(SBOTextureExtractor.class);

    /**
     * Extract all material textures from an SBO parse result.
     *
     * @param result the parsed SBO
     * @return list of decoded BufferedImages (one per material)
     */
    public List<BufferedImage> extractMaterialTextures(SBOParseResult result) {
        List<BufferedImage> images = new ArrayList<>();

        for (ParsedMaterialData material : result.materials()) {
            if (material.texturePng() == null || material.texturePng().length == 0) {
                logger.warn("Material {} has no texture data", material.name());
                continue;
            }

            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(material.texturePng()));
                if (image != null) {
                    images.add(image);
                    logger.debug("Extracted material texture '{}': {}x{}",
                            material.name(), image.getWidth(), image.getHeight());
                }
            } catch (IOException e) {
                logger.error("Failed to decode material texture '{}'", material.name(), e);
            }
        }

        return images;
    }

    /**
     * Extract the default texture (texture.omt flattened) from an SBO parse result.
     *
     * @param result the parsed SBO
     * @return the default texture as BufferedImage, or null if not available
     */
    public BufferedImage extractDefaultTexture(SBOParseResult result) {
        if (result.defaultTexturePng() == null || result.defaultTexturePng().length == 0) {
            return null;
        }

        try {
            // The defaultTexturePng is actually OMT format (ZIP with layers), not raw PNG
            // For now, try reading it as PNG first (some exporters embed raw PNG)
            return ImageIO.read(new ByteArrayInputStream(result.defaultTexturePng()));
        } catch (IOException e) {
            logger.debug("Default texture is not raw PNG, attempting OMT extraction");
            return extractFromOMT(result.defaultTexturePng());
        }
    }

    /**
     * Extract the first layer image from OMT format bytes.
     * OMT is a ZIP containing layer_N.png files.
     */
    private BufferedImage extractFromOMT(byte[] omtBytes) {
        try (var bais = new ByteArrayInputStream(omtBytes);
             var zis = new java.util.zip.ZipInputStream(bais)) {

            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().startsWith("layer_") && entry.getName().endsWith(".png")) {
                    // Read layer PNG bytes
                    var baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = zis.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    BufferedImage layerImage = ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
                    if (layerImage != null) {
                        logger.debug("Extracted OMT layer: {}x{}", layerImage.getWidth(), layerImage.getHeight());
                        return layerImage;
                    }
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            logger.error("Failed to extract OMT texture", e);
        }
        return null;
    }
}

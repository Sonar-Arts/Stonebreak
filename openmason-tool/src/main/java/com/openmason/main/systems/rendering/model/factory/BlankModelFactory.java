package com.openmason.main.systems.rendering.model.factory;

import com.openmason.main.systems.rendering.model.editable.BlockModel;
import com.openmason.main.systems.rendering.model.editable.CubeGeometry;
import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.menus.textureCreator.io.OMTSerializer;
import com.openmason.main.systems.menus.textureCreator.layers.Layer;
import com.openmason.main.systems.menus.textureCreator.layers.LayerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Factory for creating blank block models with default gray textures.
 */
public class BlankModelFactory {

    private static final Logger logger = LoggerFactory.getLogger(BlankModelFactory.class);

    // Default per-face texture size. The new uniform cube renders the full
    // texture on every face, so a single 16x16 solid swatch is all we need.
    private static final int TEXTURE_WIDTH = 16;
    private static final int TEXTURE_HEIGHT = 16;

    // Light gray color (RGB 192, 192, 192) with full alpha
    private static final int LIGHT_GRAY = PixelCanvas.packRGBA(192, 192, 192, 255);

    private final OMTSerializer omtSerializer;

    /**
     * Creates a new blank model factory.
     */
    public BlankModelFactory() {
        this.omtSerializer = new OMTSerializer();
    }

    /**
     * Creates a blank cube model with a solid gray texture.
     *
     * <p>The model is created with:
     * <ul>
     *   <li>Name: "Untitled Block"</li>
     *   <li>Geometry: 16x16x16 cube at origin</li>
     *   <li>Texture: 16x16 solid gray (rendered identically on all 6 faces)</li>
     * </ul>
     *
     * <p>The texture is saved as a temporary .OMT file that will be embedded
     * when the model is saved as a .OMO file.
     *
     * @return a new blank BlockModel
     * @throws IOException if texture file creation fails
     */
    public BlockModel createBlankCube() throws IOException {
        logger.debug("Creating blank cube model");

        Path texturePath = createSolidGrayTexture();

        BlockModel model = new BlockModel(
            "Untitled Block",
            new CubeGeometry(), // Default 16x16x16 cube
            texturePath
        );

        logger.info("Created blank cube model with texture: {}", texturePath);
        return model;
    }

    /**
     * Creates a 16x16 solid gray texture saved as a temporary .OMT file.
     */
    private Path createSolidGrayTexture() throws IOException {
        PixelCanvas canvas = new PixelCanvas(TEXTURE_WIDTH, TEXTURE_HEIGHT);
        for (int y = 0; y < TEXTURE_HEIGHT; y++) {
            for (int x = 0; x < TEXTURE_WIDTH; x++) {
                canvas.setPixel(x, y, LIGHT_GRAY);
            }
        }

        Layer layer = new Layer("Texture", canvas, true, 1.0f);

        LayerManager layerManager = new LayerManager(TEXTURE_WIDTH, TEXTURE_HEIGHT);
        layerManager.addLayerAt(1, layer);
        layerManager.removeLayer(0); // drop the default Background layer

        Path tempFile = Files.createTempFile("blank_cube_", ".omt");
        tempFile.toFile().deleteOnExit();

        if (!omtSerializer.save(layerManager, tempFile.toString())) {
            throw new IOException("Failed to save blank cube texture");
        }

        logger.debug("Created solid gray texture: {}", tempFile);
        return tempFile;
    }
}

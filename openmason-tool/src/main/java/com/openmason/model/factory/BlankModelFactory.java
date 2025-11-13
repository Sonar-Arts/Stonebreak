package com.openmason.model.factory;

import com.openmason.model.editable.BlockModel;
import com.openmason.model.editable.CubeGeometry;
import com.openmason.ui.components.textureCreator.canvas.PixelCanvas;
import com.openmason.ui.components.textureCreator.io.OMTSerializer;
import com.openmason.ui.components.textureCreator.layers.Layer;
import com.openmason.ui.components.textureCreator.layers.LayerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Factory for creating blank block models with default gray textures.
 *
 * <p>Creates a single-cube model (16x16x16) with a 64x48 cube net texture.
 * The cube net has all faces filled with light gray pixels (RGB 192, 192, 192).
 *
 * <p>Cube Net Layout (64x48 pixels):
 * <pre>
 * Column: 0      1       2       3
 * Row 0:  [ ]   [TOP]   [ ]     [ ]
 * Row 1:  [LEFT][FRONT][RIGHT] [BACK]
 * Row 2:  [ ]   [BOTTOM][ ]     [ ]
 * </pre>
 *
 * Each face is 16x16 pixels:
 * <ul>
 *   <li>TOP: (16, 0) to (31, 15)</li>
 *   <li>LEFT: (0, 16) to (15, 31)</li>
 *   <li>FRONT: (16, 16) to (31, 31)</li>
 *   <li>RIGHT: (32, 16) to (47, 31)</li>
 *   <li>BACK: (48, 16) to (63, 31)</li>
 *   <li>BOTTOM: (16, 32) to (31, 47)</li>
 * </ul>
 *
 * <p>Design Principles:
 * <ul>
 *   <li>SOLID: Factory Pattern - encapsulates complex object creation</li>
 *   <li>DRY: Reuses existing .OMT serialization infrastructure</li>
 *   <li>KISS: Simple implementation using existing components</li>
 *   <li>YAGNI: No complex features, just basic blank model generation</li>
 * </ul>
 *
 * @since 1.0
 */
public class BlankModelFactory {

    private static final Logger logger = LoggerFactory.getLogger(BlankModelFactory.class);

    // Cube net dimensions
    private static final int CUBE_NET_WIDTH = 64;
    private static final int CUBE_NET_HEIGHT = 48;
    private static final int FACE_SIZE = 16;

    // Light gray color (RGB 192, 192, 192) with full alpha
    private static final int LIGHT_GRAY = PixelCanvas.packRGBA(192, 192, 192, 255);

    // Face positions in cube net (x, y)
    private static final int[][] FACE_POSITIONS = {
        {16, 0},  // TOP
        {0, 16},  // LEFT
        {16, 16}, // FRONT
        {32, 16}, // RIGHT
        {48, 16}, // BACK
        {16, 32}  // BOTTOM
    };

    private final OMTSerializer omtSerializer;

    /**
     * Creates a new blank model factory.
     */
    public BlankModelFactory() {
        this.omtSerializer = new OMTSerializer();
    }

    /**
     * Creates a blank cube model with gray texture.
     *
     * <p>The model is created with:
     * <ul>
     *   <li>Name: "Untitled Block"</li>
     *   <li>Geometry: 16x16x16 cube at origin</li>
     *   <li>Texture: 64x48 cube net with gray faces</li>
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

        // Create gray cube net texture
        Path texturePath = createGrayCubeNetTexture();

        // Create model with default geometry and texture
        BlockModel model = new BlockModel(
            "Untitled Block",
            new CubeGeometry(), // Default 16x16x16 cube
            texturePath
        );

        logger.info("Created blank cube model with texture: {}", texturePath);
        return model;
    }

    /**
     * Creates a 64x48 cube net texture with all faces filled with gray pixels.
     *
     * @return path to the temporary .OMT file
     * @throws IOException if file creation or saving fails
     */
    private Path createGrayCubeNetTexture() throws IOException {
        // Create pixel canvas for cube net
        PixelCanvas canvas = new PixelCanvas(CUBE_NET_WIDTH, CUBE_NET_HEIGHT);

        // Fill all 6 faces with gray pixels
        for (int[] facePos : FACE_POSITIONS) {
            fillFace(canvas, facePos[0], facePos[1], LIGHT_GRAY);
        }

        // Create layer with the canvas
        Layer layer = new Layer("Texture", canvas, true, 1.0f);

        // Create layer manager (comes with default "Background" layer)
        LayerManager layerManager = new LayerManager(CUBE_NET_WIDTH, CUBE_NET_HEIGHT);

        // Add our gray texture layer first (becomes layer 1)
        layerManager.addLayerAt(1, layer);

        // Now remove the default background layer (index 0)
        // This is safe because we have 2 layers now
        layerManager.removeLayer(0);

        // Create temporary .OMT file
        Path tempFile = Files.createTempFile("blank_cube_", ".omt");
        tempFile.toFile().deleteOnExit(); // Clean up on JVM exit

        // Save to .OMT format
        boolean saved = omtSerializer.save(layerManager, tempFile.toString());
        if (!saved) {
            throw new IOException("Failed to save blank cube net texture");
        }

        logger.debug("Created gray cube net texture: {}", tempFile);
        return tempFile;
    }

    /**
     * Fills a 16x16 face in the cube net with the specified color.
     *
     * @param canvas the pixel canvas to draw on
     * @param startX X coordinate of top-left corner
     * @param startY Y coordinate of top-left corner
     * @param color the color to fill with (packed RGBA)
     */
    private void fillFace(PixelCanvas canvas, int startX, int startY, int color) {
        for (int y = startY; y < startY + FACE_SIZE; y++) {
            for (int x = startX; x < startX + FACE_SIZE; x++) {
                canvas.setPixel(x, y, color);
            }
        }
    }

    /**
     * Creates a blank cube model with a custom name.
     *
     * @param name the name for the model
     * @return a new blank BlockModel with the specified name
     * @throws IOException if texture file creation fails
     */
    public BlockModel createBlankCube(String name) throws IOException {
        BlockModel model = createBlankCube();
        model.setName(name);
        return model;
    }

    /**
     * Creates a blank cube model with custom dimensions.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param depth the depth in pixels
     * @return a new blank BlockModel with the specified dimensions
     * @throws IOException if texture file creation fails
     */
    public BlockModel createBlankCube(int width, int height, int depth) throws IOException {
        BlockModel model = createBlankCube();
        model.setGeometry(new CubeGeometry(width, height, depth));
        return model;
    }
}

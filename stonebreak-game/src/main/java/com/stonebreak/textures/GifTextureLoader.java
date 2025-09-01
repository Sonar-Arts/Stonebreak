package com.stonebreak.textures;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.util.Iterator;
import org.lwjgl.BufferUtils;

/**
 * Specialized loader for GIF texture files, specifically designed for Errockson.gif.
 * Extracts the first frame from animated GIFs and converts to RGBA format for atlas usage.
 */
public class GifTextureLoader {
    
    private static final String ERROCKSON_GIF_PATH = "/Blocks/Textures/Errockson.gif";
    private static final int EXPECTED_SIZE = 16; // 16x16 pixels
    
    /**
     * Loads Errockson.gif from resources and extracts the first frame.
     * @return BufferedImage of the first frame, or null if loading fails
     */
    public static BufferedImage loadErrocksonGif() {
        try {
            InputStream inputStream = GifTextureLoader.class.getResourceAsStream(ERROCKSON_GIF_PATH);
            if (inputStream == null) {
                System.err.println("GifTextureLoader: Could not find Errockson.gif at path: " + ERROCKSON_GIF_PATH);
                return null;
            }
            
            // Get GIF reader
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) {
                System.err.println("GifTextureLoader: No GIF reader available");
                inputStream.close();
                return null;
            }
            
            ImageReader reader = readers.next();
            ImageInputStream imageStream = ImageIO.createImageInputStream(inputStream);
            reader.setInput(imageStream);
            
            // Read the first frame
            BufferedImage firstFrame = reader.read(0);
            
            // Validate dimensions
            if (firstFrame.getWidth() != EXPECTED_SIZE || firstFrame.getHeight() != EXPECTED_SIZE) {
                System.err.println("GifTextureLoader: Errockson.gif has invalid dimensions: " + 
                                 firstFrame.getWidth() + "x" + firstFrame.getHeight() + 
                                 ", expected: " + EXPECTED_SIZE + "x" + EXPECTED_SIZE);
            }
            
            // Clean up resources
            reader.dispose();
            imageStream.close();
            inputStream.close();
            
            System.out.println("GifTextureLoader: Successfully loaded Errockson.gif first frame (" + 
                             firstFrame.getWidth() + "x" + firstFrame.getHeight() + ")");
            
            return firstFrame;
            
        } catch (IOException e) {
            System.err.println("GifTextureLoader: Failed to load Errockson.gif: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Converts a BufferedImage to RGBA ByteBuffer for OpenGL usage.
     * @param image The source image
     * @return ByteBuffer containing RGBA pixel data, or null if conversion fails
     */
    public static ByteBuffer imageToRGBABuffer(BufferedImage image) {
        if (image == null) {
            return null;
        }
        
        int width = image.getWidth();
        int height = image.getHeight();
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        
        try {
            // Convert to RGBA format
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    
                    // Extract ARGB components
                    int alpha = (argb >> 24) & 0xFF;
                    int red = (argb >> 16) & 0xFF;
                    int green = (argb >> 8) & 0xFF;
                    int blue = argb & 0xFF;
                    
                    // Put in RGBA order
                    buffer.put((byte) red);
                    buffer.put((byte) green);
                    buffer.put((byte) blue);
                    buffer.put((byte) alpha);
                }
            }
            
            buffer.flip();
            return buffer;
            
        } catch (Exception e) {
            System.err.println("GifTextureLoader: Failed to convert image to RGBA buffer: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Loads Errockson.gif and returns it as an RGBA ByteBuffer ready for OpenGL.
     * @return ByteBuffer containing RGBA pixel data, or null if loading fails
     */
    public static ByteBuffer loadErrocksonAsRGBA() {
        BufferedImage image = loadErrocksonGif();
        if (image == null) {
            System.err.println("GifTextureLoader: Failed to load Errockson.gif image");
            return null;
        }
        
        ByteBuffer buffer = imageToRGBABuffer(image);
        if (buffer == null) {
            System.err.println("GifTextureLoader: Failed to convert Errockson.gif to RGBA buffer");
            return null;
        }
        
        System.out.println("GifTextureLoader: Successfully converted Errockson.gif to RGBA buffer");
        return buffer;
    }
    
    /**
     * Creates a fallback error texture if Errockson.gif cannot be loaded.
     * Returns a 16x16 magenta texture as a recognizable error indicator.
     * @return ByteBuffer containing a magenta error texture
     */
    public static ByteBuffer createFallbackErrorTexture() {
        ByteBuffer buffer = BufferUtils.createByteBuffer(EXPECTED_SIZE * EXPECTED_SIZE * 4);
        
        // Fill with magenta color (255, 0, 255, 255)
        for (int i = 0; i < EXPECTED_SIZE * EXPECTED_SIZE; i++) {
            buffer.put((byte) 255); // Red
            buffer.put((byte) 0);   // Green
            buffer.put((byte) 255); // Blue
            buffer.put((byte) 255); // Alpha
        }
        
        buffer.flip();
        System.out.println("GifTextureLoader: Created fallback magenta error texture");
        return buffer;
    }
    
    /**
     * Gets the error texture, attempting to load Errockson.gif first, 
     * falling back to a magenta texture if loading fails.
     * @return ByteBuffer containing error texture data (never null)
     */
    public static ByteBuffer getErrorTexture() {
        ByteBuffer errorTexture = loadErrocksonAsRGBA();
        if (errorTexture == null) {
            System.err.println("GifTextureLoader: Errockson.gif failed to load, using fallback error texture");
            errorTexture = createFallbackErrorTexture();
        }
        return errorTexture;
    }
    
    /**
     * Validates that Errockson.gif can be loaded successfully.
     * @return true if Errockson.gif loads and has correct dimensions, false otherwise
     */
    public static boolean validateErrocksonGif() {
        BufferedImage image = loadErrocksonGif();
        if (image == null) {
            return false;
        }
        
        boolean validDimensions = (image.getWidth() == EXPECTED_SIZE && image.getHeight() == EXPECTED_SIZE);
        if (!validDimensions) {
            System.err.println("GifTextureLoader: Errockson.gif validation failed - incorrect dimensions");
        }
        
        return validDimensions;
    }
}
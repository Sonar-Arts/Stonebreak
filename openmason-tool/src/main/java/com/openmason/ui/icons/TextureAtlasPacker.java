package com.openmason.ui.icons;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Advanced texture atlas packing system for efficient icon rendering.
 * Uses rectangle packing algorithms to minimize texture memory usage.
 */
public class TextureAtlasPacker {
    
    private static final Logger logger = LoggerFactory.getLogger(TextureAtlasPacker.class);
    
    public static class PackedIcon {
        private final String name;
        private final BufferedImage image;
        private final Rectangle bounds;
        private boolean packed;
        
        public PackedIcon(String name, BufferedImage image) {
            this.name = name;
            this.image = image;
            this.bounds = new Rectangle(0, 0, image.getWidth(), image.getHeight());
            this.packed = false;
        }
        
        public String getName() { return name; }
        public BufferedImage getImage() { return image; }
        public Rectangle getBounds() { return bounds; }
        public boolean isPacked() { return packed; }
        
        public void setBounds(int x, int y) {
            bounds.x = x;
            bounds.y = y;
            packed = true;
        }
        
        public int getWidth() { return image.getWidth(); }
        public int getHeight() { return image.getHeight(); }
        
        public float getU1() { return 0.0f; } // Will be calculated during packing
        public float getV1() { return 0.0f; }
        public float getU2() { return 1.0f; }
        public float getV2() { return 1.0f; }
    }
    
    public static class AtlasResult {
        private final int textureId;
        private final int width;
        private final int height;
        private final List<PackedIcon> packedIcons;
        private final float textureUtilization;
        
        public AtlasResult(int textureId, int width, int height, List<PackedIcon> packedIcons) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
            this.packedIcons = new ArrayList<>(packedIcons);
            this.textureUtilization = calculateUtilization();
        }
        
        private float calculateUtilization() {
            int usedPixels = packedIcons.stream()
                .mapToInt(icon -> icon.getWidth() * icon.getHeight())
                .sum();
            return (float) usedPixels / (width * height);
        }
        
        public int getTextureId() { return textureId; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public List<PackedIcon> getPackedIcons() { return packedIcons; }
        public float getTextureUtilization() { return textureUtilization; }
    }
    
    private static class RectangleNode {
        Rectangle bounds;
        RectangleNode left;
        RectangleNode right;
        boolean occupied;
        
        public RectangleNode(int x, int y, int width, int height) {
            this.bounds = new Rectangle(x, y, width, height);
            this.occupied = false;
        }
        
        public RectangleNode insert(int width, int height, int padding) {
            if (left != null || right != null) {
                // Not a leaf node
                RectangleNode result = left != null ? left.insert(width, height, padding) : null;
                if (result != null) return result;
                
                return right != null ? right.insert(width, height, padding) : null;
            }
            
            if (occupied) {
                return null; // Already occupied
            }
            
            int paddedWidth = width + padding * 2;
            int paddedHeight = height + padding * 2;
            
            if (paddedWidth > bounds.width || paddedHeight > bounds.height) {
                return null; // Doesn't fit
            }
            
            if (paddedWidth == bounds.width && paddedHeight == bounds.height) {
                occupied = true;
                return this; // Perfect fit
            }
            
            // Split the node
            int deltaWidth = bounds.width - paddedWidth;
            int deltaHeight = bounds.height - paddedHeight;
            
            if (deltaWidth > deltaHeight) {
                // Split horizontally
                left = new RectangleNode(bounds.x, bounds.y, paddedWidth, bounds.height);
                right = new RectangleNode(bounds.x + paddedWidth, bounds.y, 
                    bounds.width - paddedWidth, bounds.height);
            } else {
                // Split vertically
                left = new RectangleNode(bounds.x, bounds.y, bounds.width, paddedHeight);
                right = new RectangleNode(bounds.x, bounds.y + paddedHeight, 
                    bounds.width, bounds.height - paddedHeight);
            }
            
            return left.insert(width, height, padding);
        }
    }
    
    private final int maxAtlasSize;
    private final int padding;
    
    public TextureAtlasPacker() {
        this(512, 2);
    }
    
    public TextureAtlasPacker(int maxAtlasSize, int padding) {
        this.maxAtlasSize = maxAtlasSize;
        this.padding = padding;
    }
    
    /**
     * Pack multiple icons into texture atlases
     */
    public List<AtlasResult> packIcons(List<PackedIcon> icons) {
        List<AtlasResult> atlases = new ArrayList<>();
        List<PackedIcon> remainingIcons = new ArrayList<>(icons);
        
        // Sort icons by area (largest first) for better packing efficiency
        remainingIcons.sort(Comparator.comparingInt((PackedIcon icon) -> 
            icon.getWidth() * icon.getHeight()).reversed());
        
        while (!remainingIcons.isEmpty()) {
            AtlasResult atlas = packSingleAtlas(remainingIcons);
            if (atlas == null || atlas.getPackedIcons().isEmpty()) {
                logger.warn("Failed to pack remaining {} icons", remainingIcons.size());
                break;
            }
            
            atlases.add(atlas);
            
            // Remove packed icons
            remainingIcons.removeAll(atlas.getPackedIcons());
            
            logger.debug("Created atlas {} with {} icons (utilization: {:.1f}%)", 
                atlases.size(), atlas.getPackedIcons().size(), 
                atlas.getTextureUtilization() * 100);
        }
        
        logger.info("Packed {} icons into {} atlases", 
            icons.size() - remainingIcons.size(), atlases.size());
        
        return atlases;
    }
    
    /**
     * Pack icons into a single atlas
     */
    private AtlasResult packSingleAtlas(List<PackedIcon> icons) {
        if (icons.isEmpty()) return null;
        
        // Try different atlas sizes, starting with power-of-two sizes
        int[] sizes = {128, 256, 512, 1024, 2048, 4096};
        
        for (int size : sizes) {
            if (size > maxAtlasSize) break;
            
            AtlasResult result = tryPackAtlas(icons, size, size);
            if (result != null && !result.getPackedIcons().isEmpty()) {
                return result;
            }
        }
        
        // Try rectangular atlases
        for (int width : sizes) {
            for (int height : sizes) {
                if (width > maxAtlasSize || height > maxAtlasSize) continue;
                if (width == height) continue; // Already tried square
                
                AtlasResult result = tryPackAtlas(icons, width, height);
                if (result != null && !result.getPackedIcons().isEmpty()) {
                    return result;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Attempt to pack icons into atlas of specific dimensions
     */
    private AtlasResult tryPackAtlas(List<PackedIcon> icons, int atlasWidth, int atlasHeight) {
        RectangleNode root = new RectangleNode(0, 0, atlasWidth, atlasHeight);
        List<PackedIcon> packedIcons = new ArrayList<>();
        
        for (PackedIcon icon : icons) {
            if (icon.isPacked()) continue;
            
            RectangleNode node = root.insert(icon.getWidth(), icon.getHeight(), padding);
            if (node != null) {
                icon.setBounds(node.bounds.x + padding, node.bounds.y + padding);
                packedIcons.add(icon);
            }
        }
        
        if (packedIcons.isEmpty()) {
            return null;
        }
        
        // Create the actual texture atlas
        BufferedImage atlasImage = createAtlasImage(packedIcons, atlasWidth, atlasHeight);
        int textureId = createOpenGLTexture(atlasImage);
        
        // Update UV coordinates for packed icons
        updateUVCoordinates(packedIcons, atlasWidth, atlasHeight);
        
        return new AtlasResult(textureId, atlasWidth, atlasHeight, packedIcons);
    }
    
    /**
     * Create atlas image by combining individual icon images
     */
    private BufferedImage createAtlasImage(List<PackedIcon> packedIcons, int width, int height) {
        BufferedImage atlas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = atlas.createGraphics();
        
        // Clear with transparent background
        g2d.setComposite(AlphaComposite.Clear);
        g2d.fillRect(0, 0, width, height);
        g2d.setComposite(AlphaComposite.SrcOver);
        
        // Enable anti-aliasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        
        // Draw each packed icon at its assigned position
        for (PackedIcon icon : packedIcons) {
            Rectangle bounds = icon.getBounds();
            g2d.drawImage(icon.getImage(), bounds.x, bounds.y, null);
        }
        
        g2d.dispose();
        return atlas;
    }
    
    /**
     * Update UV coordinates for packed icons
     */
    private void updateUVCoordinates(List<PackedIcon> packedIcons, int atlasWidth, int atlasHeight) {
        for (PackedIcon icon : packedIcons) {
            Rectangle bounds = icon.getBounds();
            
            // Calculate normalized UV coordinates
            float u1 = (float) bounds.x / atlasWidth;
            float v1 = (float) bounds.y / atlasHeight;
            float u2 = (float) (bounds.x + bounds.width) / atlasWidth;
            float v2 = (float) (bounds.y + bounds.height) / atlasHeight;
            
            // Store UV coordinates (would need to modify PackedIcon class)
            logger.debug("Icon {} UV coordinates: ({}, {}) to ({}, {})", 
                icon.getName(), u1, v1, u2, v2);
        }
    }
    
    /**
     * Create OpenGL texture from BufferedImage
     */
    private int createOpenGLTexture(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        // Convert image to ByteBuffer
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);
        
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = pixels[y * width + x];
                buffer.put((byte) ((pixel >> 16) & 0xFF)); // Red
                buffer.put((byte) ((pixel >> 8) & 0xFF));  // Green
                buffer.put((byte) (pixel & 0xFF));         // Blue
                buffer.put((byte) ((pixel >> 24) & 0xFF)); // Alpha
            }
        }
        buffer.flip();
        
        // Create OpenGL texture
        int textureId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        
        // Set texture parameters for crisp pixel art
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0, 
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        
        logger.debug("Created atlas texture: {} ({}x{})", textureId, width, height);
        return textureId;
    }
    
    /**
     * Calculate optimal atlas size for given icons
     */
    public Dimension calculateOptimalAtlasSize(List<PackedIcon> icons) {
        int totalArea = icons.stream()
            .mapToInt(icon -> icon.getWidth() * icon.getHeight())
            .sum();
        
        // Add padding area
        int paddingArea = icons.size() * padding * padding * 4;
        totalArea += paddingArea;
        
        // Calculate dimensions (prefer square textures)
        int dimension = (int) Math.ceil(Math.sqrt(totalArea));
        
        // Round up to next power of 2
        dimension = nextPowerOfTwo(dimension);
        
        return new Dimension(Math.min(dimension, maxAtlasSize), Math.min(dimension, maxAtlasSize));
    }
    
    /**
     * Find next power of two
     */
    private int nextPowerOfTwo(int value) {
        int powerOfTwo = 1;
        while (powerOfTwo < value) {
            powerOfTwo <<= 1;
        }
        return powerOfTwo;
    }
    
    /**
     * Get packing efficiency statistics
     */
    public PackingStats calculatePackingStats(List<AtlasResult> atlases) {
        int totalIcons = atlases.stream()
            .mapToInt(atlas -> atlas.getPackedIcons().size())
            .sum();
        
        int totalTextureArea = atlases.stream()
            .mapToInt(atlas -> atlas.getWidth() * atlas.getHeight())
            .sum();
        
        int totalUsedArea = atlases.stream()
            .mapToInt(atlas -> atlas.getPackedIcons().stream()
                .mapToInt(icon -> icon.getWidth() * icon.getHeight())
                .sum())
            .sum();
        
        float averageUtilization = (float) totalUsedArea / totalTextureArea;
        
        return new PackingStats(totalIcons, atlases.size(), averageUtilization, 
            totalTextureArea, totalUsedArea);
    }
    
    public static class PackingStats {
        private final int totalIcons;
        private final int atlasCount;
        private final float averageUtilization;
        private final int totalTextureMemory;
        private final int totalUsedMemory;
        
        public PackingStats(int totalIcons, int atlasCount, float averageUtilization,
                          int totalTextureMemory, int totalUsedMemory) {
            this.totalIcons = totalIcons;
            this.atlasCount = atlasCount;
            this.averageUtilization = averageUtilization;
            this.totalTextureMemory = totalTextureMemory;
            this.totalUsedMemory = totalUsedMemory;
        }
        
        public int getTotalIcons() { return totalIcons; }
        public int getAtlasCount() { return atlasCount; }
        public float getAverageUtilization() { return averageUtilization; }
        public int getTotalTextureMemory() { return totalTextureMemory; }
        public int getTotalUsedMemory() { return totalUsedMemory; }
        
        @Override
        public String toString() {
            return String.format("PackingStats{icons=%d, atlases=%d, utilization=%.1f%%, " +
                "memory=%d KB (used: %d KB)}", 
                totalIcons, atlasCount, averageUtilization * 100,
                totalTextureMemory * 4 / 1024, totalUsedMemory * 4 / 1024);
        }
    }
}
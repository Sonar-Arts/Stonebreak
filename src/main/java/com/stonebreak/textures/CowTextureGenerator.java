package com.stonebreak.textures;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Map;
import org.lwjgl.BufferUtils;

/**
 * Procedurally generates cow texture atlas from JSON color and coordinate data.
 * Creates a 256x256 texture atlas with 16x16 grid layout using baseColors from cow variants.
 */
public class CowTextureGenerator {
    
    private static final int ATLAS_WIDTH = 256;
    private static final int ATLAS_HEIGHT = 256;
    private static final int GRID_SIZE = 16;
    private static final int TILE_SIZE = ATLAS_WIDTH / GRID_SIZE; // 16 pixels per tile
    
    /**
     * Generate a complete cow texture atlas from the JSON definition.
     * 
     * @param textureDefinition The loaded cow texture definition from JSON
     * @return ByteBuffer containing RGBA pixel data for OpenGL texture loading
     */
    public static ByteBuffer generateTextureAtlas(CowTextureDefinition textureDefinition) {
        BufferedImage atlas = new BufferedImage(ATLAS_WIDTH, ATLAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = atlas.createGraphics();
        
        // Enable antialiasing for smoother textures
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        
        // Fill background with transparent
        g2d.setColor(new Color(0, 0, 0, 0));
        g2d.fillRect(0, 0, ATLAS_WIDTH, ATLAS_HEIGHT);
        
        // Generate textures for each cow variant
        Map<String, CowTextureDefinition.CowVariant> variants = textureDefinition.getCowVariants();
        
        for (Map.Entry<String, CowTextureDefinition.CowVariant> entry : variants.entrySet()) {
            String variantName = entry.getKey();
            CowTextureDefinition.CowVariant variant = entry.getValue();
            
            System.out.println("[CowTextureGenerator] Generating textures for variant: " + variantName);
            generateVariantTextures(g2d, variant, variantName);
        }
        
        g2d.dispose();
        
        // Convert BufferedImage to ByteBuffer for OpenGL
        return convertToByteBuffer(atlas);
    }
    
    /**
     * Generate textures for a specific cow variant.
     */
    private static void generateVariantTextures(Graphics2D g2d, CowTextureDefinition.CowVariant variant, String variantName) {
        if (variant.getBaseColors() == null || variant.getFaceMappings() == null) {
            System.err.println("[CowTextureGenerator] Missing color or mapping data for variant: " + variantName);
            return;
        }
        
        // Parse base colors
        Color primaryColor = parseHexColor(variant.getBaseColors().getPrimary());
        Color secondaryColor = parseHexColor(variant.getBaseColors().getSecondary());
        Color accentColor = parseHexColor(variant.getBaseColors().getAccent());
        
        // Generate textures for each mapped face
        Map<String, CowTextureDefinition.AtlasCoordinate> faceMappings = variant.getFaceMappings();
        
        for (Map.Entry<String, CowTextureDefinition.AtlasCoordinate> faceEntry : faceMappings.entrySet()) {
            String faceName = faceEntry.getKey();
            CowTextureDefinition.AtlasCoordinate coord = faceEntry.getValue();
            
            generateTileTexture(g2d, faceName, coord, primaryColor, secondaryColor, accentColor, variantName);
        }
    }
    
    /**
     * Generate texture for a specific tile based on body part type.
     */
    private static void generateTileTexture(Graphics2D g2d, String faceName, CowTextureDefinition.AtlasCoordinate coord,
                                          Color primary, Color secondary, Color accent, String variantName) {
        
        int x = coord.getAtlasX() * TILE_SIZE;
        int y = coord.getAtlasY() * TILE_SIZE;
        
        // Determine color and pattern based on body part
        if (faceName.startsWith("HEAD_")) {
            generateHeadTexture(g2d, x, y, primary, secondary, faceName, variantName);
        } else if (faceName.startsWith("BODY_")) {
            generateBodyTexture(g2d, x, y, primary, secondary, faceName);
        } else if (faceName.startsWith("LEG_")) {
            generateLegTexture(g2d, x, y, primary, faceName);
        } else if (faceName.startsWith("HORNS_")) {
            generateHornTexture(g2d, x, y, accent, faceName);
        } else if (faceName.startsWith("TAIL_")) {
            generateTailTexture(g2d, x, y, primary, accent, faceName);
        } else if (faceName.startsWith("UDDER_")) {
            generateUdderTexture(g2d, x, y, secondary, faceName);
        } else {
            // Fallback - solid primary color
            g2d.setColor(primary);
            g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
        }
        
        // Add subtle border for debugging
        g2d.setColor(new Color(0, 0, 0, 50));
        g2d.drawRect(x, y, TILE_SIZE - 1, TILE_SIZE - 1);
    }
    
    /**
     * Generate head texture with cute but derpy facial features.
     */
    private static void generateHeadTexture(Graphics2D g2d, int x, int y, Color primary, Color secondary, String faceName, String variantName) {
        // Base head color
        g2d.setColor(primary);
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
        
        if (faceName.equals("HEAD_FRONT")) {
            // Back of head texture (swapped to fix orientation)
            generateBackHeadTexture(g2d, x, y, primary, secondary, variantName);
        } else if (faceName.equals("HEAD_BACK")) {
            // Generate cute face based on variant (swapped to fix orientation)
            generateDerpyFace(g2d, x, y, primary, secondary, variantName);
        } else if (faceName.equals("HEAD_TOP")) {
            // Top of head texture
            generateTopHeadTexture(g2d, x, y, primary, secondary, variantName);
        } else if (faceName.equals("HEAD_BOTTOM")) {
            // Bottom of head (under chin) texture
            generateBottomHeadTexture(g2d, x, y, primary, secondary, variantName);
        } else if (faceName.equals("HEAD_LEFT")) {
            // Left side profile
            generateSideHeadTexture(g2d, x, y, primary, secondary, variantName, true);
        } else if (faceName.equals("HEAD_RIGHT")) {
            // Right side profile
            generateSideHeadTexture(g2d, x, y, primary, secondary, variantName, false);
        }
    }
    
    /**
     * Generate cute face with variant-specific personality.
     */
    private static void generateDerpyFace(Graphics2D g2d, int x, int y, Color primary, Color secondary, String variantName) {
        // Save the original transform
        var originalTransform = g2d.getTransform();
        
        // Apply 180-degree rotation around the center of the tile
        g2d.translate(x + TILE_SIZE/2, y + TILE_SIZE/2);
        g2d.rotate(Math.PI); // 180 degrees in radians
        g2d.translate(-TILE_SIZE/2, -TILE_SIZE/2);
        
        // For very dark cows like Angus, lighten the face area slightly for better contrast
        if (variantName.equals("angus")) {
            g2d.setColor(lighten(primary, 0.2f));
            g2d.fillRect(0, 0, TILE_SIZE, TILE_SIZE);
            System.out.println("[CowTextureGenerator] Applying lightened background for Angus cow face at coordinates relative to 0,0");
        }
        
        // Debug logging for Jersey cow
        if (variantName.equals("jersey")) {
            System.out.println("[CowTextureGenerator] Generating Jersey cow face with primary: " + 
                String.format("#%06X", primary.getRGB() & 0xFFFFFF) + " at coordinates relative to 0,0");
        }
        
        // Draw face based on variant personality (coordinates now relative to 0,0)
        switch (variantName) {
            case "default" -> drawCrossEyedFace(g2d, 0, 0, primary, secondary);
            case "angus" -> drawSleepyFace(g2d, 0, 0, primary, secondary);
            case "highland" -> drawSurprisedFace(g2d, 0, 0, primary, secondary);
            case "jersey" -> drawInnocentFace(g2d, 0, 0, primary, secondary);
            default -> drawHappyFace(g2d, 0, 0, primary, secondary);
        }
        
        // Add cute blush spots (coordinates relative to 0,0)
        g2d.setColor(new Color(255, 182, 193, 150)); // Light pink with transparency
        g2d.fillOval(2, 7, 2, 2); // Left cheek
        g2d.fillOval(12, 7, 2, 2); // Right cheek
        
        // Restore the original transform
        g2d.setTransform(originalTransform);
    }
    
    /**
     * Draw friendly forward-looking face (default cow).
     */
    private static void drawCrossEyedFace(Graphics2D g2d, int x, int y, Color primary, Color secondary) {
        // Larger, more expressive white eye backgrounds
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 2, y + 3, 5, 4);
        g2d.fillOval(x + 9, y + 3, 5, 4);
        
        // Normal forward-looking pupils (no longer cross-eyed)
        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + 4, y + 4, 2, 2); // Left eye looking forward
        g2d.fillOval(x + 11, y + 4, 2, 2); // Right eye looking forward
        
        // Bright eye highlights for life and character
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 4, y + 4, 1, 1);
        g2d.fillOval(x + 11, y + 4, 1, 1);
        g2d.fillOval(x + 5, y + 5, 1, 1); // Extra sparkle
        g2d.fillOval(x + 12, y + 5, 1, 1);
        
        // Gentle eyebrows for expression
        g2d.setColor(darken(primary, 0.3f));
        g2d.drawArc(x + 2, y + 2, 5, 3, 0, 180); // Left eyebrow
        g2d.drawArc(x + 9, y + 2, 5, 3, 0, 180); // Right eyebrow
        
        // Warm, gentle smile
        g2d.setColor(Color.BLACK);
        g2d.drawArc(x + 5, y + 10, 6, 4, 0, -180);
        
        // More detailed pink nose with better proportions
        g2d.setColor(Color.PINK);
        g2d.fillOval(x + 6, y + 7, 4, 4);
        
        // Well-defined nostrils
        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + 7, y + 8, 1, 2); // Left nostril
        g2d.fillOval(x + 8, y + 8, 1, 2); // Right nostril
        
        // Nose highlight for wet, healthy look
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 7, y + 7, 2, 1);
        
        // Subtle chin definition
        g2d.setColor(darken(primary, 0.1f));
        g2d.fillOval(x + 6, y + 12, 4, 2);
    }
    
    /**
     * Draw confident stoic face (angus cow) with distinctive character.
     */
    private static void drawSleepyFace(Graphics2D g2d, int x, int y, Color primary, Color secondary) {
        // === DISTINCTIVE WHITE FACIAL BLAZE ===
        
        // Characteristic white blaze down the center of face
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 6, y + 1, 4, 12); // Central white stripe
        g2d.fillOval(x + 7, y + 0, 2, 14); // Extended narrow blaze
        
        // === BOLD, CONFIDENT EYE STRUCTURES ===
        
        // Wide-set, determined eye sockets with more masculine shape
        g2d.setColor(darken(primary, 0.15f));
        g2d.fillOval(x + 1, y + 3, 6, 4); // Left eye socket (wider set)
        g2d.fillOval(x + 9, y + 3, 6, 4); // Right eye socket (wider set)
        
        // Strong, confident eye whites with rectangular shape
        g2d.setColor(Color.WHITE);
        g2d.fillRect(x + 2, y + 3, 4, 3); // Left eye (more rectangular)
        g2d.fillRect(x + 10, y + 3, 4, 3); // Right eye (more rectangular)
        
        // Bold, heavy eyebrow ridges for masculine appearance
        g2d.setColor(darken(primary, 0.25f));
        g2d.fillRect(x + 1, y + 2, 5, 2); // Left heavy eyebrow
        g2d.fillRect(x + 10, y + 2, 5, 2); // Right heavy eyebrow
        
        // Determined, focused pupils with intense gaze
        g2d.setColor(new Color(139, 69, 19)); // Darker brown iris for intensity
        g2d.fillOval(x + 3, y + 4, 2, 2); // Left iris (smaller, focused)
        g2d.fillOval(x + 11, y + 4, 2, 2); // Right iris (smaller, focused)
        
        // Sharp, focused pupils
        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + 3, y + 4, 1, 2); // Left pupil (vertical slit)
        g2d.fillOval(x + 11, y + 4, 1, 2); // Right pupil (vertical slit)
        
        // Minimal eye highlights for serious expression
        g2d.setColor(Color.WHITE);
        g2d.fillRect(x + 4, y + 4, 1, 1); // Left highlight (single point)
        g2d.fillRect(x + 12, y + 4, 1, 1); // Right highlight (single point)
        
        // === WEATHERED, MATURE FACIAL FEATURES ===
        
        // Strong jawline definition
        g2d.setColor(darken(primary, 0.2f));
        g2d.fillRect(x + 0, y + 8, 7, 4); // Left jaw muscle
        g2d.fillRect(x + 9, y + 8, 7, 4); // Right jaw muscle
        
        // Deep-set cheek hollows for mature appearance
        g2d.setColor(darken(primary, 0.18f));
        g2d.fillOval(x + 2, y + 7, 3, 4); // Left cheek hollow
        g2d.fillOval(x + 11, y + 7, 3, 4); // Right cheek hollow
        
        // === DISTINCTIVE ANGUS NOSE WITH CHARACTER ===
        
        // Broader, more robust nose structure
        g2d.setColor(darken(primary, 0.1f));
        g2d.fillRect(x + 5, y + 7, 6, 5); // Broader nose bridge
        
        // Distinctive large, dark nose typical of Angus cattle
        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + 5, y + 8, 6, 4); // Large black nose
        
        // Wide nostril openings for working cattle
        g2d.setColor(darken(primary, 0.5f));
        g2d.fillOval(x + 6, y + 9, 2, 2); // Left nostril (larger)
        g2d.fillOval(x + 8, y + 9, 2, 2); // Right nostril (larger)
        
        // Minimal nose highlights on dark nose
        g2d.setColor(new Color(100, 100, 100)); // Dark gray instead of white
        g2d.fillRect(x + 6, y + 8, 2, 1); // Subtle nose highlight
        
        // === STOIC MOUTH EXPRESSION ===
        
        // Straight, neutral mouth showing determination
        g2d.setColor(Color.BLACK);
        g2d.drawLine(x + 5, y + 12, x + 11, y + 12); // Straight mouth line
        
        // Strong mouth corners
        g2d.setColor(darken(primary, 0.2f));
        g2d.fillRect(x + 4, y + 11, 2, 2); // Left mouth corner
        g2d.fillRect(x + 10, y + 11, 2, 2); // Right mouth corner
        
        // === BATTLE-TESTED FACIAL MARKINGS ===
        
        // Small scar mark for character
        g2d.setColor(lighten(primary, 0.3f));
        g2d.drawLine(x + 3, y + 6, x + 4, y + 7); // Left cheek scar
        
        // Age lines around eyes for wisdom
        g2d.setColor(darken(primary, 0.12f));
        g2d.drawLine(x + 1, y + 5, x + 2, y + 6); // Left crow's feet
        g2d.drawLine(x + 1, y + 6, x + 2, y + 7);
        g2d.drawLine(x + 14, y + 5, x + 15, y + 6); // Right crow's feet
        g2d.drawLine(x + 14, y + 6, x + 15, y + 7);
        
        // === RUGGED FUR TEXTURE ===
        
        // Coarser fur texture around face edges
        g2d.setColor(darken(primary, 0.2f));
        g2d.drawLine(x + 0, y + 2, x + 1, y + 4); // Left edge fur
        g2d.drawLine(x + 0, y + 5, x + 1, y + 7);
        g2d.drawLine(x + 15, y + 2, x + 14, y + 4); // Right edge fur
        g2d.drawLine(x + 15, y + 5, x + 14, y + 7);
        
        // Forehead texture with center blaze
        g2d.setColor(darken(primary, 0.15f));
        g2d.drawLine(x + 4, y + 1, x + 5, y + 2); // Left forehead
        g2d.drawLine(x + 11, y + 1, x + 10, y + 2); // Right forehead
        
        // === CONFIDENT FACIAL STRUCTURE ===
        
        // Strong brow ridge
        g2d.setColor(darken(primary, 0.3f));
        g2d.fillRect(x + 2, y + 1, 4, 1); // Left brow ridge
        g2d.fillRect(x + 10, y + 1, 4, 1); // Right brow ridge
        
        // Defined chin area
        g2d.setColor(darken(primary, 0.1f));
        g2d.fillOval(x + 5, y + 13, 6, 2); // Strong chin definition
        
        // Overall mature facial structure
        g2d.setColor(darken(primary, 0.08f));
        g2d.drawRect(x + 1, y + 2, 14, 12); // Strong face outline
    }
    
    /**
     * Draw cute curious face (highland cow).
     */
    private static void drawSurprisedFace(Graphics2D g2d, int x, int y, Color primary, Color secondary) {
        // Large, bright curious eyes
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 2, y + 3, 5, 4);
        g2d.fillOval(x + 9, y + 3, 5, 4);
        
        // Alert, attentive pupils
        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + 4, y + 4, 2, 2);
        g2d.fillOval(x + 11, y + 4, 2, 2);
        
        // Bright, sparkly eye highlights
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 4, y + 4, 1, 1);
        g2d.fillOval(x + 11, y + 4, 1, 1);
        g2d.fillOval(x + 5, y + 5, 1, 1); // Extra sparkle
        g2d.fillOval(x + 12, y + 5, 1, 1);
        
        // Raised eyebrows for curious expression
        g2d.setColor(darken(primary, 0.25f));
        g2d.drawArc(x + 2, y + 1, 5, 3, 0, 180); // Left raised eyebrow
        g2d.drawArc(x + 9, y + 1, 5, 3, 0, 180); // Right raised eyebrow
        
        // Small curious "oh!" mouth
        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + 7, y + 11, 2, 2);
        g2d.setColor(Color.PINK);
        g2d.fillOval(x + 7, y + 11, 1, 1); // Inner mouth hint
        
        // Detailed nose with character
        g2d.setColor(Color.PINK);
        g2d.fillOval(x + 6, y + 8, 4, 3);
        
        // Well-defined nostrils
        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + 7, y + 9, 1, 1);
        g2d.fillOval(x + 8, y + 9, 1, 1);
        
        // Nose highlight
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 7, y + 8, 2, 1);
        
        // Highland cow fur tufts on forehead
        g2d.setColor(secondary);
        g2d.fillOval(x + 6, y + 1, 2, 2);
        g2d.fillOval(x + 8, y + 1, 2, 2);
        
        // Cute cheek blush for endearing look
        g2d.setColor(new Color(255, 182, 193, 120));
        g2d.fillOval(x + 1, y + 7, 2, 2);
        g2d.fillOval(x + 13, y + 7, 2, 2);
    }
    
    /**
     * Draw sweet innocent face (jersey cow).
     */
    private static void drawInnocentFace(Graphics2D g2d, int x, int y, Color primary, Color secondary) {
        // Beautiful large innocent eyes
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 2, y + 3, 5, 4);
        g2d.fillOval(x + 9, y + 3, 5, 4);
        
        // Gentle forward-looking pupils
        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + 4, y + 4, 2, 2);
        g2d.fillOval(x + 11, y + 4, 2, 2);
        
        // Multi-layered sparkly highlights
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 4, y + 4, 1, 1);
        g2d.fillOval(x + 11, y + 4, 1, 1);
        g2d.fillOval(x + 5, y + 5, 1, 1); // Extra sparkle
        g2d.fillOval(x + 12, y + 5, 1, 1);
        g2d.fillOval(x + 3, y + 5, 1, 1); // Corner sparkles
        g2d.fillOval(x + 10, y + 5, 1, 1);
        
        // Elegant, refined eyelashes
        g2d.setColor(Color.BLACK);
        g2d.drawLine(x + 2, y + 2, x + 3, y + 3); // Outer curved lash
        g2d.drawLine(x + 4, y + 1, x + 4, y + 3); // Long center lash
        g2d.drawLine(x + 6, y + 2, x + 6, y + 3); // Inner lash
        g2d.drawLine(x + 9, y + 2, x + 10, y + 3); // Right outer lash
        g2d.drawLine(x + 11, y + 1, x + 11, y + 3); // Right center lash
        g2d.drawLine(x + 13, y + 2, x + 13, y + 3); // Right inner lash
        
        // Sweet, gentle smile
        g2d.setColor(Color.BLACK);
        g2d.drawArc(x + 5, y + 10, 6, 3, 0, -180);
        
        // Refined heart-shaped nose
        g2d.setColor(Color.PINK);
        g2d.fillOval(x + 6, y + 7, 2, 2); // Left heart lobe
        g2d.fillOval(x + 8, y + 7, 2, 2); // Right heart lobe
        g2d.fillOval(x + 6, y + 8, 4, 3); // Heart bottom
        
        // Delicate nostril definition
        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + 7, y + 9, 1, 1);
        g2d.fillOval(x + 8, y + 9, 1, 1);
        
        // Heart nose highlight
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 7, y + 7, 2, 1);
        
        // Charming freckles (more refined placement)
        g2d.setColor(darken(primary, 0.25f));
        g2d.fillOval(x + 3, y + 6, 1, 1);
        g2d.fillOval(x + 12, y + 6, 1, 1);
        g2d.fillOval(x + 5, y + 12, 1, 1);
        g2d.fillOval(x + 10, y + 12, 1, 1);
        
        // Rosy cheek blush
        g2d.setColor(new Color(255, 182, 193, 140));
        g2d.fillOval(x + 1, y + 7, 2, 2);
        g2d.fillOval(x + 13, y + 7, 2, 2);
        
        // Sweet dimples when smiling
        g2d.setColor(darken(primary, 0.08f));
        g2d.fillOval(x + 4, y + 9, 1, 1);
        g2d.fillOval(x + 11, y + 9, 1, 1);
    }
    
    /**
     * Draw happy face (fallback).
     */
    private static void drawHappyFace(Graphics2D g2d, int x, int y, Color primary, Color secondary) {
        // Normal happy eyes
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 3, y + 3, 4, 4);
        g2d.fillOval(x + 9, y + 3, 4, 4);
        
        // Happy pupils
        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + 4, y + 4, 2, 2);
        g2d.fillOval(x + 10, y + 4, 2, 2);
        
        // Eye highlights
        g2d.setColor(Color.WHITE);
        g2d.fillOval(x + 5, y + 4, 1, 1);
        g2d.fillOval(x + 11, y + 4, 1, 1);
        
        // Big happy smile
        g2d.setColor(Color.BLACK);
        g2d.drawArc(x + 5, y + 10, 6, 4, 0, -180);
        
        // Pink tongue sticking out slightly
        g2d.setColor(Color.PINK);
        g2d.fillOval(x + 7, y + 12, 2, 2);
        
        // Nose
        g2d.setColor(Color.PINK);
        g2d.fillOval(x + 6, y + 8, 4, 3);
        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + 7, y + 9, 1, 1);
        g2d.fillOval(x + 8, y + 9, 1, 1);
    }
    
    /**
     * Generate back of head texture with fur/hair details.
     */
    private static void generateBackHeadTexture(Graphics2D g2d, int x, int y, Color primary, Color secondary, String variantName) {
        // Slightly darker base for back of head
        g2d.setColor(darken(primary, 0.1f));
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
        
        // Add fur/hair texture pattern
        g2d.setColor(darken(primary, 0.2f));
        // Hair strands/fur pattern
        for (int i = 0; i < 6; i++) {
            int hairX = x + 2 + (i * 2);
            int hairY = y + 2 + (i % 3);
            g2d.drawLine(hairX, hairY, hairX, hairY + 3);
        }
        
        // Back ear hints for some variants
        if (variantName.equals("highland")) {
            // Highland cows have fluffier back hair
            g2d.setColor(secondary);
            g2d.fillOval(x + 4, y + 3, 8, 6);
            g2d.setColor(darken(primary, 0.15f));
            g2d.fillOval(x + 5, y + 4, 6, 4);
        }
    }
    
    /**
     * Generate top of head texture.
     */
    private static void generateTopHeadTexture(Graphics2D g2d, int x, int y, Color primary, Color secondary, String variantName) {
        // Base top color
        g2d.setColor(darken(primary, 0.05f));
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
        
        // Central head area
        g2d.setColor(primary);
        g2d.fillOval(x + 3, y + 3, 10, 10);
        
        // Ear positions (where ears attach)
        g2d.setColor(darken(primary, 0.1f));
        g2d.fillOval(x + 1, y + 5, 4, 6); // Left ear base
        g2d.fillOval(x + 11, y + 5, 4, 6); // Right ear base
        
        // Inner ear spots
        g2d.setColor(secondary);
        g2d.fillOval(x + 2, y + 6, 2, 3);
        g2d.fillOval(x + 12, y + 6, 2, 3);
        
        // Add variant-specific top details
        switch (variantName) {
            case "highland" -> {
                // Fluffy fur on top
                g2d.setColor(secondary);
                g2d.fillOval(x + 5, y + 2, 6, 4);
            }
            case "jersey" -> {
                // Cute hair tuft
                g2d.setColor(darken(primary, 0.15f));
                g2d.fillOval(x + 7, y + 1, 2, 3);
            }
        }
    }
    
    /**
     * Generate side profile texture with partial face features.
     */
    private static void generateSideHeadTexture(Graphics2D g2d, int x, int y, Color primary, Color secondary, String variantName, boolean isLeft) {
        // Base side color
        g2d.setColor(primary);
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
        
        // Ear shape (prominent on side view)
        int earX = isLeft ? x + 2 : x + 10;
        g2d.setColor(darken(primary, 0.1f));
        g2d.fillOval(earX, y + 3, 4, 8);
        
        // Inner ear
        g2d.setColor(Color.PINK);
        g2d.fillOval(earX + 1, y + 4, 2, 6);
        
        // Visible eye from side (single eye)
        int eyeX = isLeft ? x + 8 : x + 6;
        g2d.setColor(Color.WHITE);
        g2d.fillOval(eyeX, y + 4, 3, 3);
        
        // Side view pupil
        g2d.setColor(Color.BLACK);
        int pupilX = isLeft ? eyeX + 1 : eyeX + 1;
        g2d.fillOval(pupilX, y + 5, 2, 2);
        
        // Eye highlight
        g2d.setColor(Color.WHITE);
        g2d.fillOval(pupilX, y + 5, 1, 1);
        
        // Side view of snout/nose
        int snoutX = isLeft ? x + 11 : x + 1;
        g2d.setColor(darken(primary, 0.05f));
        g2d.fillOval(snoutX, y + 7, 4, 5);
        
        // Nostril from side
        g2d.setColor(Color.PINK);
        g2d.fillOval(snoutX + 1, y + 9, 2, 2);
        g2d.setColor(Color.BLACK);
        g2d.fillOval(snoutX + 2, y + 9, 1, 1);
        
        // Add variant-specific side details
        switch (variantName) {
            case "angus" -> {
                // Droopy eye from side
                g2d.setColor(darken(primary, 0.2f));
                g2d.fillArc(eyeX, y + 3, 3, 2, 0, 180); // Eyelid
            }
            case "highland" -> {
                // Surprised wide eye
                g2d.setColor(Color.WHITE);
                g2d.fillOval(eyeX - 1, y + 3, 4, 4);
                g2d.setColor(Color.BLACK);
                g2d.fillOval(eyeX, y + 4, 2, 2);
            }
            case "jersey" -> {
                // Eyelash from side
                g2d.setColor(Color.BLACK);
                g2d.drawLine(eyeX + 1, y + 3, eyeX + 2, y + 2);
            }
        }
    }
    
    /**
     * Generate bottom of head texture (under chin view).
     */
    private static void generateBottomHeadTexture(Graphics2D g2d, int x, int y, Color primary, Color secondary, String variantName) {
        // Base chin color
        g2d.setColor(primary);
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
        
        // Chin/jaw area
        g2d.setColor(darken(primary, 0.05f));
        g2d.fillOval(x + 3, y + 8, 10, 6);
        
        // Bottom view of snout/nose
        g2d.setColor(Color.PINK);
        g2d.fillOval(x + 6, y + 2, 4, 6);
        
        // Nostrils from below
        g2d.setColor(Color.BLACK);
        g2d.fillOval(x + 7, y + 3, 1, 2);
        g2d.fillOval(x + 8, y + 3, 1, 2);
        
        // Bottom view of mouth area
        g2d.setColor(darken(primary, 0.1f));
        g2d.fillOval(x + 5, y + 10, 6, 4);
        
        // Add variant-specific bottom details
        switch (variantName) {
            case "default" -> {
                // Slight smile visible from below
                g2d.setColor(Color.BLACK);
                g2d.drawArc(x + 6, y + 11, 4, 2, 0, -180);
            }
            case "highland" -> {
                // Surprised mouth opening
                g2d.setColor(Color.BLACK);
                g2d.fillOval(x + 7, y + 11, 2, 2);
            }
        }
    }
    
    /**
     * Generate body texture with spots pattern.
     */
    private static void generateBodyTexture(Graphics2D g2d, int x, int y, Color primary, Color secondary, String faceName) {
        // Base body color
        g2d.setColor(primary);
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
        
        // Add spots pattern
        g2d.setColor(secondary);
        // Different spot patterns for different faces
        if (faceName.contains("FRONT") || faceName.contains("BACK")) {
            g2d.fillOval(x + 2, y + 2, 4, 4);
            g2d.fillOval(x + 8, y + 8, 3, 3);
            g2d.fillOval(x + 12, y + 4, 2, 2);
        } else if (faceName.contains("LEFT") || faceName.contains("RIGHT")) {
            g2d.fillOval(x + 3, y + 6, 5, 4);
            g2d.fillOval(x + 10, y + 2, 3, 3);
        }
    }
    
    /**
     * Generate leg texture.
     */
    private static void generateLegTexture(Graphics2D g2d, int x, int y, Color primary, String faceName) {
        // Legs are slightly darker primary color
        Color legColor = darken(primary, 0.15f);
        g2d.setColor(legColor);
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
        
        // Add hoof at bottom for BOTTOM face
        if (faceName.equals("LEG_BOTTOM")) {
            g2d.setColor(Color.BLACK);
            g2d.fillOval(x + 3, y + 10, 10, 6);
        }
    }
    
    /**
     * Generate horn texture.
     */
    private static void generateHornTexture(Graphics2D g2d, int x, int y, Color accent, String faceName) {
        g2d.setColor(accent);
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
        
        // Add ridges for horn texture
        g2d.setColor(darken(accent, 0.2f));
        for (int i = 0; i < TILE_SIZE; i += 3) {
            g2d.drawLine(x, y + i, x + TILE_SIZE, y + i);
        }
    }
    
    /**
     * Generate tail texture.
     */
    private static void generateTailTexture(Graphics2D g2d, int x, int y, Color primary, Color accent, String faceName) {
        g2d.setColor(primary);
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
        
        // Add hair tuft at the end
        if (faceName.equals("TAIL_BOTTOM")) {
            g2d.setColor(accent);
            g2d.fillOval(x + 4, y + 8, 8, 8);
        }
    }
    
    /**
     * Generate udder texture.
     */
    private static void generateUdderTexture(Graphics2D g2d, int x, int y, Color secondary, String faceName) {
        g2d.setColor(secondary);
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
        
        // Add pink tint for udder
        g2d.setColor(new Color(255, 192, 203, 100));
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
    }
    
    /**
     * Parse hex color string to Color object.
     */
    private static Color parseHexColor(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) {
            return Color.WHITE;
        }
        
        try {
            int rgb = Integer.parseInt(hex.substring(1), 16);
            return new Color(rgb);
        } catch (NumberFormatException e) {
            System.err.println("[CowTextureGenerator] Invalid hex color: " + hex);
            return Color.WHITE;
        }
    }
    
    /**
     * Darken a color by a given factor.
     */
    private static Color darken(Color color, float factor) {
        int r = (int) (color.getRed() * (1 - factor));
        int g = (int) (color.getGreen() * (1 - factor));
        int b = (int) (color.getBlue() * (1 - factor));
        return new Color(Math.max(0, r), Math.max(0, g), Math.max(0, b), color.getAlpha());
    }
    
    /**
     * Lighten a color by a given factor.
     */
    private static Color lighten(Color color, float factor) {
        int r = (int) (color.getRed() + ((255 - color.getRed()) * factor));
        int g = (int) (color.getGreen() + ((255 - color.getGreen()) * factor));
        int b = (int) (color.getBlue() + ((255 - color.getBlue()) * factor));
        return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b), color.getAlpha());
    }
    
    /**
     * Convert BufferedImage to ByteBuffer for OpenGL texture loading.
     */
    private static ByteBuffer convertToByteBuffer(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4); // RGBA
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                
                // Extract RGBA components
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int a = (rgb >> 24) & 0xFF;
                
                buffer.put((byte) r);
                buffer.put((byte) g);
                buffer.put((byte) b);
                buffer.put((byte) a);
            }
        }
        
        buffer.flip();
        return buffer;
    }
}
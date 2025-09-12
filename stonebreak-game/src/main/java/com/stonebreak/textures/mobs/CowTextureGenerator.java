package com.stonebreak.textures.mobs;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;
import org.lwjgl.BufferUtils;

/**
 * JSON-driven cow texture atlas generator.
 * Generates textures based on drawing instructions from individual cow variant JSON files.
 */
public class CowTextureGenerator {
    
    private static final int ATLAS_WIDTH = 256;
    private static final int ATLAS_HEIGHT = 256;
    private static final int GRID_SIZE = 16;
    private static final int TILE_SIZE = ATLAS_WIDTH / GRID_SIZE; // 16 pixels per tile
    
    /**
     * Generate a complete cow texture atlas from individual cow variant JSON files.
     * 
     * @return ByteBuffer containing RGBA pixel data for OpenGL texture loading
     */
    public static ByteBuffer generateTextureAtlas() {
        BufferedImage atlas = new BufferedImage(ATLAS_WIDTH, ATLAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = atlas.createGraphics();
        
        // Enable antialiasing for smoother textures
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        
        // Fill background with transparent
        g2d.setColor(new Color(0, 0, 0, 0));
        g2d.fillRect(0, 0, ATLAS_WIDTH, ATLAS_HEIGHT);
        
        // Generate textures for each cow variant
        String[] variants = CowTextureLoader.getAvailableVariants();
        
        for (String variantName : variants) {
            System.out.println("[CowTextureGenerator] Generating textures for variant: " + variantName);
            generateVariantTextures(g2d, variantName);
        }
        
        g2d.dispose();
        
        // Convert BufferedImage to ByteBuffer for OpenGL
        return convertToByteBuffer(atlas);
    }
    
    /**
     * Generate textures for a specific cow variant using JSON drawing instructions.
     */
    private static void generateVariantTextures(Graphics2D g2d, String variantName) {
        CowTextureDefinition.CowVariant variant = CowTextureLoader.getCowVariant(variantName);
        if (variant == null) {
            System.err.println("[CowTextureGenerator] Failed to load variant: " + variantName);
            return;
        }
        
        if (variant.getFaceMappings() == null) {
            System.err.println("[CowTextureGenerator] No face mappings for variant: " + variantName);
            return;
        }
        
        // Generate textures for each mapped face
        for (String faceName : variant.getFaceMappings().keySet()) {
            CowTextureDefinition.AtlasCoordinate coord = variant.getFaceMappings().get(faceName);
            if (coord != null) {
                generateTileFromJSON(g2d, variantName, faceName, coord);
            }
        }
    }
    
    /**
     * Generate texture for a specific tile based on JSON drawing instructions.
     */
    private static void generateTileFromJSON(Graphics2D g2d, String variantName, String faceName, 
                                           CowTextureDefinition.AtlasCoordinate coord) {
        
        int x = coord.getAtlasX() * TILE_SIZE;
        int y = coord.getAtlasY() * TILE_SIZE;
        
        // Get drawing instructions for this body part
        CowTextureDefinition.DrawingInstructions instructions = 
            CowTextureLoader.getDrawingInstructions(variantName, faceName);
        
        if (instructions == null) {
            // Fallback to solid color if no instructions
            System.err.println("[CowTextureGenerator] WARNING: Missing drawing instructions for " + variantName + ":" + faceName + 
                              " at atlas coordinates (" + coord.getAtlasX() + "," + coord.getAtlasY() + ") - using fallback texture");
            generateFallbackTexture(g2d, x, y, variantName);
            return;
        }
        
        // Apply base texture
        applyBaseTexture(g2d, x, y, instructions.getBaseTexture(), variantName);
        
        // Apply facial features if present
        if (instructions.getFacialFeatures() != null) {
            applyFacialFeatures(g2d, x, y, instructions.getFacialFeatures(), variantName);
        }
        
        // Apply patterns if present
        if (instructions.getPatterns() != null) {
            for (CowTextureDefinition.Pattern pattern : instructions.getPatterns()) {
                applyPattern(g2d, x, y, pattern, variantName);
            }
        }
        
        // Add subtle border for debugging
        g2d.setColor(new Color(0, 0, 0, 50));
        g2d.drawRect(x, y, TILE_SIZE - 1, TILE_SIZE - 1);
    }
    
    /**
     * Apply base texture fill and modifications.
     */
    private static void applyBaseTexture(Graphics2D g2d, int x, int y, 
                                       CowTextureDefinition.BaseTexture baseTexture, String variantName) {
        if (baseTexture == null) {
            return;
        }
        
        Color fillColor = resolveColor(baseTexture.getFillColor(), variantName);
        
        // Apply darkening or lightening
        if (baseTexture.getDarkenFactor() != null && baseTexture.getDarkenFactor() > 0) {
            fillColor = darken(fillColor, baseTexture.getDarkenFactor());
        } else if (baseTexture.getLightenFactor() != null && baseTexture.getLightenFactor() > 0) {
            fillColor = lighten(fillColor, baseTexture.getLightenFactor());
        }
        
        g2d.setColor(fillColor);
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
    }
    
    /**
     * Apply facial features based on JSON instructions.
     */
    private static void applyFacialFeatures(Graphics2D g2d, int x, int y, 
                                          CowTextureDefinition.FacialFeatures features, String variantName) {
        // Apply 180-degree rotation for face textures
        var originalTransform = g2d.getTransform();
        g2d.translate(x + TILE_SIZE/2, y + TILE_SIZE/2);
        g2d.rotate(Math.PI); // 180 degrees in radians
        g2d.translate(-TILE_SIZE/2, -TILE_SIZE/2);
        
        // Apply blaze if present
        if (features.getBlaze() != null && Boolean.TRUE.equals(features.getBlaze().getEnabled())) {
            applyBlaze(g2d, 0, 0, features.getBlaze(), variantName);
        }
        
        // Apply eyes
        if (features.getEyes() != null) {
            applyEyes(g2d, 0, 0, features.getEyes(), variantName);
        }
        
        // Apply nose
        if (features.getNose() != null) {
            applyNose(g2d, 0, 0, features.getNose(), variantName);
        }
        
        // Apply mouth
        if (features.getMouth() != null) {
            applyMouth(g2d, 0, 0, features.getMouth(), variantName);
        }
        
        // Apply cheek features
        if (features.getCheeks() != null) {
            applyCheekFeatures(g2d, 0, 0, features.getCheeks(), variantName);
        }
        
        // Restore the original transform
        g2d.setTransform(originalTransform);
    }
    
    /**
     * Apply blaze feature.
     */
    private static void applyBlaze(Graphics2D g2d, int x, int y, 
                                 CowTextureDefinition.BlazeFeatures blaze, String variantName) {
        Color blazeColor = resolveColor(blaze.getColor(), variantName);
        g2d.setColor(blazeColor);
        
        int blazeX = x + (blaze.getPosition() != null ? blaze.getPosition().getX() : 6);
        int blazeY = y + (blaze.getPosition() != null ? blaze.getPosition().getY() : 1);
        int blazeWidth = blaze.getWidth() != null ? blaze.getWidth() : 4;
        int blazeLength = blaze.getLength() != null ? blaze.getLength() : 12;
        
        // Draw central white stripe
        g2d.fillOval(blazeX, blazeY, blazeWidth, blazeLength);
        // Extended narrow blaze
        g2d.fillOval(blazeX + 1, blazeY - 1, blazeWidth - 2, blazeLength + 2);
    }
    
    /**
     * Apply eye features.
     */
    private static void applyEyes(Graphics2D g2d, int x, int y, 
                                CowTextureDefinition.EyeFeatures eyes, String variantName) {
        if (eyes.getSize() == null || eyes.getPosition() == null) {
            return;
        }
        
        int eyeWidth = eyes.getSize().getWidth();
        int eyeHeight = eyes.getSize().getHeight();
        int eyeX = x + eyes.getPosition().getX();
        int eyeY = y + eyes.getPosition().getY();
        
        // Determine eye spacing
        int rightEyeOffset = 7; // Default spacing
        if ("wide".equals(eyes.getSpacing())) {
            rightEyeOffset = 8;
        } else if ("close".equals(eyes.getSpacing())) {
            rightEyeOffset = 6;
        }
        
        // Draw eye whites
        g2d.setColor(Color.WHITE);
        if ("rectangular".equals(eyes.getType())) {
            g2d.fillRect(eyeX, eyeY, eyeWidth, eyeHeight);
            g2d.fillRect(eyeX + rightEyeOffset, eyeY, eyeWidth, eyeHeight);
        } else {
            g2d.fillOval(eyeX, eyeY, eyeWidth, eyeHeight);
            g2d.fillOval(eyeX + rightEyeOffset, eyeY, eyeWidth, eyeHeight);
        }
        
        // Draw pupils/iris
        Color pupilColor = resolveColor(eyes.getPupilColor(), variantName);
        if (eyes.getIrisColor() != null) {
            // Draw iris first
            g2d.setColor(resolveColor(eyes.getIrisColor(), variantName));
            g2d.fillOval(eyeX + 1, eyeY + 1, eyeWidth - 2, eyeHeight - 2);
            g2d.fillOval(eyeX + rightEyeOffset + 1, eyeY + 1, eyeWidth - 2, eyeHeight - 2);
        }
        
        // Draw pupils
        g2d.setColor(pupilColor);
        g2d.fillOval(eyeX + 1, eyeY + 1, eyeWidth - 2, eyeHeight - 2);
        g2d.fillOval(eyeX + rightEyeOffset + 1, eyeY + 1, eyeWidth - 2, eyeHeight - 2);
        
        // Apply highlights
        if (eyes.getHighlights() != null) {
            g2d.setColor(Color.WHITE);
            for (CowTextureDefinition.Highlight highlight : eyes.getHighlights()) {
                if (highlight.getPosition() != null && highlight.getSize() != null) {
                    int hx = x + highlight.getPosition().getX();
                    int hy = y + highlight.getPosition().getY();
                    int hw = highlight.getSize().getWidth();
                    int hh = highlight.getSize().getHeight();
                    g2d.fillOval(hx, hy, hw, hh);
                }
            }
        }
        
        // Apply eyebrows
        if (eyes.getEyebrows() != null) {
            applyEyebrows(g2d, x, y, eyes.getEyebrows(), variantName);
        }
        
        // Apply eyelashes
        if (eyes.getEyelashes() != null) {
            applyEyelashes(g2d, x, y, eyes.getEyelashes(), variantName);
        }
    }
    
    private static void applyEyebrows(Graphics2D g2d, int x, int y,
                                    CowTextureDefinition.EyebrowFeatures eyebrows, String variantName) {
        Color eyebrowColor = resolveColor(eyebrows.getColor(), variantName);
        g2d.setColor(eyebrowColor);
        
        if (eyebrows.getPosition() != null && eyebrows.getSize() != null) {
            int bx = x + eyebrows.getPosition().getX();
            int by = y + eyebrows.getPosition().getY();
            int bw = eyebrows.getSize().getWidth();
            int bh = eyebrows.getSize().getHeight();
            
            if ("heavy".equals(eyebrows.getType())) {
                g2d.fillRect(bx, by, bw, bh);
                g2d.fillRect(bx + 8, by, bw, bh);
            } else {
                g2d.drawArc(bx, by, bw, bh, 0, 180);
                g2d.drawArc(bx + 7, by, bw, bh, 0, 180);
            }
        }
    }
    
    private static void applyEyelashes(Graphics2D g2d, int x, int y,
                                     List<CowTextureDefinition.Eyelash> eyelashes, String variantName) {
        for (CowTextureDefinition.Eyelash lash : eyelashes) {
            if (lash.getStartPosition() != null && lash.getEndPosition() != null) {
                Color lashColor = resolveColor(lash.getColor(), variantName);
                g2d.setColor(lashColor);
                
                int x1 = x + lash.getStartPosition().getX();
                int y1 = y + lash.getStartPosition().getY();
                int x2 = x + lash.getEndPosition().getX();
                int y2 = y + lash.getEndPosition().getY();
                
                g2d.drawLine(x1, y1, x2, y2);
            }
        }
    }
    
    private static void applyNose(Graphics2D g2d, int x, int y,
                                CowTextureDefinition.NoseFeatures nose, String variantName) {
        if (nose.getSize() == null || nose.getPosition() == null) {
            return;
        }
        
        Color noseColor = resolveColor(nose.getColor(), variantName);
        g2d.setColor(noseColor);
        
        int nx = x + nose.getPosition().getX();
        int ny = y + nose.getPosition().getY();
        int nw = nose.getSize().getWidth();
        int nh = nose.getSize().getHeight();
        
        if ("heart_shaped".equals(nose.getType())) {
            g2d.fillOval(nx, ny, nw/2, nh/2);
            g2d.fillOval(nx + nw/2, ny, nw/2, nh/2);
            g2d.fillOval(nx, ny + nh/2, nw, nh/2);
        } else if ("large_black".equals(nose.getType())) {
            g2d.setColor(darken(resolveColor("primary", variantName), 0.1f));
            g2d.fillRect(nx - 1, ny - 1, nw + 2, nh + 1);
            g2d.setColor(Color.BLACK);
            g2d.fillOval(nx, ny, nw, nh);
        } else {
            g2d.fillOval(nx, ny, nw, nh);
        }
        
        if (nose.getNostrils() != null) {
            for (CowTextureDefinition.Nostril nostril : nose.getNostrils()) {
                if (nostril.getPosition() != null && nostril.getSize() != null) {
                    Color nostrilColor = resolveColor(nostril.getColor(), variantName);
                    g2d.setColor(nostrilColor);
                    
                    int nx1 = x + nostril.getPosition().getX();
                    int ny1 = y + nostril.getPosition().getY();
                    int nw1 = nostril.getSize().getWidth();
                    int nh1 = nostril.getSize().getHeight();
                    
                    g2d.fillOval(nx1, ny1, nw1, nh1);
                }
            }
        }
        
        if (nose.getHighlights() != null) {
            for (CowTextureDefinition.Highlight highlight : nose.getHighlights()) {
                if (highlight.getPosition() != null && highlight.getSize() != null) {
                    Color highlightColor = resolveColor(highlight.getColor(), variantName);
                    g2d.setColor(highlightColor);
                    
                    int hx = x + highlight.getPosition().getX();
                    int hy = y + highlight.getPosition().getY();
                    int hw = highlight.getSize().getWidth();
                    int hh = highlight.getSize().getHeight();
                    
                    g2d.fillOval(hx, hy, hw, hh);
                }
            }
        }
    }
    
    private static void applyMouth(Graphics2D g2d, int x, int y,
                                 CowTextureDefinition.MouthFeatures mouth, String variantName) {
        if (mouth.getPosition() == null || mouth.getSize() == null) {
            return;
        }
        
        Color mouthColor = resolveColor(mouth.getColor(), variantName);
        g2d.setColor(mouthColor);
        
        int mx = x + mouth.getPosition().getX();
        int my = y + mouth.getPosition().getY();
        int mw = mouth.getSize().getWidth();
        int mh = mouth.getSize().getHeight();
        
        if ("small_oh".equals(mouth.getType())) {
            g2d.fillOval(mx, my, mw, mh);
            g2d.setColor(Color.PINK);
            g2d.fillOval(mx, my, mw/2, mh/2);
        } else if ("straight_line".equals(mouth.getType())) {
            g2d.drawLine(mx, my, mx + mw, my);
        } else if ("gentle_smile".equals(mouth.getType())) {
            g2d.drawArc(mx, my, mw, mh, 0, -180);
        } else {
            g2d.drawArc(mx, my, mw, mh, 0, -180);
        }
    }
    
    private static void applyCheekFeatures(Graphics2D g2d, int x, int y,
                                         CowTextureDefinition.CheekFeatures cheeks, String variantName) {
        if (cheeks.getBlush() != null && Boolean.TRUE.equals(cheeks.getBlush().getEnabled())) {
            CowTextureDefinition.BlushFeatures blush = cheeks.getBlush();
            Color blushColor = resolveColor(blush.getColor(), variantName);
            int opacity = blush.getOpacity() != null ? blush.getOpacity() : 150;
            g2d.setColor(new Color(blushColor.getRed(), blushColor.getGreen(), blushColor.getBlue(), opacity));
            
            if (blush.getPositions() != null && blush.getSize() != null) {
                for (CowTextureDefinition.Position pos : blush.getPositions()) {
                    int bx = x + pos.getX();
                    int by = y + pos.getY();
                    int bw = blush.getSize().getWidth();
                    int bh = blush.getSize().getHeight();
                    g2d.fillOval(bx, by, bw, bh);
                }
            }
        }
        
        if (cheeks.getFreckles() != null) {
            for (CowTextureDefinition.Freckle freckle : cheeks.getFreckles()) {
                if (freckle.getPosition() != null && freckle.getSize() != null) {
                    Color freckleColor = resolveColor(freckle.getColor(), variantName);
                    g2d.setColor(freckleColor);
                    
                    int fx = x + freckle.getPosition().getX();
                    int fy = y + freckle.getPosition().getY();
                    int fw = freckle.getSize().getWidth();
                    int fh = freckle.getSize().getHeight();
                    
                    g2d.fillOval(fx, fy, fw, fh);
                }
            }
        }
        
        if (cheeks.getDimples() != null) {
            for (CowTextureDefinition.Dimple dimple : cheeks.getDimples()) {
                if (dimple.getPosition() != null && dimple.getSize() != null) {
                    Color primaryColor = resolveColor("primary", variantName);
                    float depth = dimple.getDepth() != null ? dimple.getDepth() : 0.1f;
                    g2d.setColor(darken(primaryColor, depth));
                    
                    int dx = x + dimple.getPosition().getX();
                    int dy = y + dimple.getPosition().getY();
                    int dw = dimple.getSize().getWidth();
                    int dh = dimple.getSize().getHeight();
                    
                    g2d.fillOval(dx, dy, dw, dh);
                }
            }
        }
    }
    
    private static void applyPattern(Graphics2D g2d, int x, int y, 
                                   CowTextureDefinition.Pattern pattern, String variantName) {
        if (pattern.getPositions() == null || pattern.getSize() == null) {
            return;
        }
        
        Color patternColor = resolveColor(pattern.getColor(), variantName);
        
        if (pattern.getOpacity() != null) {
            int opacity = Math.max(0, Math.min(255, pattern.getOpacity()));
            patternColor = new Color(patternColor.getRed(), patternColor.getGreen(), 
                                   patternColor.getBlue(), opacity);
        }
        
        g2d.setColor(patternColor);
        
        int pw = pattern.getSize().getWidth();
        int ph = pattern.getSize().getHeight();
        
        for (CowTextureDefinition.Position pos : pattern.getPositions()) {
            int px = x + pos.getX();
            int py = y + pos.getY();
            
            if ("ridges".equals(pattern.getType())) {
                g2d.drawLine(px, py, px + pw, py);
            } else if ("hair_strands".equals(pattern.getType())) {
                g2d.drawLine(px, py, px, py + ph);
            } else if ("line".equals(pattern.getShape()) || "eyelash_side".equals(pattern.getType())) {
                g2d.drawLine(px, py, px + pw, py + ph);
            } else if ("oval".equals(pattern.getShape()) || pattern.getShape() == null) {
                g2d.fillOval(px, py, pw, ph);
            } else if ("rect".equals(pattern.getShape())) {
                g2d.fillRect(px, py, pw, ph);
            }
        }
    }
    
    private static void generateFallbackTexture(Graphics2D g2d, int x, int y, String variantName) {
        Color fallbackColor = resolveColor("primary", variantName);
        g2d.setColor(fallbackColor);
        g2d.fillRect(x, y, TILE_SIZE, TILE_SIZE);
    }
    
    private static Color resolveColor(String colorString, String variantName) {
        if (colorString == null) {
            return Color.WHITE;
        }
        
        switch (colorString) {
            case "primary":
                return parseHexColor(CowTextureLoader.getBaseColor(variantName, "primary"));
            case "secondary":
                return parseHexColor(CowTextureLoader.getBaseColor(variantName, "secondary"));
            case "accent":
                return parseHexColor(CowTextureLoader.getBaseColor(variantName, "accent"));
            default:
                return parseHexColor(colorString);
        }
    }
    
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
    
    private static Color darken(Color color, float factor) {
        int r = (int) (color.getRed() * (1 - factor));
        int g = (int) (color.getGreen() * (1 - factor));
        int b = (int) (color.getBlue() * (1 - factor));
        return new Color(Math.max(0, r), Math.max(0, g), Math.max(0, b), color.getAlpha());
    }
    
    private static Color lighten(Color color, float factor) {
        int r = (int) (color.getRed() + ((255 - color.getRed()) * factor));
        int g = (int) (color.getGreen() + ((255 - color.getGreen()) * factor));
        int b = (int) (color.getBlue() + ((255 - color.getBlue()) * factor));
        return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b), color.getAlpha());
    }
    
    private static ByteBuffer convertToByteBuffer(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                
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
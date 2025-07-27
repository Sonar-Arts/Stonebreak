package com.openmason.ui.controllers;

import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Factory class for creating toolbar and menu icons.
 * Provides consistent icon generation with caching and fallback to shapes.
 */
public class IconFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(IconFactory.class);
    private static final int ICON_SIZE = 16;
    private static final Map<String, ImageView> iconCache = new HashMap<>();
    
    /**
     * Creates an icon for the specified name with automatic fallback to shapes.
     * 
     * @param iconName The name of the icon to create
     * @return ImageView containing the icon or a fallback shape
     */
    public static ImageView createIcon(String iconName) {
        return createIcon(iconName, ICON_SIZE);
    }
    
    /**
     * Creates an icon for the specified name and size with automatic fallback.
     * 
     * @param iconName The name of the icon to create
     * @param size The desired icon size
     * @return ImageView containing the icon or a fallback shape
     */
    public static ImageView createIcon(String iconName, int size) {
        String cacheKey = iconName + "_" + size;
        
        // Check cache first
        if (iconCache.containsKey(cacheKey)) {
            ImageView cached = iconCache.get(cacheKey);
            // Create new instance to avoid sharing
            return new ImageView(cached.getImage());
        }
        
        // Try to load from resources
        ImageView imageView = loadIconFromResource(iconName, size);
        
        // If loading failed, create fallback shape
        if (imageView == null) {
            imageView = createFallbackIcon(iconName, size);
        }
        
        // Cache the result
        if (imageView != null) {
            iconCache.put(cacheKey, imageView);
        }
        
        return imageView;
    }
    
    /**
     * Attempts to load an icon from application resources.
     */
    private static ImageView loadIconFromResource(String iconName, int size) {
        try {
            String resourcePath = "/icons/" + iconName + ".png";
            InputStream iconStream = IconFactory.class.getResourceAsStream(resourcePath);
            
            if (iconStream != null) {
                Image image = new Image(iconStream, size, size, true, true);
                ImageView imageView = new ImageView(image);
                imageView.setFitWidth(size);
                imageView.setFitHeight(size);
                imageView.setPreserveRatio(true);
                return imageView;
            }
        } catch (Exception e) {
            logger.debug("Could not load icon '{}' from resources: {}", iconName, e.getMessage());
        }
        return null;
    }
    
    /**
     * Creates a fallback shape icon when resource loading fails.
     */
    private static ImageView createFallbackIcon(String iconName, int size) {
        Shape shape = createShapeForIcon(iconName, size);
        if (shape != null) {
            // Convert shape to ImageView (simplified approach)
            // In a real implementation, you might want to render the shape to a WritableImage
            return new ImageView(); // Placeholder - would need proper shape-to-image conversion
        }
        return null;
    }
    
    /**
     * Creates appropriate shapes for different icon types.
     */
    private static Shape createShapeForIcon(String iconName, int size) {
        double iconSize = size * 0.8; // Leave some margin
        double centerX = size / 2.0;
        double centerY = size / 2.0;
        
        return switch (iconName.toLowerCase()) {
            case "new", "new_model" -> createNewIcon(centerX, centerY, iconSize);
            case "open", "open_model" -> createOpenIcon(centerX, centerY, iconSize);
            case "save", "save_model" -> createSaveIcon(centerX, centerY, iconSize);
            case "reset", "reset_view" -> createResetIcon(centerX, centerY, iconSize);
            case "zoom_in" -> createZoomInIcon(centerX, centerY, iconSize);
            case "zoom_out" -> createZoomOutIcon(centerX, centerY, iconSize);
            case "fit", "fit_to_view" -> createFitIcon(centerX, centerY, iconSize);
            case "wireframe" -> createWireframeIcon(centerX, centerY, iconSize);
            case "grid" -> createGridIcon(centerX, centerY, iconSize);
            case "axes" -> createAxesIcon(centerX, centerY, iconSize);
            case "validate" -> createValidateIcon(centerX, centerY, iconSize);
            case "generate" -> createGenerateIcon(centerX, centerY, iconSize);
            case "settings" -> createSettingsIcon(centerX, centerY, iconSize);
            default -> createDefaultIcon(centerX, centerY, iconSize);
        };
    }
    
    /**
     * Creates a "new document" icon shape.
     */
    private static Shape createNewIcon(double centerX, double centerY, double size) {
        Rectangle rect = new Rectangle(size * 0.6, size * 0.8);
        rect.setX(centerX - rect.getWidth() / 2);
        rect.setY(centerY - rect.getHeight() / 2);
        rect.setFill(Color.LIGHTBLUE);
        rect.setStroke(Color.DARKBLUE);
        rect.setStrokeWidth(1);
        return rect;
    }
    
    /**
     * Creates an "open folder" icon shape.
     */
    private static Shape createOpenIcon(double centerX, double centerY, double size) {
        Rectangle folder = new Rectangle(size * 0.7, size * 0.5);
        folder.setX(centerX - folder.getWidth() / 2);
        folder.setY(centerY - folder.getHeight() / 2 + size * 0.1);
        folder.setFill(Color.LIGHTYELLOW);
        folder.setStroke(Color.ORANGE);
        folder.setStrokeWidth(1);
        return folder;
    }
    
    /**
     * Creates a "save/disk" icon shape.
     */
    private static Shape createSaveIcon(double centerX, double centerY, double size) {
        Rectangle disk = new Rectangle(size * 0.7, size * 0.7);
        disk.setX(centerX - disk.getWidth() / 2);
        disk.setY(centerY - disk.getHeight() / 2);
        disk.setFill(Color.LIGHTGRAY);
        disk.setStroke(Color.DARKGRAY);
        disk.setStrokeWidth(1);
        return disk;
    }
    
    /**
     * Creates a "reset/refresh" icon shape.
     */
    private static Shape createResetIcon(double centerX, double centerY, double size) {
        Circle circle = new Circle(centerX, centerY, size * 0.3);
        circle.setFill(Color.TRANSPARENT);
        circle.setStroke(Color.GREEN);
        circle.setStrokeWidth(2);
        return circle;
    }
    
    /**
     * Creates a "zoom in" icon shape.
     */
    private static Shape createZoomInIcon(double centerX, double centerY, double size) {
        Circle magnifier = new Circle(centerX - size * 0.1, centerY - size * 0.1, size * 0.25);
        magnifier.setFill(Color.TRANSPARENT);
        magnifier.setStroke(Color.BLUE);
        magnifier.setStrokeWidth(2);
        
        // Add plus sign
        Line horizontal = new Line(centerX - size * 0.2, centerY - size * 0.1, centerX, centerY - size * 0.1);
        Line vertical = new Line(centerX - size * 0.1, centerY - size * 0.2, centerX - size * 0.1, centerY);
        horizontal.setStroke(Color.BLUE);
        vertical.setStroke(Color.BLUE);
        horizontal.setStrokeWidth(2);
        vertical.setStrokeWidth(2);
        
        Group group = new Group(magnifier, horizontal, vertical);
        return new Path(); // Simplified - would need proper group handling
    }
    
    /**
     * Creates a "zoom out" icon shape.
     */
    private static Shape createZoomOutIcon(double centerX, double centerY, double size) {
        Circle magnifier = new Circle(centerX - size * 0.1, centerY - size * 0.1, size * 0.25);
        magnifier.setFill(Color.TRANSPARENT);
        magnifier.setStroke(Color.BLUE);
        magnifier.setStrokeWidth(2);
        
        // Add minus sign
        Line horizontal = new Line(centerX - size * 0.2, centerY - size * 0.1, centerX, centerY - size * 0.1);
        horizontal.setStroke(Color.BLUE);
        horizontal.setStrokeWidth(2);
        
        return magnifier; // Simplified
    }
    
    /**
     * Creates a "fit to view" icon shape.
     */
    private static Shape createFitIcon(double centerX, double centerY, double size) {
        Rectangle frame = new Rectangle(size * 0.6, size * 0.6);
        frame.setX(centerX - frame.getWidth() / 2);
        frame.setY(centerY - frame.getHeight() / 2);
        frame.setFill(Color.TRANSPARENT);
        frame.setStroke(Color.PURPLE);
        frame.setStrokeWidth(2);
        frame.getStrokeDashArray().addAll(3.0, 3.0);
        return frame;
    }
    
    /**
     * Creates a "wireframe" icon shape.
     */
    private static Shape createWireframeIcon(double centerX, double centerY, double size) {
        Polygon triangle = new Polygon();
        triangle.getPoints().addAll(new Double[]{
            centerX, centerY - size * 0.3,
            centerX - size * 0.3, centerY + size * 0.2,
            centerX + size * 0.3, centerY + size * 0.2
        });
        triangle.setFill(Color.TRANSPARENT);
        triangle.setStroke(Color.RED);
        triangle.setStrokeWidth(2);
        return triangle;
    }
    
    /**
     * Creates a "grid" icon shape.
     */
    private static Shape createGridIcon(double centerX, double centerY, double size) {
        Group grid = new Group();
        
        // Create grid lines
        for (int i = 0; i <= 3; i++) {
            double offset = (i - 1.5) * size * 0.2;
            
            // Vertical lines
            Line vLine = new Line(centerX + offset, centerY - size * 0.3, centerX + offset, centerY + size * 0.3);
            vLine.setStroke(Color.GRAY);
            vLine.setStrokeWidth(1);
            grid.getChildren().add(vLine);
            
            // Horizontal lines  
            Line hLine = new Line(centerX - size * 0.3, centerY + offset, centerX + size * 0.3, centerY + offset);
            hLine.setStroke(Color.GRAY);
            hLine.setStrokeWidth(1);
            grid.getChildren().add(hLine);
        }
        
        return new Rectangle(); // Simplified - would need proper group conversion
    }
    
    /**
     * Creates an "axes" icon shape.
     */
    private static Shape createAxesIcon(double centerX, double centerY, double size) {
        Group axes = new Group();
        
        // X axis (red)
        Line xAxis = new Line(centerX - size * 0.3, centerY, centerX + size * 0.3, centerY);
        xAxis.setStroke(Color.RED);
        xAxis.setStrokeWidth(2);
        axes.getChildren().add(xAxis);
        
        // Y axis (green)  
        Line yAxis = new Line(centerX, centerY - size * 0.3, centerX, centerY + size * 0.3);
        yAxis.setStroke(Color.GREEN);
        yAxis.setStrokeWidth(2);
        axes.getChildren().add(yAxis);
        
        return new Rectangle(); // Simplified - would need proper group conversion
    }
    
    /**
     * Creates a "validate/checkmark" icon shape.
     */
    private static Shape createValidateIcon(double centerX, double centerY, double size) {
        Polyline checkmark = new Polyline();
        checkmark.getPoints().addAll(new Double[]{
            centerX - size * 0.2, centerY,
            centerX - size * 0.05, centerY + size * 0.15,
            centerX + size * 0.2, centerY - size * 0.2
        });
        checkmark.setStroke(Color.GREEN);
        checkmark.setStrokeWidth(3);
        checkmark.setFill(Color.TRANSPARENT);
        return checkmark;
    }
    
    /**
     * Creates a "generate/gear" icon shape.
     */
    private static Shape createGenerateIcon(double centerX, double centerY, double size) {
        Circle gear = new Circle(centerX, centerY, size * 0.3);
        gear.setFill(Color.LIGHTGRAY);
        gear.setStroke(Color.DARKGRAY);
        gear.setStrokeWidth(2);
        return gear;
    }
    
    /**
     * Creates a "settings/cog" icon shape.
     */
    private static Shape createSettingsIcon(double centerX, double centerY, double size) {
        Circle cog = new Circle(centerX, centerY, size * 0.25);
        cog.setFill(Color.LIGHTSTEELBLUE);
        cog.setStroke(Color.STEELBLUE);
        cog.setStrokeWidth(2);
        return cog;
    }
    
    /**
     * Creates a default placeholder icon shape.
     */
    private static Shape createDefaultIcon(double centerX, double centerY, double size) {
        Rectangle placeholder = new Rectangle(size * 0.5, size * 0.5);
        placeholder.setX(centerX - placeholder.getWidth() / 2);
        placeholder.setY(centerY - placeholder.getHeight() / 2);
        placeholder.setFill(Color.LIGHTGRAY);
        placeholder.setStroke(Color.GRAY);
        placeholder.setStrokeWidth(1);
        return placeholder;
    }
    
    /**
     * Clears the icon cache to free memory.
     */
    public static void clearCache() {
        iconCache.clear();
        logger.debug("Icon cache cleared");
    }
    
    /**
     * Gets the current cache size for monitoring.
     */
    public static int getCacheSize() {
        return iconCache.size();
    }
}
package com.openmason.ui.viewport;

import org.lwjgl.opengl.GL11;

/**
 * Helper methods for rendering grid, axes, and other viewport elements in OpenMason3DViewport.
 * Separated into its own class for better organization and reusability.
 */
public class RenderingHelpers {
    
    /**
     * Sets material color based on texture variant.
     */
    public static void setModelMaterialColor(String textureVariant) {
        float[] materialColor;
        
        switch (textureVariant.toLowerCase()) {
            case "angus" -> materialColor = new float[]{0.3f, 0.2f, 0.1f, 1.0f}; // Dark brown
            case "highland" -> materialColor = new float[]{0.8f, 0.6f, 0.4f, 1.0f}; // Light brown
            case "jersey" -> materialColor = new float[]{0.9f, 0.8f, 0.6f, 1.0f}; // Cream
            default -> materialColor = new float[]{0.9f, 0.9f, 0.9f, 1.0f}; // White/default
        }
        
        GL11.glColor4fv(materialColor);
    }
    
    /**
     * Renders a professional reference grid.
     */
    public static void renderGrid() {
        GL11.glColor3f(0.3f, 0.3f, 0.3f); // Dark gray grid lines
        
        GL11.glBegin(GL11.GL_LINES);
        
        // Grid size and spacing
        int gridSize = 10;
        float gridSpacing = 1.0f;
        float gridExtent = gridSize * gridSpacing;
        
        // Horizontal lines (along X-axis)
        for (int i = -gridSize; i <= gridSize; i++) {
            float z = i * gridSpacing;
            GL11.glVertex3f(-gridExtent, 0.0f, z);
            GL11.glVertex3f(gridExtent, 0.0f, z);
        }
        
        // Vertical lines (along Z-axis)
        for (int i = -gridSize; i <= gridSize; i++) {
            float x = i * gridSpacing;
            GL11.glVertex3f(x, 0.0f, -gridExtent);
            GL11.glVertex3f(x, 0.0f, gridExtent);
        }
        
        GL11.glEnd();
        
        // Highlight center lines
        GL11.glLineWidth(2.0f);
        GL11.glColor3f(0.5f, 0.5f, 0.5f); // Lighter gray for center lines
        
        GL11.glBegin(GL11.GL_LINES);
        // X-axis center line
        GL11.glVertex3f(-gridExtent, 0.0f, 0.0f);
        GL11.glVertex3f(gridExtent, 0.0f, 0.0f);
        // Z-axis center line
        GL11.glVertex3f(0.0f, 0.0f, -gridExtent);
        GL11.glVertex3f(0.0f, 0.0f, gridExtent);
        GL11.glEnd();
        
        GL11.glLineWidth(1.0f); // Reset line width
    }
    
    /**
     * Renders coordinate system axes.
     */
    public static void renderAxes() {
        GL11.glLineWidth(3.0f);
        
        GL11.glBegin(GL11.GL_LINES);
        
        // X-axis (Red)
        GL11.glColor3f(1.0f, 0.0f, 0.0f);
        GL11.glVertex3f(0.0f, 0.0f, 0.0f);
        GL11.glVertex3f(2.0f, 0.0f, 0.0f);
        
        // Y-axis (Green)
        GL11.glColor3f(0.0f, 1.0f, 0.0f);
        GL11.glVertex3f(0.0f, 0.0f, 0.0f);
        GL11.glVertex3f(0.0f, 2.0f, 0.0f);
        
        // Z-axis (Blue)
        GL11.glColor3f(0.0f, 0.0f, 1.0f);
        GL11.glVertex3f(0.0f, 0.0f, 0.0f);
        GL11.glVertex3f(0.0f, 0.0f, 2.0f);
        
        GL11.glEnd();
        
        GL11.glLineWidth(1.0f); // Reset line width
        GL11.glColor3f(1.0f, 1.0f, 1.0f); // Reset color
    }
}
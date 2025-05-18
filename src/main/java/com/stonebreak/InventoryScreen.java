package com.stonebreak;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;

import org.joml.Matrix4f;

import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A 2D UI for displaying the player's inventory.
 */
public class InventoryScreen {

    private Inventory inventory;
    private boolean visible;
    private Font font;
    private Renderer renderer;

    // UI constants
    private static final int SLOT_SIZE = 40;
    private static final int SLOT_PADDING = 5;
    private static final int NUM_COLS = 9;
    private static final int TITLE_HEIGHT = 30;
    
    // Colors
    private static final int BORDER_COLOR_R = 100;
    private static final int BORDER_COLOR_G = 100;
    private static final int BORDER_COLOR_B = 100;
    private static final int BACKGROUND_COLOR_R = 50;
    private static final int BACKGROUND_COLOR_G = 50;
    private static final int BACKGROUND_COLOR_B = 50;
    private static final int SLOT_BACKGROUND_R = 70;
    private static final int SLOT_BACKGROUND_G = 70;
    private static final int SLOT_BACKGROUND_B = 70;
    private static final int TEXT_COLOR_R = 255;
    private static final int TEXT_COLOR_G = 255;
    private static final int TEXT_COLOR_B = 255;
    
    /**
     * Creates a new inventory screen.
     */
    public InventoryScreen(Inventory inventory, Font font, Renderer renderer) {
        this.inventory = inventory;
        this.visible = false;
        this.font = font;
        this.renderer = renderer;
    }

    /**
     * Toggles the visibility of the inventory screen.
     */
    public void toggleVisibility() {
        this.visible = !this.visible;
    }

    /**
     * Returns whether the inventory screen is currently visible.
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Renders the inventory screen.
     */
    public void render(int screenWidth, int screenHeight) {
        if (!visible) {
            return;
        }
        
        ShaderProgram shaderProgram = renderer.getShaderProgram();
        
        // Save the current OpenGL state
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullFaceEnabled = glIsEnabled(GL_CULL_FACE);
        
        // Create a purely 2D rendering environment
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        if (cullFaceEnabled) {
            glDisable(GL_CULL_FACE); // Disable culling for 2D UI
        }
        
        // Set up 2D orthographic projection for UI
        shaderProgram.bind();
        Matrix4f uiProjection = new Matrix4f().ortho(0, screenWidth, screenHeight, 0, -1, 1);
        Matrix4f identityView = new Matrix4f();
        shaderProgram.setUniform("projectionMatrix", uiProjection);
        shaderProgram.setUniform("viewMatrix", identityView);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0); // Ensure no specific texture is bound
        
        // Get inventory items
        Map<Integer, Integer> itemsMap = inventory.getAllItems();
        List<Map.Entry<Integer, Integer>> sortedItemEntries = itemsMap.entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .collect(Collectors.toList());

        // Calculate inventory dimensions
        int currentItemCount = sortedItemEntries.size();
        int totalSlotsToDisplay = Math.max(currentItemCount, NUM_COLS);
        int numRows = Math.max(1, (int)Math.ceil((double)totalSlotsToDisplay / NUM_COLS));
        
        int inventoryWidth = NUM_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int inventoryHeight = numRows * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + TITLE_HEIGHT;
        
        // Center the inventory on screen
        int startX = (screenWidth - inventoryWidth) / 2;
        int startY = (screenHeight - inventoryHeight) / 2;

        // Explicitly set shader for solid color rendering before drawing panel background
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        
        // Draw the inventory background with border
        renderer.drawQuad(startX - 5, startY - 5, inventoryWidth + 10, inventoryHeight + 10, 
                BORDER_COLOR_R, BORDER_COLOR_G, BORDER_COLOR_B, 255);
        renderer.drawQuad(startX, startY, inventoryWidth, inventoryHeight,
                BACKGROUND_COLOR_R, BACKGROUND_COLOR_G, BACKGROUND_COLOR_B, 200);
        
        // Draw title
        String title = "Inventory";
        float titleWidth = font.getTextWidth(title);
        font.drawString(startX + (inventoryWidth - titleWidth) / 2, 
                startY + 10, title, TEXT_COLOR_R, TEXT_COLOR_G, TEXT_COLOR_B, shaderProgram);
        
        // Calculate starting Y position after the title
        int contentStartY = startY + TITLE_HEIGHT;
        
        // Draw slots and items
        int currentSlot = 0;
        for (Map.Entry<Integer, Integer> entry : sortedItemEntries) {
            int blockTypeId = entry.getKey();
            int count = entry.getValue();
            BlockType type = BlockType.getById(blockTypeId);
            
            if (type == null) continue;
            
            int col = currentSlot % NUM_COLS;
            int row = currentSlot / NUM_COLS;
            
            int slotX = startX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = contentStartY + SLOT_PADDING + row * (SLOT_SIZE + SLOT_PADDING);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, 0); // Ensure no specific texture is bound
            
            // Draw slot with border
            renderer.drawQuad(slotX, slotY, SLOT_SIZE, SLOT_SIZE, 
                    BORDER_COLOR_R, BORDER_COLOR_G, BORDER_COLOR_B, 255);
            renderer.drawQuad(slotX + 1, slotY + 1, SLOT_SIZE - 2, SLOT_SIZE - 2, 
                    SLOT_BACKGROUND_R, SLOT_BACKGROUND_G, SLOT_BACKGROUND_B, 255);
            
            // Draw the 3D block
            if (type.getAtlasX() != -1 && type.getAtlasY() != -1) {
                // We need to restore the orthographic projection after each 3D item
                renderer.draw3DItemInSlot(type, slotX + 2, slotY + 2, SLOT_SIZE - 4, SLOT_SIZE - 4);
                
                // Reset to our 2D projection after the 3D rendering
                shaderProgram.bind();
                shaderProgram.setUniform("projectionMatrix", uiProjection);
                shaderProgram.setUniform("viewMatrix", identityView);
                glDisable(GL_DEPTH_TEST); // Ensure depth test is off for 2D
                glEnable(GL_BLEND); // Ensure blending is on for 2D
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // Set blend function for 2D
            }
            
            // Draw item count
            if (count > 1) {
                String countText = String.valueOf(count);
                float textWidth = font.getTextWidth(countText);
                float textHeight = font.getLineHeight();
                font.drawString(slotX + SLOT_SIZE - textWidth - 3, 
                        slotY + SLOT_SIZE - textHeight - 1, 
                        countText, TEXT_COLOR_R, TEXT_COLOR_G, TEXT_COLOR_B, shaderProgram);
            }
            
            currentSlot++;
        }
        
        // Draw empty slots to fill the grid
        int drawnItemSlots = sortedItemEntries.size();
        int totalGridSlots = numRows * NUM_COLS;
        
        for (int i = drawnItemSlots; i < totalGridSlots; i++) {
            int col = i % NUM_COLS;
            int row = i / NUM_COLS;
            
            int slotX = startX + SLOT_PADDING + col * (SLOT_SIZE + SLOT_PADDING);
            int slotY = contentStartY + SLOT_PADDING + row * (SLOT_SIZE + SLOT_PADDING);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, 0); // Ensure no specific texture is bound
            
            // Draw empty slot
            renderer.drawQuad(slotX, slotY, SLOT_SIZE, SLOT_SIZE, 
                    BORDER_COLOR_R, BORDER_COLOR_G, BORDER_COLOR_B, 255);
            renderer.drawQuad(slotX + 1, slotY + 1, SLOT_SIZE - 2, SLOT_SIZE - 2, 
                    SLOT_BACKGROUND_R, SLOT_BACKGROUND_G, SLOT_BACKGROUND_B, 255);
        }
        
        // Restore original OpenGL state
        if (depthTestEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        if (cullFaceEnabled) {
            glEnable(GL_CULL_FACE); // Restore culling if it was enabled
        }
    }
}
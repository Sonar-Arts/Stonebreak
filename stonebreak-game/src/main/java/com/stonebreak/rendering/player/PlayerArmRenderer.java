package com.stonebreak.rendering.player;

// JOML Math Library
import org.joml.Matrix4f;
import org.joml.Vector4f;

// LWJGL OpenGL Classes
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

// LWJGL OpenGL Static Imports
import static org.lwjgl.opengl.GL11.*;

// Project Imports
import com.stonebreak.blocks.BlockType;
import com.stonebreak.config.Settings;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.TextureAtlas;

// Specialized Player Rendering Components
import com.stonebreak.rendering.player.animation.PlayerArmAnimator;
import com.stonebreak.rendering.player.geometry.ArmGeometry;
import com.stonebreak.rendering.player.items.HandItemRenderer;
import com.stonebreak.rendering.player.textures.PlayerArmTexture;

/**
 * Refactored and modular player arm renderer that coordinates specialized components.
 * Uses composition pattern with dedicated components for animation, geometry, items, and textures.
 */
public class PlayerArmRenderer {
    
    // Core dependencies
    private final ShaderProgram shaderProgram;
    private final TextureAtlas textureAtlas;
    private final Matrix4f projectionMatrix;
    
    // Specialized components
    private final PlayerArmAnimator animator;
    private final ArmGeometry armGeometry;
    private final HandItemRenderer handItemRenderer;
    private final PlayerArmTexture armTexture;
    
    // Reusable matrix to avoid allocations
    private final Matrix4f reusableArmViewModel = new Matrix4f();
    
    /**
     * Creates and initializes the player arm renderer with specialized components.
     */
    public PlayerArmRenderer(ShaderProgram shaderProgram, TextureAtlas textureAtlas, Matrix4f projectionMatrix) {
        this.shaderProgram = shaderProgram;
        this.textureAtlas = textureAtlas;
        this.projectionMatrix = projectionMatrix;
        
        // Initialize specialized components
        this.animator = new PlayerArmAnimator();
        this.armGeometry = new ArmGeometry();
        this.handItemRenderer = new HandItemRenderer(shaderProgram, textureAtlas);
        this.armTexture = new PlayerArmTexture();
        
        initialize();
    }
    
    /**
     * Initializes the arm renderer by creating required resources.
     */
    private void initialize() {
        // Create the appropriate arm model based on settings
        Settings settings = Settings.getInstance();
        if (settings.isSlimArms()) {
            armGeometry.createSlimArmVao();
        } else {
            armGeometry.createRegularArmVao();
        }
        armTexture.createArmTexture();
    }
    
    
    /**
     * Renders the player's arm with Minecraft-style positioning and animation using modular components.
     */
    public void renderPlayerArm(Player player) {
        shaderProgram.bind();

        // Enable depth testing for proper rendering
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);

        // Reset transformation matrix
        reusableArmViewModel.identity();
        
        // Apply all animations through the animator component
        animator.applyAnimations(reusableArmViewModel, player);
        
        // Determine what item to display
        ItemStack selectedItem = getSelectedItem(player);
        ItemDisplayInfo displayInfo = determineItemDisplay(selectedItem);
        
        // Apply item-specific transformations if displaying an item
        if (displayInfo.displayingItem) {
            animator.applyItemTransform(reusableArmViewModel);
        }

        // Set shader matrices
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", reusableArmViewModel);

        // Render appropriate content based on what's selected
        if (displayInfo.displayingItem) {
            renderSelectedItem(displayInfo);
        } else {
            renderDefaultArm();
        }
        
        // Clean up state
        GL30.glBindVertexArray(0);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.unbind();
    }
    
    /**
     * Gets the currently selected item from the player's inventory.
     */
    private ItemStack getSelectedItem(Player player) {
        if (player.getInventory() != null) {
            return player.getInventory().getSelectedHotbarSlot();
        }
        return null;
    }
    
    /**
     * Determines what type of item should be displayed and how.
     */
    private ItemDisplayInfo determineItemDisplay(ItemStack selectedItem) {
        ItemDisplayInfo info = new ItemDisplayInfo();
        
        if (selectedItem != null && !selectedItem.isEmpty()) {
            if (selectedItem.isPlaceable()) {
                info.selectedBlockType = selectedItem.asBlockType();
                info.isDisplayingBlock = (info.selectedBlockType != null && 
                                         info.selectedBlockType != BlockType.AIR &&
                                         info.selectedBlockType.getAtlasX() >= 0 && 
                                         info.selectedBlockType.getAtlasY() >= 0);
                info.displayingItem = info.isDisplayingBlock;
            } else if (selectedItem.isTool() || selectedItem.isMaterial()) {
                info.selectedItemType = selectedItem.asItemType();
                info.isDisplayingTool = (info.selectedItemType != null &&
                                        info.selectedItemType.getAtlasX() >= 0 && 
                                        info.selectedItemType.getAtlasY() >= 0);
                info.displayingItem = info.isDisplayingTool;
            }
        }
        
        return info;
    }
    
    /**
     * Renders the selected item using the appropriate method.
     */
    private void renderSelectedItem(ItemDisplayInfo displayInfo) {
        if (displayInfo.isDisplayingBlock && displayInfo.selectedBlockType != null) {
            handItemRenderer.renderBlockInHand(displayInfo.selectedBlockType);
        } else if (displayInfo.isDisplayingTool && displayInfo.selectedItemType != null) {
            handItemRenderer.renderToolInHand(displayInfo.selectedItemType);
        }
    }
    
    /**
     * Renders the default arm when no item is selected.
     */
    private void renderDefaultArm() {
        // Set up shader for arm texture
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        // Bind arm texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, armTexture.getArmTextureId());
        shaderProgram.setUniform("texture_sampler", 0);
        
        // Use original texture colors
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Render arm geometry using appropriate model type from settings
        Settings settings = Settings.getInstance();
        GL30.glBindVertexArray(armGeometry.getArmVao(settings.isSlimArms()));
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
    }
    
    /**
     * Helper class to encapsulate item display information.
     */
    private static class ItemDisplayInfo {
        boolean displayingItem = false;
        boolean isDisplayingBlock = false;
        boolean isDisplayingTool = false;
        BlockType selectedBlockType = null;
        ItemType selectedItemType = null;
    }
    
    
    /**
     * Cleanup resources when the renderer is destroyed.
     */
    public void cleanup() {
        armTexture.cleanup();
        armGeometry.cleanup();
        handItemRenderer.cleanup();
    }
}
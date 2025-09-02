package com.stonebreak.rendering.player;

// Standard Library Imports
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

// JOML Math Library
import com.stonebreak.rendering.ShaderProgram;
import com.stonebreak.rendering.TextureAtlas;
import org.joml.Matrix4f;
import org.joml.Vector4f;

// LWJGL Core
import org.lwjgl.BufferUtils;

// LWJGL OpenGL Classes
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

// LWJGL OpenGL Static Imports
import static org.lwjgl.opengl.GL11.*;

// Project Imports
import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.player.Player;

/**
 * Specialized renderer for handling player arm rendering with items and animation.
 * Extracted from the main Renderer to improve code organization and maintainability.
 */
public class PlayerArmRenderer {
    
    // Dependencies
    private final ShaderProgram shaderProgram;
    private final TextureAtlas textureAtlas;
    private final Matrix4f projectionMatrix;
    
    // Arm-specific resources
    private int armTextureId; // Texture ID for the player arm
    private int playerArmVao; // VAO for the player's arm
    
    // Cache for block-specific VAOs for hand rendering
    private final Map<BlockType, Integer> handBlockVaoCache = new HashMap<>();
    
    // Reusable matrix to avoid allocations
    private final Matrix4f reusableArmViewModel = new Matrix4f();
    
    /**
     * Creates and initializes the player arm renderer.
     */
    public PlayerArmRenderer(ShaderProgram shaderProgram, TextureAtlas textureAtlas, Matrix4f projectionMatrix) {
        this.shaderProgram = shaderProgram;
        this.textureAtlas = textureAtlas;
        this.projectionMatrix = projectionMatrix;
        
        initialize();
    }
    
    /**
     * Initializes the arm renderer by creating the arm VAO and texture.
     */
    private void initialize() {
        createPlayerArm();
        createArmTexture();
    }
    
    /**
     * Creates the VAO for rendering the player's arm with Minecraft proportions.
     * Minecraft Steve arm dimensions: 4x12x4 pixels
     * Using 1:3 scale ratio (4 pixels = 0.133, 12 pixels = 0.4)
     */
    private void createPlayerArm() {
        // Define arm dimensions (half-extents) - Minecraft 4x12x4 pixel proportions
        float armHalfWidth = 0.067f;  // 4 pixels width (full width 0.133)
        float armHalfHeight = 0.2f;   // 12 pixels height (full height 0.4) 
        float armHalfDepth = 0.067f;  // 4 pixels depth (full depth 0.133)

        // 8 vertices of the cuboid arm with Minecraft proportions and UV mapping
        // Minecraft arms use blocky, pixelated textures with specific UV layout
        // Each face gets proper UV coordinates for Minecraft-style skin texture mapping
        float[] vertices = {
            // Front face (z = armHalfDepth) - Main arm front
            -armHalfWidth, -armHalfHeight,  armHalfDepth, 0.0f, 1.0f, // 0: Front-Bottom-Left
             armHalfWidth, -armHalfHeight,  armHalfDepth, 0.25f, 1.0f, // 1: Front-Bottom-Right
             armHalfWidth,  armHalfHeight,  armHalfDepth, 0.25f, 0.0f, // 2: Front-Top-Right
            -armHalfWidth,  armHalfHeight,  armHalfDepth, 0.0f, 0.0f, // 3: Front-Top-Left
            // Back face (z = -armHalfDepth) - Arm back
            -armHalfWidth, -armHalfHeight, -armHalfDepth, 0.5f, 1.0f, // 4: Back-Bottom-Left
             armHalfWidth, -armHalfHeight, -armHalfDepth, 0.25f, 1.0f, // 5: Back-Bottom-Right
             armHalfWidth,  armHalfHeight, -armHalfDepth, 0.25f, 0.0f, // 6: Back-Top-Right
            -armHalfWidth,  armHalfHeight, -armHalfDepth, 0.5f, 0.0f,  // 7: Back-Top-Left
            // Top face (y = armHalfHeight) - Arm top (shoulder area)
            -armHalfWidth,  armHalfHeight, -armHalfDepth, 0.25f, 0.75f, // 8
             armHalfWidth,  armHalfHeight, -armHalfDepth, 0.5f, 0.75f, // 9
             armHalfWidth,  armHalfHeight,  armHalfDepth, 0.5f, 1.0f, // 10
            -armHalfWidth,  armHalfHeight,  armHalfDepth, 0.25f, 1.0f, // 11
            // Bottom face (y = -armHalfHeight) - Arm bottom (hand area)
            -armHalfWidth, -armHalfHeight,  armHalfDepth, 0.5f, 0.75f, // 12
             armHalfWidth, -armHalfHeight,  armHalfDepth, 0.75f, 0.75f, // 13
             armHalfWidth, -armHalfHeight, -armHalfDepth, 0.75f, 1.0f, // 14
            -armHalfWidth, -armHalfHeight, -armHalfDepth, 0.5f, 1.0f, // 15
            // Right face (x = armHalfWidth) - Outer arm side
             armHalfWidth, -armHalfHeight, -armHalfDepth, 0.75f, 1.0f, // 16
             armHalfWidth, -armHalfHeight,  armHalfDepth, 1.0f, 1.0f, // 17
             armHalfWidth,  armHalfHeight,  armHalfDepth, 1.0f, 0.0f, // 18
             armHalfWidth,  armHalfHeight, -armHalfDepth, 0.75f, 0.0f, // 19
            // Left face (x = -armHalfWidth) - Inner arm side  
            -armHalfWidth, -armHalfHeight,  armHalfDepth, 0.0f, 1.0f, // 20
            -armHalfWidth, -armHalfHeight, -armHalfDepth, 0.25f, 1.0f, // 21
            -armHalfWidth,  armHalfHeight, -armHalfDepth, 0.25f, 0.0f, // 22
            -armHalfWidth,  armHalfHeight,  armHalfDepth, 0.0f, 0.0f  // 23
        };
        // Re-index for separate face UVs
        int[] indices = {
            // Front face
            0, 1, 2, 2, 3, 0,
            // Back face
            4, 5, 6, 6, 7, 4, // Use original back face vertices 4,5,6,7
            // Top face
            11, 10, 9, 9, 8, 11, // Use new top face vertices 8,9,10,11
            // Bottom face
            12, 13, 14, 14, 15, 12, // Use new bottom face vertices 12,13,14,15
            // Right face
            17, 16, 19, 19, 18, 17, // Use new right face vertices 16,17,18,19
            // Left face
            20, 23, 22, 22, 21, 20  // Use new left face vertices 20,21,22,23
        };

        this.playerArmVao = GL30.glGenVertexArrays(); // Assign to class member
        GL30.glBindVertexArray(this.playerArmVao);

        int vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);

        // Position attribute
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 5 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        // Texture coordinate attribute
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        int ibo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);

        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Creates a Minecraft-style arm texture with pixelated skin appearance.
     */
    private void createArmTexture() {
        int texWidth = 64;  // Minecraft skin texture width
        int texHeight = 64; // Minecraft skin texture height
        ByteBuffer buffer = BufferUtils.createByteBuffer(texWidth * texHeight * 4); // RGBA

        // Minecraft Steve default skin colors
        int skinR = 245; // Light skin tone
        int skinG = 220;
        int skinB = 165;
        
        int shirtR = 111; // Blue shirt/sleeve color  
        int shirtG = 124;
        int shirtB = 172;

        for (int y = 0; y < texHeight; y++) {
            for (int x = 0; x < texWidth; x++) {
                byte r, g, b, a = (byte) 255;
                
                // Create Minecraft-style pixelated pattern
                // Arm area in Minecraft skin layout: roughly x=40-48, y=16-32 for right arm
                boolean isArmArea = (x >= 40 && x < 48 && y >= 16 && y < 32);
                boolean isSleeveArea = (x >= 40 && x < 48 && y >= 0 && y < 16); // Sleeve overlay
                
                if (isSleeveArea) {
                    // Blue shirt sleeve
                    r = (byte) shirtR;
                    g = (byte) shirtG;
                    b = (byte) shirtB;
                } else if (isArmArea) {
                    // Skin tone with slight variation for pixelated look
                    int variation = ((x + y) % 3) - 1; // -1, 0, or 1
                    r = (byte) Math.max(0, Math.min(255, skinR + variation * 5));
                    g = (byte) Math.max(0, Math.min(255, skinG + variation * 3));
                    b = (byte) Math.max(0, Math.min(255, skinB + variation * 2));
                } else {
                    // Default skin tone for other areas
                    r = (byte) skinR;
                    g = (byte) skinG;
                    b = (byte) skinB;
                }

                buffer.put(r);
                buffer.put(g);
                buffer.put(b);
                buffer.put(a);
            }
        }
        buffer.flip();

        armTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, armTextureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); // Keep pixelated look
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST); // Keep pixelated look
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, texWidth, texHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    
    /**
     * Renders the player's arm with Minecraft-style positioning and animation.
     */
    public void renderPlayerArm(Player player) {
        shaderProgram.bind(); // Ensure shader is bound

        // Use a separate projection for the arm (orthographic, but could be perspective if desired)
        // For simplicity, let's use the main projection but adjust the view.
        // The arm should be rendered with proper depth testing to avoid visual artifacts.
        glEnable(GL_DEPTH_TEST); // Enable depth testing for proper rendering
        glDepthMask(true); // Enable depth writing

        // Reuse matrix to avoid allocation
        reusableArmViewModel.identity();
        
        // Get the selected item from the Player's Inventory
        ItemStack selectedItem = null;
        if (player.getInventory() != null) {
            selectedItem = player.getInventory().getSelectedHotbarSlot();
        }
        
        // Check if we should display the item (block or tool) in hand
        boolean displayingItem = false;
        boolean isDisplayingBlock = false;
        boolean isDisplayingTool = false;
        BlockType selectedBlockType = null;
        ItemType selectedItemType = null;
        
        if (selectedItem != null && !selectedItem.isEmpty()) {
            if (selectedItem.isPlaceable()) {
                // Item is a placeable block
                selectedBlockType = selectedItem.asBlockType();
                isDisplayingBlock = (selectedBlockType != null && selectedBlockType != BlockType.AIR &&
                                    selectedBlockType.getAtlasX() >= 0 && selectedBlockType.getAtlasY() >= 0);
                displayingItem = isDisplayingBlock;
            } else if (selectedItem.isTool() || selectedItem.isMaterial()) {
                // Item is a tool or material
                selectedItemType = selectedItem.asItemType();
                isDisplayingTool = (selectedItemType != null &&
                                   selectedItemType.getAtlasX() >= 0 && selectedItemType.getAtlasY() >= 0);
                displayingItem = isDisplayingTool;
            }
        }
        
        // Get total time for animations
        float totalTime = Game.getInstance().getTotalTimeElapsed();
        
        // Check if player is walking by examining velocity
        org.joml.Vector3f velocity = player.getVelocity();
        float horizontalSpeed = (float) Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        boolean isWalking = horizontalSpeed > 0.5f; // Higher threshold to avoid false positives
        
        // Base arm position - Minecraft-style positioning (right arm only visible)
        float baseX = 0.56f;  // Right side positioning like Minecraft
        float baseY = -0.48f; // Slightly lower position for natural look
        float baseZ = -0.65f; // Closer to camera for better visibility
        
        // Add walking animation - arm swaying while walking
        if (isWalking) {
            // Walking arm swing - use a consistent faster speed
            float walkCycleTime = totalTime * 6.0f; // Fixed speed for consistent animation
            
            // Primary walking swing motion (up and down)
            float walkSwayY = (float) Math.sin(walkCycleTime) * 0.02f;
            
            // Secondary walking motion (slight forward/back)
            float walkSwayZ = (float) Math.cos(walkCycleTime) * 0.01f;
            
            // Apply walking sway
            baseY += walkSwayY;
            baseZ += walkSwayZ;
        }
        
        // Add subtle idle sway only when not walking
        float swayX = 0.0f;
        float swayY = 0.0f;
        if (!isWalking) {
            // Gentle idle movement when standing still
            swayX = (float) Math.sin(totalTime * 1.2f) * 0.003f;
            swayY = (float) Math.cos(totalTime * 1.5f) * 0.002f;
        }
        
        // Add breathing-like movement
        float breatheY = (float) Math.sin(totalTime * 2.0f) * 0.008f;
        
        // Position the arm with Minecraft-style offset
        reusableArmViewModel.translate(baseX + swayX, baseY + swayY + breatheY, baseZ);
        
        // Minecraft-style arm rotation - slight inward angle
        reusableArmViewModel.rotate((float) Math.toRadians(-10.0f), 0.0f, 1.0f, 0.0f); // Slight inward rotation
        reusableArmViewModel.rotate((float) Math.toRadians(5.0f), 1.0f, 0.0f, 0.0f);   // Slight downward tilt

        // Adjust the model based on whether we're displaying an item or arm
        if (displayingItem) {
            // Position and scale for the block - Minecraft-style item positioning
            reusableArmViewModel.scale(0.4f); // Larger scale for better visibility
            reusableArmViewModel.translate(-0.3f, 0.15f, 0.3f); // Adjust position for item in hand
            
            // Apply Minecraft-style item rotation
            reusableArmViewModel.rotate((float) Math.toRadians(20.0f), 1.0f, 0.0f, 0.0f);
            reusableArmViewModel.rotate((float) Math.toRadians(-30.0f), 0.0f, 1.0f, 0.0f);
            reusableArmViewModel.rotate((float) Math.toRadians(10.0f), 0.0f, 0.0f, 1.0f);
        }

        // Enhanced swinging animation - Diagonal swing towards center
        if (player.isAttacking()) {
            float progress = 1.0f - player.getAttackAnimationProgress(); // Reverse the progress
            
            // Diagonal swing motion towards center of screen
            float swingAngle = (float) (Math.sin(progress * Math.PI) * 45.0f); // Reduced arc for diagonal motion
            float diagonalAngle = (float) (Math.sin(progress * Math.PI) * 30.0f); // Diagonal component
            float swingLift = (float) (Math.sin(progress * Math.PI * 0.5f) * 0.08f); // Slight lift during swing
            
            // Apply diagonal swing rotation - combination of X and Y axis rotation
            reusableArmViewModel.rotate((float) Math.toRadians(-swingAngle * 0.7f), 1.0f, 0.0f, 0.0f); // Reduced downward motion
            reusableArmViewModel.rotate((float) Math.toRadians(-diagonalAngle), 0.0f, 1.0f, 0.0f); // Swing towards center (negative for inward)
            reusableArmViewModel.rotate((float) Math.toRadians(swingAngle * 0.2f), 0.0f, 0.0f, 1.0f); // Slight roll for natural motion
            
            // Translate towards center of screen during swing
            reusableArmViewModel.translate(progress * -0.1f, swingLift, progress * -0.05f); // Move inward and slightly forward
        }

        // Set model-view matrix for the arm (combining arm's transformation with camera's view)
        // We want the arm to be relative to the camera, not the world.
        // So, we use an identity view matrix for the arm, and apply transformations directly.
        shaderProgram.setUniform("projectionMatrix", projectionMatrix); // Use main projection
        shaderProgram.setUniform("viewMatrix", reusableArmViewModel); // Use the arm's own model-view

        // Check if we have a valid item to display
        if (displayingItem) {
            if (isDisplayingBlock) {
            // Add a redundant check for selectedBlockType to satisfy static analyzers
            if (selectedBlockType == null) {
                // This path should ideally not be reached if displayingBlock is true due to its definition.
                // Log this inconsistency.
                System.err.println("Inconsistent state: displayingBlock is true, but selectedBlockType is null in renderPlayerArm.");
                // Fallback or error handling: perhaps render nothing or a default.
            } else {
                // Check if this is a flower block - render as flat cross pattern instead of 3D cube
                switch (selectedBlockType) {
                    case ROSE, DANDELION -> renderFlowerInHand(selectedBlockType); // Cross pattern for flowers
                    default -> {
                        // Use block-specific cube for proper face texturing
                        shaderProgram.setUniform("u_useSolidColor", false);
                        shaderProgram.setUniform("u_isText", false);
                        shaderProgram.setUniform("u_transformUVsForItem", false); // Disable UV transformation since we're using pre-calculated UVs
                        
                        GL13.glActiveTexture(GL13.GL_TEXTURE0);
                        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
                        shaderProgram.setUniform("texture_sampler", 0);
                        
                        // No tint for block texture
                        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
                        
                        // Disable blending to prevent transparency issues
                        glDisable(GL_BLEND);
                        
                        // Get or create block-specific cube with proper face textures
                        int blockSpecificVao = getHandBlockVao(selectedBlockType); // Method to be re-added
                        GL30.glBindVertexArray(blockSpecificVao);
                        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0); // 36 indices for a cube
                        
                        // Re-enable blending for other elements
                        glEnable(GL_BLEND);
                        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    }
                }
            }
        } else if (isDisplayingTool && selectedItemType != null) {
            // Handle tool rendering - render 2D sprite in hand position
            renderToolInHand(selectedItemType);
        }
        } else {
            // Fallback to the default arm texture
            shaderProgram.setUniform("u_useSolidColor", false);
            shaderProgram.setUniform("u_isText", false);
            shaderProgram.setUniform("u_transformUVsForItem", false);
            
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, armTextureId);
            shaderProgram.setUniform("texture_sampler", 0);
            
            // Minecraft Steve skin-tone - no tint, use texture colors
            shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
            
            // Use the arm model as fallback
            GL30.glBindVertexArray(playerArmVao);
            glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
        }
        
        GL30.glBindVertexArray(0);
        
        // Reset shader state
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        // Depth testing is already enabled, so no need to re-enable
        shaderProgram.unbind(); // Unbind shader if it's not used immediately after
    }
    
    /**
     * Gets or creates a block-specific VAO for hand rendering.
     */
    private int getHandBlockVao(BlockType blockType) {
        // Check if VAO is already cached
        Integer cachedVao = handBlockVaoCache.get(blockType);
        if (cachedVao != null) {
            return cachedVao;
        }
        
        // Create new VAO and cache it
        int vao = createBlockSpecificCube(blockType);
        handBlockVaoCache.put(blockType, vao);
        return vao;
    }
    
    /**
     * Creates a VAO for a cube with textures specific to the given BlockType.
     * Each face of the cube uses the appropriate texture coordinates from the atlas.
     * This method is used for rendering blocks in hand.
     */
    private int createBlockSpecificCube(BlockType type) {
        // Use the modern metadata-driven texture atlas system instead of legacy grid coordinates
        float[] frontUVs = textureAtlas.getBlockFaceUVs(type, BlockType.Face.SIDE_NORTH);   // Front
        float[] backUVs = textureAtlas.getBlockFaceUVs(type, BlockType.Face.SIDE_SOUTH);    // Back
        float[] topUVs = textureAtlas.getBlockFaceUVs(type, BlockType.Face.TOP);            // Top
        float[] bottomUVs = textureAtlas.getBlockFaceUVs(type, BlockType.Face.BOTTOM);      // Bottom
        float[] rightUVs = textureAtlas.getBlockFaceUVs(type, BlockType.Face.SIDE_EAST);    // Right
        float[] leftUVs = textureAtlas.getBlockFaceUVs(type, BlockType.Face.SIDE_WEST);     // Left

        // Define vertices for a cube (position, normal, texCoord)
        // Each face defined separately to allow different UVs per face
        float[] vertices = {
            // Front face (+Z)
            -0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  frontUVs[0], frontUVs[3], // Bottom-left
             0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  frontUVs[2], frontUVs[3], // Bottom-right
             0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  frontUVs[2], frontUVs[1], // Top-right
            -0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  frontUVs[0], frontUVs[1], // Top-left
            
            // Back face (-Z)
            -0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[0], backUVs[3], // Bottom-left
             0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[2], backUVs[3], // Bottom-right
             0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[2], backUVs[1], // Top-right
            -0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[0], backUVs[1], // Top-left
            
            // Top face (+Y)
            -0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,  topUVs[0], topUVs[1], // Top-left
             0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,  topUVs[2], topUVs[1], // Top-right
             0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,  topUVs[2], topUVs[3], // Bottom-right
            -0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,  topUVs[0], topUVs[3], // Bottom-left
            
            // Bottom face (-Y)
            -0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f, bottomUVs[0], bottomUVs[1], // Top-left
             0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f, bottomUVs[2], bottomUVs[1], // Top-right
             0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f, bottomUVs[2], bottomUVs[3], // Bottom-right
            -0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f, bottomUVs[0], bottomUVs[3], // Bottom-left
            
            // Right face (+X)
             0.5f, -0.5f, -0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[0], rightUVs[3], // Bottom-left
             0.5f, -0.5f,  0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[2], rightUVs[3], // Bottom-right
             0.5f,  0.5f,  0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[2], rightUVs[1], // Top-right
             0.5f,  0.5f, -0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[0], rightUVs[1], // Top-left
            
            // Left face (-X)
            -0.5f, -0.5f, -0.5f, -1.0f,  0.0f,  0.0f,  leftUVs[0], leftUVs[3], // Bottom-left
            -0.5f, -0.5f,  0.5f, -1.0f,  0.0f,  0.0f,  leftUVs[2], leftUVs[3], // Bottom-right
            -0.5f,  0.5f,  0.5f, -1.0f,  0.0f,  0.0f,  leftUVs[2], leftUVs[1], // Top-right
            -0.5f,  0.5f, -0.5f, -1.0f,  0.0f,  0.0f,  leftUVs[0], leftUVs[1]  // Top-left
        };
        
        int[] indices = {
            0,  1,  2,  0,  2,  3,  // Front
            4,  5,  6,  4,  6,  7,  // Back
            8,  9, 10,  8, 10, 11,  // Top
            12, 13, 14, 12, 14, 15, // Bottom
            16, 17, 18, 16, 18, 19, // Right
            20, 21, 22, 20, 22, 23  // Left
        };
        
        // Create VAO
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        
        // Create VBO
        int vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
        
        int stride = 8 * Float.BYTES; // 3 pos, 3 normal, 2 texCoord
        // Position attribute (location 0)
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        // Normal attribute (location 2) - Make sure shader uses location 2 for normals
        GL20.glVertexAttribPointer(2, 3, GL20.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        // Texture coordinate attribute (location 1)
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, stride, 6 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);
        
        // Create IBO
        int ibo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
        
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        
        return vao;
    }
    
    /**
     * Renders flowers as a cross pattern in the player's hand.
     */
    private void renderFlowerInHand(BlockType flowerType) {
        // Set up shader for flower rendering
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        // Get UV coordinates for the flower using modern texture atlas system
        float[] uvCoords = textureAtlas.getBlockFaceUVs(flowerType, BlockType.Face.TOP);
        
        // Bind texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        shaderProgram.setUniform("texture_sampler", 0);
        
        // Enable blending for transparency
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        // No tint - use pure white
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Create two intersecting quads to form a cross pattern (like flowers in Minecraft)
        createAndRenderFlowerCross(uvCoords);
    }

    /**
     * Creates and renders the cross pattern for flowers.
     */
    private void createAndRenderFlowerCross(float[] uvCoords) {
        // Create vertices for two intersecting quads forming a cross
        // First quad (Z-aligned)
        float[] vertices1 = {
            // Quad 1: Front-back cross section
            -0.5f, -0.5f, 0.0f,  0.0f, 0.0f, 1.0f,  uvCoords[0], uvCoords[3], // Bottom-left
             0.5f, -0.5f, 0.0f,  0.0f, 0.0f, 1.0f,  uvCoords[2], uvCoords[3], // Bottom-right
             0.5f,  0.5f, 0.0f,  0.0f, 0.0f, 1.0f,  uvCoords[2], uvCoords[1], // Top-right
            -0.5f,  0.5f, 0.0f,  0.0f, 0.0f, 1.0f,  uvCoords[0], uvCoords[1]  // Top-left
        };
        
        // Second quad (X-aligned, rotated 90 degrees)
        float[] vertices2 = {
            // Quad 2: Left-right cross section  
            0.0f, -0.5f, -0.5f,  1.0f, 0.0f, 0.0f,  uvCoords[0], uvCoords[3], // Bottom-left
            0.0f, -0.5f,  0.5f,  1.0f, 0.0f, 0.0f,  uvCoords[2], uvCoords[3], // Bottom-right
            0.0f,  0.5f,  0.5f,  1.0f, 0.0f, 0.0f,  uvCoords[2], uvCoords[1], // Top-right
            0.0f,  0.5f, -0.5f,  1.0f, 0.0f, 0.0f,  uvCoords[0], uvCoords[1]  // Top-left
        };
        
        int[] indices = { 0, 1, 2, 0, 2, 3 };
        
        // Render both quads
        renderSimpleQuad(vertices1, indices);
        renderSimpleQuad(vertices2, indices);
    }
    
    /**
     * Renders tools as 2D sprites in the player's hand.
     */
    private void renderToolInHand(ItemType itemType) {
        // Get UV coordinates for the item
        float[] uvCoords = textureAtlas.getTextureCoordinatesForItem(itemType.getId());
        
        // Set up shader uniforms (keeping existing projection/view matrices)
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false); 
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Bind texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        shaderProgram.setUniform("texture_sampler", 0);
        
        // Set up OpenGL state for item rendering
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);
        
        // Create a simple 2D quad for the item - positioned higher up in the hand
        float size = 0.3f;
        float yOffset = 0.4f; // Move the item up in the hand
        float[] vertices = {
            // Position                        UV coordinates
            -size, -size + yOffset, 0.0f,   uvCoords[0], uvCoords[3], // Bottom-left
             size, -size + yOffset, 0.0f,   uvCoords[2], uvCoords[3], // Bottom-right
             size,  size + yOffset, 0.0f,   uvCoords[2], uvCoords[1], // Top-right
            -size,  size + yOffset, 0.0f,   uvCoords[0], uvCoords[1]  // Top-left
        };
        
        int[] indices = { 0, 1, 2, 0, 2, 3 };
        
        // Create temporary VAO for the item quad
        renderSimpleQuad(vertices, indices);
        
        // Restore OpenGL state
        glEnable(GL_CULL_FACE);
    }
    
    /**
     * Renders a simple quad with position and UV data.
     */
    private void renderSimpleQuad(float[] vertices, int[] indices) {
        // Create temporary buffers and VAO
        int vao = GL30.glGenVertexArrays();
        int vbo = GL20.glGenBuffers(); 
        int ebo = GL20.glGenBuffers();
        
        try {
            GL30.glBindVertexArray(vao);
            
            // Upload vertex data
            GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
            FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
            vertexBuffer.put(vertices).flip();
            GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
            
            // Upload index data
            GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ebo);
            IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
            indexBuffer.put(indices).flip();
            GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
            
            // Set up vertex attributes - position at 0, UV at 1 (matching shader)
            GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);
            
            // Draw the quad
            GL20.glDrawElements(GL_TRIANGLES, indices.length, GL_UNSIGNED_INT, 0);
            
        } finally {
            // Cleanup
            GL30.glBindVertexArray(0);
            GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
            GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, 0);
            GL20.glDeleteBuffers(vbo);
            GL20.glDeleteBuffers(ebo);
            GL30.glDeleteVertexArrays(vao);
        }
    }
    
    /**
     * Cleanup resources when the renderer is destroyed.
     */
    public void cleanup() {
        if (armTextureId != 0) {
            glDeleteTextures(armTextureId);
            armTextureId = 0;
        }
        
        if (playerArmVao != 0) {
            GL30.glDeleteVertexArrays(playerArmVao);
            playerArmVao = 0;
        }
        
        // Cleanup cached hand block VAOs
        for (int vao : handBlockVaoCache.values()) {
            if (vao != 0) {
                GL30.glDeleteVertexArrays(vao);
            }
        }
        handBlockVaoCache.clear();
    }
}
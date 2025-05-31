package com.stonebreak;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.joml.Matrix4f;
import org.joml.Vector3i;
import org.joml.Vector4f; // Added import for ByteBuffer
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_LINES;
import static org.lwjgl.opengl.GL11.GL_POLYGON_OFFSET_FILL;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_POINTS;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_BOX;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_FAN;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glBegin;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glEnd;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glIsEnabled;
import static org.lwjgl.opengl.GL11.glPointSize;
import static org.lwjgl.opengl.GL11.glPolygonOffset;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glVertex3f;
import static org.lwjgl.opengl.GL11.glViewport;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Handles rendering of the world and UI elements.
 */
public class Renderer {
    
    // Shader program
    private final ShaderProgram shaderProgram;
    
    // Textures
    private final TextureAtlas textureAtlas;
    private int armTextureId; // Texture ID for the player arm
    private final Font font; // Added Font instance
    private int windowWidth;
    private int windowHeight;
    
    // Isolated block drop renderer
    private final BlockDropRenderer blockDropRenderer;
    
    // Matrices
    private final Matrix4f projectionMatrix;
    
    // UI elements
    private int crosshairVao;
    private int hotbarVao;
    private int playerArmVao; // VAO for the player's arm
    private int uiQuadVao;    // VAO for drawing generic UI quads (positions and UVs)
    private int uiQuadVbo;    // VBO for drawing generic UI quads (positions and UVs)
    

    // 3D Item Cube for Inventory - REMOVED: Now using block-specific cubes
    
    // Block cracking overlay system
    private int crackTextureId;
    private int blockOverlayVao;
    
    // Cache for block-specific VAOs for hand rendering
    private final Map<BlockType, Integer> handBlockVaoCache = new HashMap<>();

    /**
     * Creates and initializes the renderer.
     */
    public Renderer(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
        // Create shader program
        shaderProgram = new ShaderProgram();
        shaderProgram.createVertexShader(loadResource("/shaders/vertex.glsl"));
        shaderProgram.createFragmentShader(loadResource("/shaders/fragment.glsl"));
        shaderProgram.link();
        
        // Initialize isolated block drop renderer
        blockDropRenderer = new BlockDropRenderer();
        
        // Create projection matrix
        float aspectRatio = (float) width / height;
        projectionMatrix = new Matrix4f().perspective((float) Math.toRadians(70.0f), aspectRatio, 0.1f, 1000.0f);
        
        // Create uniforms for projection and view matrices
        shaderProgram.createUniform("projectionMatrix");
        shaderProgram.createUniform("viewMatrix");
        shaderProgram.createUniform("modelMatrix");
        shaderProgram.createUniform("texture_sampler");
        shaderProgram.createUniform("u_color");          // Uniform for solid color / text tint
        shaderProgram.createUniform("u_useSolidColor");  // Uniform to toggle solid color mode
        shaderProgram.createUniform("u_isText");         // Uniform to toggle text rendering mode
        shaderProgram.createUniform("u_transformUVsForItem"); // For 3D items in UI
        shaderProgram.createUniform("u_atlasUVOffset");       // For 3D items in UI - vec2(u1, v1)
        shaderProgram.createUniform("u_atlasUVScale");        // For 3D items in UI - vec2(u2-u1, v2-v1)
        shaderProgram.createUniform("u_renderPass");          // For two-pass rendering (0 = opaque, 1 = transparent)
        
        // Load textures
        textureAtlas = new TextureAtlas(16); // 16x16 texture atlas
        
        // Initialize isolated block drop renderer with the shader and texture
        blockDropRenderer.initialize(shaderProgram, textureAtlas);

        // Initialize font
        // Ensure Roboto-VariableFont_wdth,wght.ttf is in src/main/resources/fonts/
        font = new Font("fonts/Roboto-VariableFont_wdth,wght.ttf", 24f);
        
        // Create UI elements
        createCrosshair();
        createHotbar();
        createPlayerArm();
        createArmTexture(); // Create the Perlin noise texture for the arm
        createUiQuadRenderer(); // Initialize UI quad rendering resources
        createCrackTexture();   // Initialize block cracking texture
        createBlockOverlayVao(); // Initialize block overlay rendering
    }
    
    /**
     * Updates the projection matrix when the window is resized.
     */
    public void updateProjectionMatrix(int width, int height) {
        this.windowWidth = width;
        this.windowHeight = height;
        float aspectRatio = (float) width / height;
        projectionMatrix.setPerspective((float) Math.toRadians(70.0f), aspectRatio, 0.1f, 1000.0f);
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    public Font getFont() {
        return font;
    }

    public ShaderProgram getShaderProgram() {
        return shaderProgram;
    }

    public TextureAtlas getTextureAtlas() {
        return textureAtlas;
    }
    
    /**
     * Creates the crosshair UI element.
     */
    private void createCrosshair() {
        // Create a simple crosshair in the center of the screen
        float[] vertices = {
            -0.01f, 0.0f, 0.0f,
            0.01f, 0.0f, 0.0f,
            0.0f, -0.01f, 0.0f,
            0.0f, 0.01f, 0.0f
        };
        
        // Create VAO
        crosshairVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(crosshairVao);
        
        // Create VBO
        int vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        
        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, buffer, GL20.GL_STATIC_DRAW);
        
        // Define vertex attributes
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // Unbind
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }
    
    /**
     * Creates the hotbar UI element.
     */
    private void createHotbar() {
        // Create a simple hotbar at the bottom of the screen
        float[] vertices = {
            -0.4f, -0.9f, 0.0f,
            0.4f, -0.9f, 0.0f,
            0.4f, -0.8f, 0.0f,
            -0.4f, -0.8f, 0.0f
        };
        
        int[] indices = {
            0, 1, 2,
            2, 3, 0
        };
        
        // Create VAO
        hotbarVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(hotbarVao);
        
        // Create vertex VBO
        int vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
        
        // Define vertex attributes
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // Create index VBO
        int ibo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
        
        // Unbind
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
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
     * Creates the VAO and VBO for rendering generic UI quads.
     */
    private void createUiQuadRenderer() {
        uiQuadVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(uiQuadVao);

        uiQuadVbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, uiQuadVbo);
        // Allocate buffer for 4 vertices, each with 5 floats (x, y, z, u, v).
        // Using GL_DYNAMIC_DRAW as vertex data will change frequently.
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, 4 * 5 * Float.BYTES, GL20.GL_DYNAMIC_DRAW);

        // Vertex attribute for position (location 0)
        // Stride is 5 floats (x,y,z,u,v)
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 5 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);

        // Vertex attribute for texture coordinates (location 1)
        // Stride is 5 floats, offset is 3 floats (after x,y,z)
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1); // Enable attribute for texCoords

        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Renders the world and UI elements.
     */
    public void renderWorld(World world, Player player, float totalTime) { // Added totalTime parameter
        // Update animated textures
        textureAtlas.updateAnimatedWater(totalTime);

        // Enable depth testing (globally for the world pass)
        glEnable(GL_DEPTH_TEST);
        
        // Use shader program
        shaderProgram.bind();
        
        // Set common uniforms for world rendering
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", player.getViewMatrix());
        shaderProgram.setUniform("modelMatrix", new Matrix4f()); // Identity for world chunks
        shaderProgram.setUniform("texture_sampler", 0);
        shaderProgram.setUniform("u_useSolidColor", false); // World objects are textured
        shaderProgram.setUniform("u_isText", false);        // World objects are not text
        
        // Bind texture atlas once before passes
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        // Ensure texture filtering is set (NEAREST for blocky style)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        
        // Get player chunk position
        int playerChunkX = (int) Math.floor(player.getPosition().x / World.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / World.CHUNK_SIZE);
        Map<World.ChunkPosition, Chunk> visibleChunks = world.getChunksAroundPlayer(playerChunkX, playerChunkZ);

        // --- PASS 1: Opaque objects (or non-water parts of chunks) ---
        shaderProgram.setUniform("u_renderPass", 0); // 0 for opaque/non-water pass
        glDepthMask(true);  // Enable depth writing for opaque objects
        glDisable(GL_BLEND); // Opaque objects typically don't need blending

        for (Chunk chunk : visibleChunks.values()) {
            // Texture atlas is already bound
            chunk.render(); // Chunk.render() will be called, shader will discard water fragments
        }

        // --- PASS 2: Transparent objects (water parts of chunks) ---
        shaderProgram.setUniform("u_renderPass", 1); // 1 for transparent/water pass
        glDepthMask(false); // Disable depth writing for transparent objects
        glEnable(GL_BLEND); // Enable blending
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // Standard alpha blending
 
        // Sort chunks from back to front for transparent pass
        List<Chunk> sortedTransparentChunks = new ArrayList<>(visibleChunks.values());
        org.joml.Vector3f playerPos = player.getPosition();
        Collections.sort(sortedTransparentChunks, (c1, c2) -> {
            // Calculate distance squared from player to center of each chunk
            // Chunk's world position is (c.getX() * World.CHUNK_SIZE, c.getZ() * World.CHUNK_SIZE)
            // Center of chunk is (c.getX() * World.CHUNK_SIZE + World.CHUNK_SIZE / 2.0f, ...)
            float c1CenterX = c1.getWorldX(World.CHUNK_SIZE / 2);
            float c1CenterZ = c1.getWorldZ(World.CHUNK_SIZE / 2);
            float c2CenterX = c2.getWorldX(World.CHUNK_SIZE / 2);
            float c2CenterZ = c2.getWorldZ(World.CHUNK_SIZE / 2);

            float distSq1 = (playerPos.x - c1CenterX) * (playerPos.x - c1CenterX) +
                            (playerPos.z - c1CenterZ) * (playerPos.z - c1CenterZ);
            float distSq2 = (playerPos.x - c2CenterX) * (playerPos.x - c2CenterX) +
                            (playerPos.z - c2CenterZ) * (playerPos.z - c2CenterZ);
            
            // Sort in descending order of distance (farthest first)
            return Float.compare(distSq2, distSq1);
        });

        for (Chunk chunk : sortedTransparentChunks) {
            // Texture atlas is already bound
            chunk.render(); // Chunk.render() will be called, shader will discard non-water fragments
        }
        
        glDepthMask(true);  // Restore depth writing
        glDisable(GL_BLEND); // Restore blending state (or enable if UI needs it by default) - UI pass will set its own

        // Unbind texture atlas if no longer needed by subsequent world elements (arm, particles)
        // glBindTexture(GL_TEXTURE_2D, 0); // Or rebind as needed

        // Render block crack overlay if breaking a block
        renderBlockCrackOverlay(player);

        // Render player arm (if not in pause menu, etc.)
        // Player arm needs its own shader setup, including texture
        if (!Game.getInstance().isPaused()) {
            renderPlayerArm(player); // This method binds its own shader and texture
        }

        // Render water particles
        renderWaterParticles(); // This method binds its own shader

        // Render block drops
        renderBlockDrops(world);

        // Unbind main world shader program if other things don't use it
        // shaderProgram.unbind(); // UI pass binds it again.
        
        // Render UI elements
        renderUI();
    }
    
    /**
     * Renders the world WITHOUT block drops to prevent UI corruption.
     */
    public void renderWorldWithoutDrops(World world, Player player, float totalTime) {
        // Update animated textures
        textureAtlas.updateAnimatedWater(totalTime);

        // Enable depth testing (globally for the world pass)
        glEnable(GL_DEPTH_TEST);
        
        // Use shader program
        shaderProgram.bind();
        
        // Set common uniforms for world rendering
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", player.getViewMatrix());
        shaderProgram.setUniform("modelMatrix", new Matrix4f()); // Identity for world chunks
        shaderProgram.setUniform("texture_sampler", 0);
        shaderProgram.setUniform("u_useSolidColor", false); // World objects are textured
        shaderProgram.setUniform("u_isText", false);        // World objects are not text
        
        // Bind texture atlas once before passes
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        // Ensure texture filtering is set (NEAREST for blocky style)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        
        // Get player chunk position
        int playerChunkX = (int) Math.floor(player.getPosition().x / World.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / World.CHUNK_SIZE);
        Map<World.ChunkPosition, Chunk> visibleChunks = world.getChunksAroundPlayer(playerChunkX, playerChunkZ);

        // --- PASS 1: Opaque objects (or non-water parts of chunks) ---
        shaderProgram.setUniform("u_renderPass", 0); // 0 for opaque/non-water pass
        glDepthMask(true);  // Enable depth writing for opaque objects
        glDisable(GL_BLEND); // Opaque objects typically don't need blending

        for (Chunk chunk : visibleChunks.values()) {
            // Texture atlas is already bound
            chunk.render(); // Chunk.render() will be called, shader will discard water fragments
        }

        // --- PASS 2: Transparent objects (water parts of chunks) ---
        shaderProgram.setUniform("u_renderPass", 1); // 1 for transparent/water pass
        glDepthMask(false); // Disable depth writing for transparent objects
        glEnable(GL_BLEND); // Enable blending
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // Standard alpha blending
 
        // Sort chunks from back to front for transparent pass
        List<Chunk> sortedTransparentChunks = new ArrayList<>(visibleChunks.values());
        org.joml.Vector3f playerPos = player.getPosition();
        Collections.sort(sortedTransparentChunks, (c1, c2) -> {
            // Calculate distance squared from player to center of each chunk
            // Chunk's world position is (c.getX() * World.CHUNK_SIZE, c.getZ() * World.CHUNK_SIZE)
            // Center of chunk is (c.getX() * World.CHUNK_SIZE + World.CHUNK_SIZE / 2.0f, ...)
            float c1CenterX = c1.getWorldX(World.CHUNK_SIZE / 2);
            float c1CenterZ = c1.getWorldZ(World.CHUNK_SIZE / 2);
            float c2CenterX = c2.getWorldX(World.CHUNK_SIZE / 2);
            float c2CenterZ = c2.getWorldZ(World.CHUNK_SIZE / 2);

            float distSq1 = (playerPos.x - c1CenterX) * (playerPos.x - c1CenterX) +
                            (playerPos.z - c1CenterZ) * (playerPos.z - c1CenterZ);
            float distSq2 = (playerPos.x - c2CenterX) * (playerPos.x - c2CenterX) +
                            (playerPos.z - c2CenterZ) * (playerPos.z - c2CenterZ);
            
            // Sort in descending order of distance (farthest first)
            return Float.compare(distSq2, distSq1);
        });

        for (Chunk chunk : sortedTransparentChunks) {
            // Texture atlas is already bound
            chunk.render(); // Chunk.render() will be called, shader will discard non-water fragments
        }
        
        glDepthMask(true);  // Restore depth writing
        glDisable(GL_BLEND); // Restore blending state (or enable if UI needs it by default) - UI pass will set its own

        // Render block crack overlay if breaking a block
        renderBlockCrackOverlay(player);

        // Render player arm (if not in pause menu, etc.)
        // Player arm needs its own shader setup, including texture
        if (!Game.getInstance().isPaused()) {
            renderPlayerArm(player); // This method binds its own shader and texture
        }

        // Render water particles
        renderWaterParticles(); // This method binds its own shader

        // NOTE: Block drops are NOT rendered here - they are deferred
        
        // Render UI elements
        renderUI();
    }
    
    /**
     * Renders block drops in a deferred pass after all UI rendering is complete.
     */
    public void renderBlockDropsDeferred(World world, Player player) {
        // This method renders block drops completely isolated from UI rendering
        renderBlockDrops(world);
    }
    
    /**
     * Renders the player's arm with Minecraft-style positioning and animation.
     */
    private void renderPlayerArm(Player player) {
        shaderProgram.bind(); // Ensure shader is bound

        // Use a separate projection for the arm (orthographic, but could be perspective if desired)
        // For simplicity, let's use the main projection but adjust the view.
        // The arm should be rendered with proper depth testing to avoid visual artifacts.
        glEnable(GL_DEPTH_TEST); // Enable depth testing for proper rendering
        glDepthMask(true); // Enable depth writing

        Matrix4f armViewModel = new Matrix4f();
        
        // Get the selected block type from the Player's Inventory
        int selectedBlockTypeId = 1; // Default to a valid block if inventory is null for some reason
        if (player.getInventory() != null) {
            selectedBlockTypeId = player.getInventory().getSelectedBlockTypeId();
        }
        BlockType selectedBlockType = BlockType.getById(selectedBlockTypeId);
        boolean displayingBlock = (selectedBlockType != null && selectedBlockType != BlockType.AIR &&
                                  selectedBlockType.getAtlasX() >= 0 && selectedBlockType.getAtlasY() >= 0);
        
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
        armViewModel.translate(baseX + swayX, baseY + swayY + breatheY, baseZ);
        
        // Minecraft-style arm rotation - slight inward angle
        armViewModel.rotate((float) Math.toRadians(-10.0f), 0.0f, 1.0f, 0.0f); // Slight inward rotation
        armViewModel.rotate((float) Math.toRadians(5.0f), 1.0f, 0.0f, 0.0f);   // Slight downward tilt

        // Adjust the model based on whether we're displaying a block or arm
        if (displayingBlock) {
            // Position and scale for the block - Minecraft-style item positioning
            armViewModel.scale(0.4f); // Larger scale for better visibility
            armViewModel.translate(-0.3f, 0.15f, 0.3f); // Adjust position for item in hand
            
            // Apply Minecraft-style item rotation
            armViewModel.rotate((float) Math.toRadians(20.0f), 1.0f, 0.0f, 0.0f);
            armViewModel.rotate((float) Math.toRadians(-30.0f), 0.0f, 1.0f, 0.0f);
            armViewModel.rotate((float) Math.toRadians(10.0f), 0.0f, 0.0f, 1.0f);
        }

        // Enhanced swinging animation - Minecraft-style (reversed)
        if (player.isAttacking()) {
            float progress = 1.0f - player.getAttackAnimationProgress(); // Reverse the progress
            
            // Minecraft uses a curved swing motion
            float swingAngle = (float) (Math.sin(progress * Math.PI) * 60.0f); // Larger swing arc
            float swingLift = (float) (Math.sin(progress * Math.PI * 0.5f) * 0.1f); // Lift arm up during swing
            
            // Apply swing rotation around multiple axes for more natural movement
            armViewModel.rotate((float) Math.toRadians(-swingAngle), 1.0f, 0.0f, 0.0f); // Primary swing motion
            armViewModel.rotate((float) Math.toRadians(swingAngle * 0.3f), 0.0f, 1.0f, 0.0f); // Secondary motion
            armViewModel.translate(progress * 0.15f, swingLift, progress * -0.1f); // Move arm during swing
        }

        // Set model-view matrix for the arm (combining arm's transformation with camera's view)
        // We want the arm to be relative to the camera, not the world.
        // So, we use an identity view matrix for the arm, and apply transformations directly.
        shaderProgram.setUniform("projectionMatrix", projectionMatrix); // Use main projection
        shaderProgram.setUniform("viewMatrix", armViewModel); // Use the arm's own model-view

        // Check if we have a valid block type to display
        if (displayingBlock) {
            // Add a redundant check for selectedBlockType to satisfy static analyzers
            if (selectedBlockType == null) {
                // This path should ideally not be reached if displayingBlock is true due to its definition.
                // Log this inconsistency.
                System.err.println("Inconsistent state: displayingBlock is true, but selectedBlockType is null in renderPlayerArm.");
                // Fallback or error handling: perhaps render nothing or a default.
            } else {
                // Check if this is a flower block - render as flat cross pattern instead of 3D cube
                if (selectedBlockType == BlockType.ROSE || selectedBlockType == BlockType.DANDELION) {
                    renderFlowerInHand(selectedBlockType);
                } else {
                    // Use block-specific cube for proper face texturing
                    shaderProgram.setUniform("u_useSolidColor", false);
                    shaderProgram.setUniform("u_isText", false);
                    shaderProgram.setUniform("u_transformUVsForItem", false); // Disable UV transformation since we're using pre-calculated UVs
                    
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);
                    glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
                    shaderProgram.setUniform("texture_sampler", 0);
                    
                    // No tint for block texture
                    shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
                    
                    // Disable blending to prevent transparency issues
                    glDisable(GL_BLEND);
                    
                    // Get or create block-specific cube with proper face textures
                    int blockSpecificVao = getHandBlockVao(selectedBlockType);
                    GL30.glBindVertexArray(blockSpecificVao);
                    glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0); // 36 indices for a cube
                    
                    // Re-enable blending for other elements
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                }
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
            shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
            
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
     * Renders UI elements on top of the 3D world.
     */
    private void renderUI() {
        // Clear the depth buffer to ensure UI is drawn on top of everything from the 3D world pass
        glClear(GL_DEPTH_BUFFER_BIT);

        // Disable depth testing for UI
        glDisable(GL_DEPTH_TEST);
        
        // Use shader program
        shaderProgram.bind();
        
        // Set orthographic projection
        Matrix4f orthoProjection = new Matrix4f().ortho(-1.0f, 1.0f, -1.0f, 1.0f, -1.0f, 1.0f);
        shaderProgram.setUniform("projectionMatrix", orthoProjection);
        shaderProgram.setUniform("viewMatrix", new Matrix4f());
        shaderProgram.setUniform("modelMatrix", new Matrix4f()); // Identity for UI
        shaderProgram.setUniform("texture_sampler", 0); // Ensure texture unit 0 for UI textures (like font)
          // Only show crosshair and hotbar when not paused
        if (!Game.getInstance().isPaused()) {
            shaderProgram.setUniform("u_useSolidColor", true); // Assuming crosshair/hotbar are solid colors
            shaderProgram.setUniform("u_isText", false);

            // Render crosshair (e.g., white)
            shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
            GL30.glBindVertexArray(crosshairVao);
            glDrawArrays(GL_LINES, 0, 4);
            
            // Render hotbar background (e.g., dark semi-transparent)
            // shaderProgram.setUniform("u_color", new Vector4f(0.3f, 0.3f, 0.3f, 0.7f));
            // GL30.glBindVertexArray(hotbarVao);
            // glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
            
        }
        
        // Pause menu is now rendered in Main.java using UIRenderer
        
        // Render inventory screen if visible
        InventoryScreen inventoryScreen = Game.getInstance().getInventoryScreen();
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            // Make sure we have a clean 2D state for the inventory
            glDisable(GL_DEPTH_TEST);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            
            // InventoryScreen handles its own 2D orthographic projection setup
            inventoryScreen.render(windowWidth, windowHeight);
            
            // Re-bind shader as inventory might have changed state
            shaderProgram.bind();
        }
        
        
        // Reset shader state after UI
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);

        // Unbind VAO and shader
        GL30.glBindVertexArray(0);
        shaderProgram.unbind();
        
        // Re-enable depth testing
        glEnable(GL_DEPTH_TEST);
    }

    /**
     * Draws text on the screen using the initialized font.
     * Coordinates are typically in screen space (e.g., pixels or normalized UI space).
     * The shader program should be bound, and orthographic projection set before calling this.
     */    public void drawText(String text, float x, float y, Vector4f color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        
        // Set up shader for text rendering
        shaderProgram.setUniform("u_isText", true);
        shaderProgram.setUniform("u_color", color); // This will be the tint for the font texture
        shaderProgram.setUniform("texture_sampler", 0); // Ensure texture unit 0
        
        // Enable blending for proper text rendering if not already enabled
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        if (!blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        
        // Draw the text
        font.drawText(x, y, text, shaderProgram, color);
        
        // Reset blending state if it wasn't enabled before
        if (!blendWasEnabled) {
            glDisable(GL_BLEND);
        }
        
        // Note: The caller (renderUI or pauseMenu) is responsible for resetting u_isText to false
    }

    /**
     * Draws a colored quad on the screen. Assumes shaderProgram is bound and orthographic projection is set.
     * Coordinates are in screen pixels (top-left origin).
     * @param x X-coordinate of the top-left corner
     * @param y Y-coordinate of the top-left corner
     * @param width Width of the quad
     * @param height Height of the quad
     * @param r Red component (0-255)
     * @param g Green component (0-255)
     * @param b Blue component (0-255)
     * @param a Alpha component (0-255)
     */
    public void drawQuad(int x, int y, int width, int height, int r, int g, int b, int a) {
        // Normalize color components
        float red = r / 255.0f;
        float green = g / 255.0f;
        float blue = b / 255.0f;
        float alpha = a / 255.0f;

        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new Vector4f(red, green, blue, alpha));

        // Vertices are now directly in pixel coordinates as expected by InventoryScreen's projection.
        // Projection: ortho(0, screenWidth, screenHeight, 0, -1, 1) means Y=0 is top.
        float x_pixel = (float)x;
        float y_pixel = (float)y;
        float x_plus_width_pixel = (float)(x + width);
        float y_plus_height_pixel = (float)(y + height);

        float[] vertices = {
            x_pixel,             y_pixel,               0.0f, 0.0f, 0.0f, // Top-left
            x_plus_width_pixel,  y_pixel,               0.0f, 0.0f, 0.0f, // Top-right
            x_plus_width_pixel,  y_plus_height_pixel,   0.0f, 0.0f, 0.0f, // Bottom-right
            x_pixel,             y_plus_height_pixel,   0.0f, 0.0f, 0.0f  // Bottom-left
        };

        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();

        GL30.glBindVertexArray(uiQuadVao);
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, uiQuadVbo);

        // Update the buffer data
        // uiQuadVao should have attributes 0 (position) and 1 (texCoord) enabled from createUiQuadRenderer
        GL20.glBufferSubData(GL20.GL_ARRAY_BUFFER, 0, vertexBuffer);

        // Blending is now assumed to be handled by the caller (e.g., InventoryScreen)
        // The shader will use u_useSolidColor to ignore texCoords if necessary.

        boolean texture2DWasEnabled = glIsEnabled(GL_TEXTURE_2D);
        if (texture2DWasEnabled) {
            glDisable(GL_TEXTURE_2D); // Ensure texturing is off for solid color draw
        }

        glDrawArrays(GL_TRIANGLE_FAN, 0, 4); // TRIANGLE_FAN: 0,1,2 then 0,2,3 (quad: 0,1,2,3)

        if (texture2DWasEnabled) {
            glEnable(GL_TEXTURE_2D); // Restore texturing state if we changed it
        }
        
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Draws a textured quad on the screen using the UI VAO. Assumes shaderProgram is bound and orthographic projection is set.
     * Coordinates are in screen pixels (top-left origin).
     * @param x X-coordinate of the top-left corner
     * @param y Y-coordinate of the top-left corner
     * @param width Width of the quad
     * @param height Height of the quad
     * @param textureId The ID of the texture to bind
     * @param u1 U-coordinate of the top-left texture corner
     * @param v1 V-coordinate of the top-left texture corner (often top of texture image)
     * @param u2 U-coordinate of the bottom-right texture corner
     * @param v2 V-coordinate of the bottom-right texture corner (often bottom of texture image)
     */
    public void drawTexturedQuadUI(int x, int y, int width, int height, int textureId, float u1, float v1, float u2, float v2) {
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("texture_sampler", 0); // Ensure texture unit 0

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);

        // Convert pixel coordinates to Normalized Device Coordinates (NDC)
        float ndcX = (x / (float)windowWidth) * 2.0f - 1.0f;
        float ndcY = 1.0f - (y / (float)windowHeight) * 2.0f; // Invert Y for top-left origin
        float ndcWidth = (width / (float)windowWidth) * 2.0f;
        float ndcHeight = (height / (float)windowHeight) * 2.0f;

        float x_1 = ndcX;
        float y_1 = ndcY;           // Top-left in NDC
        float x_2 = ndcX + ndcWidth;
        float y_2 = ndcY - ndcHeight; // Bottom-right in NDC

        // Vertices: x, y, z, u, v
        // OpenGL UV origin is bottom-left. TextureAtlas UVs are typically top-left.
        // Ensure UVs match the quad vertices orientation.
        // Quad vertices: TL, TR, BR, BL (for TRIANGLE_FAN from TL)
        // UVs should map accordingly:
        // TL vertex -> (u1, v1)
        // TR vertex -> (u2, v1)
        // BR vertex -> (u2, v2)
        // BL vertex -> (u1, v2)
        float[] vertices = {
            x_1, y_1, 0.0f, u1, v1,    // Top-left
            x_2, y_1, 0.0f, u2, v1,    // Top-right
            x_2, y_2, 0.0f, u2, v2,    // Bottom-right
            x_1, y_2, 0.0f, u1, v2     // Bottom-left
        };

        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();

        GL30.glBindVertexArray(uiQuadVao);
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, uiQuadVbo);
        GL20.glBufferSubData(GL20.GL_ARRAY_BUFFER, 0, vertexBuffer);

        // Enable blending for textures that might have alpha (like leaves, or UI elements)
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        if (!blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }

        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        if (!blendWasEnabled) {
            glDisable(GL_BLEND);
        }

        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0); // Unbind texture
    }    public void draw3DItemInSlot(BlockType type, int screenSlotX, int screenSlotY, int screenSlotWidth, int screenSlotHeight) {
        if (type == null || type.getAtlasX() == -1) {
            return; // Nothing to draw
        }

        // Check if this is a flower block - render as flat 2D texture instead of 3D cube
        if (type == BlockType.ROSE || type == BlockType.DANDELION) {
            drawFlat2DItemInSlot(type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight);
            return;
        }

        // --- Save current GL state ---
        boolean depthTestWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        int[] originalViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, originalViewport);
        boolean scissorWasEnabled = glIsEnabled(GL_SCISSOR_TEST);
        int[] originalScissorBox = new int[4];
        if (scissorWasEnabled) {
            glGetIntegerv(GL_SCISSOR_BOX, originalScissorBox);
        }
        
        // Store current matrices to restore later
        float[] projectionMatrixBuffer = new float[16];
        float[] viewMatrixBuffer = new float[16];
        shaderProgram.getUniformMatrix4fv("projectionMatrix", projectionMatrixBuffer);
        shaderProgram.getUniformMatrix4fv("viewMatrix", viewMatrixBuffer);

        // --- Setup GL state for 3D item rendering ---
        // Calculate viewport coordinates (convert top-left to bottom-left origin)
        int viewportX = screenSlotX;
        int viewportY = windowHeight - (screenSlotY + screenSlotHeight);
        
        // Set up viewport for this item only
        glViewport(viewportX, viewportY, screenSlotWidth, screenSlotHeight);
        
        // Use scissor test to restrict drawing to this slot
        glEnable(GL_SCISSOR_TEST);
        glScissor(viewportX, viewportY, screenSlotWidth, screenSlotHeight);
        
        // Enable depth testing for 3D rendering
        glEnable(GL_DEPTH_TEST);
        glClear(GL_DEPTH_BUFFER_BIT); // Clear depth buffer for this slot
        
        // Disable blending for opaque block rendering
        if (blendWasEnabled) {
            glDisable(GL_BLEND);
        }

        // --- Shader setup for 3D item ---
        shaderProgram.bind();
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false); // We'll use direct UV coordinates

        // Create projection matrix for the item - use orthographic for consistent appearance
        Matrix4f itemProjectionMatrix = new Matrix4f().ortho(-0.6f, 0.6f, -0.6f, 0.6f, 0.1f, 10.0f);
        shaderProgram.setUniform("projectionMatrix", itemProjectionMatrix);

        // Create view matrix for an isometric-style view
        Matrix4f itemViewMatrix = new Matrix4f();
        itemViewMatrix.translate(0, 0, -1.5f);
        // Standard isometric view angles
        itemViewMatrix.rotate((float) Math.toRadians(30.0f), 1.0f, 0.0f, 0.0f);
        itemViewMatrix.rotate((float) Math.toRadians(-45.0f), 0.0f, 1.0f, 0.0f);
        // Adjust scale to fit nicely in the slot
        itemViewMatrix.scale(0.8f);
        shaderProgram.setUniform("viewMatrix", itemViewMatrix);

        // --- Bind texture atlas with defensive state reset ---
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        // Force correct texture parameters to prevent corruption from block drops
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        shaderProgram.setUniform("texture_sampler", 0);
        
        // --- Create and draw cube with proper face textures ---
        int cubeVao = createBlockSpecificCube(type);
        GL30.glBindVertexArray(cubeVao);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
        GL30.glBindVertexArray(0);
        
        // Clean up the temporary VAO and its associated buffers
        GL30.glDeleteVertexArrays(cubeVao);

        // --- Restore previous GL state ---
        // Reset shader uniforms
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        // Restore original matrices (from values we stored earlier)
        Matrix4f originalProjection = new Matrix4f();
        originalProjection.set(projectionMatrixBuffer);
        Matrix4f originalView = new Matrix4f();
        originalView.set(viewMatrixBuffer);
        shaderProgram.setUniform("projectionMatrix", originalProjection);
        shaderProgram.setUniform("viewMatrix", originalView);
        
        // Restore viewport
        glViewport(originalViewport[0], originalViewport[1], originalViewport[2], originalViewport[3]);
        
        // Restore scissor state
        if (scissorWasEnabled) {
            glScissor(originalScissorBox[0], originalScissorBox[1], originalScissorBox[2], originalScissorBox[3]);
        } else {
            glDisable(GL_SCISSOR_TEST);
        }

        // Restore depth test state
        if (!depthTestWasEnabled) {
            glDisable(GL_DEPTH_TEST);
        }
        
        // Restore blend state
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        
        // Unbind texture
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    private void drawFlat2DItemInSlot(BlockType type, int screenSlotX, int screenSlotY, int screenSlotWidth, int screenSlotHeight) {
        // Save current GL state
        boolean depthTestWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        int[] originalViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, originalViewport);
        boolean scissorWasEnabled = glIsEnabled(GL_SCISSOR_TEST);
        int[] originalScissorBox = new int[4];
        if (scissorWasEnabled) {
            glGetIntegerv(GL_SCISSOR_BOX, originalScissorBox);
        }
        
        // Store current matrices to restore later
        float[] projectionMatrixBuffer = new float[16];
        float[] viewMatrixBuffer = new float[16];
        shaderProgram.getUniformMatrix4fv("projectionMatrix", projectionMatrixBuffer);
        shaderProgram.getUniformMatrix4fv("viewMatrix", viewMatrixBuffer);

        // Setup for 2D rendering
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        // Set up orthographic projection for the slot area
        Matrix4f orthoProjection = new Matrix4f().ortho(0, windowWidth, windowHeight, 0, -1, 1);
        Matrix4f identityView = new Matrix4f().identity();
        
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", orthoProjection);
        shaderProgram.setUniform("viewMatrix", identityView);
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("texture_sampler", 0);

        // Get texture coordinates for the flower
        float[] uvCoords = textureAtlas.getUVCoordinates(type.getAtlasX(), type.getAtlasY());
        
        // Add padding to center the flower texture within the slot
        int padding = 6;
        float textureX = screenSlotX + padding;
        float textureY = screenSlotY + padding;
        float textureWidth = screenSlotWidth - (padding * 2);
        float textureHeight = screenSlotHeight - (padding * 2);
        
        // Bind texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        
        // Create vertices for the quad in screen coordinates
        float[] vertices = {
            textureX,              textureY,               0.0f, uvCoords[0], uvCoords[1], // Top-left
            textureX + textureWidth, textureY,              0.0f, uvCoords[2], uvCoords[1], // Top-right  
            textureX + textureWidth, textureY + textureHeight, 0.0f, uvCoords[2], uvCoords[3], // Bottom-right
            textureX,              textureY + textureHeight, 0.0f, uvCoords[0], uvCoords[3]  // Bottom-left
        };

        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();

        GL30.glBindVertexArray(uiQuadVao);
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, uiQuadVbo);
        GL20.glBufferSubData(GL20.GL_ARRAY_BUFFER, 0, vertexBuffer);

        glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

        // Restore GL state
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        
        // Restore original matrices
        Matrix4f originalProjection = new Matrix4f();
        originalProjection.set(projectionMatrixBuffer);
        Matrix4f originalView = new Matrix4f();
        originalView.set(viewMatrixBuffer);
        shaderProgram.setUniform("projectionMatrix", originalProjection);
        shaderProgram.setUniform("viewMatrix", originalView);
        
        // Restore viewport
        glViewport(originalViewport[0], originalViewport[1], originalViewport[2], originalViewport[3]);
        
        // Restore scissor state
        if (scissorWasEnabled) {
            glScissor(originalScissorBox[0], originalScissorBox[1], originalScissorBox[2], originalScissorBox[3]);
        } else {
            glDisable(GL_SCISSOR_TEST);
        }

        // Restore depth test and blend state
        if (depthTestWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        if (!blendWasEnabled) {
            glDisable(GL_BLEND);
        }
    }

    /**
     * Creates a cube VAO with proper UV coordinates for each face of the specified block type.
     * This allows blocks like grass to show different textures on different faces.
     */
    private int createBlockSpecificCube(BlockType blockType) {
        // Get texture coordinates for each face
        float[] topTexCoords = blockType.getTextureCoords(0);    // Top face
        float[] bottomTexCoords = blockType.getTextureCoords(1); // Bottom face
        float[] frontTexCoords = blockType.getTextureCoords(2);  // Front side
        float[] backTexCoords = blockType.getTextureCoords(3);   // Back side
        float[] rightTexCoords = blockType.getTextureCoords(4);  // Right side
        float[] leftTexCoords = blockType.getTextureCoords(5);   // Left side
        
        // Convert texture atlas coordinates to UV coordinates
        float[] topUVs = textureAtlas.getUVCoordinates((int)topTexCoords[0], (int)topTexCoords[1]);
        float[] bottomUVs = textureAtlas.getUVCoordinates((int)bottomTexCoords[0], (int)bottomTexCoords[1]);
        float[] frontUVs = textureAtlas.getUVCoordinates((int)frontTexCoords[0], (int)frontTexCoords[1]);
        float[] backUVs = textureAtlas.getUVCoordinates((int)backTexCoords[0], (int)backTexCoords[1]);
        float[] rightUVs = textureAtlas.getUVCoordinates((int)rightTexCoords[0], (int)rightTexCoords[1]);
        float[] leftUVs = textureAtlas.getUVCoordinates((int)leftTexCoords[0], (int)leftTexCoords[1]);
        
        // Build vertices with correct UV coordinates for each face
        // Format: Position (3f), Normal (3f), UV (2f) - 8 floats per vertex
        float[] vertices = {
            // Front face (+Z) - Using front texture
            -0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  frontUVs[0], frontUVs[3], // Bottom-left
             0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  frontUVs[2], frontUVs[3], // Bottom-right
             0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  frontUVs[2], frontUVs[1], // Top-right
            -0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  frontUVs[0], frontUVs[1], // Top-left
            
            // Back face (-Z) - Using back texture
            -0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[2], backUVs[3], // Bottom-left (flipped)
             0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[0], backUVs[3], // Bottom-right
             0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[0], backUVs[1], // Top-right
            -0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[2], backUVs[1], // Top-left
            
            // Top face (+Y) - Using top texture
            -0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,  topUVs[0], topUVs[3],
             0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,  topUVs[2], topUVs[3],
             0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,  topUVs[2], topUVs[1],
            -0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,  topUVs[0], topUVs[1],
            
            // Bottom face (-Y) - Using bottom texture
            -0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,  bottomUVs[0], bottomUVs[1],
             0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,  bottomUVs[2], bottomUVs[1],
             0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,  bottomUVs[2], bottomUVs[3],
            -0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,  bottomUVs[0], bottomUVs[3],
            
            // Right face (+X) - Using right texture
             0.5f, -0.5f, -0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[0], rightUVs[3],
             0.5f, -0.5f,  0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[2], rightUVs[3],
             0.5f,  0.5f,  0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[2], rightUVs[1],
             0.5f,  0.5f, -0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[0], rightUVs[1],
            
            // Left face (-X) - Using left texture
            -0.5f, -0.5f, -0.5f, -1.0f,  0.0f,  0.0f,  leftUVs[2], leftUVs[3], // Flipped
            -0.5f, -0.5f,  0.5f, -1.0f,  0.0f,  0.0f,  leftUVs[0], leftUVs[3],
            -0.5f,  0.5f,  0.5f, -1.0f,  0.0f,  0.0f,  leftUVs[0], leftUVs[1],
            -0.5f,  0.5f, -0.5f, -1.0f,  0.0f,  0.0f,  leftUVs[2], leftUVs[1]
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
        
        int stride = 8 * Float.BYTES;
        // Position attribute (location 0)
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        // Normal attribute (location 2)
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
     * Gets or creates a cached VAO for rendering a specific block type in the player's hand.
     * This ensures proper face texturing for blocks with different textures per face.
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
    
    private void renderFlowerInHand(BlockType flowerType) {
        // Set up shader for flower rendering
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        // Get UV coordinates for the flower
        float[] uvCoords = textureAtlas.getUVCoordinates(flowerType.getAtlasX(), flowerType.getAtlasY());
        
        // Bind texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        shaderProgram.setUniform("texture_sampler", 0);
        
        // Enable blending for transparency
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        // No tint - use pure white
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Create two intersecting quads to form a cross pattern (like flowers in Minecraft)
        createAndRenderFlowerCross(uvCoords);
    }
    
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
        
        int[] indices = {
            0, 1, 2, 0, 2, 3  // Two triangles forming a quad
        };
        
        // Render first quad
        renderFlowerQuad(vertices1, indices);
        
        // Render second quad
        renderFlowerQuad(vertices2, indices);
    }
    
    private void renderFlowerQuad(float[] vertices, int[] indices) {
        // Create temporary VAO and buffers for this quad
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        
        int vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
        
        int stride = 8 * Float.BYTES;
        // Position attribute (location 0)
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        // Normal attribute (location 2)
        GL20.glVertexAttribPointer(2, 3, GL20.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        // Texture coordinate attribute (location 1)
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, stride, 6 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);
        
        int ibo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ibo);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
        
        // Render the quad
        glDrawElements(GL_TRIANGLES, indices.length, GL_UNSIGNED_INT, 0);
        
        // Clean up temporary buffers
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        GL30.glDeleteVertexArrays(vao);
        GL20.glDeleteBuffers(vbo);
        GL20.glDeleteBuffers(ibo);
    }
    
    /**
     * Creates the crack texture with multiple crack stages.
     */
    private void createCrackTexture() {
        int texWidth = 16;
        int texHeight = 16 * 10; // 10 crack stages vertically
        ByteBuffer buffer = BufferUtils.createByteBuffer(texWidth * texHeight * 4); // RGBA
        
        for (int stage = 0; stage < 10; stage++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    byte r = 0, g = 0, b = 0, a = 0;
                    
                    // Create realistic crack pattern based on stage and position
                    float intensity = (stage + 1) / 10.0f;
                    
                    // Create organic-looking cracks using multiple crack paths
                    boolean isCrack = generateCrackPattern(x, y, stage);
                    
                    if (isCrack) {
                        r = g = b = 0; // Black cracks
                        a = (byte) (intensity * 200); // More visible as stage increases
                    }
                    
                    buffer.put(r);
                    buffer.put(g);
                    buffer.put(b);
                    buffer.put(a);
                }
            }
        }
        buffer.flip();
        
        crackTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, crackTextureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, texWidth, texHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    
    /**
     * Generates realistic crack patterns for block breaking animation.
     * Creates organic-looking cracks that branch and spread as breaking progresses.
     */
    private boolean generateCrackPattern(int x, int y, int stage) {
        if (stage == 0) return false;
        
        // Define crack centers and paths for organic crack generation
        int centerX = 8, centerY = 8;
        
        // Stage 1-2: Initial small cracks from center
        if (stage >= 1) {
            // Main crack lines from center
            if (isOnCrackLine(x, y, centerX, centerY, centerX + 3, centerY - 2, 1)) return true;
            if (isOnCrackLine(x, y, centerX, centerY, centerX - 2, centerY + 3, 1)) return true;
        }
        
        // Stage 3-4: Extend cracks and add branches
        if (stage >= 3) {
            if (isOnCrackLine(x, y, centerX + 3, centerY - 2, centerX + 6, centerY - 4, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 2, centerY + 3, centerX - 4, centerY + 6, 1)) return true;
            // Add branching cracks
            if (isOnCrackLine(x, y, centerX + 1, centerY - 1, centerX + 4, centerY + 2, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 1, centerY + 1, centerX - 3, centerY - 2, 1)) return true;
        }
        
        // Stage 5-6: More extensive cracking
        if (stage >= 5) {
            if (isOnCrackLine(x, y, centerX + 6, centerY - 4, centerX + 8, centerY - 6, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 4, centerY + 6, centerX - 6, centerY + 8, 1)) return true;
            if (isOnCrackLine(x, y, centerX + 4, centerY + 2, centerX + 7, centerY + 5, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 3, centerY - 2, centerX - 6, centerY - 5, 1)) return true;
            // Add perpendicular branches
            if (isOnCrackLine(x, y, centerX + 2, centerY, centerX + 2, centerY - 4, 1)) return true;
            if (isOnCrackLine(x, y, centerX, centerY + 2, centerX - 4, centerY + 2, 1)) return true;
        }
        
        // Stage 7-8: Heavy cracking with web pattern
        if (stage >= 7) {
            if (isOnCrackLine(x, y, centerX, centerY - 3, centerX + 5, centerY - 6, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 3, centerY, centerX - 6, centerY + 5, 1)) return true;
            if (isOnCrackLine(x, y, centerX + 5, centerY, centerX + 8, centerY - 3, 1)) return true;
            if (isOnCrackLine(x, y, centerX, centerY + 5, centerX - 3, centerY + 8, 1)) return true;
            // Add connecting cracks between main lines
            if (isOnCrackLine(x, y, centerX + 3, centerY - 1, centerX + 1, centerY + 3, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 1, centerY - 3, centerX - 3, centerY + 1, 1)) return true;
        }
        
        // Stage 9: Maximum cracking with detailed fractures
        if (stage >= 9) {
            // Add fine detail cracks
            if (isOnCrackLine(x, y, centerX + 1, centerY + 1, centerX + 6, centerY + 3, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 1, centerY - 1, centerX - 6, centerY - 3, 1)) return true;
            if (isOnCrackLine(x, y, centerX + 4, centerY - 1, centerX + 7, centerY + 2, 1)) return true;
            if (isOnCrackLine(x, y, centerX - 4, centerY + 1, centerX - 7, centerY - 2, 1)) return true;
            // Add edge cracks
            if (x == 0 || x == 15 || y == 0 || y == 15) {
                if ((x + y) % 3 == 0) return true;
            }
            // Add small fracture details with consistent pattern
            if ((x * 7 + y * 3) % 11 == 0 && distanceFromCenter(x, y, centerX, centerY) < 6) return true;
            // Add additional consistent detail cracks
            if ((x * 3 + y * 5) % 13 == 0 && distanceFromCenter(x, y, centerX, centerY) < 5) return true;
        }
        
        return false;
    }
    
    /**
     * Checks if a point is on a crack line with some thickness for natural appearance.
     */
    private boolean isOnCrackLine(int x, int y, int x1, int y1, int x2, int y2, int thickness) {
        // Calculate distance from point to line segment
        float A = x - x1;
        float B = y - y1;
        float C = x2 - x1;
        float D = y2 - y1;
        
        float dot = A * C + B * D;
        float lenSq = C * C + D * D;
        
        if (lenSq == 0) {
            // Line has no length, check distance to point
            return Math.abs(x - x1) <= thickness && Math.abs(y - y1) <= thickness;
        }
        
        float param = dot / lenSq;
        
        float xx, yy;
        if (param < 0) {
            xx = x1;
            yy = y1;
        } else if (param > 1) {
            xx = x2;
            yy = y2;
        } else {
            xx = x1 + param * C;
            yy = y1 + param * D;
        }
        
        float dx = x - xx;
        float dy = y - yy;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        
        return distance <= thickness;
    }
    
    /**
     * Calculates distance from a point to the center.
     */
    private float distanceFromCenter(int x, int y, int centerX, int centerY) {
        float dx = x - centerX;
        float dy = y - centerY;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
    
    /**
     * Creates VAO for rendering block overlay (crack) on top of blocks.
     */
    private void createBlockOverlayVao() {
        // Create a unit cube for overlaying on blocks
        float[] vertices = {
            // Front face
            0.0f, 0.0f, 1.0f,  0.0f, 0.0f,
            1.0f, 0.0f, 1.0f,  1.0f, 0.0f,
            1.0f, 1.0f, 1.0f,  1.0f, 1.0f,
            0.0f, 1.0f, 1.0f,  0.0f, 1.0f,
            // Back face
            1.0f, 0.0f, 0.0f,  0.0f, 0.0f,
            0.0f, 0.0f, 0.0f,  1.0f, 0.0f,
            0.0f, 1.0f, 0.0f,  1.0f, 1.0f,
            1.0f, 1.0f, 0.0f,  0.0f, 1.0f,
            // Top face
            0.0f, 1.0f, 0.0f,  0.0f, 0.0f,
            1.0f, 1.0f, 0.0f,  1.0f, 0.0f,
            1.0f, 1.0f, 1.0f,  1.0f, 1.0f,
            0.0f, 1.0f, 1.0f,  0.0f, 1.0f,
            // Bottom face
            0.0f, 0.0f, 1.0f,  0.0f, 0.0f,
            1.0f, 0.0f, 1.0f,  1.0f, 0.0f,
            1.0f, 0.0f, 0.0f,  1.0f, 1.0f,
            0.0f, 0.0f, 0.0f,  0.0f, 1.0f,
            // Right face
            1.0f, 0.0f, 1.0f,  0.0f, 0.0f,
            1.0f, 0.0f, 0.0f,  1.0f, 0.0f,
            1.0f, 1.0f, 0.0f,  1.0f, 1.0f,
            1.0f, 1.0f, 1.0f,  0.0f, 1.0f,
            // Left face
            0.0f, 0.0f, 0.0f,  0.0f, 0.0f,
            0.0f, 0.0f, 1.0f,  1.0f, 0.0f,
            0.0f, 1.0f, 1.0f,  1.0f, 1.0f,
            0.0f, 1.0f, 0.0f,  0.0f, 1.0f
        };
        
        int[] indices = {
            0, 1, 2, 2, 3, 0,       // Front
            4, 5, 6, 6, 7, 4,       // Back
            8, 9, 10, 10, 11, 8,    // Top
            12, 13, 14, 14, 15, 12, // Bottom
            16, 17, 18, 18, 19, 16, // Right
            20, 21, 22, 22, 23, 20  // Left
        };
        
        blockOverlayVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(blockOverlayVao);
        
        int vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
        
        // Position attribute (location 0)
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, 5 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        // Texture coordinate attribute (location 1)
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
    
    // These methods are no longer needed as we're using TextureAtlas
    
    /**
     * Loads a resource as a string.
     */
    private String loadResource(String path) {
        // This is a placeholder - in a real implementation, this would load the resource from the classpath
        
        if (path.endsWith("vertex.glsl")) {
            return """
                   #version 330 core
                   layout (location=0) in vec3 position;
                   layout (location=1) in vec2 texCoord;
                   layout (location=2) in vec3 normal;
                   layout (location=3) in float isWater;
                   layout (location=4) in float isAlphaTested; // New attribute for alpha-tested blocks
                   out vec2 outTexCoord;
                   out vec3 outNormal;
                   out vec3 fragPos;
                   out float v_isWater;
                   out float v_isAlphaTested; // Pass isAlphaTested to fragment shader
                   uniform mat4 projectionMatrix;
                   uniform mat4 viewMatrix;
                   uniform mat4 modelMatrix;
                   uniform bool u_transformUVsForItem;      // Added
                   uniform vec2 u_atlasUVOffset;        // Added
                   uniform vec2 u_atlasUVScale;         // Added
                   void main() {
                       gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
                       if (u_transformUVsForItem) {
                           outTexCoord = u_atlasUVOffset + texCoord * u_atlasUVScale;
                       } else {
                           outTexCoord = texCoord;
                       }
                       outNormal = normal;
                       fragPos = position;
                       v_isWater = isWater;
                       v_isAlphaTested = isAlphaTested; // Assign to out variable
                   }""";
       } else if (path.endsWith("fragment.glsl")) {
           return """
                   #version 330 core
                   in vec2 outTexCoord;
                   in vec3 outNormal;
                   in vec3 fragPos;
                   in float v_isWater;
                   in float v_isAlphaTested; // Received from vertex shader
                   out vec4 fragColor;
                   uniform sampler2D texture_sampler;
                   uniform vec4 u_color;            // Uniform for solid color or text tint
                   uniform bool u_useSolidColor;    // Uniform to toggle solid color mode
                   uniform bool u_isText;           // Uniform to toggle text rendering mode
                   uniform int u_renderPass;        // 0 for opaque/non-water, 1 for transparent/water
                   void main() {
                       if (u_isText) {
                           float alpha = texture(texture_sampler, outTexCoord).a;
                           fragColor = vec4(u_color.rgb, u_color.a * alpha);
                       } else if (u_useSolidColor) {
                           fragColor = u_color;
                       } else {
                           vec3 lightDir = normalize(vec3(0.5, 1.0, 0.3));
                           float ambient = 0.3;
                           float diffuse = max(dot(outNormal, lightDir), 0.0);
                           float brightness = ambient + diffuse * 0.7;
                           vec4 textureColor = texture(texture_sampler, outTexCoord);
                           float sampledAlpha = textureColor.a;

                           if (v_isAlphaTested > 0.5) { // Alpha-tested object (e.g., flower, leaf)
                               if (sampledAlpha < 0.1) {
                                   discard; // Alpha test
                               }
                               // Render opaque parts of alpha-tested objects
                               fragColor = vec4(textureColor.rgb * brightness, 1.0);
                           } else if (v_isWater > 0.5) { // Water object
                               if (u_renderPass == 0) { // Opaque pass
                                   discard; // Water is not drawn in opaque pass
                               } else { // Transparent pass
                                   fragColor = vec4(textureColor.rgb * brightness, sampledAlpha); // Use water's actual alpha for blending
                               }
                           } else { // Truly opaque object (e.g., stone, dirt)
                               if (u_renderPass == 0) { // Opaque pass
                                   fragColor = vec4(textureColor.rgb * brightness, 1.0); // Render fully opaque
                               } else { // Transparent pass
                                   discard; // Opaque objects already drawn in opaque pass
                               }
                           }
                       }
                   }""";
        }
        
        return "";
    }
    
    /**
     * Renders water particles in the 3D world.
     * Call this after rendering the world but before the UI.
     */
    public void renderWaterParticles() {
        WaterEffects waterEffects = Game.getWaterEffects();
        if (waterEffects == null || waterEffects.getParticles().isEmpty()) {
            return;
        }
        
        // Get player's camera view matrix
        Matrix4f viewMatrix = Game.getPlayer().getViewMatrix();
        
        // Use the shader program
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", viewMatrix);
        
        // Set up for particle rendering
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        
        // Enable blending
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        // Disable depth writing but keep depth testing
        glDepthMask(false);
        
        // Use point rendering for particles
        glPointSize(5.0f);
        
        // Start drawing points
        glBegin(GL_POINTS);
        
        // Draw each particle
        for (WaterEffects.WaterParticle particle : waterEffects.getParticles()) {
            float opacity = particle.getOpacity();
            
            // Set particle color (light blue with variable opacity)
            shaderProgram.setUniform("u_color", new Vector4f(0.7f, 0.85f, 1.0f, opacity * 0.7f));
            
            // Draw particle at its position
            glVertex3f(
                particle.getPosition().x,
                particle.getPosition().y,
                particle.getPosition().z
            );
        }
        
        glEnd();
        
        // Reset OpenGL state
        glPointSize(1.0f);
        glDepthMask(true);
        glDisable(GL_BLEND);
        
        // Reset shader state
        shaderProgram.setUniform("u_useSolidColor", false);
        
        // Unbind shader
        shaderProgram.unbind();
    }
    
    /**
     * Renders crack overlay on the block being broken.
     */
    private void renderBlockCrackOverlay(Player player) {
        Vector3i breakingBlock = player.getBreakingBlock();
        if (breakingBlock == null) {
            return; // No block being broken
        }
        
        float progress = player.getBreakingProgress();
        if (progress <= 0.0f) {
            return; // No progress yet
        }
        
        // Calculate crack stage (0-9) with smoother transitions
        float exactStage = Math.min(9.0f, progress * 10.0f);
        int crackStage = (int) exactStage;
        
        // Add small offset to prevent flickering between stages
        if (exactStage > crackStage + 0.1f) {
            crackStage = Math.min(9, crackStage + 1);
        }
        
        // Enable blending for crack overlay
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        // Disable depth writing but keep depth testing to avoid z-fighting
        glDepthMask(false);
        glEnable(GL_DEPTH_TEST);
        
        // Enable polygon offset to prevent z-fighting
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-1.0f, -1.0f);
        
        // Use shader program
        shaderProgram.bind();
        
        // Set uniforms for overlay rendering
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", player.getViewMatrix());
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("texture_sampler", 0);
        
        // Bind crack texture
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, crackTextureId);
        
        // Set up texture coordinates for the specific crack stage
        float vOffset = crackStage / 10.0f; // V offset for the crack stage
        float vScale = 1.0f / 10.0f; // V scale for one crack stage
        
        // Apply texture coordinate transformation for crack stage
        shaderProgram.setUniform("u_transformUVsForItem", true);
        shaderProgram.setUniform("u_atlasUVOffset", new org.joml.Vector2f(0.0f, vOffset));
        shaderProgram.setUniform("u_atlasUVScale", new org.joml.Vector2f(1.0f, vScale));
        
        // Create model matrix to position the overlay at the breaking block
        Matrix4f modelMatrix = new Matrix4f()
            .translate(breakingBlock.x, breakingBlock.y, breakingBlock.z)
            .scale(1.002f); // Slightly larger to avoid z-fighting
        
        // Combine view and model matrices
        Matrix4f modelViewMatrix = new Matrix4f(player.getViewMatrix()).mul(modelMatrix);
        shaderProgram.setUniform("viewMatrix", modelViewMatrix);
        
        // Set color with some transparency
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 0.8f));
        
        // Render the block overlay
        GL30.glBindVertexArray(blockOverlayVao);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
        GL30.glBindVertexArray(0);
        
        // Restore state
        glDepthMask(true);
        glDisable(GL_BLEND);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glBindTexture(GL_TEXTURE_2D, 0);
        
        // Reset shader state
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        shaderProgram.unbind();
    }
    
    
    /**
     * Cleanup method to release resources.
     */
    public void cleanup() {
        // Delete shader program
        if (shaderProgram != null) {
            shaderProgram.cleanup();
        }
        
        // Delete textures
        if (textureAtlas != null) {
            textureAtlas.cleanup();
        }
        if (font != null) {
            font.cleanup();
        }
        if (armTextureId != 0) {
            glDeleteTextures(armTextureId);
        }
        
        // Delete UI VAOs
        GL30.glDeleteVertexArrays(crosshairVao);
        GL30.glDeleteVertexArrays(hotbarVao);
        if (playerArmVao != 0) {
            GL30.glDeleteVertexArrays(playerArmVao);
        }
        if (uiQuadVao != 0) {
            GL30.glDeleteVertexArrays(uiQuadVao);
        }
        if (uiQuadVbo != 0) {
            GL20.glDeleteBuffers(uiQuadVbo);
        }
        
        // Clean up hand block VAO cache
        for (Integer vao : handBlockVaoCache.values()) {
            if (vao != null && vao != 0) {
                GL30.glDeleteVertexArrays(vao);
            }
        }
        handBlockVaoCache.clear();
        
        
        // Cleanup pause menu
        PauseMenu pauseMenu = Game.getInstance().getPauseMenu();
        if (pauseMenu != null) {
            pauseMenu.cleanup();
        }
        
        // Cleanup isolated block drop renderer
        if (blockDropRenderer != null) {
            blockDropRenderer.cleanup();
        }
    }
    
    /**
     * Renders 3D block drops in the world using isolated rendering context.
     */
    private void renderBlockDrops(World world) {
        BlockDropManager dropManager = world.getBlockDropManager();
        if (dropManager == null) {
            return;
        }
        
        List<BlockDrop> drops = dropManager.getDrops();
        if (drops.isEmpty()) {
            return;
        }
        
        // Get current view matrix from player
        Player player = Game.getPlayer();
        Matrix4f viewMatrix = (player != null) ? player.getViewMatrix() : new Matrix4f();
        
        // Update the isolated renderer with current data
        blockDropRenderer.updateRenderData(drops, projectionMatrix, viewMatrix);
        
        // Render using isolated context (completely separated from UI state)
        blockDropRenderer.renderBlockDrops();
    }
    
    
}

package com.stonebreak;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector4f; // Added import for ByteBuffer
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_LINES;
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
    
    // Matrices
    private final Matrix4f projectionMatrix;
    
    // UI elements
    private int crosshairVao;
    private int hotbarVao;
    private int playerArmVao; // VAO for the player's arm
    private int uiQuadVao;    // VAO for drawing generic UI quads (positions and UVs)
    private int uiQuadVbo;    // VBO for drawing generic UI quads (positions and UVs)

    // 3D Item Cube for Inventory
    private int itemCubeVao;
    private int itemCubeVbo; // Interleaved: posX, posY, posZ, normX, normY, normZ, uvU, uvV
    private int itemCubeIbo;
    private int itemCubeIndexCount;

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
        
        // Create projection matrix
        float aspectRatio = (float) width / height;
        projectionMatrix = new Matrix4f().perspective((float) Math.toRadians(70.0f), aspectRatio, 0.1f, 1000.0f);
        
        // Create uniforms for projection and view matrices
        shaderProgram.createUniform("projectionMatrix");
        shaderProgram.createUniform("viewMatrix");
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

        // Initialize font
        // Ensure Roboto-VariableFont_wdth,wght.ttf is in src/main/resources/fonts/
        font = new Font("fonts/Roboto-VariableFont_wdth,wght.ttf", 24f);
        
        // Create UI elements
        createCrosshair();
        createHotbar();
        createPlayerArm();
        createArmTexture(); // Create the Perlin noise texture for the arm
        createUiQuadRenderer(); // Initialize UI quad rendering resources
        createItemCube();       // Initialize 3D item cube mesh
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
     * Creates the VAO for rendering the player's arm.
     */
    private void createPlayerArm() {
        // Define arm dimensions (half-extents) - Made thinner
        float armHalfWidth = 0.05f;  // Makes full width 0.10
        float armHalfHeight = 0.25f; // Makes full height 0.5
        float armHalfDepth = 0.05f; // Makes full depth 0.10

        // 8 vertices of the cuboid arm, with actual tex coords
        // We'll map the texture to each face.
        // For simplicity, let's assume a single texture will be tiled/stretched.
        // A more complex approach would use different UVs for each face if using a texture atlas for the arm.
        float[] vertices = {
            // Front face (z = armHalfDepth) - UVs (0,0) to (1,1)
            -armHalfWidth, -armHalfHeight,  armHalfDepth, 0.0f, 1.0f, // 0: Front-Bottom-Left
             armHalfWidth, -armHalfHeight,  armHalfDepth, 1.0f, 1.0f, // 1: Front-Bottom-Right
             armHalfWidth,  armHalfHeight,  armHalfDepth, 1.0f, 0.0f, // 2: Front-Top-Right
            -armHalfWidth,  armHalfHeight,  armHalfDepth, 0.0f, 0.0f, // 3: Front-Top-Left
            // Back face (z = -armHalfDepth) - UVs (0,0) to (1,1)
            -armHalfWidth, -armHalfHeight, -armHalfDepth, 1.0f, 1.0f, // 4: Back-Bottom-Left (UVs reversed for back)
             armHalfWidth, -armHalfHeight, -armHalfDepth, 0.0f, 1.0f, // 5: Back-Bottom-Right
             armHalfWidth,  armHalfHeight, -armHalfDepth, 0.0f, 0.0f, // 6: Back-Top-Right
            -armHalfWidth,  armHalfHeight, -armHalfDepth, 1.0f, 0.0f,  // 7: Back-Top-Left
            // Top face (y = armHalfHeight) - UVs (0,0) to (1,1)
            -armHalfWidth,  armHalfHeight, -armHalfDepth, 0.0f, 1.0f, // 8 (same as 7)
             armHalfWidth,  armHalfHeight, -armHalfDepth, 1.0f, 1.0f, // 9 (same as 6)
             armHalfWidth,  armHalfHeight,  armHalfDepth, 1.0f, 0.0f, // 10 (same as 2)
            -armHalfWidth,  armHalfHeight,  armHalfDepth, 0.0f, 0.0f, // 11 (same as 3)
            // Bottom face (y = -armHalfHeight) - UVs (0,0) to (1,1)
            -armHalfWidth, -armHalfHeight,  armHalfDepth, 0.0f, 0.0f, // 12 (same as 0)
             armHalfWidth, -armHalfHeight,  armHalfDepth, 1.0f, 0.0f, // 13 (same as 1)
             armHalfWidth, -armHalfHeight, -armHalfDepth, 1.0f, 1.0f, // 14 (same as 5)
            -armHalfWidth, -armHalfHeight, -armHalfDepth, 0.0f, 1.0f, // 15 (same as 4)
            // Right face (x = armHalfWidth) - UVs (0,0) to (1,1)
             armHalfWidth, -armHalfHeight, -armHalfDepth, 0.0f, 1.0f, // 16 (same as 5)
             armHalfWidth, -armHalfHeight,  armHalfDepth, 1.0f, 1.0f, // 17 (same as 1)
             armHalfWidth,  armHalfHeight,  armHalfDepth, 1.0f, 0.0f, // 18 (same as 2)
             armHalfWidth,  armHalfHeight, -armHalfDepth, 0.0f, 0.0f, // 19 (same as 6)
            // Left face (x = -armHalfWidth) - UVs (0,0) to (1,1)
            -armHalfWidth, -armHalfHeight,  armHalfDepth, 0.0f, 1.0f, // 20 (same as 0)
            -armHalfWidth, -armHalfHeight, -armHalfDepth, 1.0f, 1.0f, // 21 (same as 4)
            -armHalfWidth,  armHalfHeight, -armHalfDepth, 1.0f, 0.0f, // 22 (same as 7)
            -armHalfWidth,  armHalfHeight,  armHalfDepth, 0.0f, 0.0f  // 23 (same as 3)
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

    private void createArmTexture() {
        int texWidth = 32;
        int texHeight = 32;
        NoiseGenerator noiseGen = new NoiseGenerator(System.currentTimeMillis()); // Use a time-based seed
        ByteBuffer buffer = BufferUtils.createByteBuffer(texWidth * texHeight * 4); // RGBA

        for (int y = 0; y < texHeight; y++) {
            for (int x = 0; x < texWidth; x++) {
                // Generate noise value in range [-1, 1]
                float noiseVal = noiseGen.noise(x / (float)texWidth * 5.0f, y / (float)texHeight * 5.0f); // Scale input for noise frequency
                // Map noise to [0, 1]
                noiseVal = (noiseVal + 1.0f) / 2.0f;

                // Create a brownish skin tone and modulate it slightly with noise
                // Base skin color (e.g., R=205, G=155, B=100)
                float baseR = 205 / 255.0f;
                float baseG = 155 / 255.0f;
                float baseB = 100 / 255.0f;

                // Modulate brightness with noise (e.g., noise affects intensity)
                float intensity = 0.8f + noiseVal * 0.2f; // Noise adds subtle variations

                byte r = (byte) (Math.min(Math.max(baseR * intensity, 0), 1) * 255);
                byte g = (byte) (Math.min(Math.max(baseG * intensity, 0), 1) * 255);
                byte b = (byte) (Math.min(Math.max(baseB * intensity, 0), 1) * 255);

                buffer.put(r);
                buffer.put(g);
                buffer.put(b);
                buffer.put((byte) 255); // Alpha
            }
        }
        buffer.flip();

        armTextureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, armTextureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
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

        // Render player arm (if not in pause menu, etc.)
        // Player arm needs its own shader setup, including texture
        if (!Game.getInstance().isPaused()) {
            renderPlayerArm(player); // This method binds its own shader and texture
        }

        // Render water particles
        renderWaterParticles(); // This method binds its own shader

        // Unbind main world shader program if other things don't use it
        // shaderProgram.unbind(); // UI pass binds it again.
        
        // Render UI elements
        renderUI(player);
    }    /**
     * Renders the player's arm.
     */
    private void renderPlayerArm(Player player) {
        shaderProgram.bind(); // Ensure shader is bound

        // Use a separate projection for the arm (orthographic, but could be perspective if desired)
        // For simplicity, let's use the main projection but adjust the view.
        // The arm should be rendered in front of everything else.
        glDisable(GL_DEPTH_TEST); // Render arm on top

        Matrix4f armViewModel = new Matrix4f();
        
        // Get the selected block type from the Player's Inventory
        int selectedBlockTypeId = 1; // Default to a valid block if inventory is null for some reason
        if (player != null && player.getInventory() != null) {
            selectedBlockTypeId = player.getInventory().getSelectedBlockTypeId();
        }
        BlockType selectedBlockType = BlockType.getById(selectedBlockTypeId);
        boolean displayingBlock = (selectedBlockType != null && selectedBlockType != BlockType.AIR &&
                                  selectedBlockType.getAtlasX() >= 0 && selectedBlockType.getAtlasY() >= 0);
                                  
        // Position the arm more in front and slightly to the right of the camera
        armViewModel.translate(0.35f, -0.35f, -0.4f); // X: right, Y: down, Z: towards camera

        // Adjust the model based on whether we're displaying a block or arm
        if (displayingBlock) {
            // Position and scale for the block - make it a bit smaller and centered
            armViewModel.scale(0.25f); // Scale down the block to look reasonable in hand
            armViewModel.translate(-0.45f, 0.1f, 0.35f); // Adjust position to center the block in view
            
            // Apply an isometric-style rotation for the block
            armViewModel.rotate((float) Math.toRadians(30.0f), 1.0f, 0.0f, 0.0f);
            armViewModel.rotate((float) Math.toRadians(-45.0f), 0.0f, 1.0f, 0.0f);
        }

        // Swinging animation
        if (player != null && player.isAttacking()) {
            float progress = player.getAttackAnimationProgress();
            float angle = (float) Math.sin(progress * Math.PI) * -45.0f; // Swing down and back up
            armViewModel.rotate((float) Math.toRadians(angle), 1.0f, 0.0f, 0.0f); // Rotate around X-axis
            armViewModel.translate(0.0f, progress * 0.2f, progress * -0.2f); // Move arm forward and down slightly
        }

        // Set model-view matrix for the arm (combining arm's transformation with camera's view)
        // We want the arm to be relative to the camera, not the world.
        // So, we use an identity view matrix for the arm, and apply transformations directly.
        shaderProgram.setUniform("projectionMatrix", projectionMatrix); // Use main projection
        shaderProgram.setUniform("viewMatrix", armViewModel); // Use the arm's own model-view

        // Check if we have a valid block type to display
        if (displayingBlock) {
            // Use the texture from the texture atlas for the selected block
            shaderProgram.setUniform("u_useSolidColor", false);
            shaderProgram.setUniform("u_isText", false);
            shaderProgram.setUniform("u_transformUVsForItem", true);
            
            // Get UV coordinates for the selected block type
            // Add a redundant check for selectedBlockType to satisfy static analyzers
            if (selectedBlockType == null) {
                // This path should ideally not be reached if displayingBlock is true due to its definition.
                // Log this inconsistency.
                System.err.println("Inconsistent state: displayingBlock is true, but selectedBlockType is null in renderPlayerArm.");
                // Fallback or error handling: perhaps render nothing or a default.
            } else {
                float[] atlasUVs = textureAtlas.getUVCoordinates(selectedBlockType.getAtlasX(), selectedBlockType.getAtlasY());
                shaderProgram.setUniform("u_atlasUVOffset", new org.joml.Vector2f(atlasUVs[0], atlasUVs[1]));
                shaderProgram.setUniform("u_atlasUVScale", new org.joml.Vector2f(atlasUVs[2] - atlasUVs[0], atlasUVs[3] - atlasUVs[1]));
                
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
                shaderProgram.setUniform("texture_sampler", 0);
                
                // No tint for block texture
                shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
                
                // Use the 3D cube for blocks
                GL30.glBindVertexArray(itemCubeVao);
                glDrawElements(GL_TRIANGLES, itemCubeIndexCount, GL_UNSIGNED_INT, 0);
            }
        } else {
            // Fallback to the default arm texture
            shaderProgram.setUniform("u_useSolidColor", false);
            shaderProgram.setUniform("u_isText", false);
            shaderProgram.setUniform("u_transformUVsForItem", false);
            
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, armTextureId);
            shaderProgram.setUniform("texture_sampler", 0);
            
            // Skin-tone tint for the arm texture
            shaderProgram.setUniform("u_color", new org.joml.Vector4f(0.9f, 0.7f, 0.5f, 1.0f));
            
            // Use the arm model as fallback
            GL30.glBindVertexArray(playerArmVao);
            glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
        }
        
        GL30.glBindVertexArray(0);
        
        // Reset shader state
        shaderProgram.setUniform("u_transformUVsForItem", false);
        
        glEnable(GL_DEPTH_TEST); // Re-enable depth testing
        shaderProgram.unbind(); // Unbind shader if it's not used immediately after
    }

      /**
     * Renders UI elements on top of the 3D world.
     */
    private void renderUI(Player player) {
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
            
            // Render breath meter if player is underwater
            renderBreathMeter(player);
        }
        
        // Render pause menu if game is paused
        PauseMenu pauseMenu = Game.getInstance().getPauseMenu();
        if (pauseMenu != null && pauseMenu.isVisible()) {
            // PauseMenu's render method will set its own u_useSolidColor and u_color for panels/buttons
            // and then call this.drawText for text elements.
            pauseMenu.render(shaderProgram, this); // Pass renderer for text drawing
        }        // Render inventory screen if visible
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
        
        // Render breath meter if player is underwater
        renderBreathMeter(player);
        
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
        shaderProgram.setUniform("u_transformUVsForItem", true);

        // Set texture coordinates from the texture atlas
        float[] atlasUVs = textureAtlas.getUVCoordinates(type.getAtlasX(), type.getAtlasY());
        shaderProgram.setUniform("u_atlasUVOffset", new org.joml.Vector2f(atlasUVs[0], atlasUVs[1]));
        shaderProgram.setUniform("u_atlasUVScale", new org.joml.Vector2f(atlasUVs[2] - atlasUVs[0], atlasUVs[3] - atlasUVs[1]));

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
        shaderProgram.setUniform("viewMatrix", itemViewMatrix);        // --- Bind texture atlas ---
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        shaderProgram.setUniform("texture_sampler", 0);
        
        // --- Draw the 3D cube ---
        GL30.glBindVertexArray(itemCubeVao);
        glDrawElements(GL_TRIANGLES, itemCubeIndexCount, GL_UNSIGNED_INT, 0);
        GL30.glBindVertexArray(0);

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

    private void createItemCube() {
        // A standard unit cube (1x1x1) centered at origin
        // Vertices: Position (3f), Normal (3f), UV (2f) - 8 floats per vertex
        // 6 faces * 4 vertices per face = 24 vertices
        // 6 faces * 2 triangles per face * 3 indices per triangle = 36 indices
        float[] vertices = {
            // Front face (+Z)
            -0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  0.0f, 0.0f, // Bottom-left
             0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  1.0f, 0.0f, // Bottom-right
             0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  1.0f, 1.0f, // Top-right
            -0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,  0.0f, 1.0f, // Top-left
            // Back face (-Z)
            -0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  1.0f, 0.0f, // Bottom-left (UVs flipped for visual consistency from outside)
             0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  0.0f, 0.0f, // Bottom-right
             0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  0.0f, 1.0f, // Top-right
            -0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  1.0f, 1.0f, // Top-left
            // Top face (+Y)
            -0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,  0.0f, 0.0f,
             0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,  1.0f, 0.0f,
             0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,  1.0f, 1.0f,
            -0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,  0.0f, 1.0f,
            // Bottom face (-Y)
            -0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,  0.0f, 1.0f, // UVs mapped to appear correct from top view of bottom face
             0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,  1.0f, 1.0f,
             0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,  1.0f, 0.0f,
            -0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,  0.0f, 0.0f,
            // Right face (+X)
             0.5f, -0.5f, -0.5f,  1.0f,  0.0f,  0.0f,  0.0f, 0.0f,
             0.5f, -0.5f,  0.5f,  1.0f,  0.0f,  0.0f,  1.0f, 0.0f,
             0.5f,  0.5f,  0.5f,  1.0f,  0.0f,  0.0f,  1.0f, 1.0f,
             0.5f,  0.5f, -0.5f,  1.0f,  0.0f,  0.0f,  0.0f, 1.0f,
            // Left face (-X)
            -0.5f, -0.5f, -0.5f, -1.0f,  0.0f,  0.0f,  1.0f, 0.0f, // UVs flipped
            -0.5f, -0.5f,  0.5f, -1.0f,  0.0f,  0.0f,  0.0f, 0.0f,
            -0.5f,  0.5f,  0.5f, -1.0f,  0.0f,  0.0f,  0.0f, 1.0f,
            -0.5f,  0.5f, -0.5f, -1.0f,  0.0f,  0.0f,  1.0f, 1.0f
        };

        int[] indices = {
            0,  1,  2,  0,  2,  3,  // Front
            4,  5,  6,  4,  6,  7,  // Back
            8,  9, 10,  8, 10, 11, // Top
            12, 13, 14, 12, 14, 15, // Bottom
            16, 17, 18, 16, 18, 19, // Right
            20, 21, 22, 20, 22, 23  // Left
        };
        itemCubeIndexCount = indices.length;

        itemCubeVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(itemCubeVao);

        itemCubeVbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, itemCubeVbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);

        int stride = 8 * Float.BYTES;
        // Position attribute (location 0)
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        // Normal attribute (location 2 - consistent with world shader)
        GL20.glVertexAttribPointer(2, 3, GL20.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);
        // Texture coordinate attribute (location 1 - consistent with world shader)
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, stride, 6 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);
        
        itemCubeIbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, itemCubeIbo);
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
                   uniform bool u_transformUVsForItem;      // Added
                   uniform vec2 u_atlasUVOffset;        // Added
                   uniform vec2 u_atlasUVScale;         // Added
                   void main() {
                       gl_Position = projectionMatrix * viewMatrix * vec4(position, 1.0);
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
     * Renders the UI breath meter when the player is underwater
     */
    private void renderBreathMeter(Player player) {
        if (!player.isInWater()) {
            return; // Don't show breath meter when not in water
        }
        
        // Set solid color mode
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        
        // Get breath percentage
        float breathPercentage = player.getBreathPercentage();
        
        // Calculate dimensions based on screen size
        int barWidth = (int)(windowWidth * 0.3f); // 30% of screen width
        int barHeight = 10;
        int x = (windowWidth - barWidth) / 2; // Center horizontally
        int y = windowHeight - 60; // Place near the top
        
        // Draw background (dark blue)
        drawQuad(x, y, barWidth, barHeight, 0, 50, 100, 200);
        
        // Draw breath bar (light blue, width based on breath remaining)
        int breathWidth = (int)(barWidth * breathPercentage);
        
        // Color changes from blue to red as breath depletes
        int r = (int)(255 * (1.0f - breathPercentage));
        int g = (int)(150 * breathPercentage);
        int b = (int)(255 * breathPercentage);
        
        drawQuad(x, y, breathWidth, barHeight, r, g, b, 200);
        
        // Show numeric value if player is drowning
        if (player.isDrowning()) {
            String breathText = "OUT OF AIR!";
            float textWidth = font.getTextWidth(breathText);
            drawText(breathText, (windowWidth - textWidth) / 2, y - 20, new Vector4f(1.0f, 0.2f, 0.2f, 1.0f));
        }
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
        if (itemCubeVao != 0) {
            GL30.glDeleteVertexArrays(itemCubeVao);
        }
        if (itemCubeVbo != 0) {
            GL20.glDeleteBuffers(itemCubeVbo);
        }
        if (itemCubeIbo != 0) {
            GL20.glDeleteBuffers(itemCubeIbo);
        }
        
        // Cleanup pause menu
        PauseMenu pauseMenu = Game.getInstance().getPauseMenu();
        if (pauseMenu != null) {
            pauseMenu.cleanup();
        }
    }
}

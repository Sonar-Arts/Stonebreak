package com.stonebreak.rendering;

// Standard Library Imports
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

// JOML Math Library
import com.stonebreak.rendering.player.PlayerArmRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;

// LWJGL Core
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryStack;

// LWJGL OpenGL Classes
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

// LWJGL OpenGL Static Imports (GL11)
import static org.lwjgl.opengl.GL11.GL_ALWAYS;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LESS;
import static org.lwjgl.opengl.GL11.GL_LINES;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_POINTS;
import static org.lwjgl.opengl.GL11.GL_POLYGON_OFFSET_FILL;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_BOX;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_FAN;
import static org.lwjgl.opengl.GL11.GL_TRUE;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glBegin;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glColorMask;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDepthFunc;
import static org.lwjgl.opengl.GL11.glDepthMask;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glEnd;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glGetBooleanv;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glIsEnabled;
import static org.lwjgl.opengl.GL11.glPointSize;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glPolygonOffset;
import static org.lwjgl.opengl.GL11.glScissor;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glVertex3f;
import static org.lwjgl.opengl.GL11.glViewport;

// LWJGL OpenGL Static Imports (GL13)
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;

// LWJGL OpenGL Static Imports (GL14)
import static org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA;
import static org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB;
import static org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA;
import static org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB;
import static org.lwjgl.opengl.GL14.glBlendFuncSeparate;

// LWJGL OpenGL Static Imports (GL20 & GL30)
import static org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL30.GL_VERTEX_ARRAY_BINDING;

// Project Imports (Blocks)
import com.stonebreak.blocks.*;

// Project Imports (Core)
import com.stonebreak.core.Game;

// Project Imports (Items)
import com.stonebreak.items.ItemType;

// Project Imports (Player)
import com.stonebreak.player.Player;

// Project Imports (UI)
import com.stonebreak.ui.*;

// Project Imports (World)
import com.stonebreak.world.Chunk;
import com.stonebreak.world.World;


/**
 * Handles rendering of the world and UI elements.
 */
public class Renderer {
    
    // Shader program
    private final ShaderProgram shaderProgram;
    
    // Textures
    private final TextureAtlas textureAtlas;
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
    private int uiQuadVao;    // VAO for drawing generic UI quads (positions and UVs)
    private int uiQuadVbo;    // VBO for drawing generic UI quads (positions and UVs)
    private int wireframeVao; // VAO for debug wireframe bounding boxes
    

    // 3D Item Cube for Inventory - REMOVED: Now using block-specific cubes
    
    // Block cracking overlay system
    private int crackTextureId;
    private int blockOverlayVao;
    
    // Specialized renderers
    private final PlayerArmRenderer playerArmRenderer;
    
    // Reusable lists to avoid allocations during rendering
    private final List<Chunk> reusableSortedChunks = new ArrayList<>();
    
    // Reusable matrices to avoid allocations

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

        // Initialize specialized renderers
        playerArmRenderer = new PlayerArmRenderer(shaderProgram, textureAtlas, projectionMatrix);

        // Initialize font
        // Font loaded from stonebreak-game/src/main/resources/fonts/
        font = new Font("fonts/Roboto-VariableFont_wdth,wght.ttf", 24f);
        
        // Create UI elements
        createCrosshair();
        createHotbar();
        createUiQuadRenderer(); // Initialize UI quad rendering resources
        createCrackTexture();   // Initialize block cracking texture
        createBlockOverlayVao(); // Initialize block overlay rendering
        createWireframe(); // Initialize debug wireframe bounding box rendering
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
    
    public Matrix4f getProjectionMatrix() {
        return projectionMatrix;
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
     * Creates the wireframe VAO for debug bounding box rendering.
     */
    private void createWireframe() {
        // Create vertices for a unit cube wireframe (12 edges, 24 vertices)
        float[] vertices = {
            // Bottom face edges
            -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f, // Front edge
             0.5f, -0.5f, -0.5f,  0.5f, -0.5f,  0.5f, // Right edge  
             0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f, // Back edge
            -0.5f, -0.5f,  0.5f, -0.5f, -0.5f, -0.5f, // Left edge
            
            // Top face edges
            -0.5f,  0.5f, -0.5f,  0.5f,  0.5f, -0.5f, // Front edge
             0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f, // Right edge
             0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f, // Back edge
            -0.5f,  0.5f,  0.5f, -0.5f,  0.5f, -0.5f, // Left edge
            
            // Vertical edges
            -0.5f, -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, // Front-left
             0.5f, -0.5f, -0.5f,  0.5f,  0.5f, -0.5f, // Front-right
             0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f, // Back-right
            -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,  0.5f  // Back-left
        };
        
        // Create VAO
        wireframeVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(wireframeVao);
        
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
        // Clear any pending OpenGL errors from previous operations
        int pendingError;
        while ((pendingError = glGetError()) != GL_NO_ERROR) {
            String errorString = switch (pendingError) {
                case 0x0500 -> "GL_INVALID_ENUM";
                case 0x0501 -> "GL_INVALID_VALUE";
                case 0x0502 -> "GL_INVALID_OPERATION";
                case 0x0503 -> "GL_STACK_OVERFLOW";
                case 0x0504 -> "GL_STACK_UNDERFLOW";
                case 0x0505 -> "GL_OUT_OF_MEMORY";
                case 0x0506 -> "GL_INVALID_FRAMEBUFFER_OPERATION";
                default -> "UNKNOWN_ERROR_" + Integer.toHexString(pendingError);
            };
            System.err.println("PENDING OPENGL ERROR: " + errorString + " (0x" + Integer.toHexString(pendingError) + ") from previous operation");
        }
        
        // Now check for errors from our operations
        checkGLError("After clearing pending errors");
        
        // Use shader program
        shaderProgram.bind();
        checkGLError("After shader bind");
        
        // Set common uniforms for world rendering
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", player.getViewMatrix());
        shaderProgram.setUniform("modelMatrix", new Matrix4f()); // Identity for world chunks
        shaderProgram.setUniform("texture_sampler", 0);
        shaderProgram.setUniform("u_useSolidColor", false); // World objects are textured
        shaderProgram.setUniform("u_isText", false);        // World objects are not text
        checkGLError("After setting uniforms");
        
        // Bind texture atlas once before passes
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        // Ensure texture filtering is set (NEAREST for blocky style)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        checkGLError("After texture binding");
        
        // Update animated textures now that atlas is properly bound
        WaterEffects waterEffects = Game.getWaterEffects();
        textureAtlas.updateAnimatedWater(totalTime, waterEffects, player.getPosition().x, player.getPosition().z);
        checkGLError("After updateAnimatedWater");
        
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
 
        // Sort chunks from back to front for transparent pass (reuse list to avoid allocation)
        reusableSortedChunks.clear();
        reusableSortedChunks.addAll(visibleChunks.values());
        org.joml.Vector3f playerPos = player.getPosition();
        Collections.sort(reusableSortedChunks, (c1, c2) -> {
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

        for (Chunk chunk : reusableSortedChunks) {
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
            playerArmRenderer.renderPlayerArm(player); // This method binds its own shader and texture
        }

        // Render water particles
        renderWaterParticles(); // This method binds its own shader

        // Render entities (cows, etc.)
        renderEntities(player);

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
        WaterEffects waterEffects = Game.getWaterEffects();
        textureAtlas.updateAnimatedWater(totalTime, waterEffects, player.getPosition().x, player.getPosition().z);

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
 
        // Sort chunks from back to front for transparent pass (reuse list to avoid allocation)
        reusableSortedChunks.clear();
        reusableSortedChunks.addAll(visibleChunks.values());
        org.joml.Vector3f playerPos = player.getPosition();
        Collections.sort(reusableSortedChunks, (c1, c2) -> {
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

        for (Chunk chunk : reusableSortedChunks) {
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
            playerArmRenderer.renderPlayerArm(player); // This method binds its own shader and texture
        }

        // Render water particles
        renderWaterParticles(); // This method binds its own shader

        // NOTE: Block drops are NOT rendered here - they are deferred
        
        // Render UI elements
        renderUI();
    }
    
    /**
     * Renders ONLY the world geometry (no block drops, no UI) to preserve depth buffer.
     */
    public void renderWorldOnly(World world, Player player, float totalTime) {
        // Update animated textures
        WaterEffects waterEffects = Game.getWaterEffects();
        textureAtlas.updateAnimatedWater(totalTime, waterEffects, player.getPosition().x, player.getPosition().z);

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
 
        // Sort chunks from back to front for transparent pass (reuse list to avoid allocation)
        reusableSortedChunks.clear();
        reusableSortedChunks.addAll(visibleChunks.values());
        org.joml.Vector3f playerPos = player.getPosition();
        Collections.sort(reusableSortedChunks, (c1, c2) -> {
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

        for (Chunk chunk : reusableSortedChunks) {
            // Texture atlas is already bound
            chunk.render(); // Chunk.render() will be called, shader will discard non-water fragments
        }
        
        glDepthMask(true);  // Restore depth writing
        glDisable(GL_BLEND); // Restore blending state

        // Render block crack overlay if breaking a block
        renderBlockCrackOverlay(player);

        // Render player arm (if not in pause menu, etc.)
        if (!Game.getInstance().isPaused()) {
            playerArmRenderer.renderPlayerArm(player); // This method binds its own shader and texture
        }

        // Render water particles
        renderWaterParticles(); // This method binds its own shader

        // NOTE: Block drops and UI are NOT rendered here - depth buffer is preserved
    }
    
    /**
     * Renders block drops in a deferred pass after all UI rendering is complete.
     */
    public void renderBlockDropsDeferred(World world, Player player) {
        // This method renders block drops completely isolated from UI rendering
        renderBlockDrops(world);
    }
    

      /**
     * Renders UI elements on top of the 3D world.
     */
    public void renderUI() {
        // Preserve depth buffer but ensure UI renders on top using GL_ALWAYS
        // glClear(GL_DEPTH_BUFFER_BIT); // Removed to preserve world depth

        // Enable depth testing with GL_ALWAYS so UI always passes depth test
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS); // UI always renders regardless of depth
        glDepthMask(false);     // Don't write to depth buffer
        
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
        
        // All complex UI (inventory, pause menu, chat) is now rendered in Main.java using UIRenderer
        // This renderUI() method only handles simple OpenGL-based UI elements like crosshair
        
        // Render debug overlay if enabled (MOVED to Main.java render loop)
        // DebugOverlay debugOverlay = Game.getDebugOverlay();
        // if (debugOverlay != null && debugOverlay.isVisible()) {
        //     debugOverlay.render(this, Game.getPlayer(), Game.getWorld());
        // }
        
        // Render depth curtains for UI elements to prevent block drops from rendering over them
        Game gameInstance = Game.getInstance();
        InventoryScreen inventoryScreen = gameInstance.getInventoryScreen();
        
        if (inventoryScreen != null && inventoryScreen.isVisible()) {
            // Render invisible depth curtain to occlude block drops behind inventory
            renderInventoryDepthCurtain();
        } else {
            // Handle depth curtain based on current game state
            switch (gameInstance.getState()) {
                case RECIPE_BOOK_UI -> {
                    // Render depth curtain for recipe book screen
                    renderRecipeBookDepthCurtain();
                }
                case WORKBENCH_UI -> {
                    // Render depth curtain for workbench screen
                    renderWorkbenchDepthCurtain();
                }
                default -> {
                    // If no full-screen UI is visible, render hotbar depth curtain 
                    // (hotbar is rendered separately in Main.java via UIRenderer)
                    renderHotbarDepthCurtain();
                }
            }
        }
        
        
        // Reset shader state after UI
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);

        // Unbind VAO and shader
        GL30.glBindVertexArray(0);
        shaderProgram.unbind();
        
        // Restore normal depth testing state
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);   // Restore normal depth testing function
        glDepthMask(true);      // Restore depth writing
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
    }

    public void draw3DItemInSlot(BlockType type, int screenSlotX, int screenSlotY, int screenSlotWidth, int screenSlotHeight) {
        if (type == null || type.getAtlasX() == -1) {
            return; // Nothing to draw
        }

        // Check if this is a flower block - render as flat 2D texture instead of 3D cube
        // Note: Items (STICK, WOODEN_PICKAXE) are now in ItemType enum and handled separately
        if (type == BlockType.ROSE || type == BlockType.DANDELION) {
            drawFlat2DItemInSlot(type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight);
            return;
        }

        // --- Save current GL state ---
        boolean depthTestWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        boolean cullFaceWasEnabled = glIsEnabled(GL_CULL_FACE);
        boolean scissorWasEnabled = glIsEnabled(GL_SCISSOR_TEST);

        int originalShaderProgram;
        int originalVao;
        int originalActiveTexture;
        int originalTextureBinding2D;
        boolean originalDepthMask;
        int originalBlendSrcRgb, originalBlendDstRgb, originalBlendSrcAlpha, originalBlendDstAlpha;

        int[] originalViewport = new int[4];
        int[] originalScissorBox = new int[4];

        try (MemoryStack stack = MemoryStack.stackPush()) { // Use fully qualified MemoryStack here for clarity
            IntBuffer tempInt = stack.mallocInt(1);

            glGetIntegerv(GL_CURRENT_PROGRAM, tempInt);
            originalShaderProgram = tempInt.get(0);
            tempInt.clear();

            glGetIntegerv(GL_VERTEX_ARRAY_BINDING, tempInt);
            originalVao = tempInt.get(0);
            tempInt.clear();

            glGetIntegerv(GL_ACTIVE_TEXTURE, tempInt); // GL_ACTIVE_TEXTURE is from GL13, correctly used.
            originalActiveTexture = tempInt.get(0);
            tempInt.clear();

            // Assuming operations are on GL_TEXTURE0, save its binding
            glActiveTexture(GL_TEXTURE0); // glActiveTexture is from GL13
            glGetIntegerv(GL_TEXTURE_BINDING_2D, tempInt); // GL_TEXTURE_BINDING_2D from GL11
            originalTextureBinding2D = tempInt.get(0);
            tempInt.clear();
            
            ByteBuffer tempByte = stack.malloc(1); // ByteBuffer from java.nio
            glGetBooleanv(GL_DEPTH_WRITEMASK, tempByte); // GL_DEPTH_WRITEMASK from GL11
            originalDepthMask = tempByte.get(0) == GL_TRUE; // GL_TRUE from GL11
            tempByte.clear();

            glGetIntegerv(GL_VIEWPORT, originalViewport); // GL_VIEWPORT from GL11
            if (scissorWasEnabled) {
                glGetIntegerv(GL_SCISSOR_BOX, originalScissorBox); // GL_SCISSOR_BOX from GL11
            }

            if (blendWasEnabled) {
                // These constants GL_BLEND_SRC_RGB etc. are now from GL14
                glGetIntegerv(GL_BLEND_SRC_RGB, tempInt); originalBlendSrcRgb = tempInt.get(0); tempInt.clear();
                glGetIntegerv(GL_BLEND_DST_RGB, tempInt); originalBlendDstRgb = tempInt.get(0); tempInt.clear();
                glGetIntegerv(GL_BLEND_SRC_ALPHA, tempInt); originalBlendSrcAlpha = tempInt.get(0); tempInt.clear();
                glGetIntegerv(GL_BLEND_DST_ALPHA, tempInt); originalBlendDstAlpha = tempInt.get(0); tempInt.clear();
            } else {
                // Default values if blend was not enabled, GL_SRC_ALPHA and GL_ONE_MINUS_SRC_ALPHA are from GL11
                originalBlendSrcRgb = GL_SRC_ALPHA;
                originalBlendDstRgb = GL_ONE_MINUS_SRC_ALPHA;
                originalBlendSrcAlpha = GL_SRC_ALPHA;
                originalBlendDstAlpha = GL_ONE_MINUS_SRC_ALPHA;
            }
        }
        
        // Store current shader program's matrices (if bound)
        // This relies on shaderProgram being the active one, which is usually true before calling this
        // Matrix4f previousProjectionMatrix = new Matrix4f(); // Unused
        // Matrix4f previousViewMatrix = new Matrix4f(); // Unused
        if (originalShaderProgram != 0) { // Only get if a program was bound
            // It's complex to get arbitrary shader's uniforms if it's not the `shaderProgram` instance.
            // This method should assume `shaderProgram` is what we're working with if we need to restore its specific uniforms.
            // The GL state backup for GL_CURRENT_PROGRAM is the general solution.
            // For now, we'll operate under the assumption this method manipulates `this.shaderProgram` uniforms.
        }

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
        glDepthFunc(GL_LESS); // Use normal depth testing to write closer values
        
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
        // Reset shader specific to this method's item drawing
        shaderProgram.setUniform("u_transformUVsForItem", false); // This belongs to this.shaderProgram

        // Restore viewport and scissor
        glViewport(originalViewport[0], originalViewport[1], originalViewport[2], originalViewport[3]);
        if (scissorWasEnabled) {
            glScissor(originalScissorBox[0], originalScissorBox[1], originalScissorBox[2], originalScissorBox[3]);
        } else {
            glDisable(GL_SCISSOR_TEST);
        }

        // Restore GL states
        GL20.glUseProgram(originalShaderProgram);
        GL30.glBindVertexArray(originalVao);

        glActiveTexture(GL_TEXTURE0); // glActiveTexture from GL13
        glBindTexture(GL_TEXTURE_BINDING_2D, originalTextureBinding2D); // glBindTexture from GL11, GL_TEXTURE_BINDING_2D from GL11
        glActiveTexture(originalActiveTexture); // Restore previously active texture unit

        if (cullFaceWasEnabled) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }

        glDepthMask(originalDepthMask);

        if (blendWasEnabled) {
            glEnable(GL_BLEND);
            // glBlendFuncSeparate is now statically imported from GL14
            glBlendFuncSeparate(originalBlendSrcRgb, originalBlendDstRgb, originalBlendSrcAlpha, originalBlendDstAlpha);
        } else {
            glDisable(GL_BLEND);
        }
        
        if (depthTestWasEnabled) {
             glEnable(GL_DEPTH_TEST); // Ensure depth test is restored if it was on
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        // Unbind texture used by this specific function to avoid interference if it was on unit 0
        // This is implicitly handled by restoring originalTextureBinding2D on GL_TEXTURE0
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
        
        int originalBlendSrcRgb = 0, originalBlendDstRgb = 0, originalBlendSrcAlpha = 0, originalBlendDstAlpha = 0;
        if (blendWasEnabled) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer tempInt = stack.mallocInt(1);
                glGetIntegerv(GL_BLEND_SRC_RGB, tempInt); originalBlendSrcRgb = tempInt.get(0); tempInt.clear();
                glGetIntegerv(GL_BLEND_DST_RGB, tempInt); originalBlendDstRgb = tempInt.get(0); tempInt.clear();
                glGetIntegerv(GL_BLEND_SRC_ALPHA, tempInt); originalBlendSrcAlpha = tempInt.get(0); tempInt.clear();
                glGetIntegerv(GL_BLEND_DST_ALPHA, tempInt); originalBlendDstAlpha = tempInt.get(0); tempInt.clear();
            }
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

        // Get texture coordinates for the flower using modern atlas system
        float[] uvCoords = textureAtlas.getBlockFaceUVs(type, BlockType.Face.TOP);
        
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
        if (scissorWasEnabled) {
            glScissor(originalScissorBox[0], originalScissorBox[1], originalScissorBox[2], originalScissorBox[3]);
        } else {
            glDisable(GL_SCISSOR_TEST);
        }

        // Restore depth test and blend state
        if (depthTestWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        
        // Restore blend state
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
            // glBlendFuncSeparate is now statically imported from GL14
            // GL14.glBlendFuncSeparate uses GL constants like GL_SRC_ALPHA, which are available in GL11
            // However, to use glBlendFuncSeparate itself, GL14 is needed, which seems to be correctly imported.
            // Assuming these variables were intended to be fetched and used correctly if blendWasEnabled.
            // For now, direct use if blend was enabled:
            if (blendWasEnabled) { // This check was missing around the usage.
                 glBlendFuncSeparate(originalBlendSrcRgb, originalBlendDstRgb, originalBlendSrcAlpha, originalBlendDstAlpha);
            }
        } else {
            glDisable(GL_BLEND);
        }
        
        if (depthTestWasEnabled) {
             glEnable(GL_DEPTH_TEST); // Ensure depth test is restored if it was on
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        // Unbind texture used by this specific function to avoid interference if it was on unit 0
        // This is implicitly handled by restoring originalTextureBinding2D on GL_TEXTURE0
    }

    // This entire duplicated method drawFlat2DItemInSlot (from 1477 to 1606) is removed.
    // Its content was the basis for createBlockSpecificCube.

    /**
    * Creates a VAO for a cube with textures specific to the given BlockType.
    * Each face of the cube uses the appropriate texture coordinates from the atlas.
    * This method is used for rendering blocks in hand and in the inventory.
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
            -0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[0], backUVs[3], // Bottom-left (UVs might need adjustment depending on texture atlas convention for back faces)
             0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[2], backUVs[3], // Bottom-right
             0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[2], backUVs[1], // Top-right
            -0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,  backUVs[0], backUVs[1], // Top-left
            
            // Top face (+Y)
            -0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,  topUVs[0], topUVs[1], // Top-left (UV Y might be inverted from standard texture)
             0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,  topUVs[2], topUVs[1], // Top-right
             0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,  topUVs[2], topUVs[3], // Bottom-right
            -0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,  topUVs[0], topUVs[3], // Bottom-left
            
            // Bottom face (-Y)
            -0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f, bottomUVs[0], bottomUVs[1], // Top-left
             0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f, bottomUVs[2], bottomUVs[1], // Top-right
             0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f, bottomUVs[2], bottomUVs[3], // Bottom-right
            -0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f, bottomUVs[0], bottomUVs[3], // Bottom-left
            
            // Right face (+X)
             0.5f, -0.5f, -0.5f,  1.0f,  0.0f,  0.0f,  rightUVs[0], rightUVs[3], // Bottom-left (Origin for this face's UV)
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
        
        return vao; // This was the error "incompatible types: unexpected return value"
    }
    
    /**
     * Gets or creates a cached VAO for rendering a specific block type in the player's hand.
     * This ensures proper face texturing for blocks with different textures per face.
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
        // Cleanup specialized renderers
        if (playerArmRenderer != null) {
            playerArmRenderer.cleanup();
        }
        
        // Delete UI VAOs
        GL30.glDeleteVertexArrays(crosshairVao);
        GL30.glDeleteVertexArrays(hotbarVao);
        if (uiQuadVao != 0) {
            GL30.glDeleteVertexArrays(uiQuadVao);
        }
        if (uiQuadVbo != 0) {
            GL20.glDeleteBuffers(uiQuadVbo);
        }
        
        
        
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
    
    /**
     * Renders an invisible depth curtain to occlude block drops behind inventory UI.
     * This creates proper depth values for block drop occlusion without visual interference.
     */
    private void renderInventoryDepthCurtain() {
        // Save current GL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        
        // Set up depth curtain rendering state
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);     // Always pass depth test (renders over world)
        glDepthMask(true);          // Write to depth buffer (this is key!)
        glDisable(GL_BLEND);        // No blending needed for invisible quad
        glColorMask(false, false, false, false); // Don't write to color buffer (invisible)
        
        // Set up orthographic projection for screen-space rendering
        Matrix4f orthoProjection = new Matrix4f().ortho(0, windowWidth, windowHeight, 0, -1, 1);
        Matrix4f identityView = new Matrix4f().identity();
        Matrix4f modelMatrix = new Matrix4f().identity();
        
        // Bind shader and set uniforms
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", orthoProjection);
        shaderProgram.setUniform("viewMatrix", identityView);
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Calculate inventory panel dimensions (EXACTLY match InventoryScreen calculations)
        int SLOT_SIZE = 40;
        int SLOT_PADDING = 5;
        int TITLE_HEIGHT = 30;
        int CRAFTING_GRID_SIZE = 2;
        int MAIN_INVENTORY_COLS = 9; // Inventory.MAIN_INVENTORY_COLS
        int MAIN_INVENTORY_ROWS = 3; // Inventory.MAIN_INVENTORY_ROWS
        
        // Match InventoryScreen.java calculations exactly (lines 346-355)
        int baseInventoryPanelWidth = MAIN_INVENTORY_COLS * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int craftingGridVisualWidth = CRAFTING_GRID_SIZE * SLOT_SIZE + (CRAFTING_GRID_SIZE - 1) * SLOT_PADDING;
        int craftingElementsTotalWidth = craftingGridVisualWidth + SLOT_SIZE + SLOT_PADDING + SLOT_SIZE; // grid + space + arrow + space + output
        int craftingSectionHeight = CRAFTING_GRID_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING + SLOT_SIZE;
        
        int totalInventoryRows = MAIN_INVENTORY_ROWS + 1; // main rows + hotbar row
        int mainAndHotbarHeight = totalInventoryRows * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        
        int inventoryPanelWidth = Math.max(baseInventoryPanelWidth, craftingElementsTotalWidth + SLOT_PADDING * 2);
        int inventoryPanelHeight = mainAndHotbarHeight + TITLE_HEIGHT + craftingSectionHeight + SLOT_PADDING * 2;
        
        int panelStartX = (windowWidth - inventoryPanelWidth) / 2;
        int panelStartY = (windowHeight - inventoryPanelHeight) / 2;
        
        // Create vertices for the depth curtain quad covering the inventory area
        // Add small buffer padding to ensure complete coverage and handle any rounding errors
        int bufferPadding = 10;
        float left = panelStartX - bufferPadding;
        float right = panelStartX + inventoryPanelWidth + bufferPadding;
        float top = panelStartY - bufferPadding;
        float bottom = panelStartY + inventoryPanelHeight + bufferPadding;
        float nearDepth = 0.0f; // Near plane depth value
        
        float[] vertices = {
            left,  top,    nearDepth,  // Top-left
            right, top,    nearDepth,  // Top-right
            right, bottom, nearDepth,  // Bottom-right
            left,  bottom, nearDepth   // Bottom-left
        };
        
        int[] indices = {
            0, 1, 2,  // First triangle
            2, 3, 0   // Second triangle
        };
        
        // Create temporary VAO for the depth curtain
        int vao = GL30.glGenVertexArrays();
        int vbo = GL20.glGenBuffers();
        int ebo = GL20.glGenBuffers();
        
        GL30.glBindVertexArray(vao);
        
        // Upload vertex data
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = org.lwjgl.BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
        
        // Upload index data
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer indexBuffer = org.lwjgl.BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
        
        // Set up vertex attributes (position only)
        GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // Render the invisible depth curtain
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        
        // Clean up temporary resources
        GL30.glBindVertexArray(0);
        GL20.glDeleteBuffers(vbo);
        GL20.glDeleteBuffers(ebo);
        GL30.glDeleteVertexArrays(vao);
        
        // Restore GL state
        glColorMask(true, true, true, true);  // Re-enable color writing
        glDepthFunc(GL_ALWAYS);               // Keep UI depth function
        glDepthMask(false);                   // Restore UI depth mask (don't write)
        
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        
        // Unbind shader
        shaderProgram.unbind();
    }
    
    /**
     * Renders an invisible depth curtain to occlude block drops behind hotbar UI.
     * This creates proper depth values for block drop occlusion without visual interference.
     * Used when only the hotbar is visible (not the full inventory screen).
     */
    public void renderHotbarDepthCurtain() {
        // Save current GL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        
        // Set up depth curtain rendering state
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);     // Always pass depth test (renders over world)
        glDepthMask(true);          // Write to depth buffer (this is key!)
        glDisable(GL_BLEND);        // No blending needed for invisible quad
        glColorMask(false, false, false, false); // Don't write to color buffer (invisible)
        
        // Set up orthographic projection for screen-space rendering
        Matrix4f orthoProjection = new Matrix4f().ortho(0, windowWidth, windowHeight, 0, -1, 1);
        Matrix4f identityView = new Matrix4f().identity();
        Matrix4f modelMatrix = new Matrix4f().identity();
        
        // Bind shader and set uniforms
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", orthoProjection);
        shaderProgram.setUniform("viewMatrix", identityView);
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Calculate hotbar dimensions (match InventoryScreen.renderHotbar calculations)
        int HOTBAR_SIZE = 9; // Inventory.HOTBAR_SIZE
        int SLOT_SIZE = 40;
        int SLOT_PADDING = 5;
        int HOTBAR_Y_OFFSET = 20;
        
        int hotbarWidth = HOTBAR_SIZE * (SLOT_SIZE + SLOT_PADDING) + SLOT_PADDING;
        int hotbarHeight = SLOT_SIZE + SLOT_PADDING * 2;
        
        int hotbarStartX = (windowWidth - hotbarWidth) / 2;
        int hotbarStartY = windowHeight - SLOT_SIZE - HOTBAR_Y_OFFSET - SLOT_PADDING; // Background starts above slots
        
        // Create vertices for the depth curtain quad covering the hotbar area
        // Use exact UI bounds with no padding
        float left = hotbarStartX;
        float right = hotbarStartX + hotbarWidth;
        float top = hotbarStartY;
        float bottom = hotbarStartY + hotbarHeight;
        float nearDepth = 0.0f; // Near plane depth value
        
        float[] vertices = {
            left,  top,    nearDepth,  // Top-left
            right, top,    nearDepth,  // Top-right
            right, bottom, nearDepth,  // Bottom-right
            left,  bottom, nearDepth   // Bottom-left
        };
        
        int[] indices = {
            0, 1, 2,  // First triangle
            2, 3, 0   // Second triangle
        };
        
        // Create temporary VAO for the depth curtain
        int vao = GL30.glGenVertexArrays();
        int vbo = GL20.glGenBuffers();
        int ebo = GL20.glGenBuffers();
        
        GL30.glBindVertexArray(vao);
        
        // Upload vertex data
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = org.lwjgl.BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
        
        // Upload index data
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer indexBuffer = org.lwjgl.BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
        
        // Set up vertex attributes (position only)
        GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // Render the invisible depth curtain
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        
        // Clean up temporary resources
        GL30.glBindVertexArray(0);
        GL20.glDeleteBuffers(vbo);
        GL20.glDeleteBuffers(ebo);
        GL30.glDeleteVertexArrays(vao);
        
        // Restore GL state
        glColorMask(true, true, true, true);  // Re-enable color writing
        glDepthFunc(GL_ALWAYS);               // Keep UI depth function
        glDepthMask(false);                   // Restore UI depth mask (don't write)
        
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        
        // Unbind shader
        shaderProgram.unbind();
    }
    
    /**
     * Renders an invisible depth curtain to occlude block drops behind pause menu UI.
     * This creates proper depth values for block drop occlusion without visual interference.
     * Public method for external access from Main.java rendering pipeline.
     */
    public void renderPauseMenuDepthCurtain() {
        // Save current GL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        
        // Set up depth curtain rendering state - CORRECTED for post-NanoVG rendering
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);       // Use normal depth testing (not ALWAYS)
        glDepthMask(true);          // Write to depth buffer (this is key!)
        glDisable(GL_BLEND);        // No blending needed for invisible quad
        glColorMask(false, false, false, false); // Don't write to color buffer (invisible)
        
        // CRITICAL FIX: Use SAME 3D projection/view as block drops for coordinate alignment
        Player player = Game.getPlayer();
        Matrix4f worldProjection = this.projectionMatrix; // Same as block drops
        Matrix4f worldView = (player != null) ? player.getViewMatrix() : new Matrix4f();
        
        // Bind shader and set uniforms - NOW IN WORLD COORDINATES
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", worldProjection);
        shaderProgram.setUniform("viewMatrix", worldView);
        shaderProgram.setUniform("modelMatrix", new Matrix4f().identity());
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Create MASSIVE 3D wall in front of camera to block all block drops
        // Position it very close to camera in world space
        Player currentPlayer = Game.getPlayer();
        if (currentPlayer == null) return;
        
        org.joml.Vector3f forward = new org.joml.Vector3f();
        currentPlayer.getViewMatrix().positiveZ(forward).negate(); // Get forward direction
        
        // DISTANCE-ADAPTIVE SOLUTION: Extract near plane for precision-independent positioning
        // Get near plane distance from projection matrix for proper depth precision
        float nearPlane = extractNearPlaneFromProjection(this.projectionMatrix);
        
        // Position depth curtain at near plane + tiny epsilon for maximum precision
        // This ensures it's always closest to camera regardless of world distance
        float epsilon = nearPlane * 0.001f; // 0.1% of near plane
        float[] wallDistances = {
            nearPlane + epsilon,           // Primary layer at near plane
            nearPlane + epsilon * 2,      // Secondary layers for robustness  
            nearPlane + epsilon * 3,
            nearPlane + epsilon * 4
        };
        
        // PANEL-ONLY SOLUTION: Only cover the solid pause menu panel, not the entire screen
        // This allows blocks to show behind the semi-transparent overlay but hide behind solid panel
        
        // Calculate pause menu panel bounds in screen space (from UIRenderer analysis)
        float centerX = windowWidth / 2.0f;
        float centerY = windowHeight / 2.0f;
        int panelWidth = 520;  // From pause menu documentation
        int panelHeight = 450; // From pause menu documentation
        
        // Convert panel bounds to normalized device coordinates [-1, +1]
        float panelLeft = ((centerX - panelWidth/2.0f) / windowWidth) * 2.0f - 1.0f;
        float panelRight = ((centerX + panelWidth/2.0f) / windowWidth) * 2.0f - 1.0f;
        float panelTop = (1.0f - (centerY - panelHeight/2.0f) / windowHeight) * 2.0f - 1.0f;
        float panelBottom = (1.0f - (centerY + panelHeight/2.0f) / windowHeight) * 2.0f - 1.0f;
        
        // Define PANEL-ONLY bounds in normalized device coordinates
        float[] screenCorners = {
            panelLeft,  panelTop,     // Top-left NDC (panel only)
            panelRight, panelTop,     // Top-right NDC (panel only)
            panelRight, panelBottom,  // Bottom-right NDC (panel only)
            panelLeft,  panelBottom   // Bottom-left NDC (panel only)
        };
        
        // Render multiple depth layers for robust coverage
        for (float wallDistance : wallDistances) {
            // Unproject screen corners to world space at this distance
            org.joml.Vector3f[] worldCorners = new org.joml.Vector3f[4];
            
            for (int i = 0; i < 4; i++) {
                float ndcX = screenCorners[i * 2];
                float ndcY = screenCorners[i * 2 + 1];
                
                // Unproject NDC coordinates to world space
                worldCorners[i] = unprojectToWorldSpace(ndcX, ndcY, wallDistance, 
                    this.projectionMatrix, currentPlayer.getViewMatrix());
            }
            
            // Create vertices using unprojected world coordinates
            float[] vertices = {
                // Top-left
                worldCorners[0].x, worldCorners[0].y, worldCorners[0].z,
                // Top-right  
                worldCorners[1].x, worldCorners[1].y, worldCorners[1].z,
                // Bottom-right
                worldCorners[2].x, worldCorners[2].y, worldCorners[2].z,
                // Bottom-left
                worldCorners[3].x, worldCorners[3].y, worldCorners[3].z
            };
            
            int[] indices = {
                0, 1, 2,  // First triangle
                2, 3, 0   // Second triangle
            };
            
            // Create temporary VAO for this depth layer
            int vao = GL30.glGenVertexArrays();
            int vbo = GL20.glGenBuffers();
            int ebo = GL20.glGenBuffers();
            
            GL30.glBindVertexArray(vao);
            
            // Upload vertex data
            GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
            FloatBuffer vertexBuffer = org.lwjgl.BufferUtils.createFloatBuffer(vertices.length);
            vertexBuffer.put(vertices).flip();
            GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
            
            // Upload index data
            GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ebo);
            IntBuffer indexBuffer = org.lwjgl.BufferUtils.createIntBuffer(indices.length);
            indexBuffer.put(indices).flip();
            GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
            
            // Set up vertex attributes (position only)
            GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
            GL20.glEnableVertexAttribArray(0);
            
            // Render this depth layer
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
            
            // Clean up temporary resources for this layer
            GL30.glBindVertexArray(0);
            GL20.glDeleteBuffers(vbo);
            GL20.glDeleteBuffers(ebo);
            GL30.glDeleteVertexArrays(vao);
        }
        
        // Restore GL state for normal 3D rendering  
        glColorMask(true, true, true, true);  // Re-enable color writing
        glDepthFunc(GL_LESS);                 // Restore normal 3D depth function
        glDepthMask(true);                    // Restore normal depth writing
        
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        
        // Unbind shader
        shaderProgram.unbind();
    }
    
    /**
     * Renders a wireframe bounding box for debug purposes.
     * @param boundingBox The bounding box to render
     * @param color The color of the wireframe (RGB, each component 0.0-1.0)
     */
    public void renderWireframeBoundingBox(com.stonebreak.mobs.entities.Entity.BoundingBox boundingBox, Vector3f color) {
        // Save current OpenGL state
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        
        // Set up OpenGL state for wireframe rendering
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        
        // Use shader program
        shaderProgram.bind();
        
        // Set view and projection matrices
        Player player = Game.getPlayer();
        if (player != null) {
            shaderProgram.setUniform("viewMatrix", player.getCamera().getViewMatrix());
            shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        }
        
        // Calculate model matrix for the bounding box
        Matrix4f modelMatrix = new Matrix4f();
        float centerX = (boundingBox.minX + boundingBox.maxX) / 2.0f;
        float centerY = (boundingBox.minY + boundingBox.maxY) / 2.0f;
        float centerZ = (boundingBox.minZ + boundingBox.maxZ) / 2.0f;
        float scaleX = boundingBox.maxX - boundingBox.minX;
        float scaleY = boundingBox.maxY - boundingBox.minY;
        float scaleZ = boundingBox.maxZ - boundingBox.minZ;
        
        modelMatrix.translation(centerX, centerY, centerZ);
        modelMatrix.scale(scaleX, scaleY, scaleZ);
        
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        
        // Set shader uniforms for solid color rendering
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new Vector4f(color.x, color.y, color.z, 1.0f));
        
        // Render the wireframe
        GL30.glBindVertexArray(wireframeVao);
        glDrawArrays(GL_LINES, 0, 24); // 24 vertices for 12 edges
        GL30.glBindVertexArray(0);
        
        // Restore OpenGL state
        if (!depthTestEnabled) {
            glDisable(GL_DEPTH_TEST);
        }
        
        // Unbind shader
        shaderProgram.unbind();
    }
    
    /**
     * Renders a wireframe path as connected line segments.
     * @param pathPoints The list of points forming the path
     * @param color The color of the path wireframe (RGB, each component 0.0-1.0)
     */
    public void renderWireframePath(List<Vector3f> pathPoints, Vector3f color) {
        if (pathPoints == null || pathPoints.size() < 2) {
            return; // Need at least 2 points to draw a line
        }
        
        // Save current OpenGL state
        boolean depthTestEnabled = glIsEnabled(GL_DEPTH_TEST);
        
        // Set up OpenGL state for wireframe rendering
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        
        // Use shader program
        shaderProgram.bind();
        
        // Set view and projection matrices
        Player player = Game.getPlayer();
        if (player != null) {
            shaderProgram.setUniform("viewMatrix", player.getCamera().getViewMatrix());
            shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        }
        
        // Set identity model matrix
        Matrix4f modelMatrix = new Matrix4f().identity();
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        
        // Set shader uniforms for solid color rendering
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new Vector4f(color.x, color.y, color.z, 1.0f));
        
        // Create vertices for the path lines
        int vertexCount = (pathPoints.size() - 1) * 2; // Each line segment needs 2 vertices
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertexCount * 3);
        
        for (int i = 0; i < pathPoints.size() - 1; i++) {
            Vector3f start = pathPoints.get(i);
            Vector3f end = pathPoints.get(i + 1);
            
            // Add start point
            vertexBuffer.put(start.x).put(start.y).put(start.z);
            // Add end point
            vertexBuffer.put(end.x).put(end.y).put(end.z);
        }
        vertexBuffer.flip();
        
        // Create temporary VAO and VBO for path rendering
        int vao = GL30.glGenVertexArrays();
        int vbo = GL30.glGenBuffers();
        
        GL30.glBindVertexArray(vao);
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, vbo);
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, vertexBuffer, GL30.GL_DYNAMIC_DRAW);
        
        // Set vertex attributes (position)
        GL30.glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL30.glEnableVertexAttribArray(0);
        
        // Render the path lines
        glDrawArrays(GL_LINES, 0, vertexCount);
        
        // Cleanup
        GL30.glBindVertexArray(0);
        GL30.glDeleteVertexArrays(vao);
        GL30.glDeleteBuffers(vbo);
        
        // Restore OpenGL state
        if (!depthTestEnabled) {
            glDisable(GL_DEPTH_TEST);
        }
        
        // Unbind shader
        shaderProgram.unbind();
    }
    
    /**
     * Renders an invisible depth curtain to occlude block drops behind recipe book UI.
     * This creates proper depth values for block drop occlusion without visual interference.
     */
    private void renderRecipeBookDepthCurtain() {
        // ULTRATHICK DEBUG: Verify this method is being called
        System.out.println("ULTRATHICK: Rendering recipe book depth curtain");
        
        // Save current GL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        
        // ULTRATHICK: Aggressive OpenGL state setup for bulletproof depth writing
        glDisable(GL_BLEND);        // Absolutely no blending
        glEnable(GL_DEPTH_TEST);    // Force enable depth testing
        glDepthFunc(GL_ALWAYS);     // ALWAYS pass depth test (renders over everything)
        glDepthMask(true);          // FORCE write to depth buffer
        glColorMask(false, false, false, false); // Invisible (no color writing)
        glDisable(GL_CULL_FACE);    // Disable culling to ensure all faces render
        
        // Set up orthographic projection for screen-space rendering
        Matrix4f orthoProjection = new Matrix4f().ortho(0, windowWidth, windowHeight, 0, -1, 1);
        Matrix4f identityView = new Matrix4f().identity();
        Matrix4f modelMatrix = new Matrix4f().identity();
        
        // Bind shader and set uniforms
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", orthoProjection);
        shaderProgram.setUniform("viewMatrix", identityView);
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // ULTRATHICK SOLUTION: Aggressive full-coverage depth curtain to eliminate all block drop leakage
        // Use oversized coverage area and multiple depth layers for bulletproof occlusion
        int ultraBuffer = 50; // Massive buffer to handle any edge cases
        float left = -ultraBuffer;
        float right = windowWidth + ultraBuffer;
        float top = -ultraBuffer;
        float bottom = windowHeight + ultraBuffer;
        
        // Use multiple depth values to create thick depth barrier
        float[] depthLayers = {-0.999f, -0.99f, -0.9f, 0.0f, 0.1f}; // Multiple near-plane depths
        
        int[] indices = {
            0, 1, 2,  // First triangle
            2, 3, 0   // Second triangle
        };
        
        // ULTRATHICK: Render multiple depth layers for bulletproof coverage
        for (float depthValue : depthLayers) {
            float[] vertices = {
                left,  top,    depthValue,  // Top-left
                right, top,    depthValue,  // Top-right
                right, bottom, depthValue,  // Bottom-right
                left,  bottom, depthValue   // Bottom-left
            };
            
            // Create temporary VAO for this depth layer
            int vao = GL30.glGenVertexArrays();
            int vbo = GL20.glGenBuffers();
            int ebo = GL20.glGenBuffers();
            
            GL30.glBindVertexArray(vao);
            
            // Upload vertex data
            GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
            FloatBuffer vertexBuffer = org.lwjgl.BufferUtils.createFloatBuffer(vertices.length);
            vertexBuffer.put(vertices).flip();
            GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
            
            // Upload index data
            GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ebo);
            IntBuffer indexBuffer = org.lwjgl.BufferUtils.createIntBuffer(indices.length);
            indexBuffer.put(indices).flip();
            GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
            
            // Set up vertex attributes (position only)
            GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
            GL20.glEnableVertexAttribArray(0);
            
            // Render this depth layer
            glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
            
            // Clean up temporary resources for this layer
            GL30.glBindVertexArray(0);
            GL20.glDeleteBuffers(vbo);
            GL20.glDeleteBuffers(ebo);
            GL30.glDeleteVertexArrays(vao);
        }
        
        // ULTRATHICK: Aggressively restore ALL OpenGL state for normal 3D rendering
        glColorMask(true, true, true, true);  // Re-enable color writing
        glDepthFunc(GL_LESS);                 // Restore normal 3D depth function
        glDepthMask(true);                    // Restore normal depth writing
        glEnable(GL_CULL_FACE);               // Re-enable face culling
        
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        
        // ULTRATHICK DEBUG: Confirm depth curtain rendering completed
        System.out.println("ULTRATHICK: Recipe book depth curtain completed with " + depthLayers.length + " layers");
        
        // Unbind shader
        shaderProgram.unbind();
    }
    
    /**
     * Renders an invisible depth curtain to occlude block drops behind workbench UI.
     * This creates proper depth values for block drop occlusion without visual interference.
     */
    private void renderWorkbenchDepthCurtain() {
        // Save current GL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        
        // Set up depth curtain rendering state
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);     // Always pass depth test (renders over world)
        glDepthMask(true);          // Write to depth buffer (this is key!)
        glDisable(GL_BLEND);        // No blending needed for invisible quad
        glColorMask(false, false, false, false); // Don't write to color buffer (invisible)
        
        // Set up orthographic projection for screen-space rendering
        Matrix4f orthoProjection = new Matrix4f().ortho(0, windowWidth, windowHeight, 0, -1, 1);
        Matrix4f identityView = new Matrix4f().identity();
        Matrix4f modelMatrix = new Matrix4f().identity();
        
        // Bind shader and set uniforms
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", orthoProjection);
        shaderProgram.setUniform("viewMatrix", identityView);
        shaderProgram.setUniform("modelMatrix", modelMatrix);
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        
        // Workbench dimensions are calculated dynamically based on content
        // Use a conservative full-screen approach with some padding since workbench size varies
        // This ensures coverage regardless of workbench content size
        int bufferPadding = 50; // Larger buffer for dynamic sizing
        float left = bufferPadding;
        float right = windowWidth - bufferPadding;
        float top = bufferPadding;
        float bottom = windowHeight - bufferPadding;
        float nearDepth = 0.0f; // Near plane depth value
        
        float[] vertices = {
            left,  top,    nearDepth,  // Top-left
            right, top,    nearDepth,  // Top-right
            right, bottom, nearDepth,  // Bottom-right
            left,  bottom, nearDepth   // Bottom-left
        };
        
        int[] indices = {
            0, 1, 2,  // First triangle
            2, 3, 0   // Second triangle
        };
        
        // Create temporary VAO for the depth curtain
        int vao = GL30.glGenVertexArrays();
        int vbo = GL20.glGenBuffers();
        int ebo = GL20.glGenBuffers();
        
        GL30.glBindVertexArray(vao);
        
        // Upload vertex data
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = org.lwjgl.BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);
        
        // Upload index data
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer indexBuffer = org.lwjgl.BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);
        
        // Set up vertex attributes (position only)
        GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        
        // Render the depth curtain
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        
        // Clean up temporary resources
        GL30.glBindVertexArray(0);
        GL20.glDeleteBuffers(vbo);
        GL20.glDeleteBuffers(ebo);
        GL30.glDeleteVertexArrays(vao);
        
        // Restore GL state for normal 3D rendering  
        glColorMask(true, true, true, true);  // Re-enable color writing
        glDepthFunc(GL_LESS);                 // Restore normal 3D depth function
        glDepthMask(true);                    // Restore normal depth writing
        
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
        
        // Unbind shader
        shaderProgram.unbind();
    }
    
    /**
     * Extracts the near plane distance from a perspective projection matrix.
     * This is crucial for distance-independent depth curtain positioning.
     */
    private float extractNearPlaneFromProjection(Matrix4f projectionMatrix) {
        // For perspective projection matrix, near plane can be extracted from matrix elements
        // Standard perspective matrix has near plane info in specific positions
        try {
            // Get matrix elements - JOML uses column-major order
            float m22 = projectionMatrix.m22(); // (2,2) element
            float m32 = projectionMatrix.m32(); // (3,2) element
            
            // For perspective projection: near = -m32 / (m22 + 1)
            // Handle edge cases and ensure positive result
            if (Math.abs(m22 + 1) > 0.0001f) {
                float near = -m32 / (m22 + 1);
                return Math.max(near, 0.01f); // Ensure minimum reasonable near plane
            }
        } catch (Exception e) {
            System.err.println("Failed to extract near plane from projection matrix: " + e.getMessage());
        }
        
        // Fallback to reasonable default
        return 0.1f;
    }
    
    /**
     * Unprojects normalized device coordinates to world space at specified distance from camera.
     * This ensures the depth curtain exactly covers the screen area.
     */
    private org.joml.Vector3f unprojectToWorldSpace(float ndcX, float ndcY, float distance, 
                                                   Matrix4f projection, Matrix4f view) {
        try {
            // Create combined projection-view matrix
            Matrix4f projViewMatrix = new Matrix4f(projection).mul(view);
            
            // Invert the combined matrix to go from NDC back to world space
            Matrix4f invProjView = projViewMatrix.invert(new Matrix4f());
            
            // Create NDC point at specified depth (distance from camera corresponds to specific Z in NDC)
            // For perspective projection, we need to find the NDC Z that corresponds to our desired distance
            float ndcZ = calculateNDCZForDistance();
            
            // Create 4D homogeneous coordinate in NDC space
            org.joml.Vector4f ndcPoint = new org.joml.Vector4f(ndcX, ndcY, ndcZ, 1.0f);
            
            // Transform back to world space
            org.joml.Vector4f worldPoint = invProjView.transform(ndcPoint, new org.joml.Vector4f());
            
            // Perform perspective divide
            if (Math.abs(worldPoint.w) > 0.0001f) {
                worldPoint.div(worldPoint.w);
            }
            
            return new org.joml.Vector3f(worldPoint.x, worldPoint.y, worldPoint.z);
            
        } catch (Exception e) {
            System.err.println("Unprojection failed: " + e.getMessage());
            // Fallback: simple approximation
            return new org.joml.Vector3f(ndcX * distance, ndcY * distance, distance);
        }
    }
    
    /**
     * Calculates the NDC Z coordinate that corresponds to a specific distance from camera.
     */
    private float calculateNDCZForDistance() {
        try {
            // For perspective projection: ndcZ = (far + near - 2*near*far/distance) / (far - near)
            // Simplified for our needs: use a value close to near plane
            return -0.999f; // Very close to near plane in NDC space
        } catch (Exception e) {
            return -0.999f; // Safe fallback
        }
    }
    
    /**
     * Renders a tool item in the player's hand as a 2D sprite.
     * This method is called from within renderPlayerArm() when the proper
     * shader state and transformations are already set up.
     */
    
    /**
     * Renders all entities (cows, etc.) in the world using the entity renderer.
     */
    private void renderEntities(Player player) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        com.stonebreak.mobs.entities.EntityRenderer entityRenderer = Game.getEntityRenderer();
        
        if (entityManager != null && entityRenderer != null) {
            // Get all entities and render them
            for (com.stonebreak.mobs.entities.Entity entity : entityManager.getAllEntities()) {
                if (entity.isAlive()) {
                    entityRenderer.renderEntity(entity, player.getViewMatrix(), projectionMatrix);
                }
            }
        }
    }
    
    /**
     * Checks for OpenGL errors and logs them with context information.
     */
    private void checkGLError(String context) {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            String errorString = switch (error) {
                case 0x0500 -> "GL_INVALID_ENUM";
                case 0x0501 -> "GL_INVALID_VALUE";
                case 0x0502 -> "GL_INVALID_OPERATION";
                case 0x0503 -> "GL_STACK_OVERFLOW";
                case 0x0504 -> "GL_STACK_UNDERFLOW";
                case 0x0505 -> "GL_OUT_OF_MEMORY";
                case 0x0506 -> "GL_INVALID_FRAMEBUFFER_OPERATION";
                default -> "UNKNOWN_ERROR_" + Integer.toHexString(error);
            };
            
            // Get additional OpenGL state for debugging
            try {
                int[] currentProgram = new int[1];
                glGetIntegerv(GL_CURRENT_PROGRAM, currentProgram);
                int[] activeTexture = new int[1];
                glGetIntegerv(GL_ACTIVE_TEXTURE, activeTexture);
                int[] boundTexture = new int[1];
                glGetIntegerv(GL_TEXTURE_BINDING_2D, boundTexture);
                int[] viewport = new int[4];
                glGetIntegerv(GL_VIEWPORT, viewport);
                
                System.err.println("OPENGL ERROR: " + errorString + " (0x" + Integer.toHexString(error) + ") at: " + context);
                System.err.println("Time: " + java.time.LocalDateTime.now());
                System.err.println("Thread: " + Thread.currentThread().getName());
                System.err.println("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB used");
                System.err.println("GL State - Program: " + currentProgram[0] + ", Active Texture: " + (activeTexture[0] - GL_TEXTURE0) + ", Bound Texture: " + boundTexture[0]);
                System.err.println("Viewport: " + viewport[0] + "," + viewport[1] + "," + viewport[2] + "," + viewport[3]);
                
                // Log to file as well
                try {
                    java.io.FileWriter fw = new java.io.FileWriter("opengl_errors.txt", true);
                    fw.write("=== OpenGL ERROR " + java.time.LocalDateTime.now() + " ===\n");
                    fw.write("Error: " + errorString + " (0x" + Integer.toHexString(error) + ")\n");
                    fw.write("Context: " + context + "\n");
                    fw.write("Thread: " + Thread.currentThread().getName() + "\n");
                    fw.write("Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB\n");
                    fw.write("GL State - Program: " + currentProgram[0] + ", Active Texture: " + (activeTexture[0] - GL_TEXTURE0) + ", Bound Texture: " + boundTexture[0] + "\n");
                    fw.write("Viewport: " + viewport[0] + "," + viewport[1] + "," + viewport[2] + "," + viewport[3] + "\n\n");
                    fw.close();
                } catch (Exception logEx) {
                    System.err.println("Failed to write OpenGL error log: " + logEx.getMessage());
                }
            } catch (Exception stateEx) {
                System.err.println("OPENGL ERROR: " + errorString + " (0x" + Integer.toHexString(error) + ") at: " + context);
                System.err.println("Failed to get additional GL state: " + stateEx.getMessage());
            }
            
            // For critical errors, throw exception to force crash with stack trace
            if (error == 0x0505) { // GL_OUT_OF_MEMORY
                throw new RuntimeException("OpenGL OUT OF MEMORY error at: " + context);
            }
        }
    }
    
    
}

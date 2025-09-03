package com.stonebreak.rendering;

// Standard Library Imports
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

// JOML Math Library
import com.stonebreak.rendering.models.blocks.BlockDropRenderer;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import com.stonebreak.rendering.player.PlayerArmRenderer;

import com.stonebreak.rendering.UI.rendering.DebugRenderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.textures.TextureAtlas;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

// LWJGL Core
import org.lwjgl.BufferUtils;

// LWJGL OpenGL Classes
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

// LWJGL OpenGL Static Imports (GL11)
import static org.lwjgl.opengl.GL11.GL_ALWAYS;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_LESS;
import static org.lwjgl.opengl.GL11.GL_LINES;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_POINTS;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glBegin;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDepthFunc;
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
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glVertex3f;

// LWJGL OpenGL Static Imports (GL13)
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;

// LWJGL OpenGL Static Imports (GL14)

// LWJGL OpenGL Static Imports (GL20 & GL30)
import static org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM;

// Project Imports (Blocks)
import com.stonebreak.blocks.*;

// Project Imports (Core)
import com.stonebreak.core.Game;

// Project Imports (Items)

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
    
    // Specialized block renderer
    private final BlockRenderer blockRenderer;
    
    // Matrices
    private final Matrix4f projectionMatrix;
    
    // UI elements
    private int crosshairVao;
    private int hotbarVao;
    

    // 3D Item Cube for Inventory - REMOVED: Now using block-specific cubes
    
    // Specialized renderers
    private final PlayerArmRenderer playerArmRenderer;
    private DebugRenderer debugRenderer;
    
    // UI renderer for NanoVG-based UI
    private final UIRenderer uiRenderer;
    
    // Reusable lists to avoid allocations during rendering
    private final List<Chunk> reusableSortedChunks = new ArrayList<>();
    
    // Reusable matrices to avoid allocations
    
    // Entity renderer (sub-renderer)
    private EntityRenderer entityRenderer;

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
        
        // Initialize specialized block renderer
        blockRenderer = new BlockRenderer();
        
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

        // Initialize specialized block renderer dependencies
        blockRenderer.initializeDependencies(shaderProgram, textureAtlas);

        // Initialize specialized renderers
        playerArmRenderer = new PlayerArmRenderer(shaderProgram, textureAtlas, projectionMatrix);
        
        // Initialize UI renderer
        uiRenderer = new UIRenderer();
        uiRenderer.init();
        
        // Initialize depth curtain renderer with necessary parameters
        uiRenderer.initializeDepthCurtainRenderer(shaderProgram, windowWidth, windowHeight, projectionMatrix);
        
        // Initialize block icon renderer with necessary dependencies
        uiRenderer.initializeBlockIconRenderer(blockRenderer, windowHeight);
        

        // Initialize font
        // Font loaded from stonebreak-game/src/main/resources/fonts/
        font = new Font("fonts/Roboto-VariableFont_wdth,wght.ttf", 24f);
        
        // Create UI elements
        createCrosshair();
        createHotbar();
        
        // Initialize debug renderer
        debugRenderer = new DebugRenderer(shaderProgram, projectionMatrix);
        
        // Initialize entity renderer (sub-renderer)
        entityRenderer = new EntityRenderer();
        entityRenderer.initialize();
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
     * Get the entity renderer sub-component.
     * @return EntityRenderer instance
     */
    public EntityRenderer getEntityRenderer() {
        return entityRenderer;
    }
    
    // ============ UI RENDERER PROXY METHODS ============
    
    /**
     * Get the UIRenderer instance managed by this renderer.
     * @return UIRenderer instance
     */
    public UIRenderer getUIRenderer() {
        return uiRenderer;
    }
    
    /**
     * Begin a UI frame for NanoVG rendering.
     * @param width Window width
     * @param height Window height
     * @param pixelRatio Pixel ratio (typically 1.0f)
     */
    public void beginUIFrame(int width, int height, float pixelRatio) {
        if (uiRenderer != null) {
            uiRenderer.beginFrame(width, height, pixelRatio);
        }
    }
    
    /**
     * End the current UI frame.
     */
    public void endUIFrame() {
        if (uiRenderer != null) {
            uiRenderer.endFrame();
        }
    }
    
    /**
     * Render the main menu.
     * @param windowWidth Window width
     * @param windowHeight Window height
     */
    public void renderMainMenu(int windowWidth, int windowHeight) {
        if (uiRenderer != null) {
            uiRenderer.renderMainMenu(windowWidth, windowHeight);
        }
    }
    
    /**
     * Render the pause menu.
     * @param windowWidth Window width
     * @param windowHeight Window height
     * @param isQuitButtonHovered Whether quit button is hovered
     * @param isSettingsButtonHovered Whether settings button is hovered
     */
    public void renderPauseMenu(int windowWidth, int windowHeight, boolean isQuitButtonHovered, boolean isSettingsButtonHovered) {
        if (uiRenderer != null) {
            uiRenderer.renderPauseMenu(windowWidth, windowHeight, isQuitButtonHovered, isSettingsButtonHovered);
        }
    }
    
    /**
     * Render the settings menu.
     * @param windowWidth Window width
     * @param windowHeight Window height
     */
    public void renderSettingsMenu(int windowWidth, int windowHeight) {
        if (uiRenderer != null) {
            uiRenderer.renderSettingsMenu(windowWidth, windowHeight);
        }
    }
    
    /**
     * Render chat system.
     * @param chatSystem Chat system instance
     * @param windowWidth Window width
     * @param windowHeight Window height
     */
    public void renderChat(com.stonebreak.chat.ChatSystem chatSystem, int windowWidth, int windowHeight) {
        if (uiRenderer != null) {
            uiRenderer.renderChat(chatSystem, windowWidth, windowHeight);
        }
    }
    
    /**
     * Draw a button using the UI renderer.
     * @param text Button text
     * @param x X position
     * @param y Y position
     * @param w Width
     * @param h Height
     * @param highlighted Whether button is highlighted
     */
    public void drawButton(String text, float x, float y, float w, float h, boolean highlighted) {
        if (uiRenderer != null) {
            uiRenderer.drawButton(text, x, y, w, h, highlighted);
        }
    }
    
    /**
     * Draw a dropdown button using the UI renderer.
     * @param text Button text
     * @param x X position
     * @param y Y position
     * @param w Width
     * @param h Height
     * @param highlighted Whether button is highlighted
     * @param isOpen Whether dropdown is open
     */
    public void drawDropdownButton(String text, float x, float y, float w, float h, boolean highlighted, boolean isOpen) {
        if (uiRenderer != null) {
            uiRenderer.drawDropdownButton(text, x, y, w, h, highlighted, isOpen);
        }
    }
    
    /**
     * Draw a dropdown menu using the UI renderer.
     * @param options Menu options
     * @param selectedIndex Selected index
     * @param x X position
     * @param y Y position
     * @param w Width
     * @param itemHeight Height per item
     */
    public void drawDropdownMenu(String[] options, int selectedIndex, float x, float y, float w, float itemHeight) {
        if (uiRenderer != null) {
            uiRenderer.drawDropdownMenu(options, selectedIndex, x, y, w, itemHeight);
        }
    }
    
    /**
     * Draw a volume slider using the UI renderer.
     * @param label Slider label
     * @param centerX Center X position
     * @param centerY Center Y position
     * @param sliderWidth Slider width
     * @param sliderHeight Slider height
     * @param value Slider value (0.0-1.0)
     * @param highlighted Whether slider is highlighted
     */
    public void drawVolumeSlider(String label, float centerX, float centerY, float sliderWidth, float sliderHeight, float value, boolean highlighted) {
        if (uiRenderer != null) {
            uiRenderer.drawVolumeSlider(label, centerX, centerY, sliderWidth, sliderHeight, value, highlighted);
        }
    }
    
    /**
     * Check if a button was clicked using the UI renderer.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param buttonX Button X position
     * @param buttonY Button Y position
     * @param buttonW Button width
     * @param buttonH Button height
     * @return True if button was clicked
     */
    public boolean isButtonClicked(float mouseX, float mouseY, float buttonX, float buttonY, float buttonW, float buttonH) {
        if (uiRenderer != null) {
            return uiRenderer.isButtonClicked(mouseX, mouseY, buttonX, buttonY, buttonW, buttonH);
        }
        return false;
    }
    
    /**
     * Check if pause resume button was clicked.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param windowWidth Window width
     * @param windowHeight Window height
     * @return True if resume button was clicked
     */
    public boolean isPauseResumeClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (uiRenderer != null) {
            return uiRenderer.isPauseResumeClicked(mouseX, mouseY, windowWidth, windowHeight);
        }
        return false;
    }
    
    /**
     * Check if pause settings button was clicked.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param windowWidth Window width
     * @param windowHeight Window height
     * @return True if settings button was clicked
     */
    public boolean isPauseSettingsClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (uiRenderer != null) {
            return uiRenderer.isPauseSettingsClicked(mouseX, mouseY, windowWidth, windowHeight);
        }
        return false;
    }
    
    /**
     * Check if pause quit button was clicked.
     * @param mouseX Mouse X position
     * @param mouseY Mouse Y position
     * @param windowWidth Window width
     * @param windowHeight Window height
     * @return True if quit button was clicked
     */
    public boolean isPauseQuitClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (uiRenderer != null) {
            return uiRenderer.isPauseQuitClicked(mouseX, mouseY, windowWidth, windowHeight);
        }
        return false;
    }
    
    // ============ END UI RENDERER PROXY METHODS ============
    
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
        blockRenderer.renderBlockCrackOverlay(player, shaderProgram, projectionMatrix);

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
        blockRenderer.renderBlockCrackOverlay(player, shaderProgram, projectionMatrix);

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
        blockRenderer.renderBlockCrackOverlay(player, shaderProgram, projectionMatrix);

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
        blockRenderer.renderBlockDrops(world, projectionMatrix);
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
            uiRenderer.renderInventoryDepthCurtain();
        } else {
            // Handle depth curtain based on current game state
            switch (gameInstance.getState()) {
                case RECIPE_BOOK_UI -> {
                    // Render depth curtain for recipe book screen
                    uiRenderer.renderRecipeBookDepthCurtain();
                }
                case WORKBENCH_UI -> {
                    // Render depth curtain for workbench screen
                    uiRenderer.renderWorkbenchDepthCurtain();
                }
                default -> {
                    // If no full-screen UI is visible, render hotbar depth curtain 
                    // (hotbar is rendered separately in Main.java via UIRenderer)
                    uiRenderer.renderHotbarDepthCurtain();
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
        // Delegate to UI renderer's quad drawing functionality
        uiRenderer.drawQuad(shaderProgram, x, y, width, height, r, g, b, a);
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
        // Delegate to UI renderer's textured quad drawing functionality
        uiRenderer.drawTexturedQuadUI(shaderProgram, x, y, width, height, textureId, u1, v1, u2, v2);
    }

    // Method moved to BlockIconRenderer and delegated through UIRenderer

    // Method moved to UIRenderer.drawFlat2DItemInSlot()

    // This entire duplicated method drawFlat2DItemInSlot (from 1477 to 1606) is removed.
    // Its content was the basis for createBlockSpecificCube.

    
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
        
        // Use BlockRenderer for cross-shaped geometry
        com.stonebreak.rendering.models.blocks.BlockRenderer.renderFlowerCross(uvCoords);
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
        
        // Cleanup UI renderer
        if (uiRenderer != null) {
            uiRenderer.cleanup();
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
        
        // Cleanup specialized block renderer
        if (blockRenderer != null) {
            blockRenderer.cleanup();
        }
        
        // Cleanup entity renderer (sub-renderer)
        if (entityRenderer != null) {
            entityRenderer.cleanup();
        }
    }

    /**
     * Renders a wireframe bounding box for debug purposes.
     * @param boundingBox The bounding box to render
     * @param color The color of the wireframe (RGB, each component 0.0-1.0)
     */
    public void renderWireframeBoundingBox(com.stonebreak.mobs.entities.Entity.BoundingBox boundingBox, Vector3f color) {
        debugRenderer.renderWireframeBoundingBox(boundingBox, color);
    }
    
    /**
     * Renders a wireframe path as connected line segments.
     * @param pathPoints The list of points forming the path
     * @param color The color of the path wireframe (RGB, each component 0.0-1.0)
     */
    public void renderWireframePath(List<Vector3f> pathPoints, Vector3f color) {
        debugRenderer.renderWireframePath(pathPoints, color);
    }
    
    /**
     * Renders all entities (cows, etc.) in the world using the entity sub-renderer.
     */
    private void renderEntities(Player player) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        
        if (entityManager != null && entityRenderer != null) {
            // Get all entities and render them using the sub-renderer
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

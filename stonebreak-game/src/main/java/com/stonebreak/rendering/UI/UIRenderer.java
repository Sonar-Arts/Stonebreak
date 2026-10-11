package com.stonebreak.rendering.UI;

import java.nio.FloatBuffer;
import com.stonebreak.rendering.textures.BlockTextureArray;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.ui.crosshair.CrosshairSettings;
import com.stonebreak.rendering.UI.components.OpenGLQuadRenderer;
import com.stonebreak.rendering.UI.menus.BlockIconRenderer;

/**
 * UIRenderer acts as a controller that delegates rendering tasks to specialized renderers.
 * This follows the controller pattern where the main renderer orchestrates calls to
 * smaller, focused renderers for different UI components.
 */
public class UIRenderer {
    // Specialized renderers
    private BlockIconRenderer blockIconRenderer;
    private final CrosshairSettings crosshairSettings = new CrosshairSettings();
    private final com.stonebreak.ui.crosshair.Crosshair crosshair = new com.stonebreak.ui.crosshair.Crosshair();
    private final com.stonebreak.ui.worldHud.WorldHud worldHud = new com.stonebreak.ui.worldHud.WorldHud();
    private final com.stonebreak.ui.chat.ChatDocument chatDocument = new com.stonebreak.ui.chat.ChatDocument();
    private OpenGLQuadRenderer openGLQuadRenderer;

    public UIRenderer() {
        openGLQuadRenderer = new OpenGLQuadRenderer();
    }

    public void init() {
        openGLQuadRenderer.initialize();
    }

    /**
     * Initializes the depth curtain renderer with the necessary parameters from the main renderer.
     * This should be called after the main renderer has been set up.
     * @param shaderProgram The main shader program
     * @param windowWidth Current window width
     * @param windowHeight Current window height
     * @param projectionMatrix The 3D projection matrix
     */
    public void initializeDepthCurtainRenderer(ShaderProgram shaderProgram,
                                               int windowWidth, int windowHeight,
                                               org.joml.Matrix4f projectionMatrix) {
        openGLQuadRenderer.initializeDepthCurtainRenderer(shaderProgram, windowWidth, windowHeight, projectionMatrix);
    }

    /**
     * Initializes the block icon renderer with the necessary dependencies.
     * This should be called after the main renderer and block renderer have been set up.
     * @param blockRenderer The block renderer for creating block geometry
     * @param windowHeight Current window height for viewport calculations
     */
    public void initializeBlockIconRenderer(BlockRenderer blockRenderer,
                                            com.stonebreak.rendering.textures.BlockTextureArray blockTextureArray,
                                            com.stonebreak.rendering.sbo.SBOHandMeshRegistry sboHandMeshRegistry,
                                            int windowHeight) {
        this.blockIconRenderer = new BlockIconRenderer(blockRenderer, blockTextureArray, sboHandMeshRegistry, this, windowHeight);
    }

    // ===== Chat Rendering Delegation =====

    /** Paints the chat document (#301). */
    public void renderChat(ChatSystem chatSystem, int windowWidth, int windowHeight) {
        chatDocument.paint(() -> chatSystem, windowWidth, windowHeight);
    }

    // ===== OpenGL Quad Rendering Delegation =====

    public void drawFlat2DItemInSlot(ShaderProgram shaderProgram,
                                     com.stonebreak.blocks.BlockType type,
                                     int screenSlotX, int screenSlotY,
                                     int screenSlotWidth, int screenSlotHeight,
                                     com.stonebreak.rendering.textures.BlockTextureArray blockTextureArray,
                                     FloatBuffer projectionMatrixBuffer,
                                     FloatBuffer viewMatrixBuffer) {
        openGLQuadRenderer.drawFlat2DItemInSlot(shaderProgram, type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, blockTextureArray, projectionMatrixBuffer, viewMatrixBuffer);
    }

    /**
     * Renders a 3D block icon in the specified slot area.
     * Handles both 3D cube blocks and flat 2D flower blocks.
     *
     * @param shaderProgram The shader program to use for rendering
     * @param type The block type to render
     * @param screenSlotX X coordinate of the slot
     * @param screenSlotY Y coordinate of the slot
     * @param screenSlotWidth Width of the slot
     * @param screenSlotHeight Height of the slot
     * @param textureAtlas The texture atlas containing block textures
     */
    public void draw3DItemInSlot(ShaderProgram shaderProgram, BlockType type, int screenSlotX, int screenSlotY,
                                int screenSlotWidth, int screenSlotHeight, BlockTextureArray textureAtlas) {
        if (blockIconRenderer == null) {
            throw new IllegalStateException("BlockIconRenderer not initialized. Call initializeBlockIconRenderer() first.");
        }
        blockIconRenderer.draw3DItemInSlot(shaderProgram, type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, textureAtlas);
    }

    // ===== Depth Curtain Rendering Delegation =====

    public void renderPauseMenuDepthCurtain() {
        openGLQuadRenderer.renderPauseMenuDepthCurtain();
    }

    // ===== Crosshair Rendering Delegation =====

    /**
     * Paints the crosshair document at the center of the screen.
     * @param windowWidth Current window width
     * @param windowHeight Current window height
     */
    public void renderCrosshair(int windowWidth, int windowHeight) {
        crosshair.paint(windowWidth, windowHeight);
    }

    /**
     * The applied crosshair configuration (settings write it, the crosshair document draws it).
     * @return CrosshairSettings instance for customization
     */
    public CrosshairSettings getCrosshairSettings() {
        return crosshairSettings;
    }

    /** The chat's presentation: its shipped document (#301). */
    public com.stonebreak.ui.chat.ChatDocument getChatDocument() {
        return chatDocument;
    }

    /** The world-anchored HUD: its shipped document (#301). */
    public com.stonebreak.ui.worldHud.WorldHud getWorldHud() {
        return worldHud;
    }

    /** The crosshair's presentation: its shipped document (#301). */
    public com.stonebreak.ui.crosshair.Crosshair getCrosshair() {
        return crosshair;
    }

    // ===== Getter Methods =====

    /**
     * Get the block icon renderer for rendering block icons in tooltip layer.
     */
    public BlockIconRenderer getBlockIconRenderer() {
        return blockIconRenderer;
    }

    public void cleanup() {
        // Cleanup OpenGL quad renderer
        if (openGLQuadRenderer != null) {
            openGLQuadRenderer.cleanup();
        }
    }
}

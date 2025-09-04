package com.stonebreak.rendering;

import java.util.List;

import com.stonebreak.rendering.core.OpenGLErrorHandler;
import com.stonebreak.rendering.core.RenderingConfigurationManager;
import com.stonebreak.rendering.core.ResourceManager;
import com.stonebreak.rendering.models.blocks.BlockDropRenderer;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import com.stonebreak.rendering.player.PlayerArmRenderer;
import com.stonebreak.rendering.gameWorld.WorldRenderer;
import com.stonebreak.rendering.UI.rendering.DebugRenderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.ui.Font;
import com.stonebreak.rendering.shaders.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.ui.*;
import com.stonebreak.world.World;


/**
 * Handles rendering of the world and UI elements.
 */
public class Renderer {
    
    // Core managers
    private final ResourceManager resourceManager;
    private final RenderingConfigurationManager configManager;
    
    // Specialized renderers
    private final BlockDropRenderer blockDropRenderer;
    private final BlockRenderer blockRenderer;
    private final PlayerArmRenderer playerArmRenderer;
    private final UIRenderer uiRenderer;
    private final DebugRenderer debugRenderer;
    private final EntityRenderer entityRenderer;
    private final WorldRenderer worldRenderer;
    

    /**
     * Creates and initializes the renderer.
     */
    public Renderer(int width, int height) {
        // Initialize core managers
        resourceManager = new ResourceManager();
        resourceManager.initialize(16); // 16x16 texture atlas
        resourceManager.initializeShaderProgram();
        
        configManager = new RenderingConfigurationManager(width, height);
        
        // Initialize specialized renderers
        blockDropRenderer = new BlockDropRenderer();
        blockDropRenderer.initialize(resourceManager.getShaderProgram(), resourceManager.getTextureAtlas());
        
        blockRenderer = new BlockRenderer();
        blockRenderer.initializeDependencies(resourceManager.getShaderProgram(), resourceManager.getTextureAtlas());
        
        playerArmRenderer = new PlayerArmRenderer(resourceManager.getShaderProgram(), 
                                                 resourceManager.getTextureAtlas(), 
                                                 configManager.getProjectionMatrix());
        
        uiRenderer = new UIRenderer();
        uiRenderer.init();
        uiRenderer.initializeDepthCurtainRenderer(resourceManager.getShaderProgram(), 
                                                 configManager.getWindowWidth(), 
                                                 configManager.getWindowHeight(), 
                                                 configManager.getProjectionMatrix());
        uiRenderer.initializeBlockIconRenderer(blockRenderer, configManager.getWindowHeight());
        
        debugRenderer = new DebugRenderer(resourceManager.getShaderProgram(), configManager.getProjectionMatrix());
        
        entityRenderer = new EntityRenderer();
        entityRenderer.initialize();
        
        worldRenderer = new WorldRenderer(resourceManager.getShaderProgram(), 
                                         resourceManager.getTextureAtlas(), 
                                         configManager.getProjectionMatrix(),
                                         blockRenderer, playerArmRenderer, entityRenderer);
    }
    
    /**
     * Updates the projection matrix when the window is resized.
     */
    public void updateProjectionMatrix(int width, int height) {
        configManager.updateWindowSize(width, height);
    }

    public int getWindowWidth() {
        return configManager.getWindowWidth();
    }

    public int getWindowHeight() {
        return configManager.getWindowHeight();
    }

    public Font getFont() {
        return resourceManager.getFont();
    }

    public ShaderProgram getShaderProgram() {
        return resourceManager.getShaderProgram();
    }

    public TextureAtlas getTextureAtlas() {
        return resourceManager.getTextureAtlas();
    }
    
    public Matrix4f getProjectionMatrix() {
        return configManager.getProjectionMatrix();
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
     * Renders the world using the specialized WorldRenderer sub-renderer.
     * UI elements have been stripped from this method and should be rendered separately.
     */
    public void renderWorld(World world, Player player, float totalTime) {
        // Delegate to the specialized WorldRenderer
        worldRenderer.renderWorld(world, player, totalTime);
    }
    
    
    
    /**
     * Renders block drops in a deferred pass after all UI rendering is complete.
     */
    public void renderBlockDropsDeferred(World world, Player player) {
        // This method renders block drops completely isolated from UI rendering
        blockRenderer.renderBlockDrops(world, configManager.getProjectionMatrix());
    }
    

      /**
     * Renders UI elements on top of the 3D world.
     */




    
    
    /**
     * Renders crack overlay on the block being broken.
     */
    
    
    /**
     * Cleanup method to release resources.
     */
    public void cleanup() {
        // Cleanup core managers
        if (resourceManager != null) {
            resourceManager.cleanup();
        }
        
        // Cleanup specialized renderers
        if (playerArmRenderer != null) {
            playerArmRenderer.cleanup();
        }
        if (uiRenderer != null) {
            uiRenderer.cleanup();
        }
        if (blockDropRenderer != null) {
            blockDropRenderer.cleanup();
        }
        if (blockRenderer != null) {
            blockRenderer.cleanup();
        }
        if (entityRenderer != null) {
            entityRenderer.cleanup();
        }
        
        // Cleanup pause menu
        PauseMenu pauseMenu = Game.getInstance().getPauseMenu();
        if (pauseMenu != null) {
            pauseMenu.cleanup();
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
     * Checks for OpenGL errors and logs them with context information.
     */
    public void checkGLError(String context) {
        OpenGLErrorHandler.checkGLError(context);
    }
    
    
}

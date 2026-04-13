package com.stonebreak.rendering;

import java.util.List;

import com.stonebreak.rendering.core.OpenGLErrorHandler;
import com.stonebreak.rendering.core.RenderingConfigurationManager;
import com.stonebreak.rendering.core.ResourceManager;
import com.stonebreak.rendering.core.GameBlockDefinitionRegistry;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import com.stonebreak.rendering.models.entities.DropRenderer;
import com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinitionRegistry;
import com.stonebreak.rendering.player.PlayerArmRenderer;
import com.stonebreak.rendering.gameWorld.WorldRenderer;
import com.stonebreak.rendering.UI.rendering.DebugRenderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.components.OverlayRenderer;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.sbo.SBOBlockBridge;
import com.stonebreak.rendering.sbo.SBOBlockRegistry;
import com.stonebreak.rendering.sbo.SBOHandMeshRegistry;
import com.stonebreak.rendering.sbo.SBOTextureIntegrator;
import com.openmason.engine.voxel.mms.mmsIntegration.MmsFaceCullingService;
import com.openmason.engine.voxel.sbo.sboRenderer.SBORendererAPI;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOStampEmitter;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.ui.Font;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.ui.chat.ChatSystem;
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
    private final BlockDefinitionRegistry blockRegistry;
    
    // SBO renderer API (deferred emitter setup until MmsAPI is initialized)
    private SBORendererAPI sboRendererAPI;
    private SBOStampEmitter pendingSBOEmitter;
    private MmsFaceCullingService sboCullingService;

    // SBO bridge for debug/query access
    private SBOBlockBridge sboBlockBridge;

    // SBO-derived hand-rendering meshes (ensures in-hand geometry matches in-world)
    private SBOHandMeshRegistry sboHandMeshRegistry;

    // Specialized renderers
    private final BlockRenderer blockRenderer;
    private final PlayerArmRenderer playerArmRenderer;
    private final UIRenderer uiRenderer;
    private final DebugRenderer debugRenderer;
    private final EntityRenderer entityRenderer;
    private final WorldRenderer worldRenderer;
    private final OverlayRenderer overlayRenderer;
    private final DropRenderer dropRenderer;
    

    /**
     * Creates and initializes the renderer.
     */
    public Renderer(int width, int height) {
        // Initialize core managers
        resourceManager = new ResourceManager();
        resourceManager.initialize(16); // 16x16 texture atlas
        resourceManager.initializeShaderProgram();

        // Initialize SBO block system — scan, parse, and overlay textures onto atlas
        initializeSBOBlocks(resourceManager.getTextureAtlas());

        configManager = new RenderingConfigurationManager(width, height);

        // Initialize block definition registry for CBR support.
        // The SBO bridge (populated above) is consulted so SBO-declared
        // render layers (e.g. TRANSLUCENT for ice) are honored.
        blockRegistry = new GameBlockDefinitionRegistry(sboBlockBridge);
        
        
        // Initialize specialized renderers with CBR support
        blockRenderer = new BlockRenderer(resourceManager.getTextureAtlas(), blockRegistry);

        // Build SBO hand-mesh registry now that CBR's MeshManager is available.
        // Runs after the SBO bridge and texture integration (initializeSBOBlocks
        // above) so atlas UVs have already been populated.
        if (sboBlockBridge != null && sboBlockBridge.size() > 0) {
            sboHandMeshRegistry = new SBOHandMeshRegistry(
                    blockRenderer.getCBRResourceManager(),
                    resourceManager.getTextureAtlas());
            int built = sboHandMeshRegistry.buildFlowerMeshes(sboBlockBridge);
            System.out.println("[Renderer] SBO hand-mesh registry: built " + built + " flower meshes");
        }

        playerArmRenderer = new PlayerArmRenderer(resourceManager.getShaderProgram(),
                                                 resourceManager.getTextureAtlas(),
                                                 configManager.getProjectionMatrix(),
                                                 blockRegistry,
                                                 sboHandMeshRegistry);
        
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
        
        dropRenderer = new DropRenderer(blockRenderer, resourceManager.getTextureAtlas(), resourceManager.getShaderProgram());

        // Test the new voxelized item drop system
        System.out.println("[Renderer] Testing voxelized item drop system...");
        dropRenderer.testDropRendering();
        System.out.println("[Renderer] Voxelized item drop system test complete.");

        worldRenderer = new WorldRenderer(resourceManager.getShaderProgram(), 
                                         resourceManager.getTextureAtlas(), 
                                         configManager.getProjectionMatrix(),
                                         blockRenderer, playerArmRenderer, entityRenderer, dropRenderer);
        
        overlayRenderer = new OverlayRenderer(uiRenderer.getBlockIconRenderer(), 
                                             uiRenderer.getItemIconRenderer());
    }
    
    /**
     * Initializes the SBO block system.
     * Scans for .sbo files, parses them, and overlays their textures onto the atlas.
     */
    private void initializeSBOBlocks(TextureAtlas textureAtlas) {
        try {
            SBOBlockRegistry registry = new SBOBlockRegistry();
            int loaded = registry.scanAndLoad();

            if (loaded > 0) {
                SBOBlockBridge bridge = new SBOBlockBridge();
                bridge.initialize(registry);
                this.sboBlockBridge = bridge;

                if (bridge.size() > 0) {
                    SBOTextureIntegrator integrator = new SBOTextureIntegrator(textureAtlas, bridge);
                    int integrated = integrator.integrateAll();
                    System.out.println("[Renderer] SBO integration: " + integrated + " block textures updated");
                }

                // Build SBO block map for the renderer API
                java.util.Map<com.stonebreak.blocks.BlockType, SBOParseResult> sboBlockMap = new java.util.LinkedHashMap<>();
                for (com.stonebreak.blocks.BlockType blockType : com.stonebreak.blocks.BlockType.values()) {
                    if (bridge.isSBOBlock(blockType)) {
                        sboBlockMap.put(blockType, bridge.getSBODefinition(blockType));
                    }
                }

                if (!sboBlockMap.isEmpty()) {
                    // Initialize the SBO Renderer API — processes all SBO meshes into stamps
                    var uvProvider = new com.stonebreak.world.chunk.api.voxel.TextureAtlasAdapter(textureAtlas);
                    sboRendererAPI = new SBORendererAPI();
                    int processed = sboRendererAPI.initialize(sboBlockMap, uvProvider);

                    if (processed > 0) {
                        // Create deferred emitter with face culling (world set later in applySBODispatcher)
                        sboCullingService = new MmsFaceCullingService();

                        // Translucency policy: consult the CBR block registry
                        // (populated after this method returns) for the block's
                        // resolved render layer. Deferred lookup via `this`
                        // reference — the predicate is only invoked later at
                        // chunk-mesh time, by which point blockRegistry is set.
                        java.util.function.Predicate<com.openmason.engine.voxel.IBlockType> translucencyPolicy = block -> {
                            if (!(block instanceof com.stonebreak.blocks.BlockType bt)) return false;
                            if (blockRegistry == null) return false;
                            String resourceId = "stonebreak:" + bt.name().toLowerCase();
                            return blockRegistry.getDefinition(resourceId)
                                    .map(def -> def.getRenderLayer() == com.stonebreak.rendering.core.API.commonBlockResources.models.BlockDefinition.RenderLayer.TRANSLUCENT)
                                    .orElse(false);
                        };

                        sboCullingService.setTranslucencyPolicy(translucencyPolicy);

                        // Cross-plane blocks (flowers) must bypass neighbor
                        // face culling — their "faces" are packed planes,
                        // so culling can drop visible geometry (most
                        // notably when two flowers of the same type are
                        // adjacent).
                        sboCullingService.setCrossBlockPolicy(block ->
                                block instanceof com.stonebreak.blocks.BlockType bt && bt.isFlower());

                        pendingSBOEmitter = sboRendererAPI.createEmitter(sboCullingService, translucencyPolicy);
                        System.out.println("[Renderer] SBO Renderer API: " + processed + " block types processed (emitter deferred)");
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Renderer] SBO initialization failed (non-fatal): " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Returns the SBO block bridge, or null if SBO initialization failed or no SBO blocks were loaded.
     */
    public SBOBlockBridge getSBOBlockBridge() {
        return sboBlockBridge;
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

    /**
     * Apply the deferred SBO stamp emitter to MmsAPI.
     * Call this after MmsAPI is initialized.
     */
    public void applySBODispatcher() {
        if (pendingSBOEmitter != null && com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.isInitialized()) {
            com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.getInstance().setSBOStampEmitter(pendingSBOEmitter);
            com.stonebreak.world.chunk.api.mightyMesh.MmsAPI.getInstance().setSBOCullingService(sboCullingService);
            System.out.println("[Renderer] SBO stamp emitter applied to MmsAPI");
            pendingSBOEmitter = null;
        }
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
    
    /**
     * Get the block renderer sub-component.
     * @return BlockRenderer instance
     */
    public BlockRenderer getBlockRenderer() {
        return blockRenderer;
    }
    
    /**
     * Get the drop renderer sub-component.
     * @return DropRenderer instance
     */
    public DropRenderer getDropRenderer() {
        return dropRenderer;
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
    public void renderChat(ChatSystem chatSystem, int windowWidth, int windowHeight) {
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
    
    // ============ OVERLAY RENDERER METHODS ============
    
    /**
     * Renders all overlay UI elements that appear above other UI.
     * This method should be called after all other UI rendering is complete.
     */
    public void renderOverlay(Game game, int windowWidth, int windowHeight) {
        if (overlayRenderer != null) {
            beginUIFrame(windowWidth, windowHeight, 1.0f);
            overlayRenderer.renderOverlay(game, windowWidth, windowHeight);
            endUIFrame();
        }
    }
    
    /**
     * Get the overlay renderer instance.
     * @return OverlayRenderer instance
     */
    public OverlayRenderer getOverlayRenderer() {
        return overlayRenderer;
    }
    
    /**
     * Renders an item icon on the overlay layer (above all other UI).
     * This should be used for icons that need to appear above other UI elements.
     */
    public void renderOverlayItemIcon(float x, float y, float w, float h, com.stonebreak.items.Item item) {
        if (overlayRenderer != null) {
            overlayRenderer.renderItemIcon(x, y, w, h, item, resourceManager.getTextureAtlas());
        }
    }
    
    /**
     * Renders an item icon for a block type on the overlay layer (above all other UI).
     * This should be used for icons that need to appear above other UI elements.
     */
    public void renderOverlayItemIcon(float x, float y, float w, float h, int blockTypeId) {
        if (overlayRenderer != null) {
            overlayRenderer.renderItemIcon(x, y, w, h, blockTypeId, resourceManager.getTextureAtlas());
        }
    }
    
    /**
     * Renders a 3D block icon on the overlay layer (above all other UI).
     * This should be used for icons that need to appear above other UI elements.
     */
    public void renderOverlayBlockIcon(com.stonebreak.blocks.BlockType type, int screenSlotX, int screenSlotY, int screenSlotWidth, int screenSlotHeight) {
        if (overlayRenderer != null) {
            overlayRenderer.renderBlockIcon(type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, 
                                          resourceManager.getShaderProgram(), resourceManager.getTextureAtlas());
        }
    }
    
    // ============ END OVERLAY RENDERER METHODS ============
    
    



    
    /**
     * Renders the world using the specialized WorldRenderer sub-renderer.
     * UI elements have been stripped from this method and should be rendered separately.
     */
    public void renderWorld(World world, Player player, float totalTime) {
        // Delegate to the specialized WorldRenderer
        worldRenderer.renderWorld(world, player, totalTime);
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
        if (blockRegistry != null) {
            blockRegistry.close();
        }
        
        // Cleanup specialized renderers
        if (playerArmRenderer != null) {
            playerArmRenderer.cleanup();
        }
        if (uiRenderer != null) {
            uiRenderer.cleanup();
        }
        if (blockRenderer != null) {
            blockRenderer.cleanup();
        }
        if (entityRenderer != null) {
            entityRenderer.cleanup();
        }
        if (overlayRenderer != null) {
            overlayRenderer.cleanup();
        }
        if (dropRenderer != null) {
            dropRenderer.cleanup();
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
     * Renders all sound emitters as yellow triangle wireframes when debug mode is enabled.
     * @param debugMode Whether debug mode is currently enabled
     */
    public void renderSoundEmitters(boolean debugMode) {
        debugRenderer.renderSoundEmitters(debugMode);
    }


    /**
     * Checks for OpenGL errors and logs them with context information.
     */
    public void checkGLError(String context) {
        OpenGLErrorHandler.checkGLError(context);
    }
    
    
}

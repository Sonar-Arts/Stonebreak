package com.stonebreak.rendering;

import com.openmason.engine.rendering.gl.OpenGLErrorHandler;
import com.openmason.engine.rendering.gl.RenderingConfigurationManager;
import com.openmason.engine.rendering.postfx.PostFxFrameParams;
import com.openmason.engine.rendering.postfx.PostProcessingPipeline;
import com.openmason.engine.rendering.postfx.effects.GodRaysEffect;
import com.stonebreak.rendering.core.ResourceManager;
import com.stonebreak.rendering.core.GameBlockDefinitionRegistry;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import com.stonebreak.rendering.models.entities.DropRenderer;
import com.openmason.engine.rendering.cbr.models.BlockDefinitionRegistry;
import com.stonebreak.rendering.player.PlayerArmRenderer;
import com.stonebreak.rendering.gameWorld.WorldRenderer;
import com.stonebreak.rendering.UI.rendering.DebugRenderer;
import com.stonebreak.rendering.UI.UIRenderer;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.components.DamageNumberRenderer;
import com.stonebreak.rendering.UI.components.OverlayRenderer;
import com.stonebreak.rendering.UI.components.QuarryMarkerRenderer;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.rendering.sbo.SBOBlockBridge;
import com.stonebreak.rendering.sbo.SBOBlockRegistry;
import com.stonebreak.rendering.sbo.SBOHandMeshRegistry;
import com.openmason.engine.voxel.mms.mmsIntegration.MmsFaceCullingService;
import com.openmason.engine.voxel.sbo.sboRenderer.SBORendererAPI;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOStampEmitter;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.ui.Font;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.ui.chat.ChatSystem;
import org.joml.Matrix4f;
import org.joml.Vector4f;

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

    // SBO-driven block texture array (replaces the texture atlas for block rendering)
    private BlockTextureArray blockTextureArray;

    // SBO-derived hand-rendering meshes (ensures in-hand geometry matches in-world)
    private SBOHandMeshRegistry sboHandMeshRegistry;

    // Specialized renderers
    private final BlockRenderer blockRenderer;
    private final PlayerArmRenderer playerArmRenderer;
    private final UIRenderer uiRenderer;
    private final SkijaUIBackend skijaBackend;
    private final DebugRenderer debugRenderer;
    private final EntityRenderer entityRenderer;
    private final WorldRenderer worldRenderer;
    private final OverlayRenderer overlayRenderer;
    private final DropRenderer dropRenderer;

    // Post-processing (scene FBO + screen-space effects, e.g. god rays)
    private final PostProcessingPipeline postPipeline;
    

    /**
     * Creates and initializes the renderer.
     */
    public Renderer(int width, int height) {
        // Initialize core managers
        resourceManager = new ResourceManager();
        resourceManager.initialize(16); // 16x16 texture atlas
        resourceManager.initializeShaderProgram();

        // Initialize SBO block system — scan, parse, build the block texture array
        initializeSBOBlocks();

        configManager = new RenderingConfigurationManager(width, height);

        postPipeline = new PostProcessingPipeline(width, height);
        postPipeline.addEffect(new GodRaysEffect());

        // Initialize block definition registry for CBR support.
        // The SBO bridge (populated above) is consulted so SBO-declared
        // render layers (e.g. TRANSLUCENT for ice) are honored.
        blockRegistry = new GameBlockDefinitionRegistry(sboBlockBridge);
        
        
        // Initialize specialized renderers with CBR support
        blockRenderer = new BlockRenderer(blockRegistry);

        // Build SBO hand-mesh registry now that CBR's MeshManager is available.
        // Runs after the SBO bridge and texture integration (initializeSBOBlocks
        // above) so atlas UVs have already been populated.
        if (sboBlockBridge != null && sboBlockBridge.size() > 0) {
            sboHandMeshRegistry = new SBOHandMeshRegistry(
                    blockRenderer.getCBRResourceManager(),
                    blockTextureArray);
            int built = sboHandMeshRegistry.buildFlowerMeshes(sboBlockBridge);
            System.out.println("[Renderer] SBO hand-mesh registry: built " + built + " flower meshes");
        }

        playerArmRenderer = new PlayerArmRenderer(resourceManager.getShaderProgram(),
                                                 blockTextureArray,
                                                 configManager.getProjectionMatrix(),
                                                 blockRegistry,
                                                 sboHandMeshRegistry);
        
        uiRenderer = new UIRenderer();
        uiRenderer.init();
        uiRenderer.initializeDepthCurtainRenderer(resourceManager.getShaderProgram(), 
                                                 configManager.getWindowWidth(), 
                                                 configManager.getWindowHeight(), 
                                                 configManager.getProjectionMatrix());
        uiRenderer.initializeBlockIconRenderer(blockRenderer, blockTextureArray, sboHandMeshRegistry, configManager.getWindowHeight());

        skijaBackend = new SkijaUIBackend();
        try {
            skijaBackend.initialize(configManager.getWindowWidth(), configManager.getWindowHeight());
            System.out.println("[Renderer] Skija UI backend initialized (" +
                    configManager.getWindowWidth() + "x" + configManager.getWindowHeight() + ")");
        } catch (Throwable t) {
            // Skija is optional — NanoVG handles fallback UI rendering. Log and continue
            // rather than crashing the game over a non-fatal UI backend failure.
            System.err.println("[Renderer] Skija backend init failed: " + t.getMessage());
            t.printStackTrace();
        }

        // Skija-backed UI renderers (chat panel) need the backend; init now
        // that it exists. UIRenderer.init() ran earlier and only set up NanoVG.
        uiRenderer.initializeSkijaRenderers(skijaBackend);
        DamageNumberRenderer.getInstance().setBackend(skijaBackend);
        QuarryMarkerRenderer.getInstance().setBackend(skijaBackend);

        debugRenderer = new DebugRenderer(resourceManager.getShaderProgram(), configManager.getProjectionMatrix());
        
        entityRenderer = new EntityRenderer();
        entityRenderer.initialize();
        entityRenderer.initializeArrowRenderer(resourceManager.getShaderProgram());

        dropRenderer = new DropRenderer(blockRenderer, blockTextureArray, sboHandMeshRegistry, resourceManager.getShaderProgram());

        // Test the new voxelized item drop system
        System.out.println("[Renderer] Testing voxelized item drop system...");
        dropRenderer.testDropRendering();
        System.out.println("[Renderer] Voxelized item drop system test complete.");

        worldRenderer = new WorldRenderer(resourceManager.getShaderProgram(),
                                         blockTextureArray,
                                         configManager.getProjectionMatrix(),
                                         blockRenderer, playerArmRenderer, entityRenderer, dropRenderer);
        
        overlayRenderer = new OverlayRenderer(uiRenderer.getBlockIconRenderer(), 
                                             uiRenderer.getItemIconRenderer());
    }
    
    /**
     * Initializes the SBO block system.
     * Scans for .sbo files, parses them, and builds the block texture array.
     */
    private void initializeSBOBlocks() {
        try {
            SBOBlockRegistry registry = new SBOBlockRegistry();
            int loaded = registry.scanAndLoad();

            if (loaded > 0) {
                int registered = com.stonebreak.blocks.registry.BlockRegistry.getInstance().loadFrom(registry);
                System.out.println("[Renderer] BlockRegistry: " + registered + " blocks auto-populated from SBOs");

                int items = com.stonebreak.items.registry.ItemRegistry.getInstance().scanAndLoad();
                System.out.println("[Renderer] ItemRegistry: " + items + " items auto-populated from SBOs");

                SBOBlockBridge bridge = new SBOBlockBridge();
                bridge.initialize(registry);
                this.sboBlockBridge = bridge;

                // Build the SBO-driven block texture array.
                this.blockTextureArray = new BlockTextureArray(bridge);
                System.out.println("[Renderer] BlockTextureArray: "
                        + blockTextureArray.getLayerCount() + " layers built");

                // Build SBO block map for the renderer API
                java.util.Map<com.stonebreak.blocks.BlockType, SBOParseResult> sboBlockMap = new java.util.LinkedHashMap<>();
                for (com.stonebreak.blocks.BlockType blockType : com.stonebreak.blocks.BlockType.values()) {
                    if (bridge.isSBOBlock(blockType)) {
                        sboBlockMap.put(blockType, bridge.getSBODefinition(blockType));
                    }
                }

                if (!sboBlockMap.isEmpty()) {
                    // Initialize the SBO Renderer API — processes all SBO meshes into stamps
                    var arrayAdapter = new com.stonebreak.world.chunk.api.voxel.TextureArrayAdapter(blockTextureArray);
                    sboRendererAPI = new SBORendererAPI();
                    int processed = sboRendererAPI.initialize(sboBlockMap, arrayAdapter, arrayAdapter);

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
                                    .map(def -> def.getRenderLayer() == com.openmason.engine.rendering.cbr.models.BlockDefinition.RenderLayer.TRANSLUCENT)
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

                        // Snow layers are partial-height: adjacent snow blocks
                        // can have different layer counts, leaving the taller
                        // block's side face partially exposed above the shorter
                        // neighbor. Skip same-type side-face culling for these.
                        sboCullingService.setPartialHeightPolicy(block ->
                                block instanceof com.stonebreak.blocks.BlockType bt
                                        && bt == com.stonebreak.blocks.BlockType.SNOW);

                        pendingSBOEmitter = sboRendererAPI.createEmitter(sboCullingService, translucencyPolicy);

                        MmsFaceCullingService cullingForNeighborLookup = sboCullingService;

                        // Per-face cull policy: cull an ice block's top face
                        // when a snow layer sits directly on it. Snow's bottom
                        // face is coplanar with ice's top face — rendering both
                        // produces z-fighting at that shared plane.
                        pendingSBOEmitter.setInstanceFaceCullPolicy((block, lx, ly, lz, face, chunkData) -> {
                            if (face != 0) return false; // top face only
                            if (!(block instanceof com.stonebreak.blocks.BlockType bt)) return false;
                            if (bt != com.stonebreak.blocks.BlockType.ICE) return false;
                            com.openmason.engine.voxel.IBlockType above =
                                    cullingForNeighborLookup.getAdjacentBlock(lx, ly + 1, lz, chunkData);
                            return above instanceof com.stonebreak.blocks.BlockType ab
                                    && ab == com.stonebreak.blocks.BlockType.SNOW;
                        });

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
     * Returns the SBO-driven block texture array, or null if SBO initialization failed.
     */
    public BlockTextureArray getBlockTextureArray() {
        return blockTextureArray;
    }

    /**
     * Updates the projection matrix when the window is resized.
     */
    public void updateProjectionMatrix(int width, int height) {
        configManager.updateWindowSize(width, height);
        if (skijaBackend != null && skijaBackend.isAvailable()) {
            skijaBackend.resize(width, height);
        }
        postPipeline.resize(width, height);
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
     * Skija-backed UI backend (Skia bindings). Used by next-gen menus that have
     * migrated off NanoVG.
     */
    public SkijaUIBackend getSkijaBackend() {
        return skijaBackend;
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
    
    // ============ END UI RENDERER PROXY METHODS ============
    
    // ============ OVERLAY RENDERER METHODS ============
    
    /**
     * Renders all overlay UI elements that appear above other UI.
     * This method should be called after all other UI rendering is complete.
     */
    public void renderOverlay(Game game, int windowWidth, int windowHeight) {
        if (overlayRenderer != null) {
            overlayRenderer.renderOverlay(game, windowWidth, windowHeight);
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
            overlayRenderer.renderItemIcon(x, y, w, h, item, blockTextureArray);
        }
    }
    
    /**
     * Renders an item icon for a block type on the overlay layer (above all other UI).
     * This should be used for icons that need to appear above other UI elements.
     */
    public void renderOverlayItemIcon(float x, float y, float w, float h, int blockTypeId) {
        if (overlayRenderer != null) {
            overlayRenderer.renderItemIcon(x, y, w, h, blockTypeId, blockTextureArray);
        }
    }
    
    /**
     * Renders a 3D block icon on the overlay layer (above all other UI).
     * This should be used for icons that need to appear above other UI elements.
     */
    public void renderOverlayBlockIcon(com.stonebreak.blocks.BlockType type, int screenSlotX, int screenSlotY, int screenSlotWidth, int screenSlotHeight) {
        if (overlayRenderer != null) {
            overlayRenderer.renderBlockIcon(type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight,
                                          resourceManager.getShaderProgram(), blockTextureArray);
        }
    }
    
    // ============ END OVERLAY RENDERER METHODS ============
    
    



    
    /**
     * Renders the world using the specialized WorldRenderer sub-renderer.
     * UI elements have been stripped from this method and should be rendered separately.
     */
    public void renderWorld(World world, Player player, float totalTime) {
        com.stonebreak.world.TimeOfDay timeOfDay = Game.getTimeOfDay();
        org.joml.Vector3f sunDirection = timeOfDay != null
                ? timeOfDay.getSunDirection()
                : new org.joml.Vector3f(0.7f, 0.1f, 0.5f).normalize();
        float godRayStrength = com.stonebreak.config.Settings.getInstance().getGodRaysEnabled()
                ? computeGodRayStrength(sunDirection)
                : 0.0f;

        if (godRayStrength <= 0.001f) {
            // Effect off or sun below the horizon — render directly to the default
            // framebuffer, exactly the pre-post-processing path.
            worldRenderer.renderWorld(world, player, totalTime);
            return;
        }

        postPipeline.beginFrame();
        worldRenderer.renderWorld(world, player, totalTime);
        postPipeline.endFrame(new PostFxFrameParams(
                player.getViewMatrix(),
                configManager.getProjectionMatrix(),
                sunDirection,
                godRayStrength));
    }

    /**
     * God ray intensity from sun elevation: off at night, strongest near the horizon
     * (dawn/dusk), subtle at noon.
     */
    private static float computeGodRayStrength(org.joml.Vector3f sunDirection) {
        float elevation = sunDirection.y;
        if (elevation < -0.05f) {
            return 0.0f; // Night — sun below the horizon.
        }
        float horizonRamp = Math.clamp((elevation + 0.05f) / 0.15f, 0.0f, 1.0f);
        float baseStrength = 0.6f * horizonRamp;
        if (elevation > 0.0f && elevation < 0.35f) {
            baseStrength = Math.min(1.0f, baseStrength * 1.5f); // Dawn/dusk drama boost.
        }
        return baseStrength;
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
        // Cleanup post-processing pipeline
        if (postPipeline != null) {
            postPipeline.cleanup();
        }

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
        if (skijaBackend != null) {
            // Cleanup is best-effort on shutdown — swallow failures to avoid masking any
            // earlier error that caused the shutdown path to be reached.
            try {
                com.stonebreak.rendering.UI.masonryUI.textures.MTextureRegistry.disposeAll();
            } catch (Throwable t) {
                System.err.println("[Renderer] MTexture dispose failed: " + t.getMessage());
            }
            try {
                skijaBackend.dispose();
            } catch (Throwable t) {
                System.err.println("[Renderer] Skija dispose failed: " + t.getMessage());
            }
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
     * Exposes the debug renderer for batched debug-line drawing (model-fitted
     * bounding boxes and AI paths).
     * @return the DebugRenderer instance
     */
    public DebugRenderer getDebugRenderer() {
        return debugRenderer;
    }

    /**
     * Draws a debug wireframe overlay of an entity's model, using the live
     * camera matrices. The overlay re-draws the animated model mesh, so it
     * tracks the entity exactly rather than approximating it with a box.
     * @param entity the entity to outline
     * @param color  RGBA line colour
     */
    public void renderEntityWireframe(com.stonebreak.mobs.entities.Entity entity, Vector4f color) {
        com.stonebreak.player.Player player = Game.getPlayer();
        if (player == null) {
            return;
        }
        entityRenderer.renderEntityWireframe(entity, player.getViewMatrix(),
                configManager.getProjectionMatrix(), color);
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

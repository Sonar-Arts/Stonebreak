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
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.BlockTextureArray;

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
    private final BlockTextureArray blockTextureArray;
    private final Matrix4f projectionMatrix;
    
    // Specialized components
    private final PlayerArmAnimator animator;
    private final ArmGeometry armGeometry;
    private final HandItemRenderer handItemRenderer;
    private final PlayerArmTexture armTexture;
    
    // Reusable matrix to avoid allocations
    private final Matrix4f reusableArmViewModel = new Matrix4f();

    // Cached rod-tip position in arm-local space (sprite transform applied to the
    // rod's topmost voxel). Computed once; the per-frame arm animation and camera
    // transform are applied on top in getHeldRodTipWorld().
    private org.joml.Vector3f cachedRodTipModelPoint;
    private boolean rodTipComputeAttempted = false;

    // Fine nudge of the line anchor in camera space (eye units), applied after
    // the rod tip is located, to seat the line exactly on the rod's visual tip.
    // +right moves it right on screen, +up moves it up.
    private static final float ROD_TIP_NUDGE_RIGHT = 0.07f;
    private static final float ROD_TIP_NUDGE_UP    = -0.01f;
    
    /**
     * Creates and initializes the player arm renderer with specialized components.
     */
    public PlayerArmRenderer(ShaderProgram shaderProgram, BlockTextureArray blockTextureArray, Matrix4f projectionMatrix,
                             com.openmason.engine.rendering.cbr.models.BlockDefinitionRegistry blockRegistry,
                             com.stonebreak.rendering.sbo.SBOHandMeshRegistry sboHandMeshRegistry) {
        this.shaderProgram = shaderProgram;
        this.blockTextureArray = blockTextureArray;
        this.projectionMatrix = projectionMatrix;

        // Initialize specialized components
        this.animator = new PlayerArmAnimator();
        this.armGeometry = new ArmGeometry();
        this.handItemRenderer = new HandItemRenderer(shaderProgram, blockTextureArray, sboHandMeshRegistry);
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

        // Sample world light at the player's eye so the arm/held item darken in
        // caves and night. Reset to -1 at the end so subsequent draws fall back
        // to their per-vertex light (terrain must not inherit this override).
        shaderProgram.setUniform("u_playerLight", samplePlayerLight(player));

        // Reset transformation matrix
        reusableArmViewModel.identity();
        
        // Determine what item to display
        ItemStack selectedItem = getSelectedItem(player);
        ItemDisplayInfo displayInfo = determineItemDisplay(selectedItem);

        // Apply all animations through the animator component (with item context for attack animations)
        animator.applyAnimations(reusableArmViewModel, player, selectedItem);
        
        // Apply item-specific transformations if displaying an item. A drawn bow
        // blends toward the side-on aiming pose; everything else passes 0.
        if (displayInfo.displayingItem) {
            float bowDrawProgress = (displayInfo.selectedItemType == ItemType.BOW && player.isDrawingBow())
                    ? player.getBowDrawProgress()
                    : 0.0f;
            animator.applyItemTransform(reusableArmViewModel, bowDrawProgress);
        }

        // Set shader matrices
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", reusableArmViewModel);

        // Render appropriate content based on what's selected
        if (displayInfo.displayingItem) {
            renderSelectedItem(displayInfo);
            renderArrowOverlayIfDrawing(player, displayInfo);
        } else {
            renderDefaultArm();
        }
        
        // Clean up state
        GL30.glBindVertexArray(0);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("u_playerLight", -1.0f);
        shaderProgram.unbind();
    }

    /**
     * World-space position of the held fishing rod's tip, where the fishing line
     * attaches. Rebuilds the exact transform chain the rod is rendered with
     * ({@code armViewModel × spriteTransform}) and maps the rod's top vertex into
     * the world, so the line stays physically attached to the rod as the arm
     * animates (walk bob, idle sway, swing).
     *
     * @return the rod tip in world space, or {@code null} if the rod geometry
     *         isn't available.
     */
    public org.joml.Vector3f getHeldRodTipWorld(Player player) {
        // Reproduce the arm view-model used in renderPlayerArm: animations first,
        // then the held-item pose (no bow draw for a rod).
        Matrix4f armViewModel = new Matrix4f();
        animator.applyAnimations(armViewModel, player, getSelectedItem(player));
        animator.applyItemTransform(armViewModel, 0.0f);

        org.joml.Vector3f modelTip = getRodTipModelPoint(armViewModel);
        if (modelTip == null) {
            return null;
        }

        // armViewModel acts as the view here (camera at origin), so this is the
        // rod tip in eye space. Nudge it onto the rod's visual tip, then map
        // eye → world via the inverse camera view.
        org.joml.Vector3f eyeTip = armViewModel.transformPosition(new org.joml.Vector3f(modelTip));
        eyeTip.x += ROD_TIP_NUDGE_RIGHT;
        eyeTip.y += ROD_TIP_NUDGE_UP;
        Matrix4f invView = new Matrix4f();
        player.getViewMatrix().invert(invView);
        return invView.transformPosition(eyeTip);
    }

    /**
     * The rod's tip in arm-local space (sprite transform applied). Selected as
     * the voxel that sits highest on screen in the rendered pose — i.e. the top
     * of the rod, where the line should hang from. Computed once and cached; the
     * live arm animation is re-applied per frame in {@link #getHeldRodTipWorld}.
     *
     * @param armViewModel the current arm pose, used only to pick the topmost
     *                     voxel on the first call.
     */
    private org.joml.Vector3f getRodTipModelPoint(Matrix4f armViewModel) {
        if (rodTipComputeAttempted) {
            return cachedRodTipModelPoint;
        }
        rodTipComputeAttempted = true;

        com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer.VoxelizationResult result =
                com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer
                        .voxelizeSpriteWithPalette(ItemType.FISHING_ROD, null);
        if (result == null || !result.isValid() || result.getVoxels().isEmpty()) {
            return null;
        }

        Matrix4f sprite = com.stonebreak.rendering.player.items.voxelization.VoxelizedSpriteRenderer
                .getBaseSpriteTransform();
        org.joml.Vector3f best = null;
        float bestEyeY = -Float.MAX_VALUE;
        for (com.stonebreak.rendering.player.items.voxelization.VoxelData voxel : result.getVoxels()) {
            org.joml.Vector3f model = sprite.transformPosition(new org.joml.Vector3f(voxel.getPosition()));
            float eyeY = armViewModel.transformPosition(new org.joml.Vector3f(model)).y;
            if (eyeY > bestEyeY) {
                bestEyeY = eyeY;
                best = model;
            }
        }
        cachedRodTipModelPoint = best;
        return best;
    }

    /**
     * Reads the sky-shadow state at the player's eye cell. Falls back to fully
     * lit when the world isn't available (early boot, menus) so the arm is
     * never mysteriously black.
     */
    private float samplePlayerLight(Player player) {
        try {
            com.stonebreak.world.World world = com.stonebreak.core.Game.getWorld();
            if (world == null) return 1.0f;
            org.joml.Vector3f pos = player.getPosition();
            com.stonebreak.world.lighting.WorldLightingContext ctx =
                new com.stonebreak.world.lighting.WorldLightingContext(world);
            return com.openmason.engine.voxel.lighting.VertexLightSampler.samplePointSky(
                ctx, pos.x, pos.y + 1.6f, pos.z);
        } catch (Exception ignored) {
            // Lighting sampler throws when the player's chunk is unloaded (early boot, menu transitions).
            // Return full brightness so the arm is never rendered pitch-black.
            return 1.0f;
        }
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
            // Bow: override the stack's stored state with the live draw-progress state
            if (selectedItem.asItemType() == ItemType.BOW) {
                info.selectedState = com.stonebreak.core.Game.getPlayer() != null
                        ? com.stonebreak.core.Game.getPlayer().getBowSboState()
                        : selectedItem.getState();
            } else {
                info.selectedState = selectedItem.getState();
            }
            if (selectedItem.isPlaceable()) {
                info.selectedBlockType = selectedItem.asBlockType();
                info.isDisplayingBlock = (info.selectedBlockType != null && 
                                         info.selectedBlockType != BlockType.AIR &&
                                         info.selectedBlockType.getAtlasX() >= 0 && 
                                         info.selectedBlockType.getAtlasY() >= 0);
                info.displayingItem = info.isDisplayingBlock;
            } else if (selectedItem.isTool() || selectedItem.isMaterial() || selectedItem.isFood()) {
                info.selectedItemType = selectedItem.asItemType();
                // Render the item in hand when it has either legacy atlas
                // coords OR is voxelizable (SBO-backed items declare
                // atlasX/Y = -1 and pull pixels from their OMT instead).
                info.isDisplayingTool = info.selectedItemType != null
                        && ((info.selectedItemType.getAtlasX() >= 0 && info.selectedItemType.getAtlasY() >= 0)
                            || com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer
                                    .isVoxelizable(info.selectedItemType));
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
            handItemRenderer.renderToolInHand(displayInfo.selectedItemType, displayInfo.selectedState);
        }
    }

    /**
     * Renders the arrow overlaid on the bow when the player is drawing.
     * Offsets the arrow slightly so it appears nocked on the bow string.
     */
    private void renderArrowOverlayIfDrawing(Player player, ItemDisplayInfo displayInfo) {
        if (displayInfo.selectedItemType != ItemType.BOW) return;
        if (!player.isDrawingBow()) return;
        float progress = player.getBowDrawProgress();
        if (progress <= 0.05f) return;

        // Shift the arrow to sit along the bow centre, nudged forward so it
        // isn't buried in the sprite, then slide it back toward the camera as
        // the string is drawn (+Z = toward viewer) so it visibly retracts.
        float retract = 0.20f * progress;
        Matrix4f arrowMatrix = new Matrix4f(reusableArmViewModel);
        arrowMatrix.translate(0.0f, -0.08f, -0.15f + retract);
        shaderProgram.setUniform("viewMatrix", arrowMatrix);
        handItemRenderer.renderToolInHand(ItemType.ARROW, null);
        // Restore the arm matrix for any subsequent draws
        shaderProgram.setUniform("viewMatrix", reusableArmViewModel);
    }
    
    /**
     * Renders the default arm when no item is selected.
     */
    private void renderDefaultArm() {
        // Set up shader for arm texture. The arm VAO only binds position + uv —
        // it does NOT provide the normal or the packed flags attribute, so we
        // must force the shader into the UI-element path (flat textured, no
        // phong). Otherwise the phong branch would call normalize(outNormal)
        // on an unbound attribute (defaulting to zero) and produce NaN pixels.
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("u_isUIElement", true);
        shaderProgram.setUniform("u_renderPass", 0);

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

        // Restore UI-element flag so subsequent terrain draws use phong.
        shaderProgram.setUniform("u_isUIElement", false);
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
        /** SBO state name for the selected stack (1.3+); null = default. */
        String selectedState = null;
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
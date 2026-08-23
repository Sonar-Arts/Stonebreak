package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.openmason.engine.rendering.shaders.ShaderProgram;

/**
 * Specialized entity renderer managed by the main Renderer: a thin coordinator
 * that owns the per-kind sub-renderers and dispatches each entity to one of them.
 *
 * <p>All AI-driven mobs render through one generic {@link SbeMobRenderer} path
 * keyed off {@code EntityType.getSbeObjectId()} (ground-anchored via the model's
 * rest-pose feet); player-shaped figures (local third-person, remote players,
 * decoys, previews) go through {@link PlayerFigureRenderer}; effect entities
 * through {@link GlowCubeRenderer}; arrows, the bobber and anything else through
 * {@link ArrowRenderer}, {@link BobberRenderer} and {@link FallbackCubeRenderer},
 * all of which draw via the shared {@link SimpleCubePipeline}. The depth-only
 * shadow pass is {@link EntityShadowCasterRenderer}.
 */
public class EntityRenderer {
    private boolean initialized = false;

    // Shared simple-cube shader + VAO for the non-SBE paths.
    private final SimpleCubePipeline simpleCubePipeline = new SimpleCubePipeline();

    // Entity-blind renderer for SBE-driven mobs.
    private final SbeEntityRenderer sbeEntityRenderer = new SbeEntityRenderer();

    // Renderer for multiplayer remote players (cylinder).
    private final RemotePlayerRenderer remotePlayerRenderer = new RemotePlayerRenderer();

    private final EntityAttachmentRenderer attachmentRenderer = new EntityAttachmentRenderer(sbeEntityRenderer);
    private final SbeMobRenderer mobRenderer = new SbeMobRenderer(sbeEntityRenderer, attachmentRenderer);
    private final PlayerFigureRenderer playerFigureRenderer =
            new PlayerFigureRenderer(sbeEntityRenderer, remotePlayerRenderer, attachmentRenderer);
    private final FallbackCubeRenderer fallbackCubeRenderer = new FallbackCubeRenderer(simpleCubePipeline);
    private final ArrowRenderer arrowRenderer = new ArrowRenderer(simpleCubePipeline);
    private final GlowCubeRenderer glowCubeRenderer = new GlowCubeRenderer(simpleCubePipeline);
    private final BobberRenderer bobberRenderer = new BobberRenderer(sbeEntityRenderer, fallbackCubeRenderer);
    private final EntityShadowCasterRenderer shadowCasterRenderer =
            new EntityShadowCasterRenderer(sbeEntityRenderer, mobRenderer);

    /**
     * Initialize the entity renderer. Called by the main Renderer.
     */
    public void initialize() {
        if (initialized) return;

        simpleCubePipeline.initialize();
        fallbackCubeRenderer.initialize();
        glowCubeRenderer.initialize();
        arrowRenderer.initialize();
        sbeEntityRenderer.initialize();
        remotePlayerRenderer.initialize();
        initialized = true;
    }

    /**
     * Wires up the voxelized-sprite renderer used for arrow projectiles.
     * Must be called after initialize() and after the main scene shader is ready.
     */
    public void initializeArrowRenderer(ShaderProgram mainSceneShader) {
        arrowRenderer.initializeVoxelRenderer(mainSceneShader);
    }

    /**
     * Wires the cascaded-shadow state so entities receive sun shadows.
     * Called by WorldRenderer once at construction.
     */
    public void setShadowMapRenderer(com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer renderer) {
        sbeEntityRenderer.setShadowMapRenderer(renderer);
    }

    /**
     * Render an entity. Called by the main Renderer.
     */
    public void renderEntity(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        renderEntity(entity, viewMatrix, projectionMatrix, null, null);
    }

    /**
     * Render an entity with underwater fog support.
     *
     * @param entity           The entity to render
     * @param viewMatrix       The view matrix
     * @param projectionMatrix The projection matrix
     * @param world            The world (for underwater detection), can be null
     * @param cameraPos        The camera position (for fog distance), can be null
     */
    public void renderEntity(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                            com.stonebreak.world.World world, Vector3f cameraPos) {
        if (!initialized || !entity.isAlive()) return;
        if (!isInRenderableChunk(entity, world)) return;

        EntityType entityType = entity.getType();

        if (entityType == EntityType.REMOTE_PLAYER
                && entity instanceof com.stonebreak.mobs.entities.RemotePlayer rp) {
            // Untextured fallback hue is stable per remote player (same scheme as the cylinder).
            // Body/head come from the shared PlayerBodyOrientation (model space) — the raw
            // replicated rotation.y is a camera yaw and faces the figure the wrong way.
            playerFigureRenderer.renderPlayerModel(rp, rp.getBodyYaw(), rp.getHeadYaw(), rp.getHeadPitch(),
                    new Vector4f(RemotePlayerRenderer.colorFor(rp.getPlayerId()), 1f),
                    viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }

        // Illusion decoys are visual copies of the caster: render them through the SAME player
        // model the local player uses, so any texture/model change to SB_Player.sbe propagates to
        // decoys automatically. The untextured fallback reuses the local player's colour so an
        // untextured decoy matches the caster instead of an obvious "illusion" hue.
        if (entityType == EntityType.ILLUSION_DECOY
                && entity instanceof com.stonebreak.mobs.entities.IllusionDecoy decoy) {
            playerFigureRenderer.renderPlayerModel(decoy, decoy.getBodyYaw(), decoy.getHeadYaw(), decoy.getHeadPitch(),
                    playerFigureRenderer.ensureLocalPlayerColor(),
                    viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }

        // Every SBE-driven mob with an AI renders through this single path — the goose
        // included, now that its flight states live in the shared vocabulary. New mobs
        // need no renderer changes at all.
        if (SbeMobRenderer.handles(entity)) {
            mobRenderer.render((com.stonebreak.mobs.entities.LivingEntity) entity,
                    viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }

        if (GlowCubeRenderer.handles(entityType)) {
            glowCubeRenderer.render(entity, viewMatrix, projectionMatrix, cameraPos);
            return;
        }

        if (entityType == EntityType.ARROW) {
            arrowRenderer.render(entity, viewMatrix, projectionMatrix);
            return;
        }

        if (entityType == EntityType.BOBBER) {
            bobberRenderer.render(entity, viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }

        fallbackCubeRenderer.render(entity, viewMatrix, projectionMatrix, world, cameraPos);
    }

    /**
     * Renders the local player's full body model in third-person view.
     *
     * <p>The local {@link com.stonebreak.player.Player} is not an
     * {@link Entity} so it cannot go through {@link #renderEntity}; this
     * dedicated method drives the SBE pipeline directly with the player's
     * state and animation clock.
     */
    public void renderLocalPlayer(com.stonebreak.player.Player player,
                                  Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                  com.stonebreak.world.World world, Vector3f cameraPos) {
        if (!initialized) return;
        playerFigureRenderer.renderLocalPlayer(player, viewMatrix, projectionMatrix, world, cameraPos);
    }

    /**
     * Renders a glossary/preview pose of an SBE-driven entity into whatever
     * viewport/scissor the caller has set up, using a caller-supplied camera.
     *
     * <p>Unlike {@link #renderEntity}, this needs no live {@link Entity}: the
     * caller picks the appearance variant and SBE animation state directly. The
     * asset is resolved from the type's object id, exactly as the live path does.
     * Intended for UI previews (Entity Glossary), so underwater fog is disabled.
     *
     * @param type        glossary entity type (must be SBE-driven)
     * @param variant     appearance variant name (case-insensitive; unknown → default)
     * @param stateName   SBE animation-state name (unknown/null → rest pose)
     * @param animationTime elapsed clip time in seconds
     * @param position    model-space position to place the origin at
     * @param yawDegrees  Y-axis rotation in degrees
     * @param scale       world scale
     * @param viewMatrix  preview camera view matrix
     * @param projectionMatrix preview camera projection matrix
     */
    public void renderEntityPreview(EntityType type, String variant, String stateName,
                                    float animationTime, Vector3f position, float yawDegrees,
                                    Vector3f scale, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (!initialized || type == null) return;
        mobRenderer.renderPreview(type, variant, stateName, animationTime,
                position, yawDegrees, scale, viewMatrix, projectionMatrix);
    }

    /**
     * Renders a preview pose of the player SBE model into whatever
     * viewport/scissor the caller has set up, using a caller-supplied camera.
     *
     * <p>Counterpart to {@link #renderEntityPreview} for the player, which is not
     * an {@link Entity} and whose asset may be untextured: textured assets render
     * normally and untextured assets fall back to the colored path (otherwise
     * every face is skipped and nothing draws). Intended for UI previews (e.g.
     * character creation), so the head faces forward and underwater fog is disabled.
     *
     * @param stateName     SBE animation-state name (null → rest pose)
     * @param animationTime elapsed clip time in seconds
     * @param position      model-space position to place the origin at
     * @param yawDegrees    Y-axis rotation in degrees
     * @param scale         world scale
     * @param viewMatrix    preview camera view matrix
     * @param projectionMatrix preview camera projection matrix
     */
    public void renderPlayerPreview(String stateName, float animationTime,
                                    Vector3f position, float yawDegrees, Vector3f scale,
                                    Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        renderPlayerPreview(stateName, animationTime, position, yawDegrees, scale,
                viewMatrix, projectionMatrix, null);
    }

    /**
     * Variant with an attachment key so previews can include socket-mounted
     * accessories — the character creation Looks tab passes
     * {@link com.stonebreak.mobs.sbe.EntityAttachments#LOCAL_PLAYER} so the
     * equipped hat shows on the preview model. Null skips attachments.
     */
    public void renderPlayerPreview(String stateName, float animationTime,
                                    Vector3f position, float yawDegrees, Vector3f scale,
                                    Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                    Object attachmentKey) {
        if (!initialized) return;
        playerFigureRenderer.renderPlayerPreview(stateName, animationTime, position, yawDegrees, scale,
                viewMatrix, projectionMatrix, attachmentKey);
    }

    /**
     * Depth-only shadow-caster pass: draws every shadow-casting entity through
     * the SBE flat-colored path into the currently bound shadow framebuffer.
     * The shadow FBO has no color attachment, so only depth lands — the flat
     * color is discarded. Called once per cascade by ShadowMapRenderer, with
     * the cascade's light matrices standing in for view/projection.
     */
    public void renderShadowCasters(com.stonebreak.player.Player player,
                                    Matrix4f lightView, Matrix4f lightProj,
                                    Vector3f cascadeCenter, float cascadeRadius) {
        if (!initialized) return;
        shadowCasterRenderer.render(player, lightView, lightProj, cascadeCenter, cascadeRadius);
    }

    /**
     * Network-shadow entities (remote players, replicated mobs/drops) are intentionally
     * NOT removed when their chunk unloads client-side (the server owns their lifecycle —
     * see EntityManager.removeEntitiesInChunk), so they must be hidden at render time when
     * standing in a chunk this client hasn't streamed/meshed yet. Otherwise they draw
     * floating in the void. Locally-owned entities (bobber, decoy) always render.
     */
    public static boolean isInRenderableChunk(Entity entity, com.stonebreak.world.World world) {
        if (world == null || !entity.isNetworkShadow()) {
            return true;
        }
        Vector3f p = entity.getPosition();
        int cs = com.stonebreak.world.operations.WorldConfiguration.CHUNK_SIZE;
        int cx = Math.floorDiv((int) Math.floor(p.x), cs);
        int cz = Math.floorDiv((int) Math.floor(p.z), cs);
        return world.isChunkRenderableAt(cx, cz);
    }

    /**
     * Draws a debug wireframe overlay of an entity's actual model.
     *
     * <p>Unlike a bounding box, this re-draws the model's own mesh through the
     * same animated transform pipeline used by {@link #renderEntity}, so the
     * overlay always tracks the rendered entity exactly. Supported for SBE-driven
     * mobs (cows, chickens); other entity types are ignored.
     *
     * @param color RGBA line colour for the wireframe
     */
    public void renderEntityWireframe(Entity entity, Matrix4f viewMatrix,
                                      Matrix4f projectionMatrix, Vector4f color) {
        if (!initialized || !entity.isAlive()) return;

        // Same generic SBE-mob bindings (state, clip time, ground anchoring) as
        // renderEntity, so the wireframe tracks the rendered model exactly.
        if (SbeMobRenderer.handles(entity)) {
            mobRenderer.renderWireframe((com.stonebreak.mobs.entities.LivingEntity) entity,
                    viewMatrix, projectionMatrix, color);
        }
    }

    /**
     * Cleanup method called by the main Renderer.
     */
    public void cleanup() {
        if (!initialized) return;

        simpleCubePipeline.cleanup();
        fallbackCubeRenderer.cleanup();
        glowCubeRenderer.cleanup();
        arrowRenderer.cleanup();

        sbeEntityRenderer.cleanup();
        remotePlayerRenderer.cleanup();
        initialized = false;
    }
}

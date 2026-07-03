package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import com.openmason.engine.rendering.shaders.ShaderProgram;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.system.MemoryUtil.*;

/**
 * Specialized entity renderer managed by the main Renderer.
 *
 * <p>All AI-driven mobs render through one generic {@link SbeEntityRenderer}
 * path keyed off {@code EntityType.getSbeObjectId()} (ground-anchored via the
 * model's rest-pose feet); remote players use {@link RemotePlayerRenderer};
 * any other entity type falls back to a simple textured cube.
 */
public class EntityRenderer {
    private ShaderProgram shader;
    private boolean initialized = false;

    // Simple cube model for fallback entities.
    private int simpleCubeVAO;
    private int simpleCubeVBO;
    private int simpleCubeTexVBO;

    // 1x1 white texture for the fallback cube.
    private int fallbackTexture;

    // 1x1 orange texture for the fire bolt core.
    private int fireBoltTexture;

    // 1x1 brown texture for arrow projectiles.
    private int arrowTexture;

    // 1x1 violet texture for the null spike core.
    private int nullSpikeTexture;

    // 1x1 cyan texture for the leyline breach zone slab.
    private int leylineZoneTexture;

    // 1x1 metallic texture for the Rogue's caltrop clusters.
    private int caltropTexture;

    // Entity-blind renderer for SBE-driven mobs.
    private final SbeEntityRenderer sbeEntityRenderer = new SbeEntityRenderer();

    // Renderer for multiplayer remote players (cylinder).
    private final RemotePlayerRenderer remotePlayerRenderer = new RemotePlayerRenderer();

    // Stable-per-session random colour for the local player's untextured body model
    // (see renderLocalPlayer). Lazily initialised on first third-person render.
    private Vector4f localPlayerColor;

    // Voxelized sprite renderer for arrow projectiles (uses the main scene shader).
    private com.stonebreak.rendering.player.items.voxelization.VoxelizedSpriteRenderer arrowVoxelRenderer;

    /**
     * Initialize the entity renderer. Called by the main Renderer.
     */
    public void initialize() {
        if (initialized) return;

        createShader();
        createFallbackTexture();
        createFireBoltTexture();
        createArrowTexture();
        createNullSpikeTexture();
        createLeylineZoneTexture();
        createCaltropTexture();
        createSimpleCubeModel();
        sbeEntityRenderer.initialize();
        remotePlayerRenderer.initialize();
        initialized = true;
    }

    /**
     * Wires up the voxelized-sprite renderer used for arrow projectiles.
     * Must be called after initialize() and after the main scene shader is ready.
     */
    public void initializeArrowRenderer(ShaderProgram mainSceneShader) {
        if (mainSceneShader != null) {
            arrowVoxelRenderer = new com.stonebreak.rendering.player.items.voxelization.VoxelizedSpriteRenderer(mainSceneShader);
        }
    }

    /**
     * Wires the cascaded-shadow state so entities receive sun shadows.
     * Called by WorldRenderer once at construction.
     */
    public void setShadowMapRenderer(com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer renderer) {
        sbeEntityRenderer.setShadowMapRenderer(renderer);
    }

    /**
     * Sets the simple-cube shader's lighting mode for the next draw. Lit geometry
     * (fallback cubes, arrows) samples the world sky light at the entity position;
     * emissive geometry (fire bolts, glow cubes) stays unlit. The shader must be bound.
     */
    private void applySimpleLighting(Entity entity, boolean lit) {
        shader.setBool("u_lightingEnabled", lit);
        if (!lit) {
            return;
        }
        com.stonebreak.world.TimeOfDay timeOfDay = com.stonebreak.core.Game.getTimeOfDay();
        if (timeOfDay != null) {
            shader.setFloat("u_ambientLight", timeOfDay.getAmbientLightLevel());
            shader.setVec3("u_sunDirection", timeOfDay.getSunDirection());
        } else {
            shader.setFloat("u_ambientLight", 1.0f);
            shader.setVec3("u_sunDirection", new Vector3f(0.4f, 0.8f, 0.4f).normalize());
        }
        shader.setFloat("u_entityLight",
                SbeEntityRenderer.sampleEntityLight(com.stonebreak.core.Game.getWorld(), entity.getPosition()));
    }

    private void createShader() {
        String vertexShader = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec2 aTexCoord;

            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;

            out vec2 TexCoord;
            out vec3 FragWorldPos;

            void main() {
                vec4 worldPos = model * vec4(aPos, 1.0);
                FragWorldPos = worldPos.xyz;
                gl_Position = projection * view * worldPos;
                TexCoord = aTexCoord;
            }
            """;

        String fragmentShader = """
            #version 330 core
            out vec4 FragColor;

            in vec2 TexCoord;
            in vec3 FragWorldPos;

            uniform sampler2D textureSampler;
            uniform vec3 cameraPos;
            uniform float underwaterFogDensity;
            uniform vec3 underwaterFogColor;
            // Lighting is opt-in: emissive geometry (fire bolts, glow cubes)
            // keeps rendering unlit; plain entities get sun + world lighting.
            uniform bool u_lightingEnabled;
            uniform float u_ambientLight;
            uniform vec3 u_sunDirection;
            uniform float u_entityLight;

            void main() {
                vec4 texColor = texture(textureSampler, TexCoord);

                if (u_lightingEnabled) {
                    // Flat face normal from screen-space derivatives (no normal attribute).
                    vec3 normal = normalize(cross(dFdx(FragWorldPos), dFdy(FragWorldPos)));
                    float diff = max(dot(normal, normalize(u_sunDirection)), 0.0);
                    float brightness = u_ambientLight * (0.5 + 0.55 * diff);
                    brightness *= mix(0.3, 1.0, u_entityLight);
                    texColor = vec4(texColor.rgb * min(brightness, 1.0), texColor.a);
                }

                if (underwaterFogDensity > 0.0) {
                    float distance = length(FragWorldPos - cameraPos);
                    float fogFactor = exp(-underwaterFogDensity * distance);
                    fogFactor = clamp(fogFactor, 0.0, 1.0);
                    FragColor = mix(vec4(underwaterFogColor, texColor.a), texColor, fogFactor);
                } else {
                    FragColor = texColor;
                }
            }
            """;

        try {
            shader = new ShaderProgram();
            shader.createVertexShader(vertexShader);
            shader.createFragmentShader(fragmentShader);
            shader.link();

            shader.createUniform("model");
            shader.createUniform("view");
            shader.createUniform("projection");
            shader.createUniform("textureSampler");
            shader.createUniform("cameraPos");
            shader.createUniform("underwaterFogDensity");
            shader.createUniform("underwaterFogColor");
            shader.bind();
            shader.setBool("u_lightingEnabled", false);
            shader.setFloat("u_ambientLight", 1.0f);
            shader.setVec3("u_sunDirection", new Vector3f(0.4f, 0.8f, 0.4f).normalize());
            shader.setFloat("u_entityLight", 1.0f);
            shader.unbind();
        } catch (Exception e) {
            System.err.println("Failed to create entity shader: " + e.getMessage());
        }
    }

    private void createFallbackTexture() {
        fallbackTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fallbackTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        ByteBuffer whitePixel = ByteBuffer.allocateDirect(4);
        whitePixel.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, whitePixel);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void createArrowTexture() {
        arrowTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, arrowTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        pixel.put((byte) 139).put((byte) 90).put((byte) 43).put((byte) 255).flip(); // saddle-brown
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void createFireBoltTexture() {
        fireBoltTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fireBoltTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        // Bright orange-yellow for the fire bolt core
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        pixel.put((byte) 255).put((byte) 140).put((byte) 0).put((byte) 255).flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void createNullSpikeTexture() {
        nullSpikeTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, nullSpikeTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        // Arcane violet for the null spike core
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        pixel.put((byte) 178).put((byte) 102).put((byte) 255).put((byte) 255).flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void createLeylineZoneTexture() {
        leylineZoneTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, leylineZoneTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        // Translucent arcane cyan for the zone slab (alpha matters under additive blend)
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        pixel.put((byte) 64).put((byte) 210).put((byte) 255).put((byte) 110).flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void createCaltropTexture() {
        caltropTexture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, caltropTexture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        // Cool metallic steel with a faint emissive lift so clusters read in low light (additive blend).
        ByteBuffer pixel = ByteBuffer.allocateDirect(4);
        pixel.put((byte) 150).put((byte) 160).put((byte) 175).put((byte) 200).flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private void createSimpleCubeModel() {
        // Simple cube for fallback entity rendering
        float[] vertices = {
            // Front face
            -0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
            // Back face
            -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  -0.5f,  0.5f, -0.5f,
            // Left face
            -0.5f, -0.5f, -0.5f, -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  -0.5f,  0.5f, -0.5f,
            // Right face
             0.5f, -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f,   0.5f,  0.5f, -0.5f,
            // Top face
            -0.5f,  0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  -0.5f,  0.5f,  0.5f,
            // Bottom face
            -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f, -0.5f,  0.5f,  -0.5f, -0.5f,  0.5f
        };

        float[] texCoords = {
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Front
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Back
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Left
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Right
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f, // Top
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f  // Bottom
        };

        simpleCubeVAO = GL30.glGenVertexArrays();
        simpleCubeVBO = GL15.glGenBuffers();
        simpleCubeTexVBO = GL15.glGenBuffers();

        GL30.glBindVertexArray(simpleCubeVAO);

        FloatBuffer vertexBuffer = memAllocFloat(vertices.length);
        vertexBuffer.put(vertices).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, simpleCubeVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);

        FloatBuffer texCoordBuffer = memAllocFloat(texCoords.length);
        texCoordBuffer.put(texCoords).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, simpleCubeTexVBO);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, texCoordBuffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(1);

        GL30.glBindVertexArray(0);

        memFree(vertexBuffer);
        memFree(texCoordBuffer);
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
            renderPlayerModel(rp, rp.getBodyYaw(), rp.getHeadYaw(), rp.getHeadPitch(),
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
            renderPlayerModel(decoy, decoy.getBodyYaw(), decoy.getHeadYaw(), decoy.getHeadPitch(),
                    ensureLocalPlayerColor(),
                    viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }

        // Every SBE-driven mob with an AI renders through this single path: the
        // asset comes from the registry by the type's object id, the clip name
        // from the shared MobStateMapping (with MobAI.clipTime handling one-shot
        // states like the wing flap), and the model is ground-anchored so its
        // feet rest on the collision ground regardless of authored origin. New
        // mobs need no renderer changes at all.
        if (entityType.getSbeObjectId() != null
                && entity instanceof com.stonebreak.mobs.entities.LivingEntity mob
                && mob.getAI() != null) {
            com.stonebreak.mobs.sbe.SbeEntityAsset asset =
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(entityType.getSbeObjectId());
            String stateName =
                    com.stonebreak.mobs.sbe.MobStateMapping.sbeState(mob.getAI().getCurrentState());
            float clipTime =
                    mob.getAI().clipTime(mob.getAnimationController().getTotalAnimationTime());
            Vector3f anchoredPos = groundAnchoredPosition(mob, asset);
            sbeEntityRenderer.render(
                    asset,
                    mob.getTextureVariant(),
                    stateName,
                    clipTime,
                    anchoredPos,
                    mob.getRotation().y,
                    mob.getScale(),
                    viewMatrix, projectionMatrix, world, cameraPos);
            renderAttachments(mob, asset, mob.getTextureVariant(),
                    com.stonebreak.mobs.sbe.AnimState.single(stateName, clipTime),
                    anchoredPos, mob.getRotation().y, mob.getScale(), 0f, 0f,
                    viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }

        if (entityType == EntityType.FIRE_BOLT) {
            renderFireBolt(entity, viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }

        if (entityType == EntityType.NULL_SPIKE) {
            renderGlowCube(entity, nullSpikeTexture, 1.8f, viewMatrix, projectionMatrix, cameraPos);
            return;
        }

        if (entityType == EntityType.LEYLINE_BREACH_ZONE) {
            renderGlowCube(entity, leylineZoneTexture, 1.0f, viewMatrix, projectionMatrix, cameraPos);
            return;
        }

        if (entityType == EntityType.CALTROP_CLUSTER) {
            renderGlowCube(entity, caltropTexture, 1.4f, viewMatrix, projectionMatrix, cameraPos);
            return;
        }

        if (entityType == EntityType.ARROW) {
            renderArrow(entity, viewMatrix, projectionMatrix);
            return;
        }

        if (entityType == EntityType.BOBBER) {
            renderBobber(entity, viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }

        renderSimpleEntity(entity, viewMatrix, projectionMatrix, world, cameraPos);
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
        com.stonebreak.mobs.sbe.SbeEntityAsset asset =
                com.stonebreak.mobs.sbe.SbeEntityRegistry.get(
                        com.stonebreak.mobs.entities.EntityType.REMOTE_PLAYER.getSbeObjectId());
        if (asset == null) return;

        // The BASE clip is pure locomotion (jump one-shots use event-relative
        // time; looping walk uses the continuous clock). Attacking plays as an
        // OVERLAY on top: it owns only the parts its clip masks (authored in
        // the .omanim layer metadata), so the legs keep walking mid-swing. The
        // overlay envelope handles fade-in and pop-free early-exit fade-out.
        // Body facing and head angles are owned by PlayerBodyOrientation.
        String overlayState = null;
        float overlayTime = 0f;
        float overlayWeight = 0f;
        com.stonebreak.mobs.sbe.OverlayAnimState attackOverlay = player.getAttackOverlay();
        if (attackOverlay.isVisible()) {
            overlayState = com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(
                    com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.ATTACKING);
            com.openmason.engine.format.oma.ParsedAnimClip attackClip = asset.clipFor(overlayState);
            if (attackClip != null) {
                overlayTime = attackOverlay.time();
                overlayWeight = attackOverlay.weight(
                        attackClip.layer().fadeInSeconds(), attackClip.layer().fadeOutSeconds());
            }
        }

        PlayerFigureRenderState figure = new PlayerFigureRenderState(
                player.getPosition(),
                player.getBodyYaw(),
                new Vector3f(1f, 1f, 1f),
                player.getThirdPersonHeadYaw(),
                player.getThirdPersonHeadPitch(),
                com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(player.getBaseMovementState()),
                player.getBodyEventTime(),
                overlayState,
                overlayTime,
                overlayWeight,
                ensureLocalPlayerColor());

        renderPlayerFigure(asset, figure, viewMatrix, projectionMatrix, world, cameraPos,
                com.stonebreak.mobs.sbe.EntityAttachments.LOCAL_PLAYER);
    }

    /**
     * Renders a {@link com.stonebreak.mobs.entities.RemotePlayer} (or its
     * {@code IllusionDecoy} subclass) through the shared player SBE model. The
     * model is always resolved from {@link EntityType#REMOTE_PLAYER}'s asset id,
     * so any texture/geometry change to {@code SB_Player.sbe} applies to every
     * player-shaped figure — remote players and decoys alike — without copying
     * anything per entity. Falls back to the cylinder if the asset is missing so
     * the figure never goes invisible.
     */
    private void renderPlayerModel(com.stonebreak.mobs.entities.RemotePlayer rp,
                                   float yaw, float headYaw, float headPitch,
                                   Vector4f untexturedTint,
                                   Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                   com.stonebreak.world.World world, Vector3f cameraPos) {
        com.stonebreak.mobs.sbe.SbeEntityAsset asset =
                com.stonebreak.mobs.sbe.SbeEntityRegistry.get(
                        EntityType.REMOTE_PLAYER.getSbeObjectId());
        if (asset == null) {
            // Asset not loaded — fall back to cylinder so the figure never goes invisible.
            remotePlayerRenderer.render(rp, viewMatrix, projectionMatrix);
            return;
        }
        // Attack overlay from the replicated ATTACKING flag — same envelope + clip-fade
        // computation as the local player's third-person path (renderLocalPlayer), so
        // remote swings render identically to your own.
        String overlayState = null;
        float overlayTime = 0f;
        float overlayWeight = 0f;
        com.stonebreak.mobs.sbe.OverlayAnimState attackOverlay = rp.getAttackOverlay();
        if (attackOverlay.isVisible()) {
            overlayState = com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(
                    com.stonebreak.mobs.sbe.PlayerStateMapping.PlayerMovementState.ATTACKING);
            com.openmason.engine.format.oma.ParsedAnimClip attackClip = asset.clipFor(overlayState);
            if (attackClip != null) {
                overlayTime = attackOverlay.time();
                overlayWeight = attackOverlay.weight(
                        attackClip.layer().fadeInSeconds(), attackClip.layer().fadeOutSeconds());
            } else {
                overlayState = null;
            }
        }

        PlayerFigureRenderState figure = new PlayerFigureRenderState(
                rp.getPosition(),
                yaw,
                rp.getScale(),
                headYaw,
                headPitch,
                com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(rp.getMovementState()),
                rp.getAnimationController().getTotalAnimationTime(),
                overlayState,
                overlayTime,
                overlayWeight,
                untexturedTint);

        renderPlayerFigure(asset, figure, viewMatrix, projectionMatrix, world, cameraPos, rp);
    }

    /**
     * The single path that maps a {@link PlayerFigureRenderState} onto the SBE
     * pipeline — shared by the local player, remote players, and decoys. Textured
     * assets render normally; untextured assets fall back to {@code figure.tint()}
     * via the colored path (the textured path would skip every face and render
     * nothing).
     */
    private void renderPlayerFigure(com.stonebreak.mobs.sbe.SbeEntityAsset asset,
                                    PlayerFigureRenderState figure,
                                    Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                    com.stonebreak.world.World world, Vector3f cameraPos) {
        renderPlayerFigure(asset, figure, viewMatrix, projectionMatrix, world, cameraPos, null);
    }

    /**
     * Variant with an attachment key: models attached to that key's sockets
     * ({@link com.stonebreak.mobs.sbe.EntityAttachments}) render after the
     * figure; null skips attachments (UI previews).
     */
    private void renderPlayerFigure(com.stonebreak.mobs.sbe.SbeEntityAsset asset,
                                    PlayerFigureRenderState figure,
                                    Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                    com.stonebreak.world.World world, Vector3f cameraPos,
                                    Object attachmentKey) {
        com.stonebreak.mobs.sbe.AnimState anim = figure.hasOverlay()
                ? new com.stonebreak.mobs.sbe.AnimState(figure.stateName(), figure.animTime(),
                        java.util.List.of(new com.stonebreak.mobs.sbe.AnimState.Overlay(
                                figure.overlayState(), figure.overlayTime(), figure.overlayWeight())))
                : com.stonebreak.mobs.sbe.AnimState.single(figure.stateName(), figure.animTime());

        if (isTextured(asset)) {
            sbeEntityRenderer.render(
                    asset, com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                    anim, figure.position(), figure.yaw(), figure.scale(),
                    viewMatrix, projectionMatrix, world, cameraPos, figure.headYaw(), figure.headPitch());
        } else {
            sbeEntityRenderer.renderColored(
                    asset, com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                    anim, figure.position(), figure.yaw(), figure.scale(),
                    viewMatrix, projectionMatrix, figure.tint(), figure.headYaw(), figure.headPitch());
        }

        if (attachmentKey != null) {
            renderAttachments(attachmentKey, asset,
                    com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT, anim,
                    figure.position(), figure.yaw(), figure.scale(),
                    figure.headYaw(), figure.headPitch(),
                    viewMatrix, projectionMatrix, world, cameraPos);
        }
    }

    /** Flat fallback tint for attached models whose OMO carries no materials. */
    private static final Vector4f ATTACHMENT_FALLBACK_COLOR = new Vector4f(0.85f, 0.85f, 0.85f, 1f);

    /**
     * Draws every model attached to {@code entityKey}'s sockets
     * ({@link com.stonebreak.mobs.sbe.EntityAttachments}), posed at the socket's
     * world frame for the host's current animation state — so attachments track
     * walk/graze/head-turn exactly. Sockets that no longer resolve to a host
     * part are skipped (never drawn at the model origin).
     */
    private void renderAttachments(Object entityKey,
                                   com.stonebreak.mobs.sbe.SbeEntityAsset hostAsset,
                                   String variantName, com.stonebreak.mobs.sbe.AnimState anim,
                                   Vector3f position, float yawDegrees, Vector3f scale,
                                   float headYawDeg, float headPitchDeg,
                                   Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                   com.stonebreak.world.World world, Vector3f cameraPos) {
        java.util.List<com.stonebreak.mobs.sbe.EntityAttachments.Attached> attached =
                com.stonebreak.mobs.sbe.EntityAttachments.get(entityKey);
        if (attached.isEmpty() || hostAsset == null) return;

        Matrix4f base = SbePoseSolver.baseMatrix(position, yawDegrees, scale);
        Matrix4f socket = new Matrix4f();
        for (com.stonebreak.mobs.sbe.EntityAttachments.Attached a : attached) {
            if (SbePoseSolver.socketWorldMatrix(hostAsset, variantName, anim, base,
                    headYawDeg, headPitchDeg, a.socketName(), socket) == null) {
                continue;
            }
            if (isTextured(a.asset())) {
                sbeEntityRenderer.render(a.asset(),
                        com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT, null, socket,
                        viewMatrix, projectionMatrix, world, cameraPos, 0f, 0f);
            } else {
                sbeEntityRenderer.renderColored(a.asset(),
                        com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT, null, socket,
                        viewMatrix, projectionMatrix, ATTACHMENT_FALLBACK_COLOR, 0f, 0f);
            }
        }
    }

    /**
     * Stable-per-session colour for the local player's untextured body model.
     * Decoys reuse this so an untextured decoy matches its caster. Lazily
     * initialised on first use.
     */
    private Vector4f ensureLocalPlayerColor() {
        if (localPlayerColor == null) {
            localPlayerColor = new Vector4f(
                    RemotePlayerRenderer.colorFor(new java.util.Random().nextInt()), 1f);
        }
        return localPlayerColor;
    }

    /**
     * Whether an SBE asset has baked textures. Untextured assets (geometry only,
     * no materials) must be drawn via {@link SbeEntityRenderer#renderColored};
     * the textured path would skip every face and render nothing.
     */
    private static boolean isTextured(com.stonebreak.mobs.sbe.SbeEntityAsset asset) {
        com.stonebreak.mobs.sbe.SbeModelGeometry geometry = asset == null ? null
                : asset.geometryFor(com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT);
        return geometry != null && !geometry.materials().isEmpty();
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
        com.stonebreak.mobs.sbe.SbeEntityAsset asset =
                com.stonebreak.mobs.sbe.SbeEntityRegistry.get(type.getSbeObjectId());
        if (asset == null) return;
        sbeEntityRenderer.render(asset, variant, stateName, animationTime,
                position, yawDegrees, scale, viewMatrix, projectionMatrix, null, null);
    }

    /**
     * Renders a preview pose of the player SBE model into whatever
     * viewport/scissor the caller has set up, using a caller-supplied camera.
     *
     * <p>Counterpart to {@link #renderEntityPreview} for the player, which is not
     * an {@link Entity} and whose asset may be untextured. Reuses
     * {@link #renderPlayerFigure} so textured assets render normally and untextured
     * assets fall back to the colored path (otherwise every face is skipped and
     * nothing draws). Intended for UI previews (e.g. character creation), so the
     * head faces forward and underwater fog is disabled.
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
        com.stonebreak.mobs.sbe.SbeEntityAsset asset =
                com.stonebreak.mobs.sbe.SbeEntityRegistry.get(
                        EntityType.REMOTE_PLAYER.getSbeObjectId());
        if (asset == null) return;
        PlayerFigureRenderState figure = new PlayerFigureRenderState(
                position, yawDegrees, scale, 0f, 0f, stateName, animationTime,
                ensureLocalPlayerColor());
        renderPlayerFigure(asset, figure, viewMatrix, projectionMatrix, null, null,
                attachmentKey);
    }

    /** Flat white used by the depth-only shadow-caster path (color output is discarded). */
    private static final Vector4f SHADOW_CASTER_COLOR = new Vector4f(1f, 1f, 1f, 1f);

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

        float cullRadius = cascadeRadius + 8.0f;
        float cullRadiusSq = cullRadius * cullRadius;
        com.stonebreak.mobs.entities.EntityManager entityManager =
                com.stonebreak.core.Game.getEntityManager();
        com.stonebreak.world.World world = com.stonebreak.core.Game.getWorld();
        if (entityManager != null) {
            for (Entity entity : entityManager.getAllEntities()) {
                if (!entity.isAlive()) continue;
                if (!isInRenderableChunk(entity, world)) continue;
                Vector3f pos = entity.getPosition();
                float dx = pos.x - cascadeCenter.x;
                float dz = pos.z - cascadeCenter.z;
                if (dx * dx + dz * dz > cullRadiusSq) continue;
                renderEntityShadow(entity, lightView, lightProj);
            }
        }

        // The local player always casts — including first person, where the body
        // model isn't drawn to screen but its shadow still should be.
        if (player != null) {
            com.stonebreak.mobs.sbe.SbeEntityAsset asset =
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(
                            EntityType.REMOTE_PLAYER.getSbeObjectId());
            if (asset != null) {
                sbeEntityRenderer.renderColored(asset,
                        com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                        com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(player.getBaseMovementState()),
                        player.getBodyEventTime(),
                        player.getPosition(), player.getBodyYaw(), new Vector3f(1f, 1f, 1f),
                        lightView, lightProj, SHADOW_CASTER_COLOR);
            }
        }
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

    /** Depth-only draw of one entity, mirroring {@link #renderEntity}'s SBE bindings. */
    private void renderEntityShadow(Entity entity, Matrix4f lightView, Matrix4f lightProj) {
        EntityType type = entity.getType();

        // Same generic SBE-mob path as renderEntity, through the flat-colored
        // depth-only route (color output is discarded by the shadow FBO).
        if (type.getSbeObjectId() != null
                && entity instanceof com.stonebreak.mobs.entities.LivingEntity mob
                && mob.getAI() != null) {
            com.stonebreak.mobs.sbe.SbeEntityAsset asset =
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(type.getSbeObjectId());
            sbeEntityRenderer.renderColored(
                    asset,
                    mob.getTextureVariant(),
                    com.stonebreak.mobs.sbe.MobStateMapping.sbeState(mob.getAI().getCurrentState()),
                    mob.getAI().clipTime(mob.getAnimationController().getTotalAnimationTime()),
                    groundAnchoredPosition(mob, asset), mob.getRotation().y, mob.getScale(),
                    lightView, lightProj, SHADOW_CASTER_COLOR);
            return;
        }

        if ((type == EntityType.REMOTE_PLAYER || type == EntityType.ILLUSION_DECOY)
                && entity instanceof com.stonebreak.mobs.entities.RemotePlayer rp) {
            com.stonebreak.mobs.sbe.SbeEntityAsset asset =
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(
                            EntityType.REMOTE_PLAYER.getSbeObjectId());
            if (asset != null) {
                sbeEntityRenderer.renderColored(asset,
                        com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                        com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(rp.getMovementState()),
                        rp.getAnimationController().getTotalAnimationTime(),
                        rp.getPosition(), rp.getBodyYaw(), rp.getScale(),
                        lightView, lightProj, SHADOW_CASTER_COLOR);
            }
        }
        // Everything else (drops, projectiles, effect volumes) doesn't cast.
    }

    /**
     * The render position that puts the mob model's rest-pose feet
     * ({@code geometry.restMinY}) exactly on the collision ground plane at
     * {@code position.y - legHeight}. This is the model-placement contract for
     * all mobs: a model authored with its origin {@code legHeight} above its
     * feet gets a zero offset; any other authoring (e.g. origin at the feet)
     * is corrected here instead of floating or sinking.
     */
    private static Vector3f groundAnchoredPosition(com.stonebreak.mobs.entities.LivingEntity mob,
                                                   com.stonebreak.mobs.sbe.SbeEntityAsset asset) {
        Vector3f position = mob.getPosition();
        com.stonebreak.mobs.sbe.SbeModelGeometry geometry =
                asset == null ? null : asset.geometryFor(mob.getTextureVariant());
        if (geometry == null) {
            return position;
        }
        float offset = -mob.getLegHeight() - geometry.restMinY() * mob.getScale().y;
        return offset == 0f ? position
                : new Vector3f(position.x, position.y + offset, position.z);
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

        EntityType entityType = entity.getType();

        // Same generic SBE-mob bindings (state, clip time, ground anchoring) as
        // renderEntity, so the wireframe tracks the rendered model exactly.
        if (entityType.getSbeObjectId() != null
                && entity instanceof com.stonebreak.mobs.entities.LivingEntity mob
                && mob.getAI() != null) {
            com.stonebreak.mobs.sbe.SbeEntityAsset asset =
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(entityType.getSbeObjectId());
            sbeEntityRenderer.renderWireframe(
                    asset,
                    mob.getTextureVariant(),
                    com.stonebreak.mobs.sbe.MobStateMapping.sbeState(mob.getAI().getCurrentState()),
                    mob.getAI().clipTime(mob.getAnimationController().getTotalAnimationTime()),
                    groundAnchoredPosition(mob, asset),
                    mob.getRotation().y,
                    mob.getScale(),
                    viewMatrix, projectionMatrix, color);
        }
    }

    /**
     * Renders an arrow as an elongated brown cylinder approximated with a scaled cube.
     * Rotated to face the arrow's velocity direction (stored in entity.rotation.y).
     */
    private void renderArrow(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        shader.bind();

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, arrowTexture);
        shader.setUniform("textureSampler", 0);
        shader.setUniform("view", viewMatrix);
        shader.setUniform("projection", projectionMatrix);
        shader.setUniform("cameraPos", new Vector3f(0, 0, 0));
        shader.setUniform("underwaterFogDensity", 0.0f);
        shader.setUniform("underwaterFogColor", new Vector3f(0.1f, 0.3f, 0.5f));
        applySimpleLighting(entity, true);

        // Elongated along local Z (direction of travel); yaw from rotation.y
        Matrix4f modelMatrix = new Matrix4f()
                .translate(entity.getPosition())
                .rotateY((float) Math.toRadians(entity.getRotation().y))
                .scale(0.06f, 0.06f, 0.5f);
        shader.setUniform("model", modelMatrix);

        GL30.glBindVertexArray(simpleCubeVAO);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, 24);
        GL30.glBindVertexArray(0);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.unbind();
    }

    private void renderSimpleEntity(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                    com.stonebreak.world.World world, Vector3f cameraPos) {
        shader.bind();

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fallbackTexture);
        shader.setUniform("textureSampler", 0);

        shader.setUniform("view", viewMatrix);
        shader.setUniform("projection", projectionMatrix);

        float fogDensity = 0.0f;
        Vector3f fogColor = new Vector3f(0.1f, 0.3f, 0.5f);
        if (world != null && cameraPos != null
                && world.isPositionUnderwater((int) Math.floor(cameraPos.x),
                        (int) Math.floor(cameraPos.y), (int) Math.floor(cameraPos.z))) {
            fogDensity = 0.15f;
        }

        shader.setUniform("cameraPos", cameraPos != null ? cameraPos : new Vector3f(0, 0, 0));
        shader.setUniform("underwaterFogDensity", fogDensity);
        shader.setUniform("underwaterFogColor", fogColor);
        applySimpleLighting(entity, true);

        Matrix4f modelMatrix = new Matrix4f()
            .translate(entity.getPosition())
            .rotateY((float) Math.toRadians(entity.getRotation().y))
            .scale(entity.getScale());

        shader.setUniform("model", modelMatrix);

        GL30.glBindVertexArray(simpleCubeVAO);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, 24); // 6 faces × 4 vertices
        GL30.glBindVertexArray(0);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.unbind();
    }

    private void renderFireBolt(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                com.stonebreak.world.World world, Vector3f cameraPos) {
        // Once the bolt has impacted, the solid core is gone — only the fading
        // trail/impact particles remain (drawn by WorldRenderer).
        if (entity instanceof com.stonebreak.mobs.entities.FireBolt bolt && bolt.isImpacted()) {
            return;
        }

        renderGlowCube(entity, fireBoltTexture, 1.8f, viewMatrix, projectionMatrix, cameraPos);
    }

    /**
     * Draws an entity as an additively blended emissive cube (fire bolts, null spikes,
     * leyline zone slabs), with an optional larger outer glow layer.
     *
     * @param glowScale scale multiplier for the outer glow pass; {@code <= 1} skips it
     */
    private void renderGlowCube(Entity entity, int texture, float glowScale,
                                Matrix4f viewMatrix, Matrix4f projectionMatrix, Vector3f cameraPos) {
        shader.bind();

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        shader.setUniform("textureSampler", 0);
        shader.setUniform("view", viewMatrix);
        shader.setUniform("projection", projectionMatrix);

        shader.setUniform("cameraPos", cameraPos != null ? cameraPos : new Vector3f(0, 0, 0));
        shader.setUniform("underwaterFogDensity", 0.0f);
        shader.setUniform("underwaterFogColor", new Vector3f(0.1f, 0.3f, 0.5f));
        applySimpleLighting(entity, false); // emissive — never world-lit

        Matrix4f modelMatrix = new Matrix4f()
                .translate(entity.getPosition())
                .rotateY((float) Math.toRadians(entity.getRotation().y))
                .scale(entity.getScale());
        shader.setUniform("model", modelMatrix);

        // Additive blending gives a glow/emissive look
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);

        GL30.glBindVertexArray(simpleCubeVAO);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, 24);

        if (glowScale > 1f) {
            // Render a larger, semi-transparent outer glow layer. Keep simpleCubeVAO
            // bound across both draws — unbinding between them (then calling
            // glDrawArrays with no VAO) is undefined and on some drivers picks up
            // whichever VAO another renderer last used. After the player switches
            // held items mid-flight, that "last VAO" becomes the new held item's
            // mesh, so the glow quads sample its vertex buffer and stretch the bolt
            // across the screen toward the hand position (issue #177).
            Matrix4f glowMatrix = new Matrix4f()
                    .translate(entity.getPosition())
                    .rotateY((float) Math.toRadians(entity.getRotation().y))
                    .scale(new Vector3f(entity.getScale()).mul(glowScale));
            shader.setUniform("model", glowMatrix);
            GL11.glDrawArrays(GL11.GL_QUADS, 0, 24);
        }
        GL30.glBindVertexArray(0);

        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        shader.unbind();
    }

    // Cached bobber SBE asset — loaded once on first render.
    private com.stonebreak.mobs.sbe.SbeEntityAsset bobberAsset;
    private boolean bobberAssetLoadAttempted = false;

    private void renderBobber(Entity entity, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              com.stonebreak.world.World world, Vector3f cameraPos) {
        if (!bobberAssetLoadAttempted) {
            bobberAssetLoadAttempted = true;
            try {
                bobberAsset = com.stonebreak.mobs.sbe.SbeEntityLoader.load("/sbe/Mobs/SB_Bobber.sbe");
            } catch (Exception e) {
                System.err.println("[EntityRenderer] Failed to load bobber SBE: " + e.getMessage());
            }
        }

        // Apply the gentle bob offset as a render-time Y offset (does not affect physics).
        float bobY = (entity instanceof com.stonebreak.mobs.entities.FishingBobber fb)
                ? fb.getBobOffset() : 0f;
        Vector3f renderPos = new Vector3f(entity.getPosition()).add(0, bobY, 0);

        if (bobberAsset != null) {
            try {
                sbeEntityRenderer.render(
                        bobberAsset,
                        com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                        null,
                        0.0f,
                        renderPos,
                        entity.getRotation().y,
                        entity.getScale(),
                        viewMatrix, projectionMatrix, world, cameraPos);
            } catch (Exception e) {
                System.err.println("[EntityRenderer] Bobber render failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                e.printStackTrace(System.err);
                renderSimpleEntity(entity, viewMatrix, projectionMatrix, world, cameraPos);
            }
        } else {
            renderSimpleEntity(entity, viewMatrix, projectionMatrix, world, cameraPos);
        }
    }

    /**
     * Cleanup method called by the main Renderer.
     */
    public void cleanup() {
        if (!initialized) return;

        if (shader != null) {
            shader.cleanup();
        }
        if (simpleCubeVAO != 0) {
            GL30.glDeleteVertexArrays(simpleCubeVAO);
        }
        if (simpleCubeVBO != 0) {
            GL15.glDeleteBuffers(simpleCubeVBO);
        }
        if (simpleCubeTexVBO != 0) {
            GL15.glDeleteBuffers(simpleCubeTexVBO);
        }
        if (fallbackTexture != 0) {
            GL11.glDeleteTextures(fallbackTexture);
        }
        if (fireBoltTexture != 0) {
            GL11.glDeleteTextures(fireBoltTexture);
        }
        if (arrowTexture != 0) {
            GL11.glDeleteTextures(arrowTexture);
        }
        if (nullSpikeTexture != 0) {
            GL11.glDeleteTextures(nullSpikeTexture);
        }
        if (leylineZoneTexture != 0) {
            GL11.glDeleteTextures(leylineZoneTexture);
        }
        if (caltropTexture != 0) {
            GL11.glDeleteTextures(caltropTexture);
        }

        sbeEntityRenderer.cleanup();
        remotePlayerRenderer.cleanup();
        initialized = false;
    }
}

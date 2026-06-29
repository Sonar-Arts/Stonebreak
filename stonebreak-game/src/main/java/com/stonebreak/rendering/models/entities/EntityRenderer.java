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
 * <p>Cows are rendered from the {@code SB_Cow.sbe} asset via {@link SbeEntityRenderer};
 * remote players use {@link RemotePlayerRenderer}; any other entity type falls
 * back to a simple textured cube.
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

            void main() {
                vec4 texColor = texture(textureSampler, TexCoord);

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

        EntityType entityType = entity.getType();

        if (entityType == EntityType.REMOTE_PLAYER
                && entity instanceof com.stonebreak.mobs.entities.RemotePlayer rp) {
            // Untextured fallback hue is stable per remote player (same scheme as the cylinder).
            renderPlayerModel(rp, rp.getRotation().y, 0f, 0f,
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
            renderPlayerModel(decoy, decoy.getRotation().y, 0f, 0f,
                    ensureLocalPlayerColor(),
                    viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }

        if (entityType == EntityType.COW && entity instanceof com.stonebreak.mobs.cow.Cow cow) {
            // The SBE asset comes from the registry by the entity type's object
            // id; only the variant and AI-state → animation-state mapping are
            // cow-specific. The renderer itself stays entity-blind.
            sbeEntityRenderer.render(
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(entityType.getSbeObjectId()),
                    cow.getTextureVariant(),
                    com.stonebreak.mobs.sbe.CowStateMapping.sbeState(cow.getAI().getCurrentState()),
                    cow.getAnimationController().getTotalAnimationTime(),
                    cow.getPosition(),
                    cow.getRotation().y,
                    cow.getScale(),
                    viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }

        if (entityType == EntityType.SHEEP && entity instanceof com.stonebreak.mobs.sheep.Sheep sheep) {
            sbeEntityRenderer.render(
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(entityType.getSbeObjectId()),
                    sheep.getTextureVariant(),
                    com.stonebreak.mobs.sbe.SheepStateMapping.sbeState(sheep.getAI().getCurrentState()),
                    sheep.getAnimationController().getTotalAnimationTime(),
                    sheep.getPosition(),
                    sheep.getRotation().y,
                    sheep.getScale(),
                    viewMatrix, projectionMatrix, world, cameraPos);
            return;
        }

        if (entityType == EntityType.CHICKEN && entity instanceof com.stonebreak.mobs.chicken.Chicken chicken) {
            com.stonebreak.mobs.chicken.ChickenAI chickenAI = chicken.getAI();
            // The Wingflap clip is one-shot: feed it flap-relative time (the AI
            // state timer, reset when the flap starts) so it plays through once
            // instead of freezing on its last frame. Looping states use the
            // continuously advancing animation clock.
            boolean flapping = chickenAI.getCurrentState()
                    == com.stonebreak.mobs.chicken.ChickenAI.ChickenBehaviorState.WING_FLAP;
            float animationTime = flapping
                    ? chickenAI.getStateTimer()
                    : chicken.getAnimationController().getTotalAnimationTime();

            // Chicken has no appearance variants — render the default geometry.
            sbeEntityRenderer.render(
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(entityType.getSbeObjectId()),
                    com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                    com.stonebreak.mobs.sbe.ChickenStateMapping.sbeState(chickenAI.getCurrentState()),
                    animationTime,
                    chicken.getPosition(),
                    chicken.getRotation().y,
                    chicken.getScale(),
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

        // One-shot clips (Attacking, Jumping) use the event-relative time so they
        // restart each time the state is entered; looping Walking uses the
        // continuous clock. This mirrors the chicken wing-flap pattern.
        String state = com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(player.getMovementState());
        float animTime = player.getBodyEventTime();
        Vector3f position = player.getPosition();
        // Body faces the last movement direction (smoothed); the head turns toward
        // the cursor independently, clamped so it never over-rotates.
        float yaw = player.getBodyYaw(); // model faces +Z; same convention as cameraYaw + 180
        float lookYaw = player.getCamera().getYaw() + 180f;
        float headYaw = clamp(wrapDegrees(lookYaw - yaw), -70f, 70f);
        float headPitch = clamp(player.getCamera().getPitch(), -45f, 45f);
        Vector3f scale = new Vector3f(1f, 1f, 1f);

        drawPlayerSbe(asset, state, animTime, position, yaw, scale, headYaw, headPitch,
                ensureLocalPlayerColor(), viewMatrix, projectionMatrix, world, cameraPos);
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
        String state = com.stonebreak.mobs.sbe.PlayerStateMapping.sbeState(rp.getMovementState());
        float animTime = rp.getAnimationController().getTotalAnimationTime();
        drawPlayerSbe(asset, state, animTime, rp.getPosition(), yaw, rp.getScale(),
                headYaw, headPitch, untexturedTint, viewMatrix, projectionMatrix, world, cameraPos);
    }

    /**
     * Low-level draw of the player SBE model. Textured assets render normally;
     * untextured assets fall back to {@code untexturedTint} via the colored path
     * (the textured path would skip every face and render nothing).
     */
    private void drawPlayerSbe(com.stonebreak.mobs.sbe.SbeEntityAsset asset,
                               String state, float animTime, Vector3f position,
                               float yaw, Vector3f scale, float headYaw, float headPitch,
                               Vector4f untexturedTint,
                               Matrix4f viewMatrix, Matrix4f projectionMatrix,
                               com.stonebreak.world.World world, Vector3f cameraPos) {
        if (isTextured(asset)) {
            sbeEntityRenderer.render(
                    asset, com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                    state, animTime, position, yaw, scale,
                    viewMatrix, projectionMatrix, world, cameraPos, headYaw, headPitch);
        } else {
            sbeEntityRenderer.renderColored(
                    asset, com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                    state, animTime, position, yaw, scale,
                    viewMatrix, projectionMatrix, untexturedTint, headYaw, headPitch);
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

    /** Normalizes an angle in degrees to the range (-180, 180]. */
    private static float wrapDegrees(float deg) {
        deg %= 360f;
        if (deg > 180f) deg -= 360f;
        else if (deg <= -180f) deg += 360f;
        return deg;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
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

        if (entityType == EntityType.COW && entity instanceof com.stonebreak.mobs.cow.Cow cow) {
            sbeEntityRenderer.renderWireframe(
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(entityType.getSbeObjectId()),
                    cow.getTextureVariant(),
                    com.stonebreak.mobs.sbe.CowStateMapping.sbeState(cow.getAI().getCurrentState()),
                    cow.getAnimationController().getTotalAnimationTime(),
                    cow.getPosition(),
                    cow.getRotation().y,
                    cow.getScale(),
                    viewMatrix, projectionMatrix, color);
            return;
        }

        if (entityType == EntityType.SHEEP && entity instanceof com.stonebreak.mobs.sheep.Sheep sheep) {
            sbeEntityRenderer.renderWireframe(
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(entityType.getSbeObjectId()),
                    sheep.getTextureVariant(),
                    com.stonebreak.mobs.sbe.SheepStateMapping.sbeState(sheep.getAI().getCurrentState()),
                    sheep.getAnimationController().getTotalAnimationTime(),
                    sheep.getPosition(),
                    sheep.getRotation().y,
                    sheep.getScale(),
                    viewMatrix, projectionMatrix, color);
            return;
        }

        if (entityType == EntityType.CHICKEN
                && entity instanceof com.stonebreak.mobs.chicken.Chicken chicken) {
            com.stonebreak.mobs.chicken.ChickenAI chickenAI = chicken.getAI();
            // Match renderEntity()'s clip timing: the one-shot Wingflap clip is
            // fed flap-relative time so the wireframe animates in step with the
            // rendered model.
            boolean flapping = chickenAI.getCurrentState()
                    == com.stonebreak.mobs.chicken.ChickenAI.ChickenBehaviorState.WING_FLAP;
            float animationTime = flapping
                    ? chickenAI.getStateTimer()
                    : chicken.getAnimationController().getTotalAnimationTime();

            sbeEntityRenderer.renderWireframe(
                    com.stonebreak.mobs.sbe.SbeEntityRegistry.get(entityType.getSbeObjectId()),
                    com.stonebreak.mobs.sbe.SbeEntityAsset.DEFAULT_VARIANT,
                    com.stonebreak.mobs.sbe.ChickenStateMapping.sbeState(chickenAI.getCurrentState()),
                    animationTime,
                    chicken.getPosition(),
                    chicken.getRotation().y,
                    chicken.getScale(),
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

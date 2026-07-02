package com.stonebreak.rendering.gameWorld.shadow;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import static org.lwjgl.opengl.GL11.*;

import com.openmason.engine.rendering.shadow.CascadeCalculator;
import com.openmason.engine.rendering.shadow.CascadedShadowMap;
import com.openmason.engine.rendering.shadow.ShadowCascade;
import com.openmason.engine.rendering.shadow.ShadowSettings;
import com.openmason.engine.rendering.shadow.ShadowUniforms;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Orchestrates the cascaded sun-shadow depth pre-pass.
 *
 * <p>Each frame (before the main scene renders) this computes the cascade
 * matrices from the camera + sun, then re-renders shadow-casting geometry into
 * the engine {@link CascadedShadowMap}: chunk meshes through a minimal depth
 * shader (alpha-testing leaves/flowers, skipping water and translucents — they
 * don't cast), and SBE entities/players through the entity renderer's depth
 * path. Receivers then sample the map via the {@code csmShadowFactor} GLSL
 * spliced into their fragment shaders; {@link #applyToShader} pushes this
 * frame's uniforms to each of them.
 *
 * <p>The pass saves and restores the caller's framebuffer and viewport, so it
 * can run inside any pipeline stage. GL thread only.
 */
public final class ShadowMapRenderer {

    /** Texture unit the shadow map array lives on (0=2D atlas, 1=block array). */
    public static final int SHADOW_TEXTURE_UNIT = 5;

    /** Below this sun elevation (y of the sun direction) shadows are fully faded out. */
    private static final float SUN_FADE_START = 0.02f;
    /** Elevation span over which shadows fade in above {@link #SUN_FADE_START}. */
    private static final float SUN_FADE_RANGE = 0.10f;

    private final ShadowSettings settings = ShadowSettings.defaults();
    private final CascadedShadowMap shadowMap;
    private final CascadeCalculator calculator = new CascadeCalculator();
    private final ShadowCascade[] cascades = new ShadowCascade[ShadowSettings.CASCADE_COUNT];
    private final List<List<Chunk>> cascadeChunks = new ArrayList<>();
    private final ShaderProgram depthShader;
    private final Matrix4f projectionMatrix;
    private final BlockTextureArray blockTextureArray;
    private final Vector3f sunDir = new Vector3f();
    private final int[] savedViewport = new int[4];

    private boolean active;
    private float effectiveStrength;

    public ShadowMapRenderer(Matrix4f projectionMatrix, BlockTextureArray blockTextureArray) {
        this.projectionMatrix = projectionMatrix;
        this.blockTextureArray = blockTextureArray;
        this.shadowMap = new CascadedShadowMap(settings.resolution(), ShadowSettings.CASCADE_COUNT);
        for (int i = 0; i < cascades.length; i++) {
            cascades[i] = new ShadowCascade();
            cascadeChunks.add(new ArrayList<>());
        }
        this.depthShader = createDepthShader();
    }

    private static ShaderProgram createDepthShader() {
        ShaderProgram shader = new ShaderProgram();
        shader.createVertexShader("""
                #version 330 core
                layout (location=0) in vec3 position;
                layout (location=1) in vec2 texCoord;
                layout (location=3) in vec4 aFlags;
                layout (location=4) in float aLayer;
                uniform mat4 u_lightViewProj;
                out vec2 v_uv;
                out float v_layer;
                out float v_alphaTested;
                out float v_skip;
                void main() {
                    gl_Position = u_lightViewProj * vec4(position, 1.0);
                    v_uv = texCoord;
                    v_layer = aLayer;
                    v_alphaTested = aFlags.y;
                    // Water and translucent solids don't cast shadows.
                    v_skip = max(step(0.01, aFlags.x), step(0.5, aFlags.z));
                }
                """);
        shader.createFragmentShader("""
                #version 330 core
                in vec2 v_uv;
                in float v_layer;
                in float v_alphaTested;
                in float v_skip;
                uniform sampler2DArray block_sampler;
                void main() {
                    if (v_skip > 0.5) discard;
                    if (v_alphaTested > 0.5
                            && texture(block_sampler, vec3(v_uv, v_layer)).a < 0.5) discard;
                }
                """);
        shader.link();
        shader.bind();
        shader.createUniform("u_lightViewProj");
        shader.createUniform("block_sampler");
        shader.setUniform("block_sampler", 1);
        shader.unbind();
        return shader;
    }

    /**
     * Runs the depth pre-pass for this frame. Call before the main scene pass,
     * with no special framebuffer requirements — binding and viewport are
     * restored on exit. When shadows are disabled or the sun is down, the pass
     * is skipped and {@link #applyToShader} will disable sampling.
     */
    public void renderShadowPass(World world, Player player, Vector3f sunDirection,
                                 EntityRenderer entityRenderer) {
        active = false;
        if (world == null || player == null) {
            return;
        }
        if (!com.stonebreak.config.Settings.getInstance().getShadowsEnabled()) {
            return;
        }
        float elevationFade = Math.clamp((sunDirection.y - SUN_FADE_START) / SUN_FADE_RANGE, 0.0f, 1.0f);
        if (elevationFade <= 0.0f) {
            return; // Night — no sun shadows.
        }
        effectiveStrength = settings.strength() * elevationFade;
        sunDir.set(sunDirection).normalize();

        calculator.update(cascades, player.getViewMatrix(), projectionMatrix, sunDir, settings);
        collectCasterChunks(world, player);

        // Save state we clobber (the scene FBO may already be bound by post-fx).
        int prevFbo = glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, savedViewport);

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glDepthMask(true);
        glDisable(GL_BLEND);
        // Push caster depth slightly away from the light — with the receiver-side
        // normal-offset bias this kills acne without visible peter-panning.
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(2.0f, 4.0f);

        // Block texture array on unit 1 for the leaf/flower alpha test.
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        blockTextureArray.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        for (int i = 0; i < cascades.length; i++) {
            shadowMap.beginCascade(i);

            depthShader.bind();
            depthShader.setUniform("u_lightViewProj", cascades[i].lightViewProj);
            for (Chunk chunk : cascadeChunks.get(i)) {
                chunk.render();
            }
            depthShader.unbind();

            if (entityRenderer != null) {
                entityRenderer.renderShadowCasters(player,
                        cascades[i].lightView, cascades[i].lightProj,
                        cascades[i].centerWorld, cascades[i].radius);
            }
        }
        shadowMap.endShadowPass();

        glDisable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(0.0f, 0.0f);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);

        active = true;
    }

    /**
     * Pushes this frame's shadow uniforms to a (bound) receiver shader and makes
     * sure the map is bound on {@link #SHADOW_TEXTURE_UNIT}. Safe to call whether
     * or not the pass ran — a skipped pass disables sampling.
     */
    public void applyToShader(ShaderProgram shader) {
        if (active) {
            shadowMap.bindForSampling(SHADOW_TEXTURE_UNIT);
            ShadowUniforms.apply(shader, cascades, sunDir, effectiveStrength, SHADOW_TEXTURE_UNIT);
        } else {
            ShadowUniforms.applyDisabled(shader, SHADOW_TEXTURE_UNIT);
        }
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Collects loaded chunks that can cast into each cascade: a cheap XZ test of
     * the chunk footprint against the cascade sphere swept toward the sun (a tall
     * caster offset sunward still lands shadows in the volume).
     */
    private void collectCasterChunks(World world, Player player) {
        for (List<Chunk> list : cascadeChunks) {
            list.clear();
        }
        int playerChunkX = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        world.forEachChunkAroundPlayer(playerChunkX, playerChunkZ, chunk -> {
            float minX = chunk.getWorldX(0);
            float minZ = chunk.getWorldZ(0);
            float maxX = minX + WorldConfiguration.CHUNK_SIZE;
            float maxZ = minZ + WorldConfiguration.CHUNK_SIZE;
            for (int i = 0; i < cascades.length; i++) {
                if (cascades[i].intersectsXZ(minX, minZ, maxX, maxZ,
                        sunDir.x, sunDir.z, settings.casterBackup())) {
                    cascadeChunks.get(i).add(chunk);
                }
            }
        });
    }

    public void cleanup() {
        shadowMap.close();
        depthShader.cleanup();
    }
}

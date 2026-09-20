package com.stonebreak.rendering.gameWorld.shadow;

import com.openmason.engine.rendering.shadow.CascadedShadowMap;
import com.openmason.engine.rendering.shadow.PointShadowCache;
import com.openmason.engine.rendering.shadow.PointShadowProjection;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.config.Settings;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer;
import com.stonebreak.rendering.lighting.DynamicLights;
import com.stonebreak.rendering.lighting.PointLightGlsl;
import com.stonebreak.rendering.lighting.TorchLight;
import com.stonebreak.rendering.models.blocks.AnimatedBlockRenderer;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

/**
 * Omnidirectional torch shadows, independent of the sun. Terrain depth is cached;
 * moving casters are composited onto a fresh copy every frame (never accumulated).
 * Uses the same geometry/depth shader as sun shadows and the engine's depth arrays.
 */
public final class TorchShadowRenderer implements AutoCloseable {
    private final BlockTextureArray textures;
    private final PointShadowCache cache = new PointShadowCache(PointLightGlsl.MAX_LIGHTS);
    private final List<Chunk> nearbyChunks = new ArrayList<>();
    private final List<Chunk> faceChunks = new ArrayList<>();
    private final Vector3f position = new Vector3f();
    private final Matrix4f projection = PointShadowProjection.projection(TorchLight.RADIUS, new Matrix4f());
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final int[] viewport = new int[4];
    private CascadedShadowMap terrain;
    private CascadedShadowMap live;
    private ShaderProgram shader;
    private World previousWorld;
    private final TorchTerrainSnapshot[] terrainSnapshots = new TorchTerrainSnapshot[PointLightGlsl.MAX_LIGHTS];

    public TorchShadowRenderer(BlockTextureArray textures) {
        this.textures = textures;
        for (int i = 0; i < terrainSnapshots.length; i++) terrainSnapshots[i] = new TorchTerrainSnapshot();
    }

    public void suspend() {
        cache.invalidate();
        previousWorld = null;
        for (TorchTerrainSnapshot snapshot : terrainSnapshots) snapshot.clear();
        nearbyChunks.clear();
        faceChunks.clear();
    }

    public void render(World world, Player player, List<Chunk> loadedChunks,
                       EntityRenderer entities, AnimatedBlockRenderer animated, float totalTime) {
        if (world == null || !Settings.getInstance().getShadowsEnabled() || DynamicLights.count() == 0) {
            suspend();
            return;
        }
        if (world != previousWorld) {
            cache.invalidate();
            previousWorld = world;
        }
        int resolution = switch (Settings.getInstance().getShadowQuality()) {
            case "LOW" -> 128;
            case "HIGH" -> 512;
            default -> 256;
        };

        // Save before lazy allocation too: depth-map construction binds its own FBO.
        int drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        int depthFunc = glGetInteger(GL_DEPTH_FUNC);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST), blend = glIsEnabled(GL_BLEND);
        boolean cull = glIsEnabled(GL_CULL_FACE), offset = glIsEnabled(GL_POLYGON_OFFSET_FILL);
        float offsetFactor = glGetFloat(GL_POLYGON_OFFSET_FACTOR), offsetUnits = glGetFloat(GL_POLYGON_OFFSET_UNITS);
        glGetIntegerv(GL_VIEWPORT, viewport);
        glActiveTexture(GL_TEXTURE1);
        int blockBinding = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
        try {
            if (terrain == null || terrain.resolution() != resolution) {
                if (terrain != null) terrain.close();
                if (live != null) live.close();
                terrain = new CascadedShadowMap(resolution, PointLightGlsl.MAX_LIGHTS * PointShadowProjection.FACE_COUNT);
                live = new CascadedShadowMap(resolution, PointLightGlsl.MAX_LIGHTS * PointShadowProjection.FACE_COUNT);
                cache.invalidate();
            }
            if (shader == null) shader = ChunkShadowShader.create();
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glDepthMask(true);
            glDisable(GL_BLEND);
            // Both sides cast: thin cutout foliage and authored wall meshes must occlude.
            glDisable(GL_CULL_FACE);
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(1f, 1f);
            glActiveTexture(GL_TEXTURE1);
            textures.bind();
            glActiveTexture(GL_TEXTURE0);

            cache.retain(DynamicLights.count());
            for (int i = DynamicLights.count(); i < terrainSnapshots.length; i++) terrainSnapshots[i].clear();
            for (int i = 0; i < DynamicLights.count(); i++) {
                DynamicLights.position(i, position);
                // The held torch rides the camera, so the player's own body sits on top of
                // the light and would stamp a head/torso shadow over whatever they look at.
                Player bodyCaster = i == DynamicLights.heldTorchIndex() ? null : player;
                collectNearbyChunks(loadedChunks);
                boolean refresh = cache.needsRefresh(i, position) || !terrainSnapshots[i].matches(nearbyChunks);
                for (int face = 0; face < PointShadowProjection.FACE_COUNT; face++) {
                    int layer = i * PointShadowProjection.FACE_COUNT + face;
                    PointShadowProjection.view(position, face, view);
                    projection.mul(view, viewProjection);
                    if (refresh) {
                        terrain.beginCascade(layer);
                        renderTerrain();
                    }
                    terrain.copyLayerTo(layer, live);
                    if (entities != null) {
                        entities.renderShadowCasters(bodyCaster, view, projection, position, TorchLight.RADIUS);
                    }
                    if (animated != null) {
                        animated.renderShadowCasters(world, view, projection, position, TorchLight.RADIUS, totalTime);
                    }
                }
                if (refresh) {
                    cache.rendered(i, position);
                    terrainSnapshots[i].capture(nearbyChunks);
                }
            }
            live.bindForSampling(DynamicLights.POINT_SHADOW_TEXTURE_UNIT);
            DynamicLights.enableShadows();
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            glDepthFunc(depthFunc);
            glDepthMask(depthMask);
            restore(GL_DEPTH_TEST, depthTest);
            restore(GL_BLEND, blend);
            restore(GL_CULL_FACE, cull);
            restore(GL_POLYGON_OFFSET_FILL, offset);
            glPolygonOffset(offsetFactor, offsetUnits);
            glUseProgram(program);
            glBindVertexArray(vao);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D_ARRAY, blockBinding);
            glActiveTexture(activeTexture);
        }
    }

    private void collectNearbyChunks(List<Chunk> chunks) {
        nearbyChunks.clear();
        if (chunks == null) return;
        for (Chunk chunk : chunks) {
            float x = chunk.getWorldX(0), z = chunk.getWorldZ(0);
            float dx = position.x - Math.clamp(position.x, x, x + WorldConfiguration.CHUNK_SIZE);
            float dz = position.z - Math.clamp(position.z, z, z + WorldConfiguration.CHUNK_SIZE);
            if (dx * dx + dz * dz < TorchLight.RADIUS * TorchLight.RADIUS) nearbyChunks.add(chunk);
        }
    }

    private void renderTerrain() {
        faceChunks.clear();
        frustum.set(viewProjection);
        for (Chunk chunk : nearbyChunks) {
            float x = chunk.getWorldX(0), z = chunk.getWorldZ(0);
            if (frustum.testAab(x, 0, z, x + WorldConfiguration.CHUNK_SIZE,
                    WorldConfiguration.WORLD_HEIGHT, z + WorldConfiguration.CHUNK_SIZE)) {
                faceChunks.add(chunk);
            }
        }
        shader.bind();
        shader.setUniform("u_lightViewProj", viewProjection);
        if (ChunkRegionRenderer.isEnabled()) {
            ChunkRegionRenderer.getInstance().drawChunks(faceChunks, ChunkRegionRenderer.LAYER_ATLAS);
        } else {
            for (Chunk chunk : faceChunks) chunk.render();
        }
        shader.unbind();
    }

    private static void restore(int capability, boolean enabled) {
        if (enabled) glEnable(capability); else glDisable(capability);
    }

    @Override
    public void close() {
        if (terrain != null) { terrain.close(); terrain = null; }
        if (live != null) { live.close(); live = null; }
        if (shader != null) { shader.cleanup(); shader = null; }
        suspend();
    }
}

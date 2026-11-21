package com.stonebreak.rendering.gameWorld.shadow;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.shadow.CascadedShadowMap;
import com.openmason.engine.rendering.shadow.PointShadowCache;
import com.openmason.engine.rendering.shadow.PointShadowProjection;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.config.Settings;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer;
import com.stonebreak.rendering.lighting.DynamicLights;
import com.stonebreak.rendering.lighting.PointLightGlsl;
import com.stonebreak.rendering.models.blocks.AnimatedBlockRenderer;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.world.World;
import com.stonebreak.rendering.gameWorld.regions.CubeDraw;
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
    private final List<CubeDraw> nearbyChunks = new ArrayList<>();
    private final List<CubeDraw> faceChunks = new ArrayList<>();
    /** The current light's render-space position. */
    private final Vector3f position = new Vector3f();
    private final org.joml.Vector3d worldPosition = new org.joml.Vector3d();
    private final Matrix4f projection = PointShadowProjection.projection(PointLightGlsl.RADIUS, new Matrix4f());
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    // view/viewProjection are built at the light's RENDER-space position, so they are the draw matrices
    // too (the twins below are kept as named copies for the entity/animated caster calls).
    private final Matrix4f viewRender = new Matrix4f();
    private final Matrix4f viewProjectionRender = new Matrix4f();
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

    public void render(World world, Player player,
                       EntityRenderer entities, AnimatedBlockRenderer animated, float totalTime) {
        List<CubeDraw> loadedChunks = ChunkRegionRenderer.isEnabled()
                ? ChunkRegionRenderer.getInstance().cubes() : List.of();
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

            if (ChunkRegionRenderer.isEnabled()) {
                // Light views ignore camera visibility; non-READY columns stay hidden like everywhere else.
                ChunkRegionRenderer.getInstance().prepareShadowPass();
            }
            cache.retain(DynamicLights.count());
            for (int i = DynamicLights.count(); i < terrainSnapshots.length; i++) terrainSnapshots[i].clear();
            for (int i = 0; i < DynamicLights.count(); i++) {
                // Render space throughout (views, frustum, cube boxes): exact at any distance from zero.
                // The world position only feeds the entity casters' cull sphere.
                DynamicLights.renderPosition(i, position);
                DynamicLights.position(i, worldPosition);
                // The held torch rides the camera, so the player's own body sits on top of
                // the light and would stamp a head/torso shadow over whatever they look at.
                // Another player's torch likewise skips its holder (DynamicLights.holder).
                Player bodyCaster = i == DynamicLights.heldTorchIndex() ? null : player;
                collectNearbyChunks(loadedChunks);
                boolean refresh = cache.needsRefresh(i, position) || !terrainSnapshots[i].matches(nearbyChunks);
                for (int face = 0; face < PointShadowProjection.FACE_COUNT; face++) {
                    int layer = i * PointShadowProjection.FACE_COUNT + face;
                    PointShadowProjection.view(position, face, view);
                    projection.mul(view, viewProjection);
                    viewRender.set(view);
                    viewProjectionRender.set(viewProjection);
                    if (refresh) {
                        terrain.beginCascade(layer);
                        renderTerrain();
                    }
                    terrain.copyLayerTo(layer, live);
                    if (entities != null) {
                        entities.renderShadowCasters(bodyCaster, DynamicLights.holder(i),
                                viewRender, projection, worldPosition, PointLightGlsl.RADIUS);
                    }
                    if (animated != null) {
                        animated.renderShadowCasters(world, viewRender, projection, worldPosition, PointLightGlsl.RADIUS, totalTime);
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

    private void collectNearbyChunks(List<CubeDraw> cubes) {
        nearbyChunks.clear();
        if (cubes == null) return;
        float edge = WorldConfiguration.CUBE_EDGE;
        ChunkRegionRenderer regions = ChunkRegionRenderer.getInstance();
        for (int c = 0; c < cubes.size(); c++) {
            CubeDraw cube = cubes.get(c);
            if (!cube.hasSolid() || !regions.isDrawable(cube)) continue;
            float x = RenderOrigin.relativeX(cube.minBlockX());
            float y = RenderOrigin.relativeY(cube.minBlockY());
            float z = RenderOrigin.relativeZ(cube.minBlockZ());
            float dx = position.x - Math.clamp(position.x, x, x + edge);
            float dy = position.y - Math.clamp(position.y, y, y + edge);
            float dz = position.z - Math.clamp(position.z, z, z + edge);
            if (dx * dx + dy * dy + dz * dz < PointLightGlsl.RADIUS * PointLightGlsl.RADIUS) nearbyChunks.add(cube);
        }
    }

    private void renderTerrain() {
        faceChunks.clear();
        frustum.set(viewProjection);
        float edge = WorldConfiguration.CUBE_EDGE;
        for (CubeDraw cube : nearbyChunks) {
            float x = RenderOrigin.relativeX(cube.minBlockX());
            float y = RenderOrigin.relativeY(cube.minBlockY());
            float z = RenderOrigin.relativeZ(cube.minBlockZ());
            if (frustum.testAab(x, y, z, x + edge, y + edge, z + edge)) {
                faceChunks.add(cube);
            }
        }
        shader.bind();
        shader.setUniform("u_lightViewProj", viewProjectionRender);
        // Atlas + stamp handles of the cubes in this face's frustum, one multidraw per region.
        ChunkRegionRenderer.getInstance().drawCubes(faceChunks, ChunkRegionRenderer.LAYER_ATLAS);
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

package com.stonebreak.rendering.gameWorld.weather;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import com.stonebreak.world.World;
import com.stonebreak.world.weather.ClientWeather;
import com.stonebreak.world.weather.CloudColumn;
import com.stonebreak.world.weather.CloudField;
import com.stonebreak.world.weather.WeatherSurface;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

/**
 * Rain and snow under the clouds that make them. Every block column within
 * {@link #RADIUS} of the camera whose cloud precipitates gets one sheet (an
 * instanced, camera-facing quad from the column's ground up to the cloud base,
 * or up past the camera when the base is out of sight); the shader draws the
 * falling streaks or flakes. Rain stops where the cloud stops, because the
 * intensity comes from the same {@link CloudField} cell, and it never falls
 * above the cloud it comes from.
 *
 * <p>Ground height and snow come from {@link WeatherSurface} (loaded chunks
 * only), so the render thread never waits on terrain generation.</p>
 */
public final class PrecipitationRenderer {

    /** Columns drawn around the camera, in blocks. */
    static final int RADIUS = 20;
    /** How far below and above the camera sheets reach. */
    private static final float BELOW = 24f;
    private static final float ABOVE = 32f;

    private static final int FLOATS_PER_INSTANCE = 6;
    private static final int MAX_INSTANCES = (2 * RADIUS + 1) * (2 * RADIUS + 1);
    /** Cloud cells spanned by the column grid (plus a margin for the cell boundary). */
    private static final int CELL_SPAN = (2 * RADIUS + 1) / CloudField.CELL + 3;

    private final ShaderProgram shader;
    private final int vao;
    private final int cornerVbo;
    private final int instanceVbo;
    private final FloatBuffer instances = BufferUtils.createFloatBuffer(MAX_INSTANCES * FLOATS_PER_INSTANCE);
    private final CloudColumn[] cellColumns = new CloudColumn[CELL_SPAN * CELL_SPAN];
    private final Vector3f renderCamera = new Vector3f();
    private final Vector2f renderOrigin = new Vector2f();

    public PrecipitationRenderer() {
        shader = new ShaderProgram();
        shader.createVertexShader(load("/shaders/weather/precip.vert"));
        shader.createFragmentShader(load("/shaders/weather/precip.frag"));
        shader.link();

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        cornerVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, cornerVbo);
        glBufferData(GL_ARRAY_BUFFER, new float[]{0, 0, 1, 0, 0, 1, 1, 1}, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        instanceVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_INSTANCES * FLOATS_PER_INSTANCE * Float.BYTES, GL_STREAM_DRAW);
        int stride = FLOATS_PER_INSTANCE * Float.BYTES;
        glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribDivisor(1, 1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 4L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribDivisor(2, 1);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /** Lambda (not a method reference) so the caller-sensitive lookup happens in this module. */
    private static String load(String path) {
        return ShaderResourceLoader.load(path, p -> PrecipitationRenderer.class.getResourceAsStream(p));
    }

    /** Draws this frame's rain and snow over the bound framebuffer (depth-tested, no depth writes). */
    public void render(World world, ClientWeather weather, Matrix4f projection, Matrix4f view,
                       Vector3f cameraWorldPos, float time, float ambientLight) {
        if (weather == null || world == null) return;
        int count = collectColumns(world, weather, cameraWorldPos);
        if (count == 0) return;

        glBindBuffer(GL_ARRAY_BUFFER, instanceVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_INSTANCES * FLOATS_PER_INSTANCE * Float.BYTES, GL_STREAM_DRAW);
        glBufferSubData(GL_ARRAY_BUFFER, 0, instances);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int blendSrc = glGetInteger(GL_BLEND_SRC);
        int blendDst = glGetInteger(GL_BLEND_DST);

        glEnable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shader.bind();
        shader.setMat4("uProjection", projection);
        shader.setMat4("uView", view);
        shader.setVec3("uCameraPos", RenderOrigin.toRender(cameraWorldPos, renderCamera));
        shader.setVec2("uRenderOrigin", renderOrigin.set(RenderOrigin.x(), RenderOrigin.z()));
        shader.setFloat("uTime", time);
        shader.setFloat("uAmbient", ambientLight);
        shader.setFloat("uRadius", RADIUS);
        glBindVertexArray(vao);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count);
        glBindVertexArray(0);
        shader.unbind();

        glBlendFunc(blendSrc, blendDst);
        if (!blend) glDisable(GL_BLEND);
        if (!depthTest) glDisable(GL_DEPTH_TEST);
        if (cull) glEnable(GL_CULL_FACE);
        glDepthMask(depthMask);
    }

    /** Fills {@link #instances} with every raining column in range; returns how many. */
    private int collectColumns(World world, ClientWeather weather, Vector3f cam) {
        double s = weather.seconds();
        int camX = (int) Math.floor(cam.x);
        int camZ = (int) Math.floor(cam.z);
        int cell0X = CloudField.cellX(camX - RADIUS, s);
        int cell0Z = CloudField.cellZ(camZ - RADIUS, s);
        java.util.Arrays.fill(cellColumns, null);
        float originX = RenderOrigin.x();
        float originZ = RenderOrigin.z();

        instances.clear();
        int count = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
                int wx = camX + dx;
                int wz = camZ + dz;
                CloudColumn cloud = column(weather, CloudField.cellX(wx + 0.5, s) - cell0X,
                        CloudField.cellZ(wz + 0.5, s) - cell0Z, cell0X, cell0Z);
                float intensity = cloud.precipitation();
                if (intensity <= 0f) continue;

                int ground = WeatherSurface.groundY(world, wx, wz);
                float bottom = ground == WeatherSurface.UNKNOWN ? cam.y - BELOW : Math.max(ground, cam.y - BELOW);
                // Falls from the cloud base: nothing above it, nothing when the camera is above it.
                float top = Math.min(cam.y + ABOVE, cloud.baseY());
                if (top <= bottom) continue;

                instances.put(wx + 0.5f - originX).put(wz + 0.5f - originZ).put(bottom).put(top)
                        .put(intensity).put(WeatherSurface.isSnow(world, wx, wz) ? 1f : 0f);
                count++;
            }
        }
        instances.flip();
        return count;
    }

    /** Per-cloud-cell column, resolved once per frame per cell. */
    private CloudColumn column(ClientWeather weather, int i, int j, int cell0X, int cell0Z) {
        if (i < 0 || j < 0 || i >= CELL_SPAN || j >= CELL_SPAN) return CloudColumn.CLEAR;
        int idx = i * CELL_SPAN + j;
        CloudColumn col = cellColumns[idx];
        if (col == null) {
            col = CloudField.resolve(weather.field().raw(cell0X + i, cell0Z + j), weather.bias());
            cellColumns[idx] = col;
        }
        return col;
    }

    public void cleanup() {
        shader.cleanup();
        glDeleteBuffers(cornerVbo);
        glDeleteBuffers(instanceVbo);
        glDeleteVertexArrays(vao);
    }
}

package com.stonebreak.rendering.UI.rendering;

// Standard Library Imports
import java.nio.FloatBuffer;
import java.util.List;

// JOML Math Library
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

// LWJGL Core
import org.lwjgl.BufferUtils;

// LWJGL OpenGL Classes
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

// LWJGL OpenGL Static Imports (GL11)
import static org.lwjgl.opengl.GL11.*;

// Stonebreak Game Components
import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.emitters.SoundEmitterRenderer;
import com.stonebreak.audio.emitters.SoundEmitter;

/**
 * Renderer for debug line visualisations: entity AI path trails.
 *
 * <p>Drawing is batched. A caller wraps any number of {@link #drawPath} calls
 * between {@link #beginBatch()} and {@link #endBatch()}; the shader is bound and
 * the camera matrices uploaded once for the whole batch, and GL state is saved
 * and restored exactly once. The path vertex buffer is allocated up front and
 * reused every frame — nothing is generated or freed per entity.
 *
 * <p>Entity model outlines are drawn separately as see-through wireframes of the
 * actual model mesh (see {@code SbeEntityRenderer.renderWireframe}), not by this
 * class.
 */
public class DebugRenderer {

    /** Capacity of the reusable path buffer, in points. */
    private static final int MAX_PATH_POINTS = 64;

    /** Line thickness for debug geometry. */
    private static final float LINE_WIDTH = 2.0f;

    private final ShaderProgram shaderProgram;
    private final Matrix4f projectionMatrix;
    private final SoundEmitterRenderer soundEmitterRenderer;

    // Reusable dynamic buffer for path line segments.
    private int pathVao;
    private int pathVbo;
    private final FloatBuffer pathScratch =
            BufferUtils.createFloatBuffer(MAX_PATH_POINTS * 2 * 3);

    // Batch state, valid only between beginBatch() and endBatch().
    private boolean batchActive;
    private boolean savedDepthTest;
    private float savedLineWidth;

    public DebugRenderer(ShaderProgram shaderProgram, Matrix4f projectionMatrix) {
        this.shaderProgram = shaderProgram;
        this.projectionMatrix = projectionMatrix;
        this.soundEmitterRenderer = new SoundEmitterRenderer(shaderProgram, projectionMatrix);
        createPathGeometry();
    }

    /** Builds the reusable dynamic VAO/VBO used to stream path line segments. */
    private void createPathGeometry() {
        pathVao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(pathVao);

        pathVbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, pathVbo);
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER,
                (long) pathScratch.capacity() * Float.BYTES, GL15.GL_DYNAMIC_DRAW);

        GL20.glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);

        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Begins a debug-line batch: binds the shader, uploads the camera matrices
     * once, and configures GL state. Must be paired with {@link #endBatch()}.
     */
    public void beginBatch() {
        if (batchActive) {
            return;
        }
        batchActive = true;

        savedDepthTest = glIsEnabled(GL_DEPTH_TEST);
        savedLineWidth = glGetFloat(GL_LINE_WIDTH);

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glLineWidth(LINE_WIDTH);

        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);

        Player player = Game.getPlayer();
        if (player != null) {
            shaderProgram.setUniform("viewMatrix", player.getCamera().getViewMatrix());
        }

        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_useSolidColor", true);
    }

    /** Ends the batch, restoring shader and GL state saved by {@link #beginBatch()}. */
    public void endBatch() {
        if (!batchActive) {
            return;
        }
        batchActive = false;

        shaderProgram.unbind();
        glLineWidth(savedLineWidth);
        if (!savedDepthTest) {
            glDisable(GL_DEPTH_TEST);
        }
    }

    /**
     * Draws a path as connected world-space line segments using the reusable
     * dynamic buffer. Points beyond the buffer capacity are dropped.
     *
     * @param pathPoints ordered world-space path points
     * @param color      RGBA line colour
     */
    public void drawPath(List<Vector3f> pathPoints, Vector4f color) {
        if (!batchActive || pathPoints == null || pathPoints.size() < 2) {
            return;
        }

        int points = Math.min(pathPoints.size(), MAX_PATH_POINTS);
        int segments = points - 1;

        pathScratch.clear();
        for (int i = 0; i < segments; i++) {
            Vector3f start = pathPoints.get(i);
            Vector3f end = pathPoints.get(i + 1);
            pathScratch.put(start.x).put(start.y).put(start.z);
            pathScratch.put(end.x).put(end.y).put(end.z);
        }
        pathScratch.flip();

        shaderProgram.setUniform("modelMatrix", new Matrix4f());
        shaderProgram.setUniform("u_color", color);

        GL30.glBindVertexArray(pathVao);
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, pathVbo);
        // Orphan the buffer, then upload only the segments used this frame.
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER,
                (long) pathScratch.capacity() * Float.BYTES, GL15.GL_DYNAMIC_DRAW);
        GL20.glBufferSubData(GL20.GL_ARRAY_BUFFER, 0, pathScratch);
        glDrawArrays(GL_LINES, 0, segments * 2);
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Renders all sound emitters as yellow triangle wireframes when debug mode
     * is enabled. Manages its own shader state, so call it outside a batch.
     */
    public void renderSoundEmitters(boolean debugMode) {
        if (!debugMode) {
            return;
        }

        com.stonebreak.audio.emitters.SoundEmitterManager emitterManager = Game.getSoundEmitterManager();
        if (emitterManager != null && soundEmitterRenderer != null) {
            List<SoundEmitter> emitters = emitterManager.getDebugVisibleEmitters();
            soundEmitterRenderer.renderSoundEmitters(emitters, debugMode);
        }
    }

    /**
     * Renders a single sound emitter with optional color override.
     * @param emitter The sound emitter to render
     * @param color Custom color for this emitter (null to use default yellow)
     * @param debugMode Whether debug mode is enabled
     */
    public void renderSingleSoundEmitter(SoundEmitter emitter, Vector3f color, boolean debugMode) {
        if (soundEmitterRenderer != null) {
            soundEmitterRenderer.renderSingleEmitter(emitter, color, debugMode);
        }
    }

    /**
     * Cleanup method to free OpenGL resources.
     * Should be called when the renderer is no longer needed.
     */
    public void cleanup() {
        if (pathVao != 0) {
            GL30.glDeleteVertexArrays(pathVao);
            pathVao = 0;
        }
        if (pathVbo != 0) {
            GL20.glDeleteBuffers(pathVbo);
            pathVbo = 0;
        }
        if (soundEmitterRenderer != null) {
            soundEmitterRenderer.cleanup();
        }
    }
}

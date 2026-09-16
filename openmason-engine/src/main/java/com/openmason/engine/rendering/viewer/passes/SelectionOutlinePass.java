package com.openmason.engine.rendering.viewer.passes;

import com.openmason.engine.rendering.model.ModelBounds;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderType;
import com.openmason.engine.rendering.viewer.ViewerFrame;
import com.openmason.engine.rendering.viewer.ViewerPass;
import com.openmason.engine.rendering.viewer.ViewerPassOrder;
import com.openmason.engine.rendering.viewer.scene.ModelInstance;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.function.Supplier;

import static org.lwjgl.opengl.GL30.*;

/**
 * Draws a wire box around each selected instance so the selection reads without the
 * gizmo — which only ever marks the primary.
 *
 * <p>Boxes are the instances' world-space AABBs, drawn X-ray (depth test off) so an
 * instance hidden behind another is still findable. Geometry is rebuilt every frame
 * from the supplier; a selection is a handful of boxes, so a single streamed VBO is
 * cheaper than any caching scheme would be to keep correct.
 *
 * <p>Uses the GIZMO shader (position + colour attributes, {@code uModelMatrix},
 * {@code uViewProjection}, {@code uIntensity}, {@code uAlpha}), so it owns no shader.
 */
public final class SelectionOutlinePass implements ViewerPass {

    private static final int FLOATS_PER_VERTEX = 6;   // xyz + rgb
    private static final int EDGES_PER_BOX = 12;
    private static final int VERTICES_PER_BOX = EDGES_PER_BOX * 2;

    /** (corner, corner) pairs; corner bit 0 = max x, bit 1 = max y, bit 2 = max z. */
    private static final int[][] EDGES = {
            {0, 1}, {1, 3}, {3, 2}, {2, 0},   // bottom (min y)
            {4, 5}, {5, 7}, {7, 6}, {6, 4},   // top (max y)
            {0, 4}, {1, 5}, {2, 6}, {3, 7}    // verticals
    };

    private final Supplier<List<ModelInstance>> selected;
    private final Vector3f primaryColor = new Vector3f(1.0f, 0.62f, 0.15f);
    private final Vector3f secondaryColor = new Vector3f(1.0f, 0.85f, 0.45f);
    private float alpha = 0.9f;

    private int vao;
    private int vbo;
    private FloatBuffer staging = BufferUtils.createFloatBuffer(VERTICES_PER_BOX * FLOATS_PER_VERTEX * 8);
    private boolean initialized;

    /**
     * @param selected the instances to outline, in selection order — the first is the
     *                 primary and gets the stronger colour
     */
    public SelectionOutlinePass(Supplier<List<ModelInstance>> selected) {
        this.selected = java.util.Objects.requireNonNull(selected, "selected");
    }

    public void setColors(Vector3f primary, Vector3f secondary) {
        if (primary != null) primaryColor.set(primary);
        if (secondary != null) secondaryColor.set(secondary);
    }

    public void setAlpha(float alpha) {
        this.alpha = Math.max(0f, Math.min(1f, alpha));
    }

    @Override
    public int order() {
        return ViewerPassOrder.CONTENT_OVERLAY;
    }

    @Override
    public String name() {
        return "selection-outline";
    }

    @Override
    public void render(ViewerFrame frame) {
        List<ModelInstance> instances = selected.get();
        if (instances == null || instances.isEmpty()) {
            return;
        }
        ensureInitialized();

        int vertexCount = fillStaging(instances);
        if (vertexCount == 0) {
            return;
        }

        ShaderProgram shader = frame.shaders().getShaderProgram(ShaderType.GIZMO);
        if (shader == null) {
            return;
        }
        shader.use();
        Matrix4f viewProjection = new Matrix4f(frame.context().getCamera().getProjectionMatrix())
                .mul(frame.context().getCamera().getViewMatrix());
        shader.setMat4("uViewProjection", viewProjection);
        shader.setMat4("uModelMatrix", new Matrix4f());
        shader.setFloat("uIntensity", 1.0f);
        shader.setFloat("uAlpha", alpha);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, staging, GL_STREAM_DRAW);

        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        glDisable(GL_DEPTH_TEST);
        glDrawArrays(GL_LINES, 0, vertexCount);
        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private int fillStaging(List<ModelInstance> instances) {
        int needed = instances.size() * VERTICES_PER_BOX * FLOATS_PER_VERTEX;
        if (staging.capacity() < needed) {
            staging = BufferUtils.createFloatBuffer(needed);
        }
        staging.clear();

        int vertexCount = 0;
        Vector3f[] corners = new Vector3f[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = new Vector3f();
        }
        boolean first = true;
        for (ModelInstance instance : instances) {
            if (instance == null || !instance.isVisible()) {
                continue;
            }
            ModelBounds b = instance.worldBounds();
            Vector3f min = b.min();
            Vector3f max = b.max();
            for (int c = 0; c < 8; c++) {
                corners[c].set((c & 1) == 0 ? min.x : max.x,
                        (c & 2) == 0 ? min.y : max.y,
                        (c & 4) == 0 ? min.z : max.z);
            }
            Vector3f color = first ? primaryColor : secondaryColor;
            first = false;
            for (int[] edge : EDGES) {
                put(corners[edge[0]], color);
                put(corners[edge[1]], color);
                vertexCount += 2;
            }
        }
        staging.flip();
        return vertexCount;
    }

    private void put(Vector3f p, Vector3f color) {
        staging.put(p.x).put(p.y).put(p.z).put(color.x).put(color.y).put(color.z);
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        initialized = true;
    }

    @Override
    public void cleanup() {
        if (!initialized) {
            return;
        }
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        vbo = 0;
        vao = 0;
        initialized = false;
    }
}

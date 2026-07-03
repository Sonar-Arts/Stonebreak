package com.openmason.main.systems.viewport.viewportRendering.bones;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.main.systems.skeleton.AttachmentStore;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Attachment point (socket) marker overlay.
 *
 * <p>Draws a small diamond at each socket's model-space position plus a short
 * shaft along the socket's local +Z ("forward") axis so authors can see which
 * way an attached model will face. Rendered x-ray (depth-test off) alongside
 * the bone skeleton overlay; the selected socket is tinted brighter.
 *
 * <p>Lives in the {@code bones} package to reuse {@link BoneGizmoGeometry}'s
 * primitives — this package is the rigging-overlay home, not bones-only.
 */
public class AttachmentGizmoRenderer {

    private static final Logger logger = LoggerFactory.getLogger(AttachmentGizmoRenderer.class);

    private static final Vector3f SOCKET_COLOR   = new Vector3f(0.35f, 0.80f, 0.95f); // cyan
    private static final Vector3f SELECTED_COLOR = new Vector3f(0.65f, 0.95f, 1.00f); // bright cyan
    private static final float MARKER_RADIUS = 0.07f;  // model-space units
    private static final float FORWARD_TICK  = 0.25f;  // forward-axis shaft length

    private int markerVao;
    private int markerVbo;
    private int markerEbo;
    private int markerIndexCount;

    private int tickVao;
    private int tickVbo;
    private int tickEbo;
    private int tickIndexCount;

    private boolean initialized = false;

    public void initialize() {
        if (initialized) return;

        float[] markerVerts = BoneGizmoGeometry.buildJointVertices(SOCKET_COLOR);
        int[] markerIdx = BoneGizmoGeometry.jointIndices();
        markerVao = glGenVertexArrays();
        markerVbo = glGenBuffers();
        markerEbo = glGenBuffers();
        uploadInterleaved(markerVao, markerVbo, markerEbo, markerVerts, markerIdx);
        markerIndexCount = markerIdx.length;

        float[] tickVerts = BoneGizmoGeometry.buildShaftVertices(SOCKET_COLOR, 0.02f);
        int[] tickIdx = BoneGizmoGeometry.shaftIndices();
        tickVao = glGenVertexArrays();
        tickVbo = glGenBuffers();
        tickEbo = glGenBuffers();
        uploadInterleaved(tickVao, tickVbo, tickEbo, tickVerts, tickIdx);
        tickIndexCount = tickIdx.length;

        initialized = true;
        logger.debug("AttachmentGizmoRenderer initialized");
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Render every socket in the store. {@code modelMatrix} places the markers in
     * the model's transform space so they follow the same gizmo translate/rotate/
     * scale as the mesh.
     */
    public void render(ShaderProgram shader, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       Matrix4f modelMatrix, AttachmentStore store) {
        if (!initialized || store == null || store.isEmpty()) {
            return;
        }
        try {
            Matrix4f viewProjection = new Matrix4f(projectionMatrix).mul(viewMatrix);
            Matrix4f mvp = new Matrix4f();
            Matrix4f local = new Matrix4f();

            shader.use();

            boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
            glDisable(GL_DEPTH_TEST);

            List<OMOFormat.AttachmentPointEntry> points = store.getPoints();
            String selectedId = store.getSelectedAttachmentId();

            for (OMOFormat.AttachmentPointEntry p : points) {
                Vector3f color = p.id().equals(selectedId) ? SELECTED_COLOR : SOCKET_COLOR;
                shader.setVec3("uColor", color);

                // Socket local frame in model space: T(pos) · R_xyz(rot).
                local.set(modelMatrix)
                        .translate(p.posX(), p.posY(), p.posZ())
                        .rotateXYZ(
                                (float) Math.toRadians(p.rotX()),
                                (float) Math.toRadians(p.rotY()),
                                (float) Math.toRadians(p.rotZ()));

                // Diamond marker at the socket origin.
                mvp.set(viewProjection).mul(new Matrix4f(local).scale(MARKER_RADIUS));
                shader.setMat4("uMVPMatrix", mvp);
                glBindVertexArray(markerVao);
                glDrawElements(GL_TRIANGLES, markerIndexCount, GL_UNSIGNED_INT, 0L);

                // Forward tick: shaft primitive spans y ∈ [0,1]; rotate +Y onto the
                // socket's local +Z, then scale to the tick length.
                mvp.set(viewProjection).mul(new Matrix4f(local)
                        .rotateX((float) Math.toRadians(90))
                        .scale(1f, FORWARD_TICK, 1f));
                shader.setMat4("uMVPMatrix", mvp);
                glBindVertexArray(tickVao);
                glDrawElements(GL_TRIANGLES, tickIndexCount, GL_UNSIGNED_INT, 0L);
            }

            glBindVertexArray(0);

            if (depthWasEnabled) {
                glEnable(GL_DEPTH_TEST);
            }
        } catch (Exception e) {
            logger.error("Error rendering attachment gizmos", e);
        } finally {
            glUseProgram(0);
        }
    }

    private void uploadInterleaved(int vao, int vbo, int ebo, float[] verts, int[] indices) {
        glBindVertexArray(vao);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, verts, GL_STATIC_DRAW);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        // GIZMO shader reads position-only at location 0; color comes from uColor.
        int stride = 6 * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    public void cleanup() {
        if (!initialized) return;
        glDeleteVertexArrays(markerVao);
        glDeleteBuffers(markerVbo);
        glDeleteBuffers(markerEbo);
        glDeleteVertexArrays(tickVao);
        glDeleteBuffers(tickVbo);
        glDeleteBuffers(tickEbo);
        markerVao = markerVbo = markerEbo = 0;
        tickVao = tickVbo = tickEbo = 0;
        initialized = false;
    }
}

package com.openmason.main.systems.viewport.viewportRendering.bones;

import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.main.systems.skeleton.BoneStore;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Maya-style bone skeleton gizmo overlay.
 *
 * <p>Draws joint octahedra and elongated bone shafts as editor-only artifacts. Rendered
 * with depth-test disabled (x-ray) so the skeleton is always visible through geometry,
 * matching Maya's default joint display mode. Bones are pure transform data at runtime —
 * this renderer is only ever active inside Open Mason while the user has bone display on.
 *
 * <p>Geometry is generated once at initialize-time as two unit primitives (joint + shaft);
 * per-frame work is just composing a model matrix per bone.
 */
public class BoneGizmoRenderer {

    private static final Logger logger = LoggerFactory.getLogger(BoneGizmoRenderer.class);

    private static final Vector3f JOINT_COLOR = new Vector3f(1.00f, 0.85f, 0.30f); // pale yellow
    private static final Vector3f SHAFT_COLOR = new Vector3f(0.78f, 0.65f, 0.30f); // tan
    private static final float JOINT_RADIUS  = 0.06f; // model-space units
    private static final float SHAFT_HALFW   = 0.035f; // half-width of widest cross-section

    // Joint VAO/VBO/EBO
    private int jointVao;
    private int jointVbo;
    private int jointEbo;
    private int jointIndexCount;

    // Shaft VAO/VBO/EBO
    private int shaftVao;
    private int shaftVbo;
    private int shaftEbo;
    private int shaftIndexCount;

    private boolean initialized = false;

    public void initialize() {
        if (initialized) return;

        // Joint geometry (unit octahedron)
        float[] jointVerts = BoneGizmoGeometry.buildJointVertices(JOINT_COLOR);
        int[] jointIdx = BoneGizmoGeometry.jointIndices();
        jointVao = glGenVertexArrays();
        jointVbo = glGenBuffers();
        jointEbo = glGenBuffers();
        uploadInterleaved(jointVao, jointVbo, jointEbo, jointVerts, jointIdx);
        jointIndexCount = jointIdx.length;

        // Shaft geometry (elongated octahedron Y∈[0,1])
        float[] shaftVerts = BoneGizmoGeometry.buildShaftVertices(SHAFT_COLOR, SHAFT_HALFW);
        int[] shaftIdx = BoneGizmoGeometry.shaftIndices();
        shaftVao = glGenVertexArrays();
        shaftVbo = glGenBuffers();
        shaftEbo = glGenBuffers();
        uploadInterleaved(shaftVao, shaftVbo, shaftEbo, shaftVerts, shaftIdx);
        shaftIndexCount = shaftIdx.length;

        initialized = true;
        logger.debug("BoneGizmoRenderer initialized");
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Render the entire skeleton from the given {@link BoneStore}. The {@code modelMatrix}
     * places the skeleton in the model's transform space so it follows the same gizmo
     * translate/rotate/scale as the mesh.
     */
    public void render(ShaderProgram shader, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       Matrix4f modelMatrix, BoneStore store,
                       Map<String, List<Vector3f>> partPivotsByBoneId) {
        if (!initialized || store == null || store.isEmpty()) {
            return;
        }
        if (partPivotsByBoneId == null) {
            partPivotsByBoneId = Collections.emptyMap();
        }
        try {
            // The GIZMO shader takes a single uMVPMatrix and uColor uniform; compose
            // projection * view per-primitive against each bone's own model matrix.
            Matrix4f viewProjection = new Matrix4f(projectionMatrix).mul(viewMatrix);
            Matrix4f mvp = new Matrix4f();

            shader.use();

            // x-ray pass: always-visible bones
            boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
            glDisable(GL_DEPTH_TEST);

            Map<String, Vector3f> headWorld = store.snapshotJointWorldPositions();
            Map<String, Vector3f> tailWorld = store.snapshotTailWorldPositions();
            List<OMOFormat.BoneEntry> bones = store.getBones();

            // Joints — render at both ends of every bone so the head and the
            // tail are independently selectable visual anchors.
            shader.setVec3("uColor", JOINT_COLOR);
            glBindVertexArray(jointVao);
            for (OMOFormat.BoneEntry b : bones) {
                Vector3f head = headWorld.get(b.id());
                if (head != null) drawJoint(shader, viewProjection, mvp, modelMatrix, head);
                Vector3f tail = tailWorld.get(b.id());
                if (tail != null && !approxEquals(head, tail)) {
                    drawJoint(shader, viewProjection, mvp, modelMatrix, tail);
                }
            }

            // Shafts. Three flavours, all drawn with the same primitive:
            //  1. The bone itself     — head → tail.
            //  2. Bone → child bone   — parent's tail → child's head (zero-length
            //                            when the child's origin+pos is at the tail).
            //  3. Bone → child part   — parent's tail → part's world pivot.
            shader.setVec3("uColor", SHAFT_COLOR);
            glBindVertexArray(shaftVao);

            // 1. Bone segments (head → tail) — the visible "stick" between the two joints.
            for (OMOFormat.BoneEntry b : bones) {
                Vector3f head = headWorld.get(b.id());
                Vector3f tail = tailWorld.get(b.id());
                if (head == null || tail == null) continue;
                drawShaft(shader, viewProjection, mvp, modelMatrix, head, tail);
            }

            // 2. Bone → child bone (parent's tail → child's head)
            for (OMOFormat.BoneEntry b : bones) {
                if (b.isRoot()) continue;
                Vector3f childHead = headWorld.get(b.id());
                Vector3f parentTail = tailWorld.get(b.parentBoneId());
                if (childHead == null || parentTail == null) continue;
                drawShaft(shader, viewProjection, mvp, modelMatrix, parentTail, childHead);
            }

            // 3. Bone → child part (parent's tail → part pivot)
            for (OMOFormat.BoneEntry b : bones) {
                Vector3f parentTail = tailWorld.get(b.id());
                if (parentTail == null) continue;
                List<Vector3f> children = partPivotsByBoneId.get(b.id());
                if (children == null) continue;
                for (Vector3f childPivot : children) {
                    drawShaft(shader, viewProjection, mvp, modelMatrix, parentTail, childPivot);
                }
            }

            glBindVertexArray(0);

            if (depthWasEnabled) {
                glEnable(GL_DEPTH_TEST);
            }
        } catch (Exception e) {
            logger.error("Error rendering bone gizmos", e);
        } finally {
            glUseProgram(0);
        }
    }

    /** Draw a joint primitive centered at {@code position} in world space. */
    private void drawJoint(ShaderProgram shader, Matrix4f viewProjection, Matrix4f mvp,
                           Matrix4f modelMatrix, Vector3f position) {
        Matrix4f m = new Matrix4f(modelMatrix)
                .translate(position.x, position.y, position.z)
                .scale(JOINT_RADIUS);
        mvp.set(viewProjection).mul(m);
        shader.setMat4("uMVPMatrix", mvp);
        glDrawElements(GL_TRIANGLES, jointIndexCount, GL_UNSIGNED_INT, 0L);
    }

    /** Cheap "same point" test so we don't draw a second joint on top of the first
     *  when a bone has zero-length endpoint. */
    private static boolean approxEquals(Vector3f a, Vector3f b) {
        if (a == null || b == null) return false;
        return Math.abs(a.x - b.x) < 1e-5f
                && Math.abs(a.y - b.y) < 1e-5f
                && Math.abs(a.z - b.z) < 1e-5f;
    }

    /** Draw a single shaft primitive from {@code head} to {@code tail} in world space. */
    private void drawShaft(ShaderProgram shader, Matrix4f viewProjection, Matrix4f mvp,
                           Matrix4f modelMatrix, Vector3f head, Vector3f tail) {
        Vector3f delta = new Vector3f(tail).sub(head);
        float length = delta.length();
        if (length < 1e-5f) return;

        Matrix4f shaft = new Matrix4f(modelMatrix)
                .translate(head.x, head.y, head.z);
        applyAlignY(shaft, delta, length);
        shaft.scale(1.0f, length, 1.0f);

        mvp.set(viewProjection).mul(shaft);
        shader.setMat4("uMVPMatrix", mvp);
        glDrawElements(GL_TRIANGLES, shaftIndexCount, GL_UNSIGNED_INT, 0L);
    }

    /**
     * Multiply {@code into} by a rotation that maps the +Y axis onto {@code direction/length}.
     * Uses an axis-angle rotation in the cross-product plane; falls back gracefully for
     * the parallel and antiparallel cases.
     */
    private void applyAlignY(Matrix4f into, Vector3f direction, float length) {
        float dx = direction.x / length;
        float dy = direction.y / length;
        float dz = direction.z / length;
        // dot(+Y, dir) = dy
        if (dy > 0.9999f) {
            return; // already aligned
        }
        if (dy < -0.9999f) {
            // 180° around any axis perpendicular to Y; pick Z for stability
            into.rotate((float) Math.PI, 0, 0, 1);
            return;
        }
        // axis = +Y × dir = (dz, 0, -dx), normalized
        float ax = dz;
        float ay = 0;
        float az = -dx;
        float axisLen = (float) Math.sqrt(ax * ax + az * az);
        if (axisLen < 1e-6f) return;
        ax /= axisLen;
        az /= axisLen;
        float angle = (float) Math.acos(Math.max(-1.0, Math.min(1.0, dy)));
        into.rotate(angle, ax, ay, az);
    }

    private void uploadInterleaved(int vao, int vbo, int ebo, float[] verts, int[] indices) {
        glBindVertexArray(vao);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, verts, GL_STATIC_DRAW);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        // The GIZMO shader uses position-only at location 0 and reads color from a
        // uniform. Geometry happens to interleave a per-vertex color we don't use here;
        // we keep the 6-float stride and simply skip binding location 1.
        int stride = 6 * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    public void cleanup() {
        if (!initialized) return;
        glDeleteVertexArrays(jointVao);
        glDeleteBuffers(jointVbo);
        glDeleteBuffers(jointEbo);
        glDeleteVertexArrays(shaftVao);
        glDeleteBuffers(shaftVbo);
        glDeleteBuffers(shaftEbo);
        jointVao = jointVbo = jointEbo = 0;
        shaftVao = shaftVbo = shaftEbo = 0;
        initialized = false;
    }
}

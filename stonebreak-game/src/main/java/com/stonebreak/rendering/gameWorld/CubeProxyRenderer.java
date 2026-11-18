package com.stonebreak.rendering.gameWorld;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33.*;

/**
 * Proxy boxes for cubes the camera's visibility walk reaches but that are not loaded yet
 * ({@code cv_visible_cubes}' {@code out_missing}): depth-writing 32^3 boxes painted in the frame's fog
 * colour, so a cave mouth or the ground under an overhang shows "far away" instead of sky while its cube
 * streams in. They vanish as the cubes load (the streamer is loading exactly these, rule (c)).
 *
 * <p>Batched (one draw, positions rebuilt each frame in render space from exact long differences): GL 3.2
 * only, no instancing needed. Columns that are not READY are skipped while the FastLOD handover is active
 * (the LOD heightfield covers them; a fog box would hide it). GL thread.
 */
public final class CubeProxyRenderer {

    private static final int MAX_BOXES = CubeVisibility.MISSING_CAP;
    /** A box's 8 corners in the {@code (dx, dy, dz)} bit order (x = bit 0, y = bit 1, z = bit 2). */
    private static final int[] BOX_INDICES = {
        0, 2, 1, 1, 2, 3,   // -Z
        4, 5, 6, 5, 7, 6,   // +Z
        0, 1, 4, 1, 5, 4,   // -Y
        2, 6, 3, 3, 6, 7,   // +Y
        0, 4, 2, 2, 4, 6,   // -X
        1, 3, 5, 3, 7, 5};  // +X

    private ShaderProgram shader;
    private int vao, vbo, ebo;
    private FloatBuffer vertices;
    private int lastDrawn;
    private final Matrix4f viewProj = new Matrix4f();
    private boolean failed;

    /**
     * Draws the proxies of this frame's walk. Call right after the opaque terrain (depth test on, world
     * framebuffer bound); restores the program, VAO and polygon-offset state.
     */
    public void render(CubeVisibility visibility, ChunkRegionRenderer regions, Matrix4f projection, Matrix4f view,
                       Vector3f fogColor) {
        lastDrawn = 0;
        int n = visibility.missingCount();
        if (n == 0 || failed) {
            return;
        }
        if (shader == null && !init()) {
            return;
        }
        long ox = RenderOrigin.blockX(), oy = RenderOrigin.blockY(), oz = RenderOrigin.blockZ();
        vertices.clear();
        int boxes = 0;
        for (int i = 0; i < n && boxes < MAX_BOXES; i++) {
            long cx = visibility.missingX(i), cy = visibility.missingY(i), cz = visibility.missingZ(i);
            if (cx == visibility.cameraCubeX() && cy == visibility.cameraCubeY() && cz == visibility.cameraCubeZ()) {
                continue; // never box the camera in (its own cube streams first anyway)
            }
            if (!visibility.withinHorizontalRadius(cx, cz)) {
                continue; // a vertically stretched walk reaches past the render disc: no proxies out there
            }
            if (regions.handover() && !regions.isColumnReady(cx, cz)) {
                continue; // the far LOD draws this column
            }
            float x0 = (float) ((cx << 5) - ox);
            float y0 = (float) ((cy << 5) - oy);
            float z0 = (float) ((cz << 5) - oz);
            for (int c = 0; c < 8; c++) {
                vertices.put(x0 + ((c & 1) != 0 ? 32f : 0f))
                    .put(y0 + ((c & 2) != 0 ? 32f : 0f))
                    .put(z0 + ((c & 4) != 0 ? 32f : 0f));
            }
            boxes++;
        }
        if (boxes == 0) {
            return;
        }
        vertices.flip();

        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        boolean offset = glIsEnabled(GL_POLYGON_OFFSET_FILL);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_CULL_FACE); // closed boxes: winding never matters, the camera may sit inside one
        float offsetFactor = glGetFloat(GL_POLYGON_OFFSET_FACTOR), offsetUnits = glGetFloat(GL_POLYGON_OFFSET_UNITS);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        // A hair behind: the loaded neighbour's boundary faces always win the shared plane.
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1f, 8f);

        shader.bind();
        shader.setUniform("u_viewProj", projection.mul(view, viewProj));
        shader.setUniform("u_color", fogColor);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_BOXES * 8 * 3 * Float.BYTES, GL_STREAM_DRAW); // orphan
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertices);
        glDrawElements(GL_TRIANGLES, boxes * BOX_INDICES.length, GL_UNSIGNED_INT, 0L);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        lastDrawn = boxes;

        glBindVertexArray(prevVao);
        glUseProgram(prevProgram);
        if (!offset) glDisable(GL_POLYGON_OFFSET_FILL);
        if (cull) glEnable(GL_CULL_FACE);
        glPolygonOffset(offsetFactor, offsetUnits);
    }

    private boolean init() {
        try {
            shader = new ShaderProgram();
            shader.createVertexShader("""
                    #version 330 core
                    layout (location = 0) in vec3 aPos;
                    uniform mat4 u_viewProj;
                    void main() { gl_Position = u_viewProj * vec4(aPos, 1.0); }
                    """);
            shader.createFragmentShader("""
                    #version 330 core
                    uniform vec3 u_color;
                    out vec4 fragColor;
                    void main() { fragColor = vec4(u_color, 1.0); }
                    """);
            shader.link();
            shader.createUniform("u_viewProj");
            shader.createUniform("u_color");
        } catch (RuntimeException e) {
            failed = true;
            System.err.println("[CubeProxyRenderer] shader failed, no proxies: " + e.getMessage());
            return false;
        }
        vertices = MemoryUtil.memAllocFloat(MAX_BOXES * 8 * 3);
        IntBuffer indices = MemoryUtil.memAllocInt(MAX_BOXES * BOX_INDICES.length);
        for (int b = 0; b < MAX_BOXES; b++) {
            for (int idx : BOX_INDICES) {
                indices.put(b * 8 + idx);
            }
        }
        indices.flip();
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_BOXES * 8 * 3 * Float.BYTES, GL_STREAM_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
        glEnableVertexAttribArray(0);
        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
        glBindVertexArray(prevVao);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        MemoryUtil.memFree(indices);
        return true;
    }

    /** Proxies drawn last frame (F3). */
    public int lastDrawn() {
        return lastDrawn;
    }

    public void cleanup() {
        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            glDeleteBuffers(vbo);
            glDeleteBuffers(ebo);
            vao = vbo = ebo = 0;
        }
        if (vertices != null) {
            MemoryUtil.memFree(vertices);
            vertices = null;
        }
    }
}

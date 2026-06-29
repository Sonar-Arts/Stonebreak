package com.openmason.engine.rendering.postfx;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * A single fullscreen triangle used by post-processing passes.
 *
 * <p>One oversized triangle ((-1,-1), (3,-1), (-1,3)) covers the whole screen without the
 * diagonal seam of a two-triangle quad. The vertex shader derives UVs as {@code aPos * 0.5 + 0.5}.</p>
 */
public class FullscreenQuad {

    private static final float[] VERTICES = {
        -1.0f, -1.0f,
         3.0f, -1.0f,
        -1.0f,  3.0f
    };

    private int vao;
    private int vbo;

    public FullscreenQuad() {
        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, VERTICES, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Draws the triangle. The caller is responsible for binding the shader program and
     * configuring blend/depth state.
     */
    public void draw() {
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
    }

    public void cleanup() {
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            glDeleteBuffers(vbo);
            vbo = 0;
        }
    }
}

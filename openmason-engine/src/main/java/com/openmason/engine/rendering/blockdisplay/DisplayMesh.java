package com.openmason.engine.rendering.blockdisplay;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;

/**
 * GPU handles for one indexed display mesh built by {@link BlockDisplayMeshes}.
 */
public final class DisplayMesh implements AutoCloseable {
    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int indexCount;
    private final int vertexCount;
    private final String name;
    private boolean disposed = false;

    DisplayMesh(int vao, int vbo, int ebo, int indexCount, int vertexCount, String name) {
        this.vao = vao;
        this.vbo = vbo;
        this.ebo = ebo;
        this.indexCount = indexCount;
        this.vertexCount = vertexCount;
        this.name = name;
    }

    public void bind() {
        if (disposed) {
            throw new IllegalStateException("DisplayMesh has been disposed: " + name);
        }
        glBindVertexArray(vao);
    }

    public void unbind() {
        glBindVertexArray(0);
    }

    public void draw() {
        if (disposed) {
            throw new IllegalStateException("DisplayMesh has been disposed: " + name);
        }
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
    }

    public void bindAndDraw() {
        bind();
        draw();
    }

    public int getIndexCount() { return indexCount; }
    public int getVertexCount() { return vertexCount; }
    public int getTriangleCount() { return indexCount / 3; }
    public String getName() { return name; }

    @Override
    public void close() {
        if (!disposed) {
            glDeleteVertexArrays(vao);
            glDeleteBuffers(vbo);
            glDeleteBuffers(ebo);
            disposed = true;
        }
    }

    @Override
    public String toString() {
        return String.format("DisplayMesh[%s: VAO=%d, vertices=%d, triangles=%d]",
                name, vao, vertexCount, getTriangleCount());
    }
}

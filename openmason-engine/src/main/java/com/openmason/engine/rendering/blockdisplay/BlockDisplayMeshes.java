package com.openmason.engine.rendering.blockdisplay;

import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

/**
 * Builds and owns the standalone meshes that show a single block outside the
 * world: the held item, dropped items and inventory icons. World chunks never
 * come through here (they are MMS meshes).
 *
 * <p>Meshes are for the block texture array: vertices are
 * {@code x,y,z,u,v,layer} with tile-local UVs, on attributes 0 (position),
 * 1 (texcoord) and 4 (layer) — the world shader's array path.
 *
 * <p>Named meshes are replaced (and the old one released) when the same name
 * is built again. {@link #close()} releases everything this instance built.
 */
public final class BlockDisplayMeshes implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(BlockDisplayMeshes.class);

    /** {@code x,y,z,u,v,layer}. */
    private static final int FLOATS_PER_VERTEX = 6;

    /**
     * Unit-cube face corners, four per face (bottom-left, bottom-right,
     * top-right, top-left), in {@link #createLayeredCube display-cube face
     * order}: front(+Z), back(-Z), left(-X), right(+X), top(+Y), bottom(-Y).
     */
    private static final float[][] CUBE_FACES = {
        {-0.5f,-0.5f, 0.5f,  0.5f,-0.5f, 0.5f,  0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f}, // front +Z
        { 0.5f,-0.5f,-0.5f, -0.5f,-0.5f,-0.5f, -0.5f, 0.5f,-0.5f,  0.5f, 0.5f,-0.5f}, // back -Z
        {-0.5f,-0.5f,-0.5f, -0.5f,-0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f,-0.5f}, // left -X
        { 0.5f,-0.5f, 0.5f,  0.5f,-0.5f,-0.5f,  0.5f, 0.5f,-0.5f,  0.5f, 0.5f, 0.5f}, // right +X
        {-0.5f, 0.5f, 0.5f,  0.5f, 0.5f, 0.5f,  0.5f, 0.5f,-0.5f, -0.5f, 0.5f,-0.5f}, // top +Y
        {-0.5f,-0.5f,-0.5f,  0.5f,-0.5f,-0.5f,  0.5f,-0.5f, 0.5f, -0.5f,-0.5f, 0.5f}  // bottom -Y
    };

    private final Map<String, DisplayMesh> named = new ConcurrentHashMap<>();
    private boolean disposed = false;

    /**
     * Builds a unit cube for the block texture array, one layer per face.
     * Needs a current GL context, as does every builder here.
     *
     * @param name       mesh name; rebuilding a name releases the previous mesh
     * @param faceLayers six texture-array layers in display-cube face order:
     *                   front(+Z), back(-Z), left(-X), right(+X), top(+Y), bottom(-Y)
     */
    public DisplayMesh createLayeredCube(String name, float[] faceLayers) {
        ensureLive();
        // Per-vertex UV corners (bl, br, tr, tl). V is flipped so the texture
        // is upright — array layers are uploaded top-row-first.
        float[] uv = {0f,1f, 1f,1f, 1f,0f, 0f,0f};

        float[] vertices = new float[6 * 4 * FLOATS_PER_VERTEX]; // 6 faces * 4 verts
        int v = 0;
        for (int f = 0; f < 6; f++) {
            float layer = faceLayers[f];
            for (int i = 0; i < 4; i++) {
                vertices[v++] = CUBE_FACES[f][i * 3];
                vertices[v++] = CUBE_FACES[f][i * 3 + 1];
                vertices[v++] = CUBE_FACES[f][i * 3 + 2];
                vertices[v++] = uv[i * 2];
                vertices[v++] = uv[i * 2 + 1];
                vertices[v++] = layer;
            }
        }
        return register(name, upload(vertices, cubeIndices(), name));
    }

    /**
     * Builds a mesh from interleaved {@code x,y,z,u,v,layer} vertices for the
     * block texture array.
     *
     * @param name     mesh name; rebuilding a name releases the previous mesh
     * @param vertices interleaved {@code x,y,z,u,v,layer} (6 floats per vertex)
     * @param indices  triangle indices
     */
    public DisplayMesh createLayeredMesh(String name, float[] vertices, int[] indices) {
        ensureLive();
        return register(name, upload(vertices, indices, name));
    }

    @Override
    public void close() {
        if (!disposed) {
            named.values().forEach(DisplayMesh::close);
            named.clear();
            disposed = true;
            logger.debug("[BlockDisplayMeshes] Disposed all display meshes");
        }
    }

    private DisplayMesh register(String name, DisplayMesh mesh) {
        DisplayMesh previous = named.put(name, mesh);
        if (previous != null) {
            previous.close();
        }
        return mesh;
    }

    private void ensureLive() {
        if (disposed) {
            throw new IllegalStateException("BlockDisplayMeshes has been disposed");
        }
    }

    private static int[] cubeIndices() {
        int[] indices = new int[36];
        for (int f = 0; f < 6; f++) {
            int b = f * 4;
            int o = f * 6;
            indices[o]   = b;     indices[o+1] = b + 1; indices[o+2] = b + 2;
            indices[o+3] = b + 2; indices[o+4] = b + 3; indices[o+5] = b;
        }
        return indices;
    }

    /** Uploads interleaved {@code x,y,z,u,v,layer} vertices on attributes 0, 1 and 4. */
    private static DisplayMesh upload(float[] vertices, int[] indices, String name) {
        int vao = glGenVertexArrays();
        glBindVertexArray(vao);

        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertices.length);
        vertexBuffer.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);

        int ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indices.length);
        indexBuffer.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL_STATIC_DRAW);

        int stride = FLOATS_PER_VERTEX * Float.BYTES;
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(4, 1, GL_FLOAT, false, stride, 5 * Float.BYTES);
        glEnableVertexAttribArray(4);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

        return new DisplayMesh(vao, vbo, ebo, indices.length, vertices.length / FLOATS_PER_VERTEX, name);
    }
}

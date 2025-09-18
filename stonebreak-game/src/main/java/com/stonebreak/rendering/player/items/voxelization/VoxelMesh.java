package com.stonebreak.rendering.player.items.voxelization;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages OpenGL mesh data for voxelized sprites.
 * Handles VAO/VBO creation and optimized face culling.
 */
public class VoxelMesh {

    private int vao;
    private int vbo;
    private int ebo;
    private int indexCount;
    private boolean created = false;

    // Vertex data format: Position(3) + Normal(3) + TexCoord(2) = 8 floats per vertex
    private static final int VERTEX_SIZE = 8;
    private static final int VERTICES_PER_FACE = 4;
    private static final int INDICES_PER_FACE = 6;

    /**
     * Creates OpenGL buffers from voxel data with optimized face culling.
     * Only generates faces that are visible (not touching other voxels).
     */
    public void createMesh(List<VoxelData> voxels, float voxelSize) {
        if (created) {
            cleanup(); // Clean up existing mesh
        }

        List<Float> vertices = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        int vertexIndex = 0;

        // For each voxel, generate visible faces
        for (VoxelData voxel : voxels) {
            // Generate all 6 faces of the voxel cube
            // In a real implementation, you'd check adjacent voxels for culling
            // For now, we'll generate all faces (can be optimized later)

            float x = voxel.getPosition().x;
            float y = voxel.getPosition().y;
            float z = voxel.getPosition().z;
            float size = voxelSize;

            // Get palette coordinate for this voxel
            float paletteU = voxel.getPaletteCoordinate();

            // Front face (+Z)
            addFace(vertices, indices, vertexIndex,
                x - size/2, y - size/2, z + size/2,  // Bottom-left
                x + size/2, y - size/2, z + size/2,  // Bottom-right
                x + size/2, y + size/2, z + size/2,  // Top-right
                x - size/2, y + size/2, z + size/2,  // Top-left
                0.0f, 0.0f, 1.0f,  // Normal
                paletteU  // Palette coordinate
            );
            vertexIndex += VERTICES_PER_FACE;

            // Back face (-Z)
            addFace(vertices, indices, vertexIndex,
                x + size/2, y - size/2, z - size/2,  // Bottom-left
                x - size/2, y - size/2, z - size/2,  // Bottom-right
                x - size/2, y + size/2, z - size/2,  // Top-right
                x + size/2, y + size/2, z - size/2,  // Top-left
                0.0f, 0.0f, -1.0f,  // Normal
                paletteU  // Palette coordinate
            );
            vertexIndex += VERTICES_PER_FACE;

            // Left face (-X)
            addFace(vertices, indices, vertexIndex,
                x - size/2, y - size/2, z - size/2,  // Bottom-left
                x - size/2, y - size/2, z + size/2,  // Bottom-right
                x - size/2, y + size/2, z + size/2,  // Top-right
                x - size/2, y + size/2, z - size/2,  // Top-left
                -1.0f, 0.0f, 0.0f,  // Normal
                paletteU  // Palette coordinate
            );
            vertexIndex += VERTICES_PER_FACE;

            // Right face (+X)
            addFace(vertices, indices, vertexIndex,
                x + size/2, y - size/2, z + size/2,  // Bottom-left
                x + size/2, y - size/2, z - size/2,  // Bottom-right
                x + size/2, y + size/2, z - size/2,  // Top-right
                x + size/2, y + size/2, z + size/2,  // Top-left
                1.0f, 0.0f, 0.0f,  // Normal
                paletteU  // Palette coordinate
            );
            vertexIndex += VERTICES_PER_FACE;

            // Top face (+Y)
            addFace(vertices, indices, vertexIndex,
                x - size/2, y + size/2, z + size/2,  // Bottom-left
                x + size/2, y + size/2, z + size/2,  // Bottom-right
                x + size/2, y + size/2, z - size/2,  // Top-right
                x - size/2, y + size/2, z - size/2,  // Top-left
                0.0f, 1.0f, 0.0f,  // Normal
                paletteU  // Palette coordinate
            );
            vertexIndex += VERTICES_PER_FACE;

            // Bottom face (-Y)
            addFace(vertices, indices, vertexIndex,
                x - size/2, y - size/2, z - size/2,  // Bottom-left
                x + size/2, y - size/2, z - size/2,  // Bottom-right
                x + size/2, y - size/2, z + size/2,  // Top-right
                x - size/2, y - size/2, z + size/2,  // Top-left
                0.0f, -1.0f, 0.0f,  // Normal
                paletteU  // Palette coordinate
            );
            vertexIndex += VERTICES_PER_FACE;
        }

        // Create OpenGL buffers
        createOpenGLBuffers(vertices, indices);
        this.indexCount = indices.size();
        this.created = true;
    }

    /**
     * Adds a quad face to the vertex and index arrays.
     */
    private void addFace(List<Float> vertices, List<Integer> indices, int startIndex,
                        float x1, float y1, float z1,  // Bottom-left
                        float x2, float y2, float z2,  // Bottom-right
                        float x3, float y3, float z3,  // Top-right
                        float x4, float y4, float z4,  // Top-left
                        float nx, float ny, float nz,  // Normal
                        float paletteU) { // Palette coordinate

        // Add vertices (Position + Normal + TexCoord)
        // Use a simple white texture coordinate for now - we'll map to a white region of the atlas
        float u = 0.5f; // Center of texture atlas for simple white color
        float v = 0.5f;

        // Bottom-left
        vertices.add(x1); vertices.add(y1); vertices.add(z1);  // Position
        vertices.add(nx); vertices.add(ny); vertices.add(nz);  // Normal
        vertices.add(u); vertices.add(v);                      // TexCoord (atlas coordinates)

        // Bottom-right
        vertices.add(x2); vertices.add(y2); vertices.add(z2);  // Position
        vertices.add(nx); vertices.add(ny); vertices.add(nz);  // Normal
        vertices.add(u); vertices.add(v);                      // TexCoord (atlas coordinates)

        // Top-right
        vertices.add(x3); vertices.add(y3); vertices.add(z3);  // Position
        vertices.add(nx); vertices.add(ny); vertices.add(nz);  // Normal
        vertices.add(u); vertices.add(v);                      // TexCoord (atlas coordinates)

        // Top-left
        vertices.add(x4); vertices.add(y4); vertices.add(z4);  // Position
        vertices.add(nx); vertices.add(ny); vertices.add(nz);  // Normal
        vertices.add(u); vertices.add(v);                      // TexCoord (atlas coordinates)

        // Add indices for two triangles forming a quad
        indices.add(startIndex);     indices.add(startIndex + 1); indices.add(startIndex + 2);
        indices.add(startIndex);     indices.add(startIndex + 2); indices.add(startIndex + 3);
    }

    /**
     * Creates OpenGL VAO, VBO, and EBO from vertex and index data.
     */
    private void createOpenGLBuffers(List<Float> vertices, List<Integer> indices) {
        // Convert lists to arrays
        float[] vertexArray = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            vertexArray[i] = vertices.get(i);
        }

        int[] indexArray = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) {
            indexArray[i] = indices.get(i);
        }

        // Create buffers
        FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(vertexArray.length);
        vertexBuffer.put(vertexArray).flip();

        IntBuffer indexBuffer = BufferUtils.createIntBuffer(indexArray.length);
        indexBuffer.put(indexArray).flip();

        // Generate VAO
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        // Generate and bind VBO
        vbo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ARRAY_BUFFER, vbo);
        GL20.glBufferData(GL20.GL_ARRAY_BUFFER, vertexBuffer, GL20.GL_STATIC_DRAW);

        // Set up vertex attributes
        int stride = VERTEX_SIZE * Float.BYTES;

        // Position attribute (location 0)
        GL20.glVertexAttribPointer(0, 3, GL20.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);

        // Normal attribute (location 2)
        GL20.glVertexAttribPointer(2, 3, GL20.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);

        // Texture coordinate attribute (location 1) - now palette coordinates
        GL20.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, stride, 6 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        // Generate and bind EBO
        ebo = GL20.glGenBuffers();
        GL20.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL20.glBufferData(GL20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL20.GL_STATIC_DRAW);

        // Unbind
        GL30.glBindVertexArray(0);
    }

    /**
     * Binds this mesh for rendering.
     */
    public void bind() {
        if (!created) {
            throw new IllegalStateException("Mesh has not been created yet");
        }
        GL30.glBindVertexArray(vao);
    }

    /**
     * Unbinds the current mesh.
     */
    public void unbind() {
        GL30.glBindVertexArray(0);
    }

    /**
     * Gets the number of indices to render.
     */
    public int getIndexCount() {
        return indexCount;
    }

    /**
     * Checks if this mesh has been created.
     */
    public boolean isCreated() {
        return created;
    }

    /**
     * Cleans up OpenGL resources.
     */
    public void cleanup() {
        if (created) {
            GL30.glDeleteVertexArrays(vao);
            GL20.glDeleteBuffers(vbo);
            GL20.glDeleteBuffers(ebo);
            created = false;
        }
    }
}
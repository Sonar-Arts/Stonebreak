package com.openmason.main.systems.rendering.model.gmr;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of IVertexDataManager.
 * Manages mutable vertex position data for model rendering.
 */
public class VertexDataManager implements IVertexDataManager {

    private static final Logger logger = LoggerFactory.getLogger(VertexDataManager.class);

    private float[] vertices;
    private float[] texCoords;
    private int[] indices;

    @Override
    public void setData(float[] vertices, float[] texCoords, int[] indices) {
        this.vertices = vertices;
        this.texCoords = texCoords;
        this.indices = indices;
        logger.debug("Set vertex data: {} vertices, {} texCoords, {} indices",
            vertices != null ? vertices.length / 3 : 0,
            texCoords != null ? texCoords.length / 2 : 0,
            indices != null ? indices.length : 0);
    }

    @Override
    public float[] getVertices() {
        return vertices;
    }

    @Override
    public float[] getTexCoords() {
        return texCoords;
    }

    @Override
    public int[] getIndices() {
        return indices;
    }

    @Override
    public boolean updateVertexPosition(int globalIndex, Vector3f position) {
        if (vertices == null || globalIndex < 0) {
            return false;
        }

        int offset = globalIndex * 3;
        if (offset + 2 >= vertices.length) {
            logger.warn("Vertex index {} out of bounds (max {})", globalIndex, vertices.length / 3 - 1);
            return false;
        }

        vertices[offset] = position.x;
        vertices[offset + 1] = position.y;
        vertices[offset + 2] = position.z;
        return true;
    }

    @Override
    public void updateVertexPositions(int[] indices, Vector3f position) {
        if (vertices == null || indices == null || position == null) {
            return;
        }

        for (int vertexIndex : indices) {
            int offset = vertexIndex * 3;
            if (offset + 2 < vertices.length) {
                vertices[offset] = position.x;
                vertices[offset + 1] = position.y;
                vertices[offset + 2] = position.z;
            }
        }
    }

    @Override
    public Vector3f getVertexPosition(int globalIndex) {
        if (vertices == null || globalIndex < 0) {
            return null;
        }

        int offset = globalIndex * 3;
        if (offset + 2 >= vertices.length) {
            return null;
        }

        return new Vector3f(
            vertices[offset],
            vertices[offset + 1],
            vertices[offset + 2]
        );
    }

    @Override
    public int getTotalVertexCount() {
        return vertices != null ? vertices.length / 3 : 0;
    }

    @Override
    public float[] getAllMeshVertexPositions() {
        if (vertices == null) {
            return null;
        }
        return vertices.clone();
    }

    @Override
    public List<Integer> findMeshVerticesAtPosition(Vector3f position, float epsilon) {
        List<Integer> result = new ArrayList<>();
        if (vertices == null || position == null) {
            return result;
        }

        int count = vertices.length / 3;
        float epsilonSq = epsilon * epsilon;

        for (int i = 0; i < count; i++) {
            float dx = vertices[i * 3] - position.x;
            float dy = vertices[i * 3 + 1] - position.y;
            float dz = vertices[i * 3 + 2] - position.z;
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < epsilonSq) {
                result.add(i);
            }
        }
        return result;
    }

    @Override
    public void expandVertexArrays(int additionalVertices) {
        if (additionalVertices <= 0) {
            return;
        }

        int currentVertexCount = vertices != null ? vertices.length / 3 : 0;
        int newVertexCount = currentVertexCount + additionalVertices;

        // Expand vertices array
        float[] newVertices = new float[newVertexCount * 3];
        if (vertices != null) {
            System.arraycopy(vertices, 0, newVertices, 0, vertices.length);
        }
        vertices = newVertices;

        // Expand texCoords array
        float[] newTexCoords = new float[newVertexCount * 2];
        if (texCoords != null) {
            System.arraycopy(texCoords, 0, newTexCoords, 0, texCoords.length);
        }
        texCoords = newTexCoords;

        logger.debug("Expanded vertex arrays: {} -> {} vertices", currentVertexCount, newVertexCount);
    }

    @Override
    public void setVertexPositionDirect(int vertexIndex, float x, float y, float z) {
        if (vertices == null) {
            return;
        }

        int offset = vertexIndex * 3;
        if (offset + 2 < vertices.length) {
            vertices[offset] = x;
            vertices[offset + 1] = y;
            vertices[offset + 2] = z;
        }
    }

    @Override
    public void setTexCoordDirect(int vertexIndex, float u, float v) {
        if (texCoords == null) {
            return;
        }

        int offset = vertexIndex * 2;
        if (offset + 1 < texCoords.length) {
            texCoords[offset] = u;
            texCoords[offset + 1] = v;
        }
    }

    @Override
    public void setIndices(int[] indices) {
        this.indices = indices;
    }
}

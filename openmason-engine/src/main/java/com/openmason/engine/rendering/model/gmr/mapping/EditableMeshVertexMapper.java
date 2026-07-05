package com.openmason.engine.rendering.model.gmr.mapping;

import com.openmason.engine.rendering.model.gmr.editable.EditableMesh;
import com.openmason.engine.rendering.model.gmr.editable.RenderMesh;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link IUniqueVertexMapper} backed by the authoritative {@link EditableMesh}
 * and its derived {@link RenderMesh} corner maps — the exact (epsilon-free)
 * replacement for the legacy position-weld mapper.
 *
 * <p>Identity contract: "unique vertex index" == editable vertex id;
 * "mesh vertex index" == render corner index. Updated by the rebuild pipeline
 * after every render-mesh derivation.
 */
public class EditableMeshVertexMapper implements IUniqueVertexMapper {

    private static final Logger logger = LoggerFactory.getLogger(EditableMeshVertexMapper.class);

    private EditableMesh mesh;
    private RenderMesh renderMesh;

    /** Rebind to the current mesh + derived render mesh (called on every rebuild). */
    public void update(EditableMesh mesh, RenderMesh renderMesh) {
        this.mesh = mesh;
        this.renderMesh = renderMesh;
    }

    /**
     * @deprecated Position welding only happens at import time now
     *             ({@code MeshImporter}); the mapping here is exact and
     *             derived, never rebuilt from positions.
     */
    @Override
    @Deprecated
    public void buildMapping(float[] vertices) {
        logger.warn("buildMapping(float[]) ignored — mapping is derived from the EditableMesh");
    }

    @Override
    public Vector3f getUniqueVertexPosition(int uniqueIndex, float[] vertices) {
        if (mesh == null || uniqueIndex < 0 || uniqueIndex >= mesh.vertexCount()) {
            return null;
        }
        return mesh.position(uniqueIndex);
    }

    @Override
    public int[] getMeshIndicesForUniqueVertex(int uniqueIndex) {
        if (renderMesh == null || uniqueIndex < 0
                || uniqueIndex >= renderMesh.vertexIdToCorners().length) {
            return new int[0];
        }
        return renderMesh.vertexIdToCorners()[uniqueIndex].clone();
    }

    @Override
    public int getUniqueIndexForMeshVertex(int meshIndex) {
        if (renderMesh == null || meshIndex < 0
                || meshIndex >= renderMesh.cornerToVertexId().length) {
            return -1;
        }
        return renderMesh.cornerToVertexId()[meshIndex];
    }

    @Override
    public float[] getAllUniqueVertexPositions(float[] vertices) {
        if (mesh == null || mesh.vertexCount() == 0) {
            return null;
        }
        int count = mesh.vertexCount();
        float[] positions = new float[count * 3];
        Vector3f p = new Vector3f();
        for (int v = 0; v < count; v++) {
            mesh.position(v, p);
            positions[v * 3]     = p.x;
            positions[v * 3 + 1] = p.y;
            positions[v * 3 + 2] = p.z;
        }
        return positions;
    }

    @Override
    public int getUniqueVertexCount() {
        return mesh != null ? mesh.vertexCount() : 0;
    }

    @Override
    public boolean hasMapping() {
        return mesh != null && renderMesh != null;
    }

    @Override
    public void clear() {
        mesh = null;
        renderMesh = null;
    }
}

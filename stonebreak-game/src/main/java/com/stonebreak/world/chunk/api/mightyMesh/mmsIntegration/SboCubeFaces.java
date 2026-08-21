package com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;

import com.openmason.engine.voxel.mms.mmsGeometry.MmsCuboidGenerator;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.FaceStamp;
import com.openmason.engine.voxel.sbo.sboRenderer.SBOStampEmitter;
import com.stonebreak.blocks.BlockType;

/**
 * The SBO-cube fast path: which SBO block types are <em>exact unit cubes</em>
 * (six boundary-flush unit-square faces, no interior geometry, one state, no
 * translucency) and, for those, the per-face texture frame and layer their
 * stamp carries. Such blocks — most modern terrain (dirt, stone, grass, sand,
 * logs, leaves…) — can run through the native cube kernel, greedy merging and
 * the pulled-quad format exactly like legacy cube blocks instead of being
 * emitted as two raw triangles per face, which is what makes the compact
 * formats pay off on real worlds.
 *
 * <p>Built once from the stamp cache when the emitter is wired; indexed by
 * block id. Everything that isn't a clean cube stays on the stamp emitter.
 */
public final class SboCubeFaces {

    private static final float EPS = 1e-4f;

    /** Per block id: {@code [face][corner*2 + c]} texture coordinates in FACE_VERTEX_OFFSETS corner order. */
    private final float[][][] texCoords;
    /** Per block id, per face: texture-array layer. */
    private final float[][] layers;
    private final boolean[] cube;
    private final boolean[] shaped; // SBO block that is NOT a cube (stairs, slabs, snow…)

    public SboCubeFaces(SBOStampEmitter emitter) {
        int maxId = 0;
        for (BlockType type : BlockType.values()) {
            maxId = Math.max(maxId, type.getId());
        }
        texCoords = new float[maxId + 1][][];
        layers = new float[maxId + 1][];
        cube = new boolean[maxId + 1];
        shaped = new boolean[maxId + 1];
        for (BlockType type : BlockType.values()) {
            if (!emitter.hasBlock(type)) {
                continue;
            }
            int id = type.getId();
            if (!tryBuildCube(emitter, type, id)) {
                shaped[id] = true;
            }
        }
    }

    private boolean tryBuildCube(SBOStampEmitter emitter, BlockType type, int id) {
        if (emitter.getCache().variantCount(type) != 1 || emitter.isTranslucent(type)
                || type == BlockType.SNOW) { // snow layers scale by height per cell
            return false;
        }
        BlockStamp stamp = emitter.getCache().get(type);
        if (stamp == null || stamp.faces() == null || stamp.faces().length != 6) {
            return false;
        }
        float[][] uv = new float[6][8];
        float[] layer = new float[6];
        for (int face = 0; face < 6; face++) {
            if (stamp.interior() != null && stamp.interior()[face] != null
                    && stamp.interior()[face].vertexCount() != 0) {
                return false;
            }
            if (stamp.occludesFace() != null && !stamp.occludesFace()[face]) {
                return false;
            }
            FaceStamp fs = stamp.faces()[face];
            if (fs == null || fs.vertexCount() != 6) {
                return false;
            }
            if (!extractFace(fs, face, uv[face], layer, face)) {
                return false;
            }
        }
        texCoords[id] = uv;
        layers[id] = layer;
        cube[id] = true;
        return true;
    }

    /**
     * Verifies the six stamp vertices lie on the face's unit square (positions
     * ±0.5 relative to the cell centre, normal = face normal, one layer) and
     * reads the UV of each of the four cube corners in FACE_VERTEX_OFFSETS order.
     */
    private static boolean extractFace(FaceStamp fs, int face, float[] uvOut, float[] layerOut, int li) {
        float[] p = fs.positions();
        float[] n = fs.normals();
        float[] uv = fs.atlasUVs();
        float[] ly = fs.layers();
        float nx = face == 4 ? 1 : face == 5 ? -1 : 0;
        float ny = face == 0 ? 1 : face == 1 ? -1 : 0;
        float nz = face == 3 ? 1 : face == 2 ? -1 : 0;
        float layer = ly[0];
        boolean[] seen = new boolean[4];
        for (int v = 0; v < 6; v++) {
            if (Math.abs(n[v * 3] - nx) > EPS || Math.abs(n[v * 3 + 1] - ny) > EPS
                    || Math.abs(n[v * 3 + 2] - nz) > EPS || Math.abs(ly[v] - layer) > EPS) {
                return false;
            }
            // Which cube corner (0/1 offsets) is this vertex? Positions are ±0.5 about the centre.
            int corner = -1;
            for (int c = 0; c < 4; c++) {
                float ox = MmsCuboidGenerator.cornerOffset(face, c, 0) - 0.5f;
                float oy = MmsCuboidGenerator.cornerOffset(face, c, 1) - 0.5f;
                float oz = MmsCuboidGenerator.cornerOffset(face, c, 2) - 0.5f;
                if (Math.abs(p[v * 3] - ox) < EPS && Math.abs(p[v * 3 + 1] - oy) < EPS
                        && Math.abs(p[v * 3 + 2] - oz) < EPS) {
                    corner = c;
                    break;
                }
            }
            if (corner < 0) {
                return false;
            }
            if (seen[corner] && (Math.abs(uvOut[corner * 2] - uv[v * 2]) > EPS
                    || Math.abs(uvOut[corner * 2 + 1] - uv[v * 2 + 1]) > EPS)) {
                return false; // same corner, different UV — not a plain quad
            }
            uvOut[corner * 2] = uv[v * 2];
            uvOut[corner * 2 + 1] = uv[v * 2 + 1];
            seen[corner] = true;
        }
        for (boolean b : seen) {
            if (!b) {
                return false;
            }
        }
        layerOut[li] = layer;
        return true;
    }

    /** True when this SBO block is an exact unit cube and may take the cube path. */
    public boolean isCube(BlockType type) {
        int id = type.getId();
        return id < cube.length && cube[id];
    }

    /** True for SBO blocks that are NOT unit cubes (stay on the stamp emitter). */
    public boolean isShaped(BlockType type) {
        int id = type.getId();
        return id < shaped.length && shaped[id];
    }

    /** Texture coordinates of a cube face, FACE_VERTEX_OFFSETS corner order (8 floats). */
    public float[] texCoords(BlockType type, int face) {
        return texCoords[type.getId()][face];
    }

    public float layer(BlockType type, int face) {
        return layers[type.getId()][face];
    }

    public int cubeCount() {
        int n = 0;
        for (boolean b : cube) {
            if (b) {
                n++;
            }
        }
        return n;
    }
}

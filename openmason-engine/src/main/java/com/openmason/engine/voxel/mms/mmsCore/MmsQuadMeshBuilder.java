package com.openmason.engine.voxel.mms.mmsCore;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Builder for {@link MmsVertexFormat#QUAD16} meshes: collects packed quad
 * records ({@link MmsQuadCodec}) and emits an {@link MmsMeshData} whose
 * "vertex" bytes are the quad records (4 bytes per implied vertex), with no
 * per-mesh index data — pulled meshes draw through the shared quad index
 * buffer. Reusable like {@link MmsMeshBuilder}: {@link #reset()} keeps the
 * backing array.
 */
public final class MmsQuadMeshBuilder {

    private static final byte[] NO_INDICES = new byte[0];

    private int[] words;
    private int quadCount;
    private float originX, originY, originZ;

    public MmsQuadMeshBuilder(int estimatedQuads) {
        this.words = new int[Math.max(16, estimatedQuads) * 4];
    }

    /** World-space origin the quad positions are relative to (the region origin for arena uploads). */
    public MmsQuadMeshBuilder setOrigin(float x, float y, float z) {
        this.originX = x;
        this.originY = y;
        this.originZ = z;
        return this;
    }

    public float originX() {
        return originX;
    }

    public float originY() {
        return originY;
    }

    public float originZ() {
        return originZ;
    }

    /**
     * Adds one face rectangle. {@code x,y,z} are whole-block coordinates
     * relative to the origin (the rectangle's minimum corner cell), {@code w}
     * spans {@code MmsCuboidGenerator.uAxis(face)}, {@code h} the v axis.
     *
     * @return false (nothing added) when the mesh is already at the per-draw
     *         quad limit — the caller emits the face as per-vertex geometry
     */
    public boolean addQuad(int x, int y, int z, int face, int w, int h, int orientation,
                           boolean alpha, boolean translucent, int layer,
                           float l0, float l1, float l2, float l3) {
        if (quadCount >= MmsQuadCodec.MAX_QUADS_PER_DRAW) {
            return false; // u16 shared index buffer: caller routes the rest elsewhere
        }
        int base = quadCount * 4;
        if (base + 4 > words.length) {
            words = Arrays.copyOf(words, words.length + (words.length >> 1) + 4);
        }
        words[base] = MmsQuadCodec.word0(x, y, z, face, w);
        words[base + 1] = MmsQuadCodec.word1(h, orientation, alpha, translucent, layer);
        words[base + 2] = MmsQuadCodec.word2(l0, l1, l2, l3);
        words[base + 3] = 0;
        quadCount++;
        return true;
    }

    /** True once the mesh holds the most quads one shared-EBO draw can address. */
    public boolean isFull() {
        return quadCount >= MmsQuadCodec.MAX_QUADS_PER_DRAW;
    }

    public int getQuadCount() {
        return quadCount;
    }

    public boolean isEmpty() {
        return quadCount == 0;
    }

    public MmsQuadMeshBuilder reset() {
        quadCount = 0;
        originX = originY = originZ = 0f;
        return this;
    }

    /** Packs the quads into an immutable {@link MmsVertexFormat#QUAD16} mesh. */
    public MmsMeshData build() {
        if (quadCount == 0) {
            return MmsMeshData.empty();
        }
        byte[] bytes = new byte[quadCount * MmsQuadCodec.QUAD_BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asIntBuffer().put(words, 0, quadCount * 4);
        return MmsMeshData.fromPacked(bytes, NO_INDICES, true, quadCount * 4, quadCount * 6,
            MmsVertexFormat.QUAD16, originX, originY, originZ);
    }

    public MmsMeshData buildAndReset() {
        MmsMeshData mesh = build();
        reset();
        return mesh;
    }
}

package com.openmason.engine.voxel.mms.mmsCore;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/**
 * Mighty Mesh System - Immutable CPU-side mesh data container.
 *
 * This is the core data structure for all mesh operations in MMS.
 * Follows SOLID principles through immutability and single responsibility.
 *
 * <p>Two representations exist:
 * <ul>
 *   <li><b>Packed</b> (built by {@link MmsMeshBuilder#build()}): one interleaved
 *       vertex byte array in the exact GPU layout ({@link MmsBufferLayout},
 *       40 bytes/vertex, flags pre-quantized to unsigned bytes) plus an index
 *       byte array (u16 when the mesh has ≤65536 vertices, u32 otherwise).
 *       Upload is a bulk copy; retained CPU memory is ~40 B/vertex instead of
 *       the 52 B/vertex of eight separate float arrays. The SoA getters below
 *       still work on packed instances but MATERIALIZE a fresh array per call
 *       (flags come back 1/255-quantized — the exact values the GPU sees) —
 *       they are for tests/tools, not hot paths.</li>
 *   <li><b>SoA</b> (the public constructors): eight per-attribute float arrays,
 *       interleaved at upload time. Used by FastLOD and direct constructors.</li>
 * </ul>
 *
 * <p>{@code equals}/{@code hashCode} are representation-sensitive: two meshes
 * compare equal only when they hold the same representation with identical
 * content.
 *
 * Design Philosophy:
 * - Immutable: Thread-safe by design (KISS principle)
 * - Validated: All data validated on construction (fail-fast)
 * - Efficient: Zero-copy transfer to GPU buffers
 * - Extensible: Easy to add new vertex attributes
 *
 * @since MMS 1.0
 */
public final class MmsMeshData {

    // Vertex attributes (SoA representation; all null when packed)
    private final float[] vertexPositions;    // x, y, z per vertex
    private final float[] textureCoordinates;  // u, v per vertex
    private final float[] vertexNormals;       // nx, ny, nz per vertex
    private final float[] waterHeightFlags;    // water height encoding per vertex
    private final float[] alphaTestFlags;      // alpha test flag per vertex
    private final float[] translucentFlags;    // translucent render flag per vertex (0.0 opaque/cutout, 1.0 translucent blend)
    private final float[] lightValues;         // per-vertex world light in [0,1], 1.0 = fully lit
    private final float[] layerIndices;        // texture-array layer index per vertex

    // Index data (SoA representation; null when packed)
    private final int[] indices;
    private final int indexCount;

    // Packed representation (both null in SoA form)
    private final byte[] packedVertexData;     // interleaved MmsBufferLayout stride, native order
    private final byte[] packedIndexData;      // u16 or u32 indices, native order
    private final boolean shortIndices;
    /** Byte layout of {@link #packedVertexData} (LEGACY40 for SoA meshes). */
    private final MmsVertexFormat format;
    /** World-space origin the packed positions are relative to (0 for LEGACY40 / SoA). */
    private final float originX, originY, originZ;

    // Metadata
    private final int vertexCount;
    private final int triangleCount;
    private final long memoryUsageBytes;
    /** True when any vertex carries a non-zero translucent flag (ice). */
    private final boolean hasTranslucent;

    // Empty mesh singleton
    private static final MmsMeshData EMPTY = new MmsMeshData(
        new float[0], new float[0], new float[0],
        new float[0], new float[0], new float[0], new float[0], new int[0], 0
    );

    /**
     * Creates immutable mesh data with full validation.
     *
     * @param vertexPositions Vertex positions (x,y,z per vertex)
     * @param textureCoordinates Texture coordinates (u,v per vertex)
     * @param vertexNormals Normal vectors (nx,ny,nz per vertex)
     * @param waterHeightFlags Water height encoding (1 float per vertex)
     * @param alphaTestFlags Alpha test flags (1 float per vertex)
     * @param translucentFlags Translucent render flags (1 float per vertex)
     * @param indices Triangle indices
     * @param indexCount Number of valid indices to use
     * @throws NullPointerException if any array is null
     * @throws IllegalArgumentException if arrays are inconsistent or invalid
     */
    public MmsMeshData(float[] vertexPositions, float[] textureCoordinates, float[] vertexNormals,
                       float[] waterHeightFlags, float[] alphaTestFlags, float[] translucentFlags,
                       int[] indices, int indexCount) {
        this(vertexPositions, textureCoordinates, vertexNormals,
             waterHeightFlags, alphaTestFlags, translucentFlags,
             defaultLightArray(vertexPositions), indices, indexCount);
    }

    /**
     * Creates immutable mesh data including per-vertex light values.
     *
     * @param lightValues Per-vertex world light in [0,1] (1 float per vertex)
     */
    public MmsMeshData(float[] vertexPositions, float[] textureCoordinates, float[] vertexNormals,
                       float[] waterHeightFlags, float[] alphaTestFlags, float[] translucentFlags,
                       float[] lightValues,
                       int[] indices, int indexCount) {
        this(vertexPositions, textureCoordinates, vertexNormals,
             waterHeightFlags, alphaTestFlags, translucentFlags, lightValues,
             defaultLayerArray(vertexPositions), indices, indexCount);
    }

    /**
     * Creates immutable mesh data including per-vertex texture-array layer indices.
     *
     * @param layerIndices Per-vertex texture-array layer index (1 float per vertex)
     */
    public MmsMeshData(float[] vertexPositions, float[] textureCoordinates, float[] vertexNormals,
                       float[] waterHeightFlags, float[] alphaTestFlags, float[] translucentFlags,
                       float[] lightValues, float[] layerIndices,
                       int[] indices, int indexCount) {
        // Null checks
        this.vertexPositions = Objects.requireNonNull(vertexPositions, "vertexPositions cannot be null");
        this.textureCoordinates = Objects.requireNonNull(textureCoordinates, "textureCoordinates cannot be null");
        this.vertexNormals = Objects.requireNonNull(vertexNormals, "vertexNormals cannot be null");
        this.waterHeightFlags = Objects.requireNonNull(waterHeightFlags, "waterHeightFlags cannot be null");
        this.alphaTestFlags = Objects.requireNonNull(alphaTestFlags, "alphaTestFlags cannot be null");
        this.translucentFlags = Objects.requireNonNull(translucentFlags, "translucentFlags cannot be null");
        this.lightValues = Objects.requireNonNull(lightValues, "lightValues cannot be null");
        this.layerIndices = Objects.requireNonNull(layerIndices, "layerIndices cannot be null");
        this.indices = Objects.requireNonNull(indices, "indices cannot be null");
        this.indexCount = indexCount;
        this.packedVertexData = null;
        this.packedIndexData = null;
        this.shortIndices = false;
        this.format = MmsVertexFormat.LEGACY40;
        this.originX = 0f;
        this.originY = 0f;
        this.originZ = 0f;

        // Validate index count
        if (indexCount < 0 || indexCount > indices.length) {
            throw new IllegalArgumentException(
                String.format("indexCount out of bounds: %d (array length: %d)", indexCount, indices.length)
            );
        }

        // Calculate derived metadata
        this.vertexCount = vertexPositions.length / 3;
        this.triangleCount = indexCount / 3;

        // Validate array sizes are consistent (fail-fast on construction)
        if (!isEmpty()) {
            validateArraySizes();
        }

        // Calculate memory usage
        this.memoryUsageBytes = estimateMemoryUsage();

        boolean translucent = false;
        for (float flag : translucentFlags) {
            if (flag != 0f) {
                translucent = true;
                break;
            }
        }
        this.hasTranslucent = translucent;
    }

    /** Packed-representation constructor; see {@link #fromPacked}. */
    private MmsMeshData(byte[] packedVertexData, byte[] packedIndexData, boolean shortIndices,
                        int vertexCount, int indexCount, MmsVertexFormat format,
                        float originX, float originY, float originZ) {
        this.format = format;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.vertexPositions = null;
        this.textureCoordinates = null;
        this.vertexNormals = null;
        this.waterHeightFlags = null;
        this.alphaTestFlags = null;
        this.translucentFlags = null;
        this.lightValues = null;
        this.layerIndices = null;
        this.indices = null;
        this.packedVertexData = packedVertexData;
        this.packedIndexData = packedIndexData;
        this.shortIndices = shortIndices;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.triangleCount = indexCount / 3;
        this.memoryUsageBytes = packedVertexData.length + packedIndexData.length + 64L;

        // Translucent flag = byte 2 of the packed flags word per vertex.
        boolean translucent = false;
        if (format.pulled()) {
            ByteBuffer quads = ByteBuffer.wrap(packedVertexData).order(ByteOrder.nativeOrder());
            for (int q = 0; q < vertexCount / 4; q++) {
                if (MmsQuadCodec.translucent(quads.getInt(q * MmsQuadCodec.QUAD_BYTES + 4))) {
                    translucent = true;
                    break;
                }
            }
        } else {
            int stride = format.stride();
            int flagByte = format.flagsOffset() + 2;
            for (int i = 0; i < vertexCount; i++) {
                if (packedVertexData[i * stride + flagByte] != 0) {
                    translucent = true;
                    break;
                }
            }
        }
        this.hasTranslucent = translucent;
    }

    /**
     * Creates a packed-representation mesh from data already interleaved into
     * the {@link MmsBufferLayout} GPU layout (native byte order). The arrays
     * are taken by reference — the caller hands over ownership.
     *
     * @param packedVertexData exactly {@code vertexCount * VERTEX_STRIDE_BYTES} bytes
     * @param packedIndexData  exactly {@code indexCount * 2} bytes (u16) or
     *                         {@code indexCount * 4} bytes (u32)
     * @param shortIndices     true when indices are unsigned shorts
     */
    public static MmsMeshData fromPacked(byte[] packedVertexData, byte[] packedIndexData,
                                         boolean shortIndices, int vertexCount, int indexCount) {
        return fromPacked(packedVertexData, packedIndexData, shortIndices, vertexCount, indexCount,
            MmsVertexFormat.LEGACY40, 0f, 0f, 0f);
    }

    /**
     * Wraps packed bytes in the given {@link MmsVertexFormat}. For formats with
     * local positions, {@code origin*} is the world-space point the stored
     * positions are relative to (the region origin for arena uploads).
     */
    public static MmsMeshData fromPacked(byte[] packedVertexData, byte[] packedIndexData,
                                         boolean shortIndices, int vertexCount, int indexCount,
                                         MmsVertexFormat format,
                                         float originX, float originY, float originZ) {
        Objects.requireNonNull(packedVertexData, "packedVertexData cannot be null");
        Objects.requireNonNull(packedIndexData, "packedIndexData cannot be null");
        Objects.requireNonNull(format, "format cannot be null");
        if (vertexCount <= 0 || indexCount <= 0) {
            throw new IllegalArgumentException("Packed meshes cannot be empty; use empty()");
        }
        if (packedVertexData.length != vertexCount * format.stride()) {
            throw new IllegalArgumentException(String.format(
                "Packed vertex data size mismatch: expected %d bytes, got %d",
                vertexCount * format.stride(), packedVertexData.length));
        }
        int bytesPerIndex = shortIndices ? Short.BYTES : Integer.BYTES;
        if (format.pulled()) {
            if (packedIndexData.length != 0 || !shortIndices || vertexCount % 4 != 0
                    || indexCount != vertexCount / 4 * 6) {
                throw new IllegalArgumentException("Pulled meshes carry no index data: "
                    + "vertexCount must be 4×quads and indexCount 6×quads");
            }
        } else if (packedIndexData.length != indexCount * bytesPerIndex) {
            throw new IllegalArgumentException(String.format(
                "Packed index data size mismatch: expected %d bytes, got %d",
                indexCount * bytesPerIndex, packedIndexData.length));
        }
        if (indexCount % 3 != 0) {
            throw new IllegalArgumentException(
                "Index count must be multiple of 3 (triangles): " + indexCount);
        }
        if (shortIndices && vertexCount > 65536) {
            throw new IllegalArgumentException(
                "u16 indices cannot address " + vertexCount + " vertices");
        }
        return new MmsMeshData(packedVertexData, packedIndexData, shortIndices,
            vertexCount, indexCount, format, originX, originY, originZ);
    }

    /**
     * Converts an SoA mesh into the packed representation: one interleaved
     * vertex byte array in the exact {@link MmsBufferLayout} GPU layout (flags
     * quantized to unsigned bytes, native byte order — byte-identical to what
     * {@link MmsMeshBuilder#build()} and the legacy upload interleave produce)
     * plus a u16 (≤65536 vertices) or u32 index byte array.
     *
     * <p>Intended for worker threads: packing off the render thread turns the
     * GL upload into a bulk copy and lets the mesh join shared region arenas
     * (which require packed u16 form). Packed and empty meshes return
     * {@code this} unchanged.
     */
    public MmsMeshData toPacked() {
        if (isPacked() || isEmpty()) {
            return this;
        }
        MmsVertexFormat fmt = MmsVertexFormat.active().stampFormat();
        if (!fmt.localPositions()) {
            return toPacked(fmt, 0f, 0f, 0f);
        }
        // Self-contained origin: the integer floor of the mesh's minimum corner.
        // Good for stand-alone handles; arena uploads must pass the REGION origin
        // via toPacked(format, ox, oy, oz) so every mesh in the region agrees.
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        for (int i = 0; i < vertexCount; i++) {
            minX = Math.min(minX, vertexPositions[i * 3]);
            minY = Math.min(minY, vertexPositions[i * 3 + 1]);
            minZ = Math.min(minZ, vertexPositions[i * 3 + 2]);
        }
        return toPacked(fmt, (float) Math.floor(minX), (float) Math.floor(minY), (float) Math.floor(minZ));
    }

    /**
     * Packs this SoA mesh in {@code fmt} with positions relative to the given
     * world-space origin (ignored by formats with absolute positions).
     */
    public MmsMeshData toPacked(MmsVertexFormat fmt, float originX, float originY, float originZ) {
        if (isPacked() || isEmpty()) {
            return this;
        }
        fmt = fmt.stampFormat(); // SoA geometry can never be pulled
        if (!fmt.localPositions()) {
            originX = originY = originZ = 0f;
        }
        byte[] vertexBytes = new byte[vertexCount * fmt.stride()];
        ByteBuffer vertexBuf = ByteBuffer.wrap(vertexBytes).order(ByteOrder.nativeOrder());
        for (int i = 0; i < vertexCount; i++) {
            int p3 = i * 3, p2 = i * 2;
            fmt.encode(vertexBuf,
                vertexPositions[p3], vertexPositions[p3 + 1], vertexPositions[p3 + 2],
                textureCoordinates[p2], textureCoordinates[p2 + 1],
                vertexNormals[p3], vertexNormals[p3 + 1], vertexNormals[p3 + 2],
                waterHeightFlags[i], alphaTestFlags[i], translucentFlags[i], lightValues[i],
                layerIndices[i], originX, originY, originZ);
        }
        boolean shortIdx = vertexCount <= 65536;
        byte[] indexBytes = new byte[indexCount * (shortIdx ? Short.BYTES : Integer.BYTES)];
        ByteBuffer indexBuf = ByteBuffer.wrap(indexBytes).order(ByteOrder.nativeOrder());
        if (shortIdx) {
            for (int i = 0; i < indexCount; i++) {
                indexBuf.putShort((short) indices[i]);
            }
        } else {
            for (int i = 0; i < indexCount; i++) {
                indexBuf.putInt(indices[i]);
            }
        }
        return fromPacked(vertexBytes, indexBytes, shortIdx, vertexCount, indexCount,
            fmt, originX, originY, originZ);
    }

    /**
     * Returns a reusable empty mesh instance.
     *
     * @return Singleton empty mesh
     */
    public static MmsMeshData empty() {
        return EMPTY;
    }

    /**
     * Checks if this mesh has no geometry.
     *
     * @return true if mesh is empty
     */
    public boolean isEmpty() {
        return indexCount == 0 || vertexCount == 0;
    }

    /**
     * Validates that all array sizes are consistent.
     * Called automatically during construction.
     *
     * @throws IllegalArgumentException if arrays are inconsistent
     */
    private void validateArraySizes() {
        if (textureCoordinates.length != vertexCount * 2) {
            throw new IllegalArgumentException(
                String.format("Texture coordinate array size mismatch: expected %d, got %d",
                    vertexCount * 2, textureCoordinates.length)
            );
        }

        if (vertexNormals.length != vertexCount * 3) {
            throw new IllegalArgumentException(
                String.format("Normal array size mismatch: expected %d, got %d",
                    vertexCount * 3, vertexNormals.length)
            );
        }

        if (waterHeightFlags.length != vertexCount) {
            throw new IllegalArgumentException(
                String.format("Water flag array size mismatch: expected %d, got %d",
                    vertexCount, waterHeightFlags.length)
            );
        }

        if (alphaTestFlags.length != vertexCount) {
            throw new IllegalArgumentException(
                String.format("Alpha test flag array size mismatch: expected %d, got %d",
                    vertexCount, alphaTestFlags.length)
            );
        }

        if (translucentFlags.length != vertexCount) {
            throw new IllegalArgumentException(
                String.format("Translucent flag array size mismatch: expected %d, got %d",
                    vertexCount, translucentFlags.length)
            );
        }

        if (lightValues.length != vertexCount) {
            throw new IllegalArgumentException(
                String.format("Light values array size mismatch: expected %d, got %d",
                    vertexCount, lightValues.length)
            );
        }

        if (layerIndices.length != vertexCount) {
            throw new IllegalArgumentException(
                String.format("Layer index array size mismatch: expected %d, got %d",
                    vertexCount, layerIndices.length)
            );
        }

        if (indexCount % 3 != 0) {
            throw new IllegalArgumentException(
                String.format("Index count must be multiple of 3 (triangles), got %d", indexCount)
            );
        }
    }

    /**
     * Estimates memory usage in bytes.
     *
     * @return Estimated memory usage
     */
    private long estimateMemoryUsage() {
        return (long) vertexPositions.length * Float.BYTES +
               (long) textureCoordinates.length * Float.BYTES +
               (long) vertexNormals.length * Float.BYTES +
               (long) waterHeightFlags.length * Float.BYTES +
               (long) alphaTestFlags.length * Float.BYTES +
               (long) translucentFlags.length * Float.BYTES +
               (long) lightValues.length * Float.BYTES +
               (long) layerIndices.length * Float.BYTES +
               (long) indices.length * Integer.BYTES +
               // Object overhead (approximate)
               64L;
    }

    // === Packed representation accessors ===

    /** True when this mesh holds the packed (interleaved bytes) representation. */
    public boolean isPacked() {
        return packedVertexData != null;
    }

    /**
     * Interleaved vertex bytes in the {@link MmsBufferLayout} GPU layout;
     * null for SoA-form meshes. Do not modify.
     */
    public byte[] getPackedVertexData() {
        return packedVertexData;
    }

    /** Index bytes (u16 or u32, native order); null for SoA-form meshes. Do not modify. */
    public byte[] getPackedIndexData() {
        return packedIndexData;
    }

    /** True when the packed index data is unsigned shorts. */
    public boolean hasShortIndices() {
        return shortIndices;
    }

    /** Byte layout of the packed vertex data ({@link MmsVertexFormat#LEGACY40} for SoA meshes). */
    public MmsVertexFormat getFormat() {
        return format;
    }

    /** World-space X the packed positions are relative to (0 unless the format has local positions). */
    public float getOriginX() {
        return originX;
    }

    public float getOriginY() {
        return originY;
    }

    public float getOriginZ() {
        return originZ;
    }

    /**
     * True when any vertex carries a non-zero translucent flag (ice). Lets
     * the transparent render pass skip meshes that would contribute nothing —
     * previously every visible chunk's whole geometry re-drew in that pass
     * just for its shader-discarded fragments.
     */
    public boolean hasTranslucentGeometry() {
        return hasTranslucent;
    }

    // === Getters ===
    // On SoA-form meshes these return direct references (do not modify).
    // On packed meshes they MATERIALIZE a fresh array per call by decoding the
    // interleaved bytes — flag/light values come back 1/255-quantized, exactly
    // what the GPU sees. Test/tool use only; never call these per frame.

    /**
     * Gets vertex position data (x,y,z per vertex).
     */
    public float[] getVertexPositions() {
        if (packedVertexData != null) {
            return materializePositions();
        }
        return vertexPositions;
    }

    /**
     * Gets texture coordinate data (u,v per vertex).
     */
    public float[] getTextureCoordinates() {
        if (packedVertexData != null) {
            return materialize(2, (buf, i, c) -> format.texCoord(buf, i, c));
        }
        return textureCoordinates;
    }

    /**
     * Gets vertex normal data (nx,ny,nz per vertex).
     */
    public float[] getVertexNormals() {
        if (packedVertexData != null) {
            return materialize(3, (buf, i, c) -> format.normal(buf, i, c));
        }
        return vertexNormals;
    }

    /**
     * Gets water height flag data (1 float per vertex).
     */
    public float[] getWaterHeightFlags() {
        if (packedVertexData != null) {
            return materializeFlagBytes(0);
        }
        return waterHeightFlags;
    }

    /**
     * Gets alpha test flag data (1 float per vertex).
     */
    public float[] getAlphaTestFlags() {
        if (packedVertexData != null) {
            return materializeFlagBytes(1);
        }
        return alphaTestFlags;
    }

    /**
     * Gets translucent flag data (1 float per vertex, 1.0 = translucent).
     */
    public float[] getTranslucentFlags() {
        if (packedVertexData != null) {
            return materializeFlagBytes(2);
        }
        return translucentFlags;
    }

    /**
     * Gets per-vertex world light values in [0,1] (1 float per vertex).
     */
    public float[] getLightValues() {
        if (packedVertexData != null) {
            return materializeFlagBytes(3);
        }
        return lightValues;
    }

    /**
     * Gets per-vertex texture-array layer indices (1 float per vertex).
     */
    public float[] getLayerIndices() {
        if (packedVertexData != null) {
            return materialize(1, (buf, i, c) -> format.layer(buf, i));
        }
        return layerIndices;
    }

    /**
     * Gets index data.
     */
    public int[] getIndices() {
        if (format.pulled()) {
            // Shared quad pattern (b, b+2, b+1, b, b+3, b+2) — what the shared EBO draws.
            int[] out = new int[indexCount];
            for (int q = 0, i = 0; q < vertexCount / 4; q++) {
                int b = q * 4;
                out[i++] = b;
                out[i++] = b + 2;
                out[i++] = b + 1;
                out[i++] = b;
                out[i++] = b + 3;
                out[i++] = b + 2;
            }
            return out;
        }
        if (packedIndexData != null) {
            int[] out = new int[indexCount];
            java.nio.ByteBuffer buf =
                java.nio.ByteBuffer.wrap(packedIndexData).order(java.nio.ByteOrder.nativeOrder());
            if (shortIndices) {
                for (int i = 0; i < indexCount; i++) {
                    out[i] = Short.toUnsignedInt(buf.getShort(i * Short.BYTES));
                }
            } else {
                for (int i = 0; i < indexCount; i++) {
                    out[i] = buf.getInt(i * Integer.BYTES);
                }
            }
            return out;
        }
        return indices;
    }

    /** Decodes {@code components} floats per vertex starting at a stride offset. */
    private interface Component {
        float read(java.nio.ByteBuffer buf, int vertex, int component);
    }

    private float[] materialize(int components, Component reader) {
        float[] out = new float[vertexCount * components];
        java.nio.ByteBuffer buf =
            java.nio.ByteBuffer.wrap(packedVertexData).order(java.nio.ByteOrder.nativeOrder());
        for (int i = 0; i < vertexCount; i++) {
            for (int c = 0; c < components; c++) {
                out[i * components + c] = reader.read(buf, i, c);
            }
        }
        return out;
    }

    private float[] materializePositions() {
        return materialize(3, (buf, i, c) -> format.position(buf, i, c, originX, originY, originZ));
    }

    private float[] materializeFlagBytes(int flagByte) {
        float[] out = new float[vertexCount];
        java.nio.ByteBuffer buf =
            java.nio.ByteBuffer.wrap(packedVertexData).order(java.nio.ByteOrder.nativeOrder());
        for (int i = 0; i < vertexCount; i++) {
            int packed = format.flags(buf, i);
            out[i] = ((packed >>> (flagByte * 8)) & 0xFF) / 255.0f;
        }
        return out;
    }

    /**
     * Gets the number of valid indices to use for rendering.
     *
     * @return Index count
     */
    public int getIndexCount() {
        return indexCount;
    }

    /**
     * Gets the number of vertices in this mesh.
     *
     * @return Vertex count
     */
    public int getVertexCount() {
        return vertexCount;
    }

    /**
     * Gets the number of triangles in this mesh.
     *
     * @return Triangle count
     */
    public int getTriangleCount() {
        return triangleCount;
    }

    /**
     * Gets the estimated memory usage in bytes.
     *
     * @return Memory usage in bytes
     */
    public long getMemoryUsageBytes() {
        return memoryUsageBytes;
    }

    // === Object methods ===

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MmsMeshData that = (MmsMeshData) o;
        if (indexCount != that.indexCount || vertexCount != that.vertexCount
                || isPacked() != that.isPacked()) {
            return false;
        }
        if (isPacked()) {
            return shortIndices == that.shortIndices &&
                   Arrays.equals(packedVertexData, that.packedVertexData) &&
                   Arrays.equals(packedIndexData, that.packedIndexData);
        }
        return Arrays.equals(vertexPositions, that.vertexPositions) &&
               Arrays.equals(textureCoordinates, that.textureCoordinates) &&
               Arrays.equals(vertexNormals, that.vertexNormals) &&
               Arrays.equals(waterHeightFlags, that.waterHeightFlags) &&
               Arrays.equals(alphaTestFlags, that.alphaTestFlags) &&
               Arrays.equals(translucentFlags, that.translucentFlags) &&
               Arrays.equals(lightValues, that.lightValues) &&
               Arrays.equals(layerIndices, that.layerIndices) &&
               Arrays.equals(indices, that.indices);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(indexCount, vertexCount);
        if (isPacked()) {
            result = 31 * result + Arrays.hashCode(packedVertexData);
            result = 31 * result + Arrays.hashCode(packedIndexData);
            return result;
        }
        result = 31 * result + Arrays.hashCode(vertexPositions);
        result = 31 * result + Arrays.hashCode(textureCoordinates);
        result = 31 * result + Arrays.hashCode(vertexNormals);
        result = 31 * result + Arrays.hashCode(waterHeightFlags);
        result = 31 * result + Arrays.hashCode(alphaTestFlags);
        result = 31 * result + Arrays.hashCode(translucentFlags);
        result = 31 * result + Arrays.hashCode(lightValues);
        result = 31 * result + Arrays.hashCode(layerIndices);
        result = 31 * result + Arrays.hashCode(indices);
        return result;
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "MmsMeshData{empty}";
        }
        return String.format("MmsMeshData{vertices=%d, triangles=%d, memory=%d bytes}",
            vertexCount, triangleCount, memoryUsageBytes);
    }

    /** Returns a full-brightness (1.0) light array sized to match the given position array. */
    private static float[] defaultLightArray(float[] positions) {
        int verts = positions == null ? 0 : positions.length / 3;
        float[] arr = new float[verts];
        java.util.Arrays.fill(arr, 1.0f);
        return arr;
    }

    /** Returns a zero-filled (layer 0) layer-index array sized to match the given position array. */
    private static float[] defaultLayerArray(float[] positions) {
        int verts = positions == null ? 0 : positions.length / 3;
        return new float[verts];
    }
}

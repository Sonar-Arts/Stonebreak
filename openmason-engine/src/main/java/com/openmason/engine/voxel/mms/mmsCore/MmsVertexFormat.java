package com.openmason.engine.voxel.mms.mmsCore;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.nio.ByteBuffer;

/**
 * The GPU byte layout of one packed MMS vertex — the single seam every
 * producer ({@link MmsMeshBuilder#build()}, {@link MmsMeshData#toPacked}),
 * decoder ({@link MmsMeshData} getters, {@link MmsMeshValidator}) and GL
 * binder ({@link MmsRenderableHandle#setupVertexAttributes()},
 * {@code MmsChunkRegion.rebuildVao}) goes through, so a format is selected in
 * exactly one place: {@code -Dstonebreak.mesh.vertexformat=legacy40|compact20}.
 *
 * <p>Every format presents the SAME five shader attributes at the same
 * locations (vec3 position, vec2 texCoord, vec3 normal, vec4 aFlags, float
 * aLayer) — the integer formats are converted by the attribute pointer type,
 * so the fragment stages and all consumers are format-agnostic. The one
 * vertex-stage addition is {@link #ORIGIN_LOCATION}: compact formats store
 * positions as fixed-point offsets from a per-mesh origin, and the VAO carries
 * that origin as a divisor-1 (per-instance) attribute {@code vec4(ox, oy, oz,
 * scale)} — VAO state, so no draw path has to set a uniform. VAOs that don't
 * enable the attribute (UI, items, entities) read GL's default generic value
 * {@code (0,0,0,1)}, i.e. identity. Shaders compute
 * {@code worldPos = aOrigin.xyz + position * aOrigin.w}.
 *
 * <p>Strides stay multiples of 4 bytes: {@code MmsStagingRing.upload} rejects
 * unaligned arena offsets, and a 4-aligned stride keeps every vertex offset
 * aligned by construction.
 */
public enum MmsVertexFormat {

    /**
     * The original 40-byte layout: pos 3×f32 (world-space), uv 2×f32,
     * normal 3×f32, flags 4×u8 normalized, layer f32. Origin unused (0).
     */
    LEGACY40(40, false, 1f),

    /**
     * 20 bytes: pos 3×i16 in 1/32-block units relative to the mesh origin, uv
     * 2×f16, normal 3×i8 snorm (+1 pad), layer u16, flags 4×u8 normalized.
     * Arbitrary normals survive, so SBO stamps, crosses and FastLOD smooth
     * shading all work unchanged.
     *
     * <p>X and Z reach ±1024 blocks, far past the 8×8 chunk region and 16×16
     * LOD region they have to span. Y is the tight axis — every producer passes
     * originY = 0, so a Y offset is an absolute world Y and the format has to
     * span {@code WORLD_HEIGHT} on its own — and it is stored against a window
     * shifted up by {@link #COMPACT_Y_SHIFT}, giving {@link #COMPACT_MIN_Y}
     * ..{@link #COMPACT_MAX_Y}: the whole 1024-tall column with room to spare
     * above it and 512 blocks of slack below for model meshes pivoted on zero.
     *
     * <p>Both properties were bought in one 2026-09-04 pass, and neither
     * touched the 20-byte layout or a line of GLSL. The unit was 1/64 block
     * (±512) — sized for the old 256-tall world like the pulled codecs, and
     * missed when they were widened, so every stamp above y=512 threw and its
     * chunk rendered as nothing. Halving the resolution doubled the range,
     * because the unit is data: it rides to the shader as {@code aOrigin.w}.
     * That still left the top face of a top-layer block one unit out of reach,
     * which the Y shift closed the same way — it rides in {@code aOrigin.y}.
     * Blocks are the geometric unit here and stamp models are authored on the
     * 1/16 grid, so 1/32 represents authored vertices exactly.
     */
    COMPACT20(20, true, 1f / 32f),

    /**
     * Vertex pulling: 16 bytes per QUAD ({@link MmsQuadCodec}) — nominally 4
     * bytes per implied vertex — read by the vertex shader from a buffer
     * texture on {@link MmsQuadCodec#QUAD_TEXTURE_UNIT}; no per-mesh index
     * data (shared quad EBO). Only axis-aligned cube faces can be encoded;
     * the mesher routes everything else to a {@link #stampFormat()} mesh.
     * The origin attribute carries {@code w = -1} so the shared shaders
     * detect pull mode per VAO without any per-draw uniform.
     */
    QUAD16(4, true, -1f),

    /**
     * Vertex pulling for FastLOD terrain: 16 bytes per cell/skirt/tree quad
     * ({@link MmsLodQuadCodec}) with four octahedral corner normals, half-block
     * Y and tall spans. Origin attribute {@code w = -2} selects the LOD decoder
     * in the shared shaders. Produced only by the FastLOD mesher.
     */
    LODQUAD16(4, true, -2f),

    /**
     * Vertex pulling for water: 16 bytes per cell face / LOD sea sheet
     * ({@link MmsWaterQuadCodec}) with four u8 vertex heights and four u8
     * surface flags. Origin attribute {@code w = -3}; decoded in
     * {@code water.vert}.
     */
    WATERQUAD16(4, true, -3f);

    /** Attribute location of the per-mesh origin {@code vec4(ox, oy, oz, scale)}. */
    public static final int ORIGIN_LOCATION = 5;

    /**
     * {@link #COMPACT20} fixed-point position units per block — derived from
     * the format's own scale so the constructor argument stays the one place
     * the unit is chosen. It sets both the stored resolution (1/32 block) and
     * how far a position can sit from the mesh origin — {@link
     * #COMPACT_MAX_BLOCKS} on X/Z, and {@link #COMPACT_MIN_Y}..{@link
     * #COMPACT_MAX_Y} on the shifted Y axis, which has to cover the world
     * height because chunk meshes carry a Y origin of 0.
     */
    public static final float COMPACT_POSITION_UNITS = 1f / COMPACT20.positionScale;

    /** Largest {@link #COMPACT20} X/Z offset from the mesh origin, in blocks. */
    public static final float COMPACT_MAX_BLOCKS = Short.MAX_VALUE * COMPACT20.positionScale;

    /**
     * Blocks {@link #COMPACT20} adds to the mesh's Y origin before storing a
     * position — see {@link #originYShift()}. i16 is symmetric about zero but a
     * world column is not: every producer passes originY = 0 and no geometry
     * sits below y=0, so half the range was spent on blocks that cannot exist
     * while the top of a 1024-tall world fell one unit outside it. Shifting the
     * window up by half a world trades unreachable depth for the headroom.
     */
    public static final float COMPACT_Y_SHIFT = 512f;

    /** Lowest Y a {@link #COMPACT20} mesh can store, as an offset from its origin. */
    public static final float COMPACT_MIN_Y = Short.MIN_VALUE * COMPACT20.positionScale
        + COMPACT_Y_SHIFT;

    /** Highest Y a {@link #COMPACT20} mesh can store, as an offset from its origin. */
    public static final float COMPACT_MAX_Y = COMPACT_MAX_BLOCKS + COMPACT_Y_SHIFT;

    /**
     * The shipped default. Promoted from LEGACY40 on 2026-08-20 after the chunk
     * footprint lab (Testing/chunk-lab.sh) measured 172 → 16 bytes per cube
     * face with pixel-identical output; {@code -Dstonebreak.mesh.vertexformat=
     * legacy40|compact20} selects the older layouts.
     */
    public static final MmsVertexFormat DEFAULT = QUAD16;

    private static final String PROPERTY = "stonebreak.mesh.vertexformat";
    private static volatile MmsVertexFormat active;

    private final int stride;
    private final boolean localPositions;
    private final float positionScale;

    MmsVertexFormat(int stride, boolean localPositions, float positionScale) {
        this.stride = stride;
        this.localPositions = localPositions;
        this.positionScale = positionScale;
    }

    /** Bytes per vertex. */
    public int stride() {
        return stride;
    }

    /** True when positions are stored relative to the mesh origin (needs the origin attribute). */
    public boolean localPositions() {
        return localPositions;
    }

    /** World units per stored position unit (the {@code aOrigin.w} the shader multiplies by). */
    public float positionScale() {
        return positionScale;
    }

    /**
     * Blocks this format adds to a mesh's Y origin before storing positions:
     * {@link #COMPACT_Y_SHIFT} for {@link #COMPACT20}, zero for every other
     * format (the pulled codecs document Y as absolute world Y and must keep
     * reading an unshifted origin). Callers pass the mesh's true origin
     * everywhere; only the stored bytes and the origin attribute carry it.
     */
    public float originYShift() {
        return this == COMPACT20 ? COMPACT_Y_SHIFT : 0f;
    }

    /** True for vertex-pulling formats (per-quad records, no per-mesh indices). */
    public boolean pulled() {
        return this == QUAD16 || this == LODQUAD16 || this == WATERQUAD16;
    }

    /** The format water meshes use when this format is active: {@link #WATERQUAD16} under pulling. */
    public MmsVertexFormat waterFormat() {
        return pulled() ? WATERQUAD16 : this;
    }

    /**
     * The pulled format FastLOD terrain uses when this format is active:
     * {@link #LODQUAD16} under any pulled format, else this format (per-vertex).
     */
    public MmsVertexFormat lodFormat() {
        return pulled() ? LODQUAD16 : this;
    }

    /** Bytes per index element in a mesh/arena of this format (0 when pulled). */
    public int indexStride() {
        return pulled() ? 0 : Short.BYTES;
    }

    /**
     * The format non-quad geometry uses when this format is active: the
     * format itself, or {@link #COMPACT20} under {@link #QUAD16} (stamps,
     * crosses, water and FastLOD can't be pulled).
     */
    public MmsVertexFormat stampFormat() {
        return pulled() ? COMPACT20 : this;
    }

    /**
     * The process-wide format, read once from {@value #PROPERTY} (default
     * {@link #DEFAULT}). Unknown names log and fall back to legacy.
     */
    public static MmsVertexFormat active() {
        MmsVertexFormat f = active;
        if (f == null) {
            synchronized (MmsVertexFormat.class) {
                f = active;
                if (f == null) {
                    String name = System.getProperty(PROPERTY, DEFAULT.name()).trim().toUpperCase();
                    try {
                        f = valueOf(name);
                    } catch (IllegalArgumentException e) {
                        System.err.println("[MmsVertexFormat] unknown " + PROPERTY + "=" + name
                            + " — using LEGACY40");
                        f = LEGACY40;
                    }
                    active = f;
                    System.out.println("[MmsVertexFormat] packed chunk vertices: " + f
                        + (f.pulled() ? " (16 bytes/quad, pulled)" : " (" + f.stride + " bytes/vertex)")
                        + (f == DEFAULT ? "" : " [-D" + PROPERTY + "]"));
                }
            }
        }
        return f;
    }

    /** Test hook: overrides the active format for the rest of the process. */
    public static void override(MmsVertexFormat format) {
        synchronized (MmsVertexFormat.class) {
            active = format;
        }
    }

    // ─── Encoding ──────────────────────────────────────────────────────────

    /**
     * Appends one vertex at the buffer's position. {@code x,y,z} are WORLD
     * coordinates; local formats subtract the origin here.
     *
     * @throws IllegalArgumentException when a local position doesn't fit the
     *                                  fixed-point range (the mesh was given
     *                                  the wrong origin)
     */
    public void encode(ByteBuffer dst, float x, float y, float z, float u, float v,
                       float nx, float ny, float nz,
                       float water, float alpha, float translucent, float light, float layer,
                       float originX, float originY, float originZ) {
        switch (this) {
            case LEGACY40 -> {
                dst.putFloat(x).putFloat(y).putFloat(z);
                dst.putFloat(u).putFloat(v);
                dst.putFloat(nx).putFloat(ny).putFloat(nz);
                dst.putInt(MmsBufferLayout.packFlags(water, alpha, translucent, light));
                dst.putFloat(layer);
            }
            case QUAD16, LODQUAD16, WATERQUAD16 -> throw new UnsupportedOperationException(
                "pulled meshes are built per quad via MmsQuadMeshBuilder");
            case COMPACT20 -> {
                dst.putShort(fixed16(x - originX, "x"));
                dst.putShort(fixed16Y(y - originY));
                dst.putShort(fixed16(z - originZ, "z"));
                dst.putShort(Float.floatToFloat16(u));
                dst.putShort(Float.floatToFloat16(v));
                dst.put(snorm8(nx)).put(snorm8(ny)).put(snorm8(nz)).put((byte) 0);
                dst.putShort((short) Math.clamp(Math.round(layer), 0, 65535));
                dst.putInt(MmsBufferLayout.packFlags(water, alpha, translucent, light));
            }
        }
    }

    private static short fixed16(float local, String axis) {
        long q = Math.round(local * COMPACT_POSITION_UNITS);
        if (q < Short.MIN_VALUE || q > Short.MAX_VALUE) {
            throw new IllegalArgumentException("COMPACT20 position out of range on " + axis
                + ": local " + local + " blocks (max ±" + COMPACT_MAX_BLOCKS
                + ") — wrong mesh origin?");
        }
        return (short) q;
    }

    /**
     * Packs a Y offset, which is stored relative to {@code originY +
     * COMPACT_Y_SHIFT} rather than to originY. Nothing outside this class sees
     * the shift: {@link #position} adds it back, and the GPU gets it for free
     * because {@link #createOriginBuffer} folds it into the origin attribute
     * the shaders already add.
     */
    private static short fixed16Y(float local) {
        long q = Math.round((local - COMPACT_Y_SHIFT) * COMPACT_POSITION_UNITS);
        if (q < Short.MIN_VALUE || q > Short.MAX_VALUE) {
            throw new IllegalArgumentException("COMPACT20 position out of range on y: local "
                + local + " blocks (allowed " + COMPACT_MIN_Y + " .. " + COMPACT_MAX_Y
                + ") — wrong mesh origin?");
        }
        return (short) q;
    }

    private static byte snorm8(float n) {
        return (byte) Math.round(Math.clamp(n, -1f, 1f) * 127f);
    }

    // ─── Decoding (CPU readback: getters, validator, cache, tests) ─────────

    /** Position component {@code c} (0..2) of vertex {@code i} in world space. */
    public float position(ByteBuffer src, int i, int c, float originX, float originY, float originZ) {
        int base = i * stride;
        return switch (this) {
            case LEGACY40 -> src.getFloat(base + c * 4);
            case COMPACT20 -> src.getShort(base + c * 2) * positionScale
                + (c == 0 ? originX : c == 1 ? originY + COMPACT_Y_SHIFT : originZ);
            case QUAD16 -> MmsQuadCodec.position(src, i >> 2, i & 3, c, originX, originY, originZ);
            case LODQUAD16 -> MmsLodQuadCodec.position(src, i >> 2, i & 3, c, originX, originY, originZ);
            case WATERQUAD16 -> MmsWaterQuadCodec.position(src, i >> 2, i & 3, c, originX, originY, originZ);
        };
    }

    public float texCoord(ByteBuffer src, int i, int c) {
        int base = i * stride;
        return switch (this) {
            case LEGACY40 -> src.getFloat(base + 12 + c * 4);
            case COMPACT20 -> Float.float16ToFloat(src.getShort(base + 6 + c * 2));
            case QUAD16 -> MmsQuadCodec.texCoord(src, i >> 2, i & 3, c);
            case LODQUAD16 -> MmsLodQuadCodec.texCoord(src, i >> 2, i & 3, c);
            case WATERQUAD16 -> MmsWaterQuadCodec.texCoord(src, i >> 2, i & 3, c);
        };
    }

    public float normal(ByteBuffer src, int i, int c) {
        int base = i * stride;
        return switch (this) {
            case LEGACY40 -> src.getFloat(base + 20 + c * 4);
            case COMPACT20 -> Math.max(-1f, src.get(base + 10 + c) / 127f);
            case QUAD16 -> MmsQuadCodec.normal(src, i >> 2, c);
            case LODQUAD16 -> MmsLodQuadCodec.normal(src, i >> 2, i & 3, c);
            case WATERQUAD16 -> MmsWaterQuadCodec.normal(src, i >> 2, c);
        };
    }

    /** The packed flags word (water | alpha<<8 | translucent<<16 | light<<24). */
    public int flags(ByteBuffer src, int i) {
        if (this == QUAD16) {
            return MmsQuadCodec.flags(src, i >> 2, i & 3);
        }
        if (this == LODQUAD16) {
            return MmsLodQuadCodec.flags(src, i >> 2);
        }
        if (this == WATERQUAD16) {
            return MmsWaterQuadCodec.flags(src, i >> 2, i & 3);
        }
        return src.getInt(i * stride + flagsOffset());
    }

    public float layer(ByteBuffer src, int i) {
        int base = i * stride;
        return switch (this) {
            case LEGACY40 -> src.getFloat(base + 36);
            case COMPACT20 -> src.getShort(base + 14) & 0xFFFF;
            case QUAD16 -> MmsQuadCodec.layer(src, i >> 2);
            case LODQUAD16 -> MmsLodQuadCodec.layer(src, i >> 2);
            case WATERQUAD16 -> 0f;
        };
    }

    /** Byte offset of the packed flags word inside a vertex. */
    public int flagsOffset() {
        return switch (this) {
            case LEGACY40 -> (int) MmsBufferLayout.FLAGS_OFFSET;
            case COMPACT20 -> 16;
            case QUAD16, LODQUAD16, WATERQUAD16 -> -1; // no per-vertex flags word; see the codecs' flags()
        };
    }

    // ─── GL binding ────────────────────────────────────────────────────────

    /**
     * Records the five attribute pointers for this format on the currently
     * bound VAO + ARRAY_BUFFER. Shader-visible values are identical across
     * formats (floats / normalized floats).
     */
    public void setupVertexAttributes() {
        switch (this) {
            case LEGACY40 -> {
                GL33.glVertexAttribDivisor(MmsBufferLayout.POSITION_LOCATION, 0);
                attrib(MmsBufferLayout.POSITION_LOCATION, 3, GL15.GL_FLOAT, false, 0);
                attrib(MmsBufferLayout.TEXTURE_LOCATION, 2, GL15.GL_FLOAT, false, 12);
                attrib(MmsBufferLayout.NORMAL_LOCATION, 3, GL15.GL_FLOAT, false, 20);
                attrib(MmsBufferLayout.FLAGS_LOCATION, 4, GL15.GL_UNSIGNED_BYTE, true, 32);
                attrib(MmsBufferLayout.LAYER_LOCATION, 1, GL15.GL_FLOAT, false, 36);
            }
            case QUAD16, LODQUAD16, WATERQUAD16 -> {
                // Pulled: no per-vertex attributes; the shader reads the quad
                // buffer texture by gl_VertexID. Disable the five slots so a
                // VAO reused across formats can't leave stale pointers.
                for (int loc = 0; loc <= MmsBufferLayout.LAYER_LOCATION; loc++) {
                    GL30.glDisableVertexAttribArray(loc);
                }
            }
            case COMPACT20 -> {
                GL33.glVertexAttribDivisor(MmsBufferLayout.POSITION_LOCATION, 0);
                attrib(MmsBufferLayout.POSITION_LOCATION, 3, GL15.GL_SHORT, false, 0);
                attrib(MmsBufferLayout.TEXTURE_LOCATION, 2, GL30.GL_HALF_FLOAT, false, 6);
                attrib(MmsBufferLayout.NORMAL_LOCATION, 3, GL15.GL_BYTE, true, 10);
                attrib(MmsBufferLayout.LAYER_LOCATION, 1, GL15.GL_UNSIGNED_SHORT, false, 14);
                attrib(MmsBufferLayout.FLAGS_LOCATION, 4, GL15.GL_UNSIGNED_BYTE, true, 16);
            }
        }
    }

    private void attrib(int location, int size, int type, boolean normalized, long offset) {
        GL30.glEnableVertexAttribArray(location);
        GL30.glVertexAttribPointer(location, size, type, normalized, stride, offset);
    }

    /**
     * Binds {@code originBufferId} (a 16-byte buffer holding
     * {@code vec4(ox, oy, oz, scale)}) as the per-instance origin attribute on
     * the currently bound VAO. Call after {@link #setupVertexAttributes()};
     * leaves ARRAY_BUFFER bound to the origin buffer. For {@link #LEGACY40}
     * the attribute is left disabled (generic default = identity).
     */
    public void setupOriginAttribute(int originBufferId) {
        if (!localPositions) {
            GL30.glDisableVertexAttribArray(ORIGIN_LOCATION);
            return;
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, originBufferId);
        GL30.glEnableVertexAttribArray(ORIGIN_LOCATION);
        GL30.glVertexAttribPointer(ORIGIN_LOCATION, 4, GL15.GL_FLOAT, false, 16, 0);
        GL33.glVertexAttribDivisor(ORIGIN_LOCATION, 1);
        if (pulled()) {
            // Compatibility-profile drivers alias generic attribute 0 to
            // glVertex and emit NO vertices while array 0 is disabled. Keep it
            // enabled as a per-instance read of the same 16-byte origin buffer
            // (the pull branch never reads `position`), so the draw runs.
            GL30.glEnableVertexAttribArray(MmsBufferLayout.POSITION_LOCATION);
            GL30.glVertexAttribPointer(MmsBufferLayout.POSITION_LOCATION, 3, GL15.GL_FLOAT, false, 16, 0);
            GL33.glVertexAttribDivisor(MmsBufferLayout.POSITION_LOCATION, 1);
        }
    }

    /** Creates and fills a 16-byte origin buffer for this format. */
    public int createOriginBuffer(float originX, float originY, float originZ) {
        int id = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
            new float[]{originX, originY + originYShift(), originZ, positionScale},
            GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return id;
    }
}

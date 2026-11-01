package com.openmason.engine.rendering;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.opengl.GL15;

import java.util.HashMap;
import java.util.Map;

/**
 * The frame's rendering origin: every position handed to a shader is expressed
 * relative to this point instead of relative to world zero.
 *
 * <h2>Why</h2>
 * Shader arithmetic is {@code float32}, whose spacing grows with magnitude —
 * 0.001 blocks at 10k from spawn, 0.008 at 100k. Feeding absolute world
 * coordinates through {@code projection * view * model} therefore quantizes
 * geometry in proportion to how far the player has walked, which showed up as
 * jittering terrain and z-fighting far out, and — far earlier — as per-pixel
 * noise on mobs, whose flat normals are <em>differentiated</em> out of the
 * interpolated position ({@code dFdx}/{@code dFdy}) and so amplify the same
 * error by the reciprocal of the per-pixel delta.
 *
 * <p>Rebasing on a point near the camera makes the error scale with distance
 * <em>from the viewer</em> rather than from spawn, so the angular error is a
 * constant ~2^-23 radians everywhere: permanently sub-pixel, at any world
 * coordinate.
 *
 * <h2>The invariant</h2>
 * Everything a shader sees lives in "render space" — world axes, translated by
 * {@code -origin}:
 * <ul>
 *   <li>view matrix: {@code V_render = V_world * T(+origin)} (the camera is
 *       built at its relative position — see {@code Camera.getViewMatrix()});</li>
 *   <li>model matrices: {@code M_render = T(-origin) * M_world} — use
 *       {@link #modelAt};</li>
 *   <li>mesh origin attributes: baked relative, see
 *       {@link #createOriginBuffer};</li>
 *   <li>light-space matrices: {@code L_render = L_world * T(+origin)} — use
 *       {@link #applyToWorldConsumer};</li>
 *   <li>loose world positions in uniforms (camera position, point lights,
 *       frustum AABBs): {@link #toRender}.</li>
 * </ul>
 * Because the same translation is applied to both sides of every product, the
 * rendered result is identical — only the precision changes.
 *
 * <h2>Why it is snapped, and why Y is untouched</h2>
 * The origin moves in {@link #GRID}-block steps rather than tracking the camera
 * continuously. A moving origin shifts the coordinate system the shadow
 * cascades texel-snap against and the phase of world-space procedural patterns
 * (water waves), so each move costs a one-frame shimmer; snapping makes that
 * cost land once per {@value #GRID} blocks travelled instead of every frame,
 * while still bounding near-camera coordinates to ~{@value #GRID}·√2, where
 * float32 spacing is ~8e-6 blocks.
 *
 * <p>Y is deliberately always zero: world height is bounded (a few hundred
 * blocks), so the vertical axis never loses precision, and leaving it alone
 * keeps every {@code floor(pos.y)} block-base computation, the COMPACT20 Y
 * shift and the vertical half of the pulled codecs working unchanged.
 *
 * <p>All methods are render-thread only.
 */
public final class RenderOrigin {

    /**
     * Blocks between origin steps. A power of two so the snap is exact and the
     * step is representable in float32 at any world coordinate the game can
     * reach, which is what makes {@code meshOrigin - origin} an exact
     * subtraction for integer mesh origins.
     */
    public static final int GRID = 64;

    private static float originX;
    private static float originZ;

    /**
     * Live per-mesh origin buffers, keyed by GL buffer id, valued by the
     * <em>absolute</em> origin they were created with ({@code x, y, z, scale}).
     * Held so {@link #refreshBuffers} can re-bake them when the origin steps;
     * the mesh data itself never moves.
     */
    private static final Map<Integer, float[]> ORIGIN_BUFFERS = new HashMap<>();

    private static final Vector3f SCRATCH = new Vector3f();

    private RenderOrigin() {}

    /** Current origin X (a multiple of {@link #GRID}). */
    public static float x() {
        return originX;
    }

    /** Current origin Z (a multiple of {@link #GRID}). */
    public static float z() {
        return originZ;
    }

    /**
     * Re-snaps the origin to the grid cell containing the camera and re-bakes
     * every live mesh origin buffer if it moved. Call once per frame on the
     * render thread, before any pass reads a matrix or builds a uniform.
     *
     * @return true when the origin stepped this frame
     */
    public static boolean update(float cameraX, float cameraZ) {
        float snappedX = (float) (Math.floor(cameraX / GRID) * GRID);
        float snappedZ = (float) (Math.floor(cameraZ / GRID) * GRID);
        if (snappedX == originX && snappedZ == originZ) {
            return false;
        }
        originX = snappedX;
        originZ = snappedZ;
        refreshBuffers();
        return true;
    }

    /**
     * Resets the origin to world zero without touching GL — for tests and for
     * teardown between worlds, where no mesh buffers are live.
     */
    public static void reset() {
        originX = 0f;
        originZ = 0f;
        ORIGIN_BUFFERS.clear();
    }

    // ── World → render space ────────────────────────────────────────────────

    /** Writes {@code world - origin} into {@code dest} and returns it. */
    public static Vector3f toRender(Vector3fc world, Vector3f dest) {
        return dest.set(world.x() - originX, world.y(), world.z() - originZ);
    }

    /**
     * A shared-scratch {@link #toRender} for uniform uploads. The result is
     * valid only until the next call — never store it.
     */
    public static Vector3f toRender(Vector3fc world) {
        return toRender(world, SCRATCH);
    }

    /** Writes {@code render + origin} into {@code dest} and returns it. */
    public static Vector3f toWorld(Vector3fc render, Vector3f dest) {
        return dest.set(render.x() + originX, render.y(), render.z() + originZ);
    }

    /** A model matrix translated to a world position, in render space. */
    public static Matrix4f modelAt(float worldX, float worldY, float worldZ) {
        return new Matrix4f().translation(worldX - originX, worldY, worldZ - originZ);
    }

    /** {@link #modelAt} into a caller-owned matrix, for allocation-free paths. */
    public static Matrix4f modelAt(Matrix4f dest, float worldX, float worldY, float worldZ) {
        return dest.translation(worldX - originX, worldY, worldZ - originZ);
    }

    /**
     * Rebases a matrix that was built to consume <em>world</em> positions so it
     * consumes render-space ones instead ({@code m * T(+origin)}) — the
     * conversion for any matrix standing to the left of a position, such as a
     * light-space matrix fitted in world coordinates but used to draw
     * render-space geometry.
     *
     * <p>Mutates and returns {@code m}.
     */
    public static Matrix4f acceptRenderSpace(Matrix4f m) {
        return m.translate(originX, 0f, originZ);
    }

    /**
     * The inverse of {@link #acceptRenderSpace}: rebases a matrix that consumes
     * render-space positions so it consumes <em>world</em> ones instead
     * ({@code m * T(-origin)}). For consumers whose inputs cannot be rebased —
     * GPU-side AABBs uploaded in world space, for one.
     *
     * <p>Mutates and returns {@code m}.
     */
    public static Matrix4f acceptWorldSpace(Matrix4f m) {
        return m.translate(-originX, 0f, -originZ);
    }

    // ── Per-mesh origin buffers ─────────────────────────────────────────────

    /**
     * Creates the 16-byte per-instance origin attribute buffer for a mesh whose
     * stored positions are relative to {@code (originX, originY, originZ)},
     * baked into render space and registered for re-baking on origin steps.
     *
     * <p>{@code scale} rides in {@code w} — see {@code MmsVertexFormat}, whose
     * shaders compute {@code pos = aOrigin.xyz + position * aOrigin.w}.
     */
    public static int createOriginBuffer(float meshOriginX, float meshOriginY,
                                         float meshOriginZ, float scale) {
        int id = GL15.glGenBuffers();
        ORIGIN_BUFFERS.put(id, new float[]{meshOriginX, meshOriginY, meshOriginZ, scale});
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
            new float[]{meshOriginX - originX, meshOriginY, meshOriginZ - originZ, scale},
            GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return id;
    }

    /**
     * Drops a buffer from the re-bake set. Call immediately before deleting it;
     * a buffer left registered would be written to after deletion.
     */
    public static void releaseOriginBuffer(int id) {
        ORIGIN_BUFFERS.remove(id);
    }

    /** Number of live registered origin buffers — for diagnostics and tests. */
    public static int liveOriginBufferCount() {
        return ORIGIN_BUFFERS.size();
    }

    /**
     * Re-bakes every live origin buffer against the current origin. One
     * 16-byte write per loaded mesh, which is why the origin steps on a coarse
     * grid rather than following the camera.
     */
    private static void refreshBuffers() {
        if (ORIGIN_BUFFERS.isEmpty()) {
            return;
        }
        float[] staging = new float[4];
        for (Map.Entry<Integer, float[]> entry : ORIGIN_BUFFERS.entrySet()) {
            float[] absolute = entry.getValue();
            staging[0] = absolute[0] - originX;
            staging[1] = absolute[1];
            staging[2] = absolute[2] - originZ;
            staging[3] = absolute[3];
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, entry.getKey());
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, staging);
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }
}

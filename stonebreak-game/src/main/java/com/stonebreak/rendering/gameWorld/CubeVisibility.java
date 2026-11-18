package com.stonebreak.rendering.gameWorld;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.voxel.world.CubeWorld;
import com.openmason.engine.voxel.world.VisibleCubeBuffers;
import com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer;
import com.stonebreak.rendering.gameWorld.regions.CubeDraw;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The camera pass's cube set (Phase 5, game side): a frustum-limited visibility-graph walk from the camera
 * cube ({@code cv_visible_cubes}, Checchi's cave culling) decides which resident cubes draw this frame, and
 * reports the visible-but-unknown cubes the proxies stand in for. The walk's frustum planes are the render-
 * space camera planes rebased onto the camera cube's corner in exact long arithmetic.
 *
 * <p>When the walk is unavailable (a world without native voxels, an engine error,
 * {@code -Dstonebreak.viscull=off}, or a camera outside the world's vertical bounds) every drawable cube inside the CPU frustum is visible and no proxies are
 * reported ({@link #walkFrame()} is then -1, which {@link ChunkRegionRenderer#prepareCameraPass} reads as
 * "no visibility filter").
 *
 * <p>The walk writes into one reusable {@link VisibleCubeBuffers} (no per-frame allocation once the buffers
 * have grown to the walk's size); the visible list is marked straight from it and the missing cubes are read
 * from it in place - at most {@link #MISSING_CAP} of them are offered to the proxies, while
 * {@link #missingTotal()} counts them all.
 */
public final class CubeVisibility {

    private static final Logger LOGGER = LoggerFactory.getLogger(CubeVisibility.class);
    /** Most missing cubes offered to the proxies per frame (the walk itself reports all of them). */
    static final int MISSING_CAP = 4096;
    /** cv_visible_cubes' radius limit. */
    static final int MAX_RADIUS = 255;

    private final boolean walkEnabled = !"off".equalsIgnoreCase(System.getProperty("stonebreak.viscull", "on"));
    private final float[] planes = new float[24];
    private final Vector4f plane = new Vector4f();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final List<CubeDraw> visible = new ArrayList<>();
    private final List<CubeDraw> backToFront = new ArrayList<>();
    private final List<CubeDraw> translucent = new ArrayList<>();
    private final List<CubeDraw> water = new ArrayList<>();
    private final VisibleCubeBuffers buffers = new VisibleCubeBuffers();
    private final int missingCap;
    private int missingCount;
    private long missingTotal;
    private int frame;
    private int walkFrame = -1;
    private boolean walkFailed;
    private long[] sortKeys = new long[512];
    private long lastWalkNanos;
    private long camCx, camCy, camCz;
    private int horizontalRadius;
    private int walkRadius;

    public CubeVisibility() {
        this(MISSING_CAP);
    }

    /** Tests: a smaller proxy cap. */
    CubeVisibility(int missingCap) {
        this.missingCap = Math.max(0, missingCap);
    }

    /**
     * Runs this frame's walk.
     *
     * @param viewProjRender render-space {@code projection * view}
     * @param camX           camera world position (block units)
     * @param radiusBlocks   horizontal render radius in blocks
     */
    public void update(World world, ChunkRegionRenderer regions, Matrix4f viewProjRender,
                       double camX, double camY, double camZ, int radiusBlocks) {
        update(world != null ? world.voxels() : null, regions, viewProjRender, camX, camY, camZ, radiusBlocks);
    }

    /** {@link #update(World, ChunkRegionRenderer, Matrix4f, double, double, double, int)} over the voxels. */
    void update(CubeWorld voxels, ChunkRegionRenderer regions, Matrix4f viewProjRender,
                double camX, double camY, double camZ, int radiusBlocks) {
        frame++;
        visible.clear();
        missingCount = 0;
        missingTotal = 0;
        walkFrame = -1;
        frustum.set(viewProjRender);
        long t0 = System.nanoTime();
        long ccx = Math.floorDiv((long) Math.floor(camX), 32L);
        long ccy = Math.floorDiv((long) Math.floor(camY), 32L);
        long ccz = Math.floorDiv((long) Math.floor(camZ), 32L);
        camCx = ccx;
        camCy = ccy;
        camCz = ccz;
        horizontalRadius = Math.min(MAX_RADIUS, Math.max(1, (radiusBlocks + 31) / 32 + 1));
        walkRadius = 0;
        // A camera above the world's top (or below its floor) has nothing between it and the terrain that could
        // occlude anything, so the walk is skipped there: every drawable cube in the frustum draws. Walking from
        // the nearest in-bounds cube of the camera's column instead (the previous fix) broke as soon as that
        // column was UNKNOWN - a REMOTE client flying horizontally above the world soon stands over columns it
        // holds no record of, the walk's first step meets only unknown (terminal) cubes and reached nothing, and
        // every READY column still loaded (the LOD hidden there) vanished at once: blank terrain.
        if (walkEnabled && !walkFailed && voxels != null && !outsideVerticalBounds(ccy)) {
            cameraCubePlanes(viewProjRender, ccx, ccy, ccz);
            // The walk's radius is Chebyshev (one value for x, y and z), but the surface band keeps the terrain
            // loaded at ANY height difference: a camera flown far above the ground needs the walk to reach
            // down to it, or the READY columns under it (the LOD hidden there) draw nothing - a sky hole.
            walkRadius = (int) Math.min(MAX_RADIUS,
                Math.max(horizontalRadius, regions.residentVerticalReach(ccy)));
            try {
                voxels.visibleCubes(ccx, ccy, ccz, walkRadius, planes, buffers);
                regions.markVisible(buffers, frame, visible);
                missingTotal = buffers.missingCount();
                missingCount = (int) Math.min(missingTotal, missingCap);
                walkFrame = frame;
            } catch (RuntimeException e) {
                walkFailed = true;
                LOGGER.error("cv_visible_cubes failed; falling back to frustum-only cube culling", e);
            }
        }
        if (walkFrame < 0) {
            List<CubeDraw> all = regions.cubes();
            for (int i = 0; i < all.size(); i++) {
                CubeDraw d = all.get(i);
                if (regions.isDrawable(d) && inFrustum(d)) {
                    visible.add(d);
                }
            }
        }
        lastWalkNanos = System.nanoTime() - t0;
        sortBackToFront(camX, camY, camZ);
    }

    private boolean inFrustum(CubeDraw d) {
        float x = RenderOrigin.relativeX(d.minBlockX());
        float y = RenderOrigin.relativeY(d.minBlockY());
        float z = RenderOrigin.relativeZ(d.minBlockZ());
        return frustum.testAab(x, y, z, x + 32f, y + 32f, z + 32f);
    }

    /** True when camera cube y lies outside the world's vertical bounds (no walk: frustum only). */
    static boolean outsideVerticalBounds(long cameraCubeY) {
        return cameraCubeY < WorldConfiguration.MIN_CUBE_Y || cameraCubeY >= WorldConfiguration.MAX_CUBE_Y;
    }

    /**
     * Rebases the render-space frustum planes onto the camera cube's min corner (the walk's coordinate
     * system): a point {@code q} relative to the corner is {@code q + (corner - origin)} in render space, so
     * {@code d' = d + n . (corner - origin)}, the offset an exact long difference.
     */
    void cameraCubePlanes(Matrix4f viewProjRender, long ccx, long ccy, long ccz) {
        double ox = (double) ((ccx << 5) - RenderOrigin.blockX());
        double oy = (double) ((ccy << 5) - RenderOrigin.blockY());
        double oz = (double) ((ccz << 5) - RenderOrigin.blockZ());
        for (int p = 0; p < 6; p++) {
            viewProjRender.frustumPlane(p, plane);
            planes[p * 4] = plane.x;
            planes[p * 4 + 1] = plane.y;
            planes[p * 4 + 2] = plane.z;
            planes[p * 4 + 3] = (float) (plane.w + plane.x * ox + plane.y * oy + plane.z * oz);
        }
    }

    private void sortBackToFront(double camX, double camY, double camZ) {
        backToFront.clear();
        translucent.clear();
        water.clear();
        int count = visible.size();
        if (count == 0) {
            return;
        }
        if (sortKeys.length < count) {
            sortKeys = new long[Math.max(count, sortKeys.length * 2)];
        }
        for (int i = 0; i < count; i++) {
            CubeDraw d = visible.get(i);
            double dx = (d.minBlockX() + 16) - camX;
            double dy = (d.minBlockY() + 16) - camY;
            double dz = (d.minBlockZ() + 16) - camZ;
            float distSq = (float) (dx * dx + dy * dy + dz * dz);
            sortKeys[i] = ((long) Float.floatToIntBits(distSq) << 32) | (i & 0xFFFFFFFFL);
        }
        Arrays.sort(sortKeys, 0, count);
        for (int i = count - 1; i >= 0; i--) {
            CubeDraw d = visible.get((int) sortKeys[i]);
            backToFront.add(d);
            if (d.hasTranslucent()) translucent.add(d);
            if (d.hasWater()) water.add(d);
        }
    }

    /** Walk stamp for {@link ChunkRegionRenderer#prepareCameraPass}, or -1 (frustum only). */
    public int walkFrame() {
        return walkFrame;
    }

    /** Drawable visible cubes, walk order (near to far). */
    public List<CubeDraw> visible() {
        return visible;
    }

    /** Visible cubes, farthest first. */
    public List<CubeDraw> backToFront() {
        return backToFront;
    }

    /** Visible cubes holding translucent quads, farthest first. */
    public List<CubeDraw> translucentBackToFront() {
        return translucent;
    }

    /** Visible cubes holding water, farthest first. */
    public List<CubeDraw> waterBackToFront() {
        return water;
    }

    /**
     * Missing (visible, not loaded) cube {@code i} of this frame's walk, {@code i < }{@link #missingCount()}
     * (walk order: near to far, so the cap drops the farthest).
     */
    public long missingX(int i) {
        return buffers.missingX(i);
    }

    public long missingY(int i) {
        return buffers.missingY(i);
    }

    public long missingZ(int i) {
        return buffers.missingZ(i);
    }

    /** Missing cubes offered to the proxies this frame (at most {@link #MISSING_CAP}, walk order). */
    public int missingCount() {
        return missingCount;
    }

    /** Times the walk's reusable buffers had to grow (F3 / allocation checks: constant once steady). */
    public int bufferGrowCount() {
        return buffers.growCount();
    }

    /** Missing cubes the walk found in total (may exceed {@link #MISSING_CAP}). */
    public long missingTotal() {
        return missingTotal;
    }

    /**
     * Whether column cube (cx, cz) lies within the walk's HORIZONTAL radius (render distance + 1 cube) - the
     * walk may reach further out when it is stretched vertically ({@link #walkRadius()}); proxies stay inside.
     */
    public boolean withinHorizontalRadius(long cx, long cz) {
        return Math.abs(cx - camCx) <= horizontalRadius && Math.abs(cz - camCz) <= horizontalRadius;
    }

    /** The Chebyshev radius (cubes) of this frame's walk. */
    public int walkRadius() {
        return walkRadius;
    }

    public long cameraCubeX() {
        return camCx;
    }

    public long cameraCubeY() {
        return camCy;
    }

    public long cameraCubeZ() {
        return camCz;
    }

    public long lastWalkNanos() {
        return lastWalkNanos;
    }

    public boolean walkActive() {
        return walkFrame >= 0;
    }
}

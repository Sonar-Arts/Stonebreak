package com.stonebreak.rendering.gameWorld.regions;

import com.openmason.engine.voxel.world.StreamMesh;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;
import com.stonebreak.world.voxel.CubeEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Binds the rendered {@link World} to the cube region renderer: the world's stream events
 * ({@link CubeEvents}) feed {@link ChunkRegionRenderer} - REMESHED meshes upload (or drop when empty),
 * UNLOADED cubes free their meshes, COLUMN_READY / UNREADY drive the FastLOD handover (enable bits here,
 * node fades in the LOD pass through {@link #addColumnListener}).
 *
 * <p>Attach once per rendered world ({@link #attach}, idempotent - the world renderer calls it every frame).
 * Attaching a new world detaches the previous one, frees every mesh and asks the new world's stream to re-mesh
 * every installed cube ({@code CubeStream.remeshAll}), so meshes handed out before the attach (and dropped for
 * want of a consumer) come back. Events are consumed only on the thread that
 * attached (the GL thread): a world ticked elsewhere would upload from a thread without a context, so its
 * meshes are refused (logged once).
 */
public final class CubeTerrain implements CubeEvents.MeshConsumer, CubeEvents.UnloadListener,
        CubeEvents.ColumnReadinessListener, CubeEvents.InstallListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CubeTerrain.class);
    private static final CubeTerrain INSTANCE = new CubeTerrain();

    private final List<CubeEvents.ColumnReadinessListener> columnListeners = new CopyOnWriteArrayList<>();
    private World world;
    private Thread glThread;
    private boolean warnedThread;

    private CubeTerrain() {
    }

    /** Makes {@code world} the rendered world (GL thread). Null detaches. Idempotent. */
    public static void attach(World world) {
        INSTANCE.bind(world);
    }

    /** The world the renderer currently consumes, or null. */
    public static World attached() {
        return INSTANCE.world;
    }

    /** FastLOD hook: column readiness of the attached world, after the renderer's own enable update. */
    public static void addColumnListener(CubeEvents.ColumnReadinessListener listener) {
        INSTANCE.columnListeners.add(listener);
    }

    public static void removeColumnListener(CubeEvents.ColumnReadinessListener listener) {
        INSTANCE.columnListeners.remove(listener);
    }

    private void bind(World next) {
        if (next == world) {
            return;
        }
        if (world != null) {
            world.removeMeshConsumer(this);
            world.removeUnloadListener(this);
            world.removeColumnReadinessListener(this);
            world.removeInstallListener(this);
        }
        if (ChunkRegionRenderer.isEnabled()) {
            ChunkRegionRenderer.getInstance().reset();
        }
        world = next;
        glThread = Thread.currentThread();
        warnedThread = false;
        if (next != null) {
            next.addMeshConsumer(this);
            next.addUnloadListener(this);
            next.addColumnReadinessListener(this);
            next.addInstallListener(this);
            remeshAll(next);
        }
    }

    /** Re-delivers every installed cube's mesh (owner thread = this GL thread). */
    private static void remeshAll(World w) {
        var stream = w.stream();
        if (stream == null || w.isClosed()) {
            return;
        }
        try {
            stream.remeshAll();
        } catch (RuntimeException e) {
            LOGGER.warn("remeshAll on attach failed: {}", e.getMessage());
        }
    }

    private boolean onGlThread(World w) {
        if (w != world) {
            return false; // a stale registration of a detached world
        }
        if (Thread.currentThread() == glThread) {
            return true;
        }
        if (!warnedThread) {
            warnedThread = true;
            LOGGER.warn("Cube events of the rendered world arrive on {} (not the GL thread {}): terrain meshes "
                + "are refused", Thread.currentThread().getName(), glThread.getName());
        }
        return false;
    }

    @Override
    public void onCubeMesh(World w, StreamMesh mesh) {
        if (!onGlThread(w) || !ChunkRegionRenderer.isEnabled()) {
            return;
        }
        long cx = mesh.cx(), cy = mesh.cy(), cz = mesh.cz();
        CubeDraw draw = ChunkRegionRenderer.getInstance().accept(mesh, w.isColumnReady(cx, cz));
        Cube cube = w.getCube(cx, cy, cz);
        if (cube != null) {
            cube.setRenderState(draw);
        }
    }

    @Override
    public void onCubeInstalled(World w, Cube cube) {
        if (!onGlThread(w) || !ChunkRegionRenderer.isEnabled()) {
            return;
        }
        cube.setRenderState(ChunkRegionRenderer.getInstance().get(cube.cx(), cube.cy(), cube.cz()));
    }

    @Override
    public void onCubeUnloaded(World w, Cube cube) {
        if (!onGlThread(w) || !ChunkRegionRenderer.isEnabled()) {
            return;
        }
        ChunkRegionRenderer.getInstance().drop(cube.cx(), cube.cy(), cube.cz());
        cube.setRenderState(null);
    }

    @Override
    public void onColumnReady(World w, long cx, long cz) {
        if (!onGlThread(w)) {
            return;
        }
        if (ChunkRegionRenderer.isEnabled()) {
            ChunkRegionRenderer.getInstance().setColumnReady(cx, cz, true);
        }
        for (CubeEvents.ColumnReadinessListener l : columnListeners) {
            l.onColumnReady(w, cx, cz);
        }
    }

    @Override
    public void onColumnUnready(World w, long cx, long cz) {
        if (!onGlThread(w)) {
            return;
        }
        if (ChunkRegionRenderer.isEnabled()) {
            ChunkRegionRenderer.getInstance().setColumnReady(cx, cz, false);
        }
        for (CubeEvents.ColumnReadinessListener l : columnListeners) {
            l.onColumnUnready(w, cx, cz);
        }
    }
}

package com.openmason.main.systems.scripting.live;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;
import com.openmason.main.systems.scripting.doc.FacePixelStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Journaling decorator over the live {@link FacePixelStore}: records each
 * pre-existing texture's before-bytes on first write and tracks textures the
 * script created, so a failed run restores every touched texel and deletes
 * orphaned GPU textures ({@link #rollback()}), and a successful run folds
 * pixel deltas into the script's single undo entry ({@link #buildDeltas()}).
 */
public final class RecordingFacePixelStore implements FacePixelStore {

    private static final Logger logger = LoggerFactory.getLogger(RecordingFacePixelStore.class);

    /**
     * Textures larger than this on either side are not journaled (undo of
     * their pixels degrades gracefully) — full before/after byte pairs for
     * huge textures would dominate undo memory. Face textures are tiny.
     */
    private static final int MAX_JOURNAL_SIZE = 2048;

    private record BeforeState(int width, int height, byte[] pixels) {
    }

    private final FacePixelStore delegate;
    private final Map<Integer, BeforeState> touched = new LinkedHashMap<>();
    private final Set<Integer> createdTextureIds = new LinkedHashSet<>();
    private final Set<Integer> skippedTextureIds = new LinkedHashSet<>();

    public RecordingFacePixelStore(FacePixelStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public int[] textureSize(int gpuTextureId) {
        return delegate.textureSize(gpuTextureId);
    }

    @Override
    public byte[] readPixels(int gpuTextureId) {
        return delegate.readPixels(gpuTextureId);
    }

    @Override
    public void writeRegion(int gpuTextureId, int x, int y, int width, int height, byte[] rgbaBytes) {
        journalBefore(gpuTextureId);
        delegate.writeRegion(gpuTextureId, x, y, width, height, rgbaBytes);
    }

    @Override
    public int createTexture(PixelCanvas canvas) {
        int id = delegate.createTexture(canvas);
        if (id > 0) {
            createdTextureIds.add(id);
        }
        return id;
    }

    @Override
    public int allocateMaterialId() {
        return delegate.allocateMaterialId();
    }

    @Override
    public void assignFaceMaterials(int[] globalFaceIds, int[] materialIds) {
        // Mapping/material state is covered by the mesh snapshot — no journal.
        delegate.assignFaceMaterials(globalFaceIds, materialIds);
    }

    @Override
    public void deleteTexture(int gpuTextureId) {
        delegate.deleteTexture(gpuTextureId);
    }

    /** True when the run wrote pixels or created textures. */
    public boolean touchedAnything() {
        return !touched.isEmpty() || !createdTextureIds.isEmpty();
    }

    /**
     * Failed-run cleanup: restore every journaled texture's before-bytes and
     * delete textures the script created. Best-effort — a rollback must never
     * make things worse.
     */
    public void rollback() {
        for (Map.Entry<Integer, BeforeState> e : touched.entrySet()) {
            try {
                BeforeState before = e.getValue();
                delegate.writeRegion(e.getKey(), 0, 0, before.width(), before.height(), before.pixels());
            } catch (RuntimeException ex) {
                logger.error("Failed to restore texture {} during script rollback", e.getKey(), ex);
            }
        }
        for (int id : createdTextureIds) {
            try {
                delegate.deleteTexture(id);
            } catch (RuntimeException ex) {
                logger.error("Failed to delete script-created texture {} during rollback", id, ex);
            }
        }
        touched.clear();
        createdTextureIds.clear();
    }

    /**
     * Successful-run capture: before/after pixel pairs for every journaled
     * pre-existing texture (script-created textures need no delta — the mesh
     * snapshot governs whether their material is mapped, and the texture
     * itself stays alive while the undo entry exists).
     */
    public List<TexturePixelDelta> buildDeltas() {
        List<TexturePixelDelta> deltas = new ArrayList<>(touched.size());
        for (Map.Entry<Integer, BeforeState> e : touched.entrySet()) {
            BeforeState before = e.getValue();
            byte[] after = delegate.readPixels(e.getKey());
            if (after == null) {
                logger.warn("Could not read back texture {} for the undo delta", e.getKey());
                continue;
            }
            deltas.add(new TexturePixelDelta(e.getKey(), before.width(), before.height(),
                    before.pixels(), after));
        }
        return deltas;
    }

    private void journalBefore(int gpuTextureId) {
        if (createdTextureIds.contains(gpuTextureId)
                || touched.containsKey(gpuTextureId)
                || skippedTextureIds.contains(gpuTextureId)) {
            return;
        }
        int[] dims = delegate.textureSize(gpuTextureId);
        if (dims == null) {
            skippedTextureIds.add(gpuTextureId);
            return;
        }
        if (dims[0] > MAX_JOURNAL_SIZE || dims[1] > MAX_JOURNAL_SIZE) {
            logger.warn("Texture {} is {}x{} — too large to journal; script undo will not "
                    + "restore its pixels", gpuTextureId, dims[0], dims[1]);
            skippedTextureIds.add(gpuTextureId);
            return;
        }
        byte[] pixels = delegate.readPixels(gpuTextureId);
        if (pixels == null) {
            skippedTextureIds.add(gpuTextureId);
            return;
        }
        touched.put(gpuTextureId, new BeforeState(dims[0], dims[1], pixels));
    }
}

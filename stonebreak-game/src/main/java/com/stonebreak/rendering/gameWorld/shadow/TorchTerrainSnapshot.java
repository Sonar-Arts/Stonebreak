package com.stonebreak.rendering.gameWorld.shadow;

import com.stonebreak.world.chunk.Chunk;
import java.util.List;

/** Exact mesh identities, not a lossy hash: edits/uploads/unloads invalidate cached terrain depth. */
final class TorchTerrainSnapshot {
    private Object[] references = new Object[0];
    private boolean valid;

    boolean matches(List<Chunk> chunks) {
        if (!valid || references.length != chunks.size() * 5) return false;
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            int at = i * 5;
            if (references[at] != chunk
                    || references[at + 1] != chunk.getMmsRenderableHandle()
                    || references[at + 2] != chunk.getRegionAtlasHandle()
                    || references[at + 3] != chunk.getStampRenderableHandle()
                    || references[at + 4] != chunk.getRegionStampHandle()) return false;
        }
        return true;
    }

    void capture(List<Chunk> chunks) {
        if (references.length != chunks.size() * 5) references = new Object[chunks.size() * 5];
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            int at = i * 5;
            references[at] = chunk;
            references[at + 1] = chunk.getMmsRenderableHandle();
            references[at + 2] = chunk.getRegionAtlasHandle();
            references[at + 3] = chunk.getStampRenderableHandle();
            references[at + 4] = chunk.getRegionStampHandle();
        }
        valid = true;
    }

    void clear() {
        java.util.Arrays.fill(references, null);
        valid = false;
    }
}

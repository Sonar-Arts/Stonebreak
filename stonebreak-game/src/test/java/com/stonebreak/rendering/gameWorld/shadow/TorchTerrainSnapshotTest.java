package com.stonebreak.rendering.gameWorld.shadow;

import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle;
import com.stonebreak.world.chunk.Chunk;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TorchTerrainSnapshotTest {
    @Test
    void blockEditsInvalidateBothLegacyAndRegionMeshesIncludingStamps() {
        Chunk chunk = mock(Chunk.class);
        List<Chunk> chunks = List.of(chunk);
        TorchTerrainSnapshot snapshot = new TorchTerrainSnapshot();
        assertFalse(snapshot.matches(chunks));
        snapshot.capture(chunks);
        assertTrue(snapshot.matches(chunks));

        when(chunk.getMmsRenderableHandle()).thenReturn(mock(MmsRenderableHandle.class));
        assertFalse(snapshot.matches(chunks));
        snapshot.capture(chunks);
        assertTrue(snapshot.matches(chunks));
        when(chunk.getRegionAtlasHandle()).thenReturn(mock(MmsRegionMeshHandle.class));
        assertFalse(snapshot.matches(chunks));
        snapshot.capture(chunks);
        when(chunk.getStampRenderableHandle()).thenReturn(mock(MmsRenderableHandle.class));
        assertFalse(snapshot.matches(chunks));
        snapshot.capture(chunks);
        when(chunk.getRegionStampHandle()).thenReturn(mock(MmsRegionMeshHandle.class));
        assertFalse(snapshot.matches(chunks));
        snapshot.capture(chunks);
        when(chunk.getStampRenderableHandle()).thenReturn(null);
        assertFalse(snapshot.matches(chunks), "removing geometry also clears its cached shadow");
    }

    @Test
    void streamingAndWorldChangesInvalidateTheCache() {
        TorchTerrainSnapshot snapshot = new TorchTerrainSnapshot();
        Chunk chunk = mock(Chunk.class);
        snapshot.capture(List.of(chunk));
        assertFalse(snapshot.matches(List.of()));
        assertFalse(snapshot.matches(List.of(mock(Chunk.class))));
        snapshot.clear();
        assertFalse(snapshot.matches(List.of(chunk)));
        snapshot.capture(List.of());
        assertTrue(snapshot.matches(List.of()));
    }
}

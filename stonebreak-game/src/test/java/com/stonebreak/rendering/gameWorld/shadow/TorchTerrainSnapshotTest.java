package com.stonebreak.rendering.gameWorld.shadow;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** The snapshot compares mesh identities (a cube's CubeDraw is a new instance on every remesh). */
class TorchTerrainSnapshotTest {
    @Test
    void meshReplacementsInvalidateTheCachedDepth() {
        Object mesh = new Object();
        TorchTerrainSnapshot snapshot = new TorchTerrainSnapshot();
        assertFalse(snapshot.matches(List.of(mesh)));
        snapshot.capture(List.of(mesh));
        assertTrue(snapshot.matches(List.of(mesh)));

        Object remeshed = new Object();
        assertFalse(snapshot.matches(List.of(remeshed)), "a new mesh invalidates the cached shadow");
        snapshot.capture(List.of(remeshed));
        assertTrue(snapshot.matches(List.of(remeshed)));
        assertFalse(snapshot.matches(List.of()), "removing geometry also clears its cached shadow");
    }

    @Test
    void streamingAndWorldChangesInvalidateTheCache() {
        TorchTerrainSnapshot snapshot = new TorchTerrainSnapshot();
        Object a = new Object(), b = new Object();
        snapshot.capture(List.of(a, b));
        assertFalse(snapshot.matches(List.of(b, a)), "order is part of the identity");
        assertFalse(snapshot.matches(List.of(a)));
        snapshot.clear();
        assertFalse(snapshot.matches(List.of(a, b)));
        snapshot.capture(List.of());
        assertTrue(snapshot.matches(List.of()));
    }
}

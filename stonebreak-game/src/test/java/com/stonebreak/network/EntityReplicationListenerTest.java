package com.stonebreak.network;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stonebreak.battletest.BattleTestSession;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.network.server.IntegratedServer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Regression test for issue #375: the spawn/despawn listener must delegate unconditionally —
 * a listener that re-reads the battletest flag at callback time drops the straddling tick's
 * spawn/despawn when {@code /battletest enter} flips the flag mid-server-tick.
 */
class EntityReplicationListenerTest {

    @Test
    void delegatesSpawnAndDespawnEvenWhenBattletestIsActive() {
        Entity e = mock(Entity.class);
        IntegratedServer server = mock(IntegratedServer.class);
        try (MockedStatic<BattleTestSession> bt = mockStatic(BattleTestSession.class)) {
            bt.when(BattleTestSession::isActive).thenReturn(true);
            EntityReplicationListener listener = new EntityReplicationListener(() -> server);
            listener.onEntityAdded(e);
            listener.onEntityRemoved(e);
        }
        verify(server).onEntitySpawned(e);
        verify(server).onEntityDespawned(e);
    }

    @Test
    void nullServerSupplierIsANoOp() {
        Entity e = mock(Entity.class);
        EntityReplicationListener listener = new EntityReplicationListener(() -> null);
        assertDoesNotThrow(() -> {
            listener.onEntityAdded(e);
            listener.onEntityRemoved(e);
        });
        verifyNoInteractions(e);
    }
}

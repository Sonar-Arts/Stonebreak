package com.stonebreak.player.combat;

import com.stonebreak.core.Game;
import com.stonebreak.core.state.GameStateController;
import com.stonebreak.items.Inventory;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Camera;
import com.stonebreak.player.state.PhysicsState;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * The client half of #357: the death and respawn edges reach the server on the reliable
 * channel, the death edge ahead of the death scatter, the respawn edge carrying the respawn
 * point (not the corpse).
 */
class DeathHandlerLifeEdgeTest {

    private MockedStatic<Game> gameStatic;
    private MockedStatic<MultiplayerSession> session;
    private PhysicsState state;
    private HealthController health;
    private DeathHandler handler;

    @BeforeEach
    void setUp() {
        Game game = mock(Game.class);
        when(game.getStateController()).thenReturn(mock(GameStateController.class));
        gameStatic = mockStatic(Game.class);
        gameStatic.when(Game::getInstance).thenReturn(game);
        session = mockStatic(MultiplayerSession.class);
        session.when(MultiplayerSession::sendDeathDrop).thenReturn(true);

        state = new PhysicsState();
        state.getPosition().set(40f, 70f, -12f);
        health = new HealthController();
        handler = new DeathHandler(state, health, mock(Inventory.class), mock(Camera.class), null);
    }

    @AfterEach
    void tearDown() {
        session.close();
        gameStatic.close();
    }

    @Test
    void deathReportsDeadAtTheCorpseBeforeTheScatter() {
        health.damage(1000f);

        handler.processDeathIfNeeded();

        ArgumentCaptor<Vector3f> pos = ArgumentCaptor.forClass(Vector3f.class);
        InOrder order = inOrder(MultiplayerSession.class);
        order.verify(session, () -> MultiplayerSession.sendPlayerLife(eq(true), pos.capture()));
        order.verify(session, MultiplayerSession::sendDeathDrop);
        assertEquals(new Vector3f(40f, 70f, -12f), pos.getValue());
        session.verify(() -> MultiplayerSession.sendPlayerLife(eq(false), any()), never());
    }

    @Test
    void deathIsReportedOncePerDeath() {
        health.damage(1000f);

        handler.processDeathIfNeeded();
        handler.processDeathIfNeeded(); // the death screen polls every frame

        session.verify(() -> MultiplayerSession.sendPlayerLife(eq(true), any()));
    }

    @Test
    void respawnReportsAliveAtTheRespawnPoint() {
        health.damage(1000f);
        handler.processDeathIfNeeded();

        handler.respawn();

        ArgumentCaptor<Vector3f> pos = ArgumentCaptor.forClass(Vector3f.class);
        session.verify(() -> MultiplayerSession.sendPlayerLife(eq(false), pos.capture()));
        assertEquals(state.getPosition(), pos.getValue());
        assertEquals(false, pos.getValue().equals(new Vector3f(40f, 70f, -12f)),
                "the respawn edge must not carry the corpse position");
        session.verify(() -> MultiplayerSession.sendPlayerLife(anyBoolean(), any()),
                org.mockito.Mockito.times(2));
    }
}

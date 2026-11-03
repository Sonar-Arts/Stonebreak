package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.network.packet.chat.ChatMessageS2C;
import com.stonebreak.network.packet.player.PlayerHealthS2C;
import com.stonebreak.network.server.ServerLevel;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /invincible runs ON THE SERVER, which owns player health (#343): the SERVER's cheats flag
 * decides, and an invincible player takes nothing from any hit the server resolves.
 */
class InvincibleCommandServerTest {

    private ServerPlayer sp;
    private ServerWorldContext ctx;
    private ServerLevel level;
    private final ServerCommandHandler commands = new ServerCommandHandler();

    @BeforeEach
    void playerInAWorld() {
        sp = new ServerPlayer(mock(ServerConnection.class), 5);
        sp.markHandshakeDone();
        sp.updateState(0f, 64f, 0f, 0f, 0f);
        ctx = mock(ServerWorldContext.class);
        level = mock(ServerLevel.class);
        when(ctx.serverLevel()).thenReturn(level);
    }

    private boolean replied(String fragment) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeastOnce()).send(captor.capture(), anyBoolean());
        return captor.getAllValues().stream()
                .anyMatch(p -> p instanceof ChatMessageS2C m && m.text().contains(fragment));
    }

    @Test
    void refusedWithoutServerCheats() {
        when(level.cheatsEnabled()).thenReturn(false);

        assertTrue(commands.handleCommand(sp, "/invincible", ctx), "consumed as a command, not chat");

        assertFalse(sp.invincible());
        assertTrue(replied("Cheats"));
    }

    @Test
    void togglesAndTakesExplicitStates() {
        when(level.cheatsEnabled()).thenReturn(true);

        commands.handleCommand(sp, "/invincible", ctx);
        assertTrue(sp.invincible());
        commands.handleCommand(sp, "/invincible", ctx);
        assertFalse(sp.invincible());
        commands.handleCommand(sp, "/invincible on", ctx);
        commands.handleCommand(sp, "/invincible on", ctx);
        assertTrue(sp.invincible(), "'on' is idempotent");
        commands.handleCommand(sp, "/invincible banana", ctx);
        assertTrue(replied("Usage"));
    }

    @Test
    void turningItOffNeverNeedsCheats() {
        sp.setInvincible(true);
        when(level.cheatsEnabled()).thenReturn(false);

        commands.handleCommand(sp, "/invincible off", ctx);

        assertFalse(sp.invincible());
    }

    @Test
    void anInvinciblePlayerTakesNoDamageButMayStillGiveUpItsLife() {
        ServerHealthHandler health = new ServerHealthHandler();
        sp.setInvincible(true);
        float full = sp.health();
        long now = System.nanoTime();

        for (byte cause : new byte[] {PlayerHealthS2C.CAUSE_MOB, PlayerHealthS2C.CAUSE_FALL,
                PlayerHealthS2C.CAUSE_CACTUS, PlayerHealthS2C.CAUSE_PLAYER}) {
            assertEquals(0f, health.hurt(sp, 1000f, cause, now, ctx), "cause " + cause);
        }
        assertEquals(full, sp.health());
        assertFalse(sp.isDead());

        sp.setInvincible(false);
        assertTrue(health.hurt(sp, 1f, PlayerHealthS2C.CAUSE_MOB, now, ctx) > 0f, "vulnerable again");
    }
}

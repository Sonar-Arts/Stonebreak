package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.cow.Cow;
import com.stonebreak.world.World;
import com.stonebreak.mobs.entities.status.StatusEffectBits;
import com.stonebreak.mobs.entities.status.StatusEffectType;
import com.stonebreak.network.packet.entity.EntitySpawnS2C;
import com.stonebreak.network.packet.entity.EntityStatusS2C;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Server half of #371: a mob's health fraction and active status effects reach interested
 * clients as {@link EntityStatusS2C} — on change only, and with the spawn of a late viewer.
 */
class EntityStatusReplicationTest {

    private static ServerPlayer viewer(int id) {
        ServerPlayer sp = new ServerPlayer(mock(ServerConnection.class), id);
        sp.markHandshakeDone();
        sp.updateState(0f, 64f, 0f, 0f, 0f);
        return sp;
    }

    private static List<EntityStatusS2C> statusSent(ServerPlayer sp) {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(sp.connection(), atLeast(0)).send(captor.capture(), anyBoolean());
        return captor.getAllValues().stream()
                .filter(p -> p instanceof EntityStatusS2C).map(EntityStatusS2C.class::cast).toList();
    }

    @Test
    void healthAndEffectsReplicateOnChangeOnly() {
        ServerPlayer sp = viewer(1);
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        when(ctx.players()).thenReturn(List.of(sp));
        ServerEntityHandler handler = new ServerEntityHandler();
        Cow mob = new Cow(mock(World.class), new Vector3f(3f, 64f, 0f));
        handler.onEntitySpawned(mob, ctx);

        handler.tick(ctx);
        assertTrue(statusSent(sp).isEmpty(), "a full-health mob without effects sends nothing");

        mob.damage(mob.getMaxHealth() * 0.75f, LivingEntity.DamageSource.UNKNOWN);
        mob.applyStatusEffect(StatusEffectType.FLAT_FOOTED, 3f, 0f);
        handler.tick(ctx);
        handler.tick(ctx); // unchanged — no resend

        List<EntityStatusS2C> sent = statusSent(sp);
        assertEquals(1, sent.size());
        EntityStatusS2C status = sent.get(0);
        assertEquals(mob.getNetworkId(), status.networkId());
        assertEquals(0.25f, status.healthFraction(), 1e-4f);
        assertEquals(StatusEffectBits.bit(StatusEffectType.FLAT_FOOTED), status.statusBits());
    }

    @Test
    void aLateViewerGetsTheCurrentStateWithTheSpawn() {
        ServerWorldContext ctx = mock(ServerWorldContext.class);
        when(ctx.players()).thenReturn(List.of());
        ServerEntityHandler handler = new ServerEntityHandler();
        Cow mob = new Cow(mock(World.class), new Vector3f(3f, 64f, 0f));
        handler.onEntitySpawned(mob, ctx);
        mob.applyStatusEffect(StatusEffectType.SPELLMARKED, 4f, 0f);

        ServerPlayer late = viewer(2);
        handler.onPeerJoined(late, ctx);

        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(late.connection(), atLeast(1)).send(captor.capture(), anyBoolean());
        List<Packet> packets = captor.getAllValues();
        int spawnAt = -1;
        int statusAt = -1;
        for (int i = 0; i < packets.size(); i++) {
            if (packets.get(i) instanceof EntitySpawnS2C) spawnAt = i;
            if (packets.get(i) instanceof EntityStatusS2C) statusAt = i;
        }
        assertTrue(spawnAt >= 0 && statusAt > spawnAt, "the status follows the spawn it refers to");
        assertTrue(StatusEffectBits.has(((EntityStatusS2C) packets.get(statusAt)).statusBits(),
                StatusEffectType.SPELLMARKED));
    }
}

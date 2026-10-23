package com.stonebreak.network.server.handlers;

import com.openmason.engine.net.server.ServerConnection;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.IllusionDecoy;
import com.stonebreak.network.packet.entity.DecoyCastC2S;
import com.stonebreak.network.server.ServerPlayer;
import com.stonebreak.network.server.ServerWorldContext;
import com.stonebreak.player.PlayerConstants;
import com.stonebreak.player.combat.illusionist.IllusionistAbilityController;
import com.stonebreak.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #341: Mirrored Deceit's decoys are SERVER entities — spawned in the server world (so
 * server mobs can target them), driven from the server's record of the caster and replicated
 * to every client — instead of client-only figures.
 */
class ServerIllusionHandlerTest {

    private static final int PLAYER_ID = 4;

    private ServerWorldContext ctx;
    private EntityManager em;
    private ServerPlayer sp;
    private final ServerIllusionHandler handler = new ServerIllusionHandler();

    @BeforeEach
    void casterInAWorld() {
        ctx = mock(ServerWorldContext.class);
        em = mock(EntityManager.class);
        when(ctx.world()).thenReturn(mock(World.class));
        when(ctx.entityManager()).thenReturn(em);
        sp = new ServerPlayer(mock(ServerConnection.class), PLAYER_ID);
        sp.updateState(5f, 64f, 5f, 0f, 0f);
        when(ctx.player(PLAYER_ID)).thenReturn(sp);
        sp.inventory().setSlot(0, new ItemStack(ItemType.STICK, 1));
        sp.inventory().selectHotbarSlot(0);
        // An Illusionist with Mirrored Deceit unlocked (also selects the class).
        sp.serverStats().spendCpOnAbility(IllusionistAbilityController.MIRRORED_DECEIT_KEY);
    }

    @Test
    void aCastSpawnsTheDecoysInTheServerWorld() {
        handler.handleDecoyCast(sp, new DecoyCastC2S(), ctx);

        ArgumentCaptor<Entity> added = ArgumentCaptor.forClass(Entity.class);
        verify(em, times(IllusionDecoy.fanAngles().length)).addEntity(added.capture());
        for (Entity e : added.getAllValues()) {
            IllusionDecoy decoy = (IllusionDecoy) e;
            assertEquals(PLAYER_ID, decoy.getOwnerPlayerId(), "replicated with its caster");
            assertSame(ItemType.STICK, decoy.getHeldItem(), "mirrors the caster's held item");
            assertTrue(decoy.getType().replicates(), "every client sees it");
        }
    }

    @Test
    void aSecondCastWhileTheDecoysAreOutIsIgnored() {
        handler.handleDecoyCast(sp, new DecoyCastC2S(), ctx);
        handler.handleDecoyCast(sp, new DecoyCastC2S(), ctx);

        verify(em, times(IllusionDecoy.fanAngles().length)).addEntity(any());
    }

    @Test
    void theDecoysMirrorTheCastersReportedMovement() {
        handler.handleDecoyCast(sp, new DecoyCastC2S(), ctx);
        IllusionDecoy decoy = handler.decoysOf(sp).get(0);
        float startX = decoy.getPosition().x;
        float startZ = decoy.getPosition().z;

        sp.updateState(7f, 64f, 5f, 0f, 0f); // the caster walks 2 blocks along +X
        decoy.update(0.05f);                 // the server's entity tick

        float moved = (float) Math.hypot(decoy.getPosition().x - startX, decoy.getPosition().z - startZ);
        assertEquals(2f, moved, 1e-3f, "the same distance, rotated by the decoy's fan angle");
        assertNotEquals(startX + 2f, decoy.getPosition().x, 1e-3f);
    }

    @Test
    void theDecoysLapseWithTheAbilityDuration() {
        handler.handleDecoyCast(sp, new DecoyCastC2S(), ctx);
        List<IllusionDecoy> decoys = handler.decoysOf(sp);

        handler.tick(ctx, PlayerConstants.ILLUSIONIST_DECOY_DURATION + 0.1f);

        decoys.forEach(d -> assertFalse(d.isAlive()));
        assertTrue(handler.decoysOf(sp).isEmpty());
        handler.handleDecoyCast(sp, new DecoyCastC2S(), ctx);
        assertTrue(handler.decoysOf(sp).isEmpty(), "the cooldown still runs after the decoys lapse");
    }

    @Test
    void onlyAnIllusionistWithTheAbilityCanCast() {
        ServerPlayer rogue = new ServerPlayer(mock(ServerConnection.class), PLAYER_ID + 1);
        rogue.updateState(5f, 64f, 5f, 0f, 0f);
        rogue.serverStats().spendCpOnAbility("rogue:0");
        handler.handleDecoyCast(rogue, new DecoyCastC2S(), ctx);

        ServerPlayer locked = new ServerPlayer(mock(ServerConnection.class), PLAYER_ID + 2);
        locked.updateState(5f, 64f, 5f, 0f, 0f);
        locked.serverStats().selectClass(IllusionistAbilityController.CLASS_ID);
        handler.handleDecoyCast(locked, new DecoyCastC2S(), ctx);

        verify(em, times(0)).addEntity(any());
    }

    @Test
    void decoysTakeNoDamage() {
        // Nothing may strike a decoy yet: a stray projectile, cactus or burn must not "destroy"
        // it and make the caster's client punish a bystander mob.
        handler.handleDecoyCast(sp, new DecoyCastC2S(), ctx);
        IllusionDecoy decoy = handler.decoysOf(sp).get(0);
        decoy.damage(50f, com.stonebreak.mobs.entities.LivingEntity.DamageSource.PLAYER);
        assertTrue(decoy.isAlive());
    }

    @Test
    void theCasterLeavingRetiresTheDecoys() {
        handler.handleDecoyCast(sp, new DecoyCastC2S(), ctx);
        List<IllusionDecoy> decoys = handler.decoysOf(sp);
        when(ctx.player(PLAYER_ID)).thenReturn(null);

        handler.tick(ctx, 0.05f);

        decoys.forEach(d -> assertFalse(d.isAlive()));
    }
}

package com.stonebreak.mobs.goblin.camp;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.combat.MobWeapon;
import com.stonebreak.mobs.goblin.Goblin;
import com.stonebreak.mobs.goblin.GoblinLoadout;
import com.stonebreak.mobs.goblin.GoblinLoadout.Headwear;
import com.stonebreak.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A camp whose fire goes out breaks up — and that includes the goblins who were away from it. */
class CampDisbandTest {

    private static final BlockPos FIRE = new BlockPos(4, 64, 4);

    @Test
    void goblinsAwayFromAPutOutFireComeBackAsLoners() {
        World world = mock(World.class);
        EntityManager entities = mock(EntityManager.class);
        when(world.getEntityManager()).thenReturn(entities);
        CampRegistry camps = new CampRegistry(world);

        GoblinCamp frozen = new GoblinCamp(FIRE);
        frozen.addWaiting(new GoblinLoadout(MobWeapon.DAGGER, Headwear.NONE, null, false));
        frozen.addWaiting(new GoblinLoadout(MobWeapon.DAGGER, Headwear.NONE, null, false));
        camps.loaded(FIRE, CampCodec.encode(frozen));

        camps.extinguished(FIRE);

        ArgumentCaptor<Goblin> spawned = ArgumentCaptor.forClass(Goblin.class);
        verify(entities, times(2)).addEntity(spawned.capture());
        for (Goblin g : spawned.getAllValues()) {
            assertNull(g.pack().camp(), "a loner, with no camp to go back to");
            assertTrue(g.getPosition().distance(FIRE.x() + 0.5f, FIRE.y(), FIRE.z() + 0.5f) < 2.0f, "at the dead fire");
        }
        assertEquals(0, camps.camps().size(), "the camp is gone");
    }
}

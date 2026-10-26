package com.stonebreak.audio;

import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #344: a mob's voice on the authoritative server world goes to the world's sound sink —
 * which replicates it to the players near it — and never into this client's mixer from the
 * server's tick thread (#340).
 */
class EntitySoundsRoutingTest {

    private record Heard(EntityType type, String event, float x, float y, float z) {}

    @Test
    void aServerWorldVoiceGoesToItsSoundSinkAtTheEntitysMiddle() {
        List<Heard> heard = new ArrayList<>();
        ServerMutationSinks sinks = new ServerMutationSinks();
        sinks.setSoundSink((type, event, x, y, z) -> heard.add(new Heard(type, event, x, y, z)));
        Entity cow = entity(sinks);

        EntitySounds.emit(cow, EntitySounds.EVENT_DEATH);

        assertEquals(List.of(new Heard(EntityType.COW, EntitySounds.EVENT_DEATH, 3f, 65f, -2f)), heard);
    }

    @Test
    void aWorldWithoutASinkIsSilent() {
        // A client render world never has one installed: nothing to replicate, nothing thrown.
        EntitySounds.emit(entity(new ServerMutationSinks()), EntitySounds.EVENT_HURT);
        EntitySounds.emit(null, EntitySounds.EVENT_HURT);
    }

    private static Entity entity(ServerMutationSinks sinks) {
        World world = mock(World.class);
        when(world.serverSinks()).thenReturn(sinks);
        Entity entity = mock(Entity.class);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getType()).thenReturn(EntityType.COW);
        when(entity.getPosition()).thenReturn(new Vector3f(3f, 64f, -2f));
        when(entity.getHeight()).thenReturn(2f);
        return entity;
    }
}

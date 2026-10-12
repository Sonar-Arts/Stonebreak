package com.stonebreak.world.save.util;

import com.stonebreak.core.Game;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.save.model.PlayerData;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** A world saved while the player was dead reloads with the player respawned (#302). */
class StateConverterRespawnTest {

    private static final Vector3f SPAWN = new Vector3f(10.5f, 80f, -4.5f);

    @Test
    void reloadingWhileDeadRespawnsThePlayer() {
        World world = mock(World.class);
        when(world.getSpawnPosition()).thenAnswer(i -> new Vector3f(SPAWN));
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            globals.when(Game::getInstance).thenReturn(mock(Game.class));
            globals.when(Game::getWorld).thenReturn(world);

            Player died = new Player(world);
            died.setPosition(300, 12, 300);
            died.damage(1_000f);
            assertTrue(died.isDead());
            PlayerData saved = StateConverter.toPlayerData(died, "w");
            assertEquals(0f, saved.getHealth(), "saved from the death screen");

            Player loaded = new Player(world);
            StateConverter.applyPlayerData(loaded, saved);
            assertFalse(loaded.isDead());
            assertEquals(loaded.getMaxHealth(), loaded.getHealth(), "full health, not alive at zero");
            assertEquals(SPAWN, loaded.getPosition(), "at the world spawn, not where they died");
        }
    }

    @Test
    void aLivingPlayerReloadsWhereTheyWere() {
        World world = mock(World.class);
        when(world.getSpawnPosition()).thenAnswer(i -> new Vector3f(SPAWN));
        try (MockedStatic<Game> globals = mockStatic(Game.class)) {
            globals.when(Game::getInstance).thenReturn(mock(Game.class));
            globals.when(Game::getWorld).thenReturn(world);

            Player hurt = new Player(world);
            hurt.setPosition(300, 12, 300);
            hurt.setHealth(3);
            Player loaded = new Player(world);
            StateConverter.applyPlayerData(loaded, StateConverter.toPlayerData(hurt, "w"));
            assertEquals(3f, loaded.getHealth());
            assertEquals(new Vector3f(300, 12, 300), loaded.getPosition());
        }
    }
}

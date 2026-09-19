package com.stonebreak.battletest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.player.Player;
import com.stonebreak.world.World;
import com.stonebreak.world.save.util.StateConverter;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class BattleTestSessionTest {
    @Test
    void cannotEnterFromMenusOrMultiplayer() throws Exception {
        try (var net = mockStatic(MultiplayerSession.class)) {
            for (var mode : new MultiplayerSession.Mode[] {MultiplayerSession.Mode.MENU,
                     MultiplayerSession.Mode.HOST, MultiplayerSession.Mode.JOIN}) {
                net.when(MultiplayerSession::getMode).thenReturn(mode);
                assertTrue(BattleTestSession.enter().contains("singleplayer"));
                assertFalse(BattleTestSession.isActive());
            }
        }
    }
    @Test
    void resetLeaveAndSavesNeverLeakArenaStateIntoTheWorld() throws Exception {
        World original = mock(World.class);
        Game game = mock(Game.class);
        when(game.getCurrentWorldName()).thenReturn("normal-world");
        try (MockedStatic<Game> globals = mockStatic(Game.class);
            MockedStatic<MultiplayerSession> net = mockStatic(MultiplayerSession.class)) {
            globals.when(Game::getInstance).thenReturn(game);
            globals.when(Game::getWorld).thenReturn(original);
            Player player = new Player(original);
            player.setPosition(101, 72, -203);
            player.setHealth(8);
            player.getCamera().setYaw(37);
            player.getCamera().setPitch(-12);
            player.getInventory().setHotbarItem(0, new ItemStack(BlockType.STONE, 12));
            globals.when(Game::getPlayer).thenReturn(player);
            net.when(MultiplayerSession::getMode).thenReturn(MultiplayerSession.Mode.SINGLEPLAYER);
            net.when(MultiplayerSession::isLocalPlayerDataReady).thenReturn(true);
            try {
                assertTrue(BattleTestSession.enter().contains("Frostbound Crucible"));
                var session = BattleTestSession.current();
                assertTrue(BattleTestSession.enter().contains("Already"));
                assertSame(session, BattleTestSession.current());
                assertEquals(9, player.getPosition().z);
                player.getInventory().getHotbarItem(0).setCount(1);
                player.setPosition(3, 4, 5);
                player.setHealth(2);
                var saved = StateConverter.toPlayerData(player, "normal-world");
                assertEquals(72, saved.getPosition().y);
                assertEquals(12, saved.getInventory()[0].getCount());
                assertEquals(8, saved.getHealth());
                session.reset();
                assertEquals(9, player.getPosition().z);
                assertTrue(BattleTestSession.leave());
                assertFalse(BattleTestSession.leave());
                assertEquals(101, player.getPosition().x);
                assertEquals(72, player.getPosition().y);
                assertEquals(-203, player.getPosition().z);
                assertEquals(37, player.getCamera().getYaw());
                assertEquals(-12, player.getCamera().getPitch());
                assertEquals(8, player.getHealth());
                assertEquals(12, player.getInventory().getHotbarItem(0).getCount());
                assertEquals(72, StateConverter.toPlayerData(player, "normal-world").getPosition().y);
            } finally {
                BattleTestSession.leave();
            }
        }
    }
}

package com.stonebreak.network.client;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a joining client applies the host's {@code PlayerDataS2C} (#367): a creation echo is a
 * character the host already built (items + background bonuses included), so the client only
 * runs the fresh-spawn reset; a blob the client cannot read leaves the session instead of
 * playing on with a stand-in character whose prefs would replace the host's record.
 */
class JoinRestoreTest {

    private final ClientWorldView view = new ClientWorldView();

    @AfterEach
    void tearDown() {
        view.shutdown();
    }

    private static Player playerWith(CharacterStats stats) {
        Player p = mock(Player.class);
        when(p.getCharacterStats()).thenReturn(stats);
        return p;
    }

    @Test
    void creationEchoGetsTheFreshSpawnResetButNoSecondBonusOrKit() {
        CharacterStats stats = new CharacterStats(null);
        stats.setSelectedBackground("farmer");
        byte[] echo = stats.toCreationJson();
        CharacterStats target = new CharacterStats(null);
        Player p = playerWith(target);

        view.onPlayerData(echo);
        view.applyRestoreTo(p);

        verify(p).prepareServerCreatedCharacter();
        verify(p, never()).giveStartingItems();
        assertEquals("farmer", target.getSelectedBackground(), "the echo was applied");
        assertNull(view.kickReason(), "a readable restore stays connected");
    }

    @Test
    void unreadableSaveLeavesInsteadOfPlayingOnWithAStandIn() {
        Player p = playerWith(new CharacterStats(null));

        view.onPlayerData("{ this is not a player save".getBytes(StandardCharsets.UTF_8));
        view.applyRestoreTo(p);

        verify(p, never()).giveStartingItems();
        verify(p, never()).prepareServerCreatedCharacter();
        assertNotNull(view.kickReason(), "the player is told why they left");
        assertTrue(view.isDisconnected());
    }

    @Test
    void emptyBlobIsANewPlayerOnThisServer() {
        Player p = playerWith(new CharacterStats(null));

        view.onPlayerData(new byte[0]);
        view.applyRestoreTo(p);

        verify(p).giveStartingItems();
        assertNull(view.kickReason());
    }
}

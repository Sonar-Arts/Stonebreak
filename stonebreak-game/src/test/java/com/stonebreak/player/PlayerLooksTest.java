package com.stonebreak.player;

import com.stonebreak.mobs.sbe.SbeAttachmentPoint;
import com.stonebreak.mobs.sbe.EntityAttachments;
import java.util.List;
import java.util.stream.Stream;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityLoader;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the Looks-tab hat catalog: option lookup semantics, and
 * that every bundled hat asset actually decodes and the player model actually
 * carries the socket hats mount to — so renaming the socket in Open Mason or
 * moving an asset breaks loudly here instead of silently in-game.
 */
class PlayerLooksTest {

    @Test
    void firstOptionIsBareHead() {
        PlayerLooks.CosmeticOption none = PlayerLooks.HAT_OPTIONS.getFirst();
        assertEquals(PlayerLooks.NO_HAT_ID, none.id());
        assertNull(none.resourcePath());
    }

    @Test
    void optionIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (PlayerLooks.CosmeticOption option : allOptions()) {
            if (option.resourcePath() == null) continue;
            assertTrue(ids.add(option.id().toUpperCase()),
                    "duplicate hat id: " + option.id());
        }
    }

    @Test
    void optionForMatchesCaseInsensitivelyAndFallsBackToNone() {
        assertEquals("TOP_HAT", PlayerLooks.optionFor("top_hat").id());
        assertEquals(PlayerLooks.NO_HAT_ID, PlayerLooks.optionFor("no_such_hat").id());
        assertEquals(PlayerLooks.NO_HAT_ID, PlayerLooks.optionFor(null).id());
    }

    @Test
    void everyHatAssetDecodesFromTheClasspath() {
        for (PlayerLooks.CosmeticOption option : allOptions()) {
            if (option.resourcePath() == null) continue;
            SbeEntityAsset asset = SbeEntityLoader.loadAttachableResource(option.resourcePath());
            assertNotNull(asset.geometryFor(SbeEntityAsset.DEFAULT_VARIANT),
                    option.id() + " asset has no default geometry: " + option.resourcePath());
        }
    }

    @Test
    void playerModelHasEverySocketOptionsMountTo() {
        SbeEntityAsset player = SbeEntityLoader.loadAttachableResource("/sbe/Mobs/SB_Player.sbe");
        SbeModelGeometry geometry = player.geometryFor(SbeEntityAsset.DEFAULT_VARIANT);
        assertNotNull(geometry);
        Set<String> playerSockets = new HashSet<>();
        for (SbeAttachmentPoint point : geometry.attachmentPoints()) {
            playerSockets.add(point.name().toUpperCase());
        }
        for (PlayerLooks.CosmeticOption option : allOptions()) {
            if (option.socket() == null) continue;
            assertTrue(playerSockets.contains(option.socket().toUpperCase()),
                    option.id() + " mounts on '" + option.socket()
                            + "' but the player model has no such socket; sockets: " + playerSockets);
        }
    }

    private static List<PlayerLooks.CosmeticOption> allOptions() {
        return Stream.concat(PlayerLooks.HAT_OPTIONS.stream(), PlayerLooks.HAIR_OPTIONS.stream()).toList();
    }

    @Test
    void catalogsKeepHairSeparateFromClothing() {
        assertEquals(List.of("NONE", "TOP_HAT"), PlayerLooks.HAT_OPTIONS.stream()
                .map(PlayerLooks.CosmeticOption::id).toList());
        assertEquals(List.of("NONE", "MALE_HAIR_1", "MALE_HAIR_2"), PlayerLooks.HAIR_OPTIONS.stream()
                .map(PlayerLooks.CosmeticOption::id).toList());
        assertEquals("MALE_HAIR_2", PlayerLooks.hairOptionFor("male_hair_2").id());
        assertEquals("NONE", PlayerLooks.hairOptionFor(null).id());
        assertEquals("NONE", PlayerLooks.hairOptionFor("TOP_HAT").id());
        assertEquals("NONE", PlayerLooks.optionFor("MALE_HAIR_1").id());
    }

    @Test
    void hatAndHairCanBeEquippedTogetherAndChangedIndependently() {
        Object player = new Object();
        PlayerLooks.applyHat(player, "TOP_HAT");
        var hat = EntityAttachments.get(player).getFirst();
        PlayerLooks.applyHair(player, "MALE_HAIR_1");
        assertEquals(2, EntityAttachments.get(player).size());
        assertTrue(EntityAttachments.get(player).contains(hat));
        PlayerLooks.applyHair(player, "MALE_HAIR_2");
        assertEquals(2, EntityAttachments.get(player).size());
        assertTrue(EntityAttachments.get(player).contains(hat));
        var hair = EntityAttachments.get(player).stream()
                .filter(a -> a.socketName().equals(PlayerLooks.HAIR_SOCKET)).findFirst().orElseThrow();
        assertEquals(SbeEntityLoader.loadAttachableResource(PlayerLooks.hairOptionFor("MALE_HAIR_2").resourcePath()),
                hair.asset());
        PlayerLooks.applyHat(player, "NONE");
        assertEquals(List.of(hair), EntityAttachments.get(player));
        PlayerLooks.applyHat(player, "TOP_HAT");
        PlayerLooks.applyHair(player, "NONE");
        assertEquals(List.of(hat), EntityAttachments.get(player));
        PlayerLooks.applyHat(player, "NONE");
        assertTrue(EntityAttachments.get(player).isEmpty());
    }

    @Test
    void clearingUnknownChoiceLeavesOtherSocketsAlone() {
        Object player = new Object();
        PlayerLooks.applyHair(player, "MALE_HAIR_1");
        var hair = EntityAttachments.get(player).getFirst();
        EntityAttachments.attach(player, "test_other_socket", hair.asset());
        var before = EntityAttachments.get(player);
        PlayerLooks.applyHat(player, "TOP_HAT");
        PlayerLooks.applyHat(player, "missing_hat");
        assertEquals(before, EntityAttachments.get(player));
    }
}

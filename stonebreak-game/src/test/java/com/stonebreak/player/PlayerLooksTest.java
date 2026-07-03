package com.stonebreak.player;

import com.stonebreak.mobs.sbe.SbeAttachmentPoint;
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
        PlayerLooks.HatOption none = PlayerLooks.HAT_OPTIONS.getFirst();
        assertEquals(PlayerLooks.NO_HAT_ID, none.id());
        assertNull(none.resourcePath());
    }

    @Test
    void optionIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (PlayerLooks.HatOption option : PlayerLooks.HAT_OPTIONS) {
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
        for (PlayerLooks.HatOption option : PlayerLooks.HAT_OPTIONS) {
            if (option.resourcePath() == null) continue;
            SbeEntityAsset asset = SbeEntityLoader.loadAttachableResource(option.resourcePath());
            assertNotNull(asset.geometryFor(SbeEntityAsset.DEFAULT_VARIANT),
                    option.id() + " asset has no default geometry: " + option.resourcePath());
        }
    }

    @Test
    void playerModelHasTheHatSocket() {
        SbeEntityAsset player = SbeEntityLoader.loadAttachableResource("/sbe/Mobs/SB_Player.sbe");
        SbeModelGeometry geometry = player.geometryFor(SbeEntityAsset.DEFAULT_VARIANT);
        assertNotNull(geometry);
        assertTrue(geometry.attachmentPoints().stream()
                        .map(SbeAttachmentPoint::name)
                        .anyMatch(name -> name.equalsIgnoreCase(PlayerLooks.HAT_SOCKET)),
                "player model has no '" + PlayerLooks.HAT_SOCKET + "' socket; sockets: "
                        + geometry.attachmentPoints().stream()
                                .map(SbeAttachmentPoint::name).toList());
    }
}

package com.stonebreak.player.appearance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.entity.model.EntityAsset;
import com.stonebreak.mobs.entities.IllusionDecoy;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.mobs.sbe.AttachmentVisual;
import com.stonebreak.mobs.sbe.BodyMounts;
import com.stonebreak.mobs.sbe.GameSbeAssets;
import com.stonebreak.mobs.sbe.HostSockets;
import com.stonebreak.player.PlayerCharacter;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #459: the look record (engine omlook, binary + JSON) over the cosmetics catalog - defaults, round trips, the
 * catalog's rules, every catalog item loading and fitting a socket the player has - and how bodies wear it: the slots
 * become mounts, a remote player dresses from the known looks, and a decoy wears its caster's.
 */
class PlayerAppearanceTest {

    @BeforeAll
    static void native_() {
        assumeTrue(CendaKernels.isAvailable(), "the look codec is native");
    }

    @AfterEach
    void forget() {
        KnownLooks.clear();
    }

    @Test
    void defaultsCoverEveryCatalogSlot() {
        CosmeticCatalog catalog = CosmeticCatalog.get();
        PlayerAppearance d = PlayerAppearance.defaults(catalog);
        for (CosmeticCatalog.Slot s : catalog.slots()) assertEquals(CosmeticCatalog.NONE, d.item(s.name()));
        for (CosmeticCatalog.Tint t : catalog.tints()) assertEquals(t.defaultRgb(), d.tints().get(t.name()));
        assertNull(d.problem(catalog));
    }

    @Test
    void binaryAndJsonRoundTrip() {
        PlayerAppearance look = PlayerAppearance.defaults().withItem("hat", "bandana_blue").withItem("hair", "MALE_HAIR_2")
                .withTint("hair_tint", 0x123456);
        assertEquals("BANDANA_BLUE", look.item("hat"));
        byte[] binary = look.toBinary();
        assertTrue(binary.length < PlayerAppearance.MAX_BYTES, "a look is small: " + binary.length);
        assertEquals(look, PlayerAppearance.read(binary));
        assertEquals(look, PlayerAppearance.readJson(look.toJson()));
        assertArrayEquals(binary, PlayerAppearance.readJson(look.toJson()).toBinary());
        assertThrows(IllegalArgumentException.class, () -> PlayerAppearance.read(new byte[]{'O', 'L', 9}));
    }

    @Test
    void theCatalogRefusesWhatItDoesNotKnow() {
        CosmeticCatalog catalog = CosmeticCatalog.get();
        PlayerAppearance d = PlayerAppearance.defaults();
        assertNull(d.withItem("hat", "TOP_HAT").problem(catalog));
        assertNotNull(d.withItem("hat", "CROWN").problem(catalog), "unknown item");
        assertNotNull(d.withItem("hat", "MALE_HAIR_1").problem(catalog), "item in the wrong slot");
        assertNotNull(d.withItem("cape", "NONE").problem(catalog), "unknown slot");
        assertNotNull(d.withTint("glow", 0xffffff).problem(catalog), "unknown tint");
        // A partial record is completed with the defaults.
        PlayerAppearance partial = new PlayerAppearance(Map.of("hat", "TOP_HAT"), Map.of(), Map.of());
        assertNull(partial.problem(catalog));
        assertEquals(PlayerAppearance.defaults().withItem("hat", "TOP_HAT"), partial.withDefaults(catalog));
    }

    @Test
    void malformedCatalogsAreRefused() throws Exception {
        ObjectMapper m = new ObjectMapper();
        assertThrows(IllegalArgumentException.class, () -> CosmeticCatalog.parse(m.readTree("{\"format\":\"x\"}")));
        assertThrows(IllegalArgumentException.class, () -> CosmeticCatalog.parse(m.readTree(
                "{\"format\":\"stonebreak.cosmetics\",\"version\":1,\"slots\":[{\"name\":\"hat\",\"socket\":\"H\"}],"
                        + "\"items\":[{\"id\":\"A\",\"slot\":\"cape\",\"asset\":\"/a.sbe\"}]}")));
        assertThrows(IllegalArgumentException.class, () -> CosmeticCatalog.parse(m.readTree(
                "{\"format\":\"stonebreak.cosmetics\",\"version\":1,\"tints\":[{\"name\":\"x\",\"default\":\"red\"}]}")));
    }

    @Test
    void everyItemLoadsAndHangsOnASocketThePlayerHas() {
        CosmeticCatalog catalog = CosmeticCatalog.get();
        var sockets = HostSockets.of(PlayerCharacter.get().asset(), null);
        for (CosmeticCatalog.Item item : catalog.items()) {
            EntityAsset asset = GameSbeAssets.loadAttachableResource(item.asset());
            assertNotNull(asset.geometryFor(EntityAsset.DEFAULT_VARIANT), item.id());
            if (item.variant() != null) assertTrue(asset.variants().containsKey(item.variant()), item.id());
            assertTrue(sockets.find(catalog.socketOf(item)) >= 0, item.id() + " socket " + catalog.socketOf(item));
        }
    }

    @Test
    void aLookBecomesMountsOneASlot() {
        BodyMounts mounts = new BodyMounts();
        mounts.put(BodyMounts.Mount.on("weapon_r", null));
        AppearanceMounts.apply(PlayerAppearance.defaults().withItem("hat", "BANDANA_RED").withItem("accessory",
                "GOLD_EARRING"), mounts);
        BodyMounts.Mount hat = mounts.get("hat");
        assertEquals("Hatzone", hat.socket());
        assertEquals("Red", ((AttachmentVisual.Model) hat.visual()).variant());
        assertNotNull(mounts.get("accessory"));
        assertNull(mounts.get("hair"));
        assertNotNull(mounts.get("weapon_r"), "other mounts are left alone");
        AppearanceMounts.apply(PlayerAppearance.defaults(), mounts);
        assertNull(mounts.get("hat"));
        assertNull(mounts.get("accessory"));
    }

    @Test
    void remotePlayersDressFromTheKnownLooksAndDecoysWearTheirCasters() {
        RemotePlayer ann = new RemotePlayer(null, new Vector3d(), 7, "Ann");
        IllusionDecoy decoy = new IllusionDecoy(null, new Vector3d(), null, 120f);
        decoy.setOwnerPlayerId(7);
        try {
            assertNull(ann.lookMounts().get("hat"), "nothing known yet");
            KnownLooks.put(7, PlayerAppearance.defaults().withItem("hat", "TOP_HAT"));
            assertNotNull(ann.lookMounts().get("hat"));
            assertNotNull(decoy.lookMounts().get("hat"), "the decoy wears its caster's look");
            KnownLooks.put(7, PlayerAppearance.defaults().withItem("hair", "MALE_HAIR_1"));
            assertNull(ann.lookMounts().get("hat"), "a change re-dresses");
            assertNotNull(decoy.lookMounts().get("hair"));
        } finally {
            ann.onRemovedFromWorld();
            decoy.onRemovedFromWorld();
        }
    }
}

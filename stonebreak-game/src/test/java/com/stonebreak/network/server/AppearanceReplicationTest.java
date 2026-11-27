package com.stonebreak.network.server;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.network.packet.player.PlayerAppearanceC2S;
import com.stonebreak.network.packet.player.PlayerAppearanceS2C;
import com.stonebreak.network.packet.player.PlayerJoinS2C;
import com.stonebreak.player.appearance.PlayerAppearance;
import com.stonebreak.world.save.model.PlayerData;
import com.stonebreak.world.save.serialization.JsonPlayerSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #459 acceptance, over a real server and two TCP clients ({@link ServerHarness}): a look client A sets reaches client
 * B when B joins and again when A changes it; a refused look never spreads; the look is saved with the player and
 * comes back on a rejoin; a save from before #459 loads with the defaults and takes the client's offered look once.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
@Tag("integration")
class AppearanceReplicationTest {

    @TempDir
    Path home;

    @BeforeAll
    static void native_() {
        assumeTrue(CendaKernels.isAvailable(), "the look codec is native");
    }

    private static byte[] look(String hat, String hair) {
        return PlayerAppearance.defaults().withItem("hat", hat).withItem("hair", hair).toBinary();
    }

    private static PlayerAppearance read(byte[] bytes) {
        return PlayerAppearance.read(bytes);
    }

    @Test
    void aLookSetByOneClientReachesTheOtherAtJoinAndOnChange() throws Exception {
        assumeTrue(ServerHarness.isolatedHomeAvailable(), "worlds would land in XDG_DATA_HOME");
        try (ServerHarness server = ServerHarness.boot(home, "looks-459");
             HarnessClient a = HarnessClient.join(server, "Ann")) {
            int annId = server.player("Ann").playerId();
            // A learns its own look on joining (a new character: the defaults it created with).
            PlayerAppearanceS2C own = a.await(PlayerAppearanceS2C.class, p -> p.playerId() == annId, HarnessClient.PROMPT)
                    .orElseThrow(() -> new AssertionError("Ann never learned her look"));
            assertEquals("NONE", read(own.appearance()).item("hat"));

            assertTrue(a.send(new PlayerAppearanceC2S(look("TOP_HAT", "MALE_HAIR_1"), false)));
            assertTrue(ServerHarness.await(() -> lookOf(server, "Ann").item("hat").equals("TOP_HAT"),
                    HarnessClient.PROMPT), "the server took Ann's change");

            try (HarnessClient b = HarnessClient.join(server, "Ben")) {
                PlayerJoinS2C annJoin = b.await(PlayerJoinS2C.class, j -> j.playerId() == annId, HarnessClient.PROMPT)
                        .orElseThrow(() -> new AssertionError("Ben never heard of Ann"));
                PlayerAppearance seen = read(annJoin.appearance());
                assertEquals("TOP_HAT", seen.item("hat"), "B sees A's look after joining");
                assertEquals("MALE_HAIR_1", seen.item("hair"));

                assertTrue(a.send(new PlayerAppearanceC2S(look("PIRATE_HAT", "NONE"), false)));
                PlayerAppearanceS2C change = b.await(PlayerAppearanceS2C.class,
                        p -> p.playerId() == annId && read(p.appearance()).item("hat").equals("PIRATE_HAT"),
                        HarnessClient.PROMPT).orElseThrow(() -> new AssertionError("Ben never saw Ann's change"));
                assertEquals("NONE", read(change.appearance()).item("hair"), "B sees A's look after a change");

                // A refused look (an item the catalog lacks / in the wrong slot) never spreads; A gets its look back.
                int before = b.received(PlayerAppearanceS2C.class).size();
                assertTrue(a.send(new PlayerAppearanceC2S(
                        PlayerAppearance.defaults().withItem("hat", "MALE_HAIR_2").toBinary(), false)));
                assertTrue(a.await(PlayerAppearanceS2C.class, p -> p.playerId() == annId
                        && read(p.appearance()).item("hat").equals("PIRATE_HAT")
                        && read(p.appearance()).item("hair").equals("NONE"), HarnessClient.PROMPT).isPresent());
                Thread.sleep(200);
                assertEquals(before, b.received(PlayerAppearanceS2C.class).size(), "a refused look is not broadcast");
                assertEquals("PIRATE_HAT", lookOf(server, "Ann").item("hat"));
            }
        }
    }

    @Test
    void theLookIsSavedWithThePlayerAndOldSavesTakeTheOfferOnce() throws Exception {
        assumeTrue(ServerHarness.isolatedHomeAvailable(), "worlds would land in XDG_DATA_HOME");
        try (ServerHarness server = ServerHarness.boot(home, "looks-save-459")) {
            // A save from before #459: no appearance.
            server.ctx().serverLevel().saveService()
                    .saveNamedPlayer("Old", new JsonPlayerSerializer().serialize(PlayerData.createDefault("looks-save-459")))
                    .get(10, TimeUnit.SECONDS);
            try (HarnessClient old = HarnessClient.join(server, "Old")) {
                ServerPlayer sp = server.player("Old");
                assertFalse(server.onTick(sp::appearanceStored), "an old save has no look");
                assertEquals("NONE", lookOf(server, "Old").item("hat"), "it loads with the defaults");

                assertTrue(old.send(new PlayerAppearanceC2S(look("TOP_HAT", "NONE"), true)));
                assertTrue(ServerHarness.await(() -> lookOf(server, "Old").item("hat").equals("TOP_HAT"),
                        HarnessClient.PROMPT), "the offer is taken once");
                assertTrue(old.send(new PlayerAppearanceC2S(look("PIRATE_HAT", "NONE"), true)));
                Thread.sleep(300);
                assertEquals("TOP_HAT", lookOf(server, "Old").item("hat"), "a second offer is ignored");
            }
            assertTrue(ServerHarness.await(() -> gone(server, "Old"), HarnessClient.PROMPT));
            byte[] saved = server.savedPlayer("Old");
            String json = new JsonPlayerSerializer().deserialize(saved).getAppearance();
            assertNotNull(json, "the leave saved the look");
            assertEquals("TOP_HAT", PlayerAppearance.readJson(json).item("hat"));

            try (HarnessClient again = HarnessClient.join(server, "Old")) {
                assertTrue(server.onTick(() -> server.player("Old").appearanceStored()));
                assertEquals("TOP_HAT", lookOf(server, "Old").item("hat"), "the rejoin restored the saved look");
                assertTrue(again.send(new PlayerAppearanceC2S(look("PIRATE_HAT", "NONE"), true)));
                Thread.sleep(300);
                assertEquals("TOP_HAT", lookOf(server, "Old").item("hat"), "no offer replaces a saved look");
            }
        }
    }

    @Test
    void aSaveWithoutALookReadsNoLook() {
        PlayerData data = new JsonPlayerSerializer().deserialize(
                new JsonPlayerSerializer().serialize(PlayerData.createDefault("w")));
        assertNull(data.getAppearance());
        PlayerData withLook = new PlayerData.Builder(PlayerData.createDefault("w"))
                .appearance(PlayerAppearance.defaults().withItem("hat", "TOP_HAT").toJson()).build();
        PlayerData back = new JsonPlayerSerializer().deserialize(new JsonPlayerSerializer().serialize(withLook));
        assertEquals("TOP_HAT", PlayerAppearance.readJson(back.getAppearance()).item("hat"));
    }

    private static PlayerAppearance lookOf(ServerHarness server, String name) {
        try {
            ServerPlayer sp = server.player(name);
            return sp == null ? PlayerAppearance.defaults() : server.onTick(sp::appearance);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static boolean gone(ServerHarness server, String name) {
        try {
            return server.player(name) == null;
        } catch (Exception e) {
            return false;
        }
    }
}

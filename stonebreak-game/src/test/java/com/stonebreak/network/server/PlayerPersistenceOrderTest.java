package com.stonebreak.network.server;

import com.stonebreak.items.ItemType;
import com.stonebreak.network.packet.handshake.KickS2C;
import com.stonebreak.network.packet.handshake.WelcomeS2C;
import com.stonebreak.world.save.SaveService;
import com.stonebreak.world.save.WorldStorage;
import com.stonebreak.world.save.repository.PlayerSaveNames;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #347: one player's saves and loads happen in order — a load never sees anything older
 * than the last save queued before it (it may see a newer one: it waits for the write in flight,
 * not for later ones), always reads a whole record, and a leave's save is what the next join
 * reads — and a load that fails refuses the join rather than resetting the player.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class PlayerPersistenceOrderTest {

    @TempDir
    Path home;

    private static byte[] version(int n) {
        return ("{\"version\":" + n + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static int versionOf(byte[] record) {
        String json = new String(record, StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{\"version\":") && json.endsWith("}"), "a torn record: " + json);
        return Integer.parseInt(json.substring("{\"version\":".length(), json.length() - 1));
    }

    @Test
    void interleavedSavesAndLoadsOfOnePlayerHappenInOrder() throws Exception {
        try (SaveService saves = new SaveService(home.resolve("order").toString())) {
            List<CompletableFuture<byte[]>> loads = new ArrayList<>();
            List<Integer> expected = new ArrayList<>();
            int last = -1;
            for (int i = 0; i < 300; i++) {
                if (i % 4 == 3) {
                    loads.add(saves.loadNamedPlayer("Ord"));
                    expected.add(last);
                } else {
                    saves.saveNamedPlayer("Ord", version(i));
                    last = i;
                }
            }
            for (int i = 0; i < loads.size(); i++) {
                int seen = versionOf(loads.get(i).get(10, TimeUnit.SECONDS));
                assertTrue(seen >= expected.get(i),
                        "load " + i + " saw version " + seen + ", older than the save queued before it ("
                                + expected.get(i) + ")");
                assertTrue(seen % 4 != 3 && seen <= last, "a whole record that was really saved: " + seen);
            }
            assertArrayEquals(version(last), saves.loadNamedPlayer("Ord").get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    @Tag("integration")
    void aLeaveSaveIsWhatAnImmediateRejoinLoads() throws Exception {
        assumeTrue(ServerHarness.isolatedHomeAvailable(), "worlds would land in XDG_DATA_HOME");
        int stick = ItemType.STICK.getId();
        try (ServerHarness server = ServerHarness.boot(home, "persist-347")) {
            int had;
            try (HarnessClient first = HarnessClient.join(server, "Pat")) {
                ServerPlayer sp = server.player("Pat");
                had = server.onTick(() -> {
                    sp.inventory().add(stick, 7);
                    return sp.inventory().countOf(stick);
                });
            } // the leave persists the record...
            assertTrue(ServerHarness.await(() -> gone(server, "Pat"), HarnessClient.PROMPT));

            try (HarnessClient again = HarnessClient.join(server, "Pat")) { // ...and the join reads it after
                ServerPlayer back = server.player("Pat");
                assertEquals(had, (int) server.onTick(() -> back.inventory().countOf(stick)),
                        "the rejoin restored what the leave saved");
            }
        }
    }

    @Test
    @Tag("integration")
    void aFailedLoadRefusesTheJoinAndNeverResetsThePlayer() throws Exception {
        assumeTrue(ServerHarness.isolatedHomeAvailable(), "worlds would land in XDG_DATA_HOME");
        try (ServerHarness server = ServerHarness.boot(home, "failload-347")) {
            Path file = WorldStorage.worldDir("failload-347").resolve("players")
                    .resolve(PlayerSaveNames.stem("Quin") + ".json");
            Files.createDirectories(file); // the record cannot be read: an I/O failure

            try (HarnessClient c = HarnessClient.connect(server)) {
                c.handshake("Quin", "");
                KickS2C kick = c.await(KickS2C.class, HarnessClient.PROMPT).orElseThrow();
                assertTrue(kick.reason().contains("could not be loaded"), kick.reason());
                assertTrue(c.received(WelcomeS2C.class).isEmpty(), "never let in as a new player");
            }
            assertTrue(Files.isDirectory(file), "nothing was written over the unreadable record");
            try (var entries = Files.list(file)) {
                assertEquals(0, entries.count());
            }
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

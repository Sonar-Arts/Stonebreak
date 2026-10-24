package com.stonebreak.network.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Issue #335: the client keeps each server's join secret per host:port and name, across runs. */
class ClientSecretStoreTest {

    @TempDir
    Path dir;

    @Test
    void secretsArePerServerAndNameAndSurviveARestart() {
        Path file = dir.resolve("multiplayer_secrets.properties");
        ClientSecretStore store = new ClientSecretStore(file);
        assertEquals("", store.get("example.org", 25565, "Bob"), "none issued yet");

        store.put("Example.org", 25565, "Bob", "s1");
        store.put("example.org", 25566, "Bob", "s2");
        store.put("example.org", 25565, "bob", "s3");

        ClientSecretStore reopened = new ClientSecretStore(file);
        assertEquals("s1", reopened.get("example.org ", 25565, "Bob"), "host names ignore case and padding");
        assertEquals("s2", reopened.get("example.org", 25566, "Bob"));
        assertEquals("s3", reopened.get("example.org", 25565, "bob"), "player names keep their case");

        reopened.put("example.org", 25565, "Bob", "rotated");
        assertEquals("rotated", new ClientSecretStore(file).get("example.org", 25565, "Bob"));
    }
}

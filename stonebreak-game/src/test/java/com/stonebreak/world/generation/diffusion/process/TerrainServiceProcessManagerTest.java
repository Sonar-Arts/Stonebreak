package com.stonebreak.world.generation.diffusion.process;

import com.stonebreak.world.generation.diffusion.TerrainBridgeException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rules {@link TerrainServiceProcessManager} uses to decide a port is busy, that the
 * process holding it is one of ours, and that the service answering /health is the one we started.
 *
 * <p>All three exist because of the same failure: a terrain service leaked by an earlier JVM kept
 * :8180, every freshly spawned bridge died on {@code EADDRINUSE}, and the leaked process answered
 * the health probe on its behalf — so startup "succeeded", the dead child was noticed on the next
 * ensureRunningForSeed call, and both services were torn down and relaunched in a tight loop while
 * the map rendered the leaked instance's seed.
 */
class TerrainServiceProcessManagerTest {

    @Test
    void portBoundOnLoopbackOnlyStillCountsAsBusy() throws IOException {
        // uvicorn binds 127.0.0.1; the probe binds the wildcard. If that read as "free" the manager
        // would spawn a child doomed to EADDRINUSE, which is the whole bug.
        try (ServerSocket loopbackOnly = new ServerSocket()) {
            loopbackOnly.bind(new java.net.InetSocketAddress("127.0.0.1", 0), 1);
            assertFalse(TerrainServiceProcessManager.isPortFree(loopbackOnly.getLocalPort()));
        }
    }

    @Test
    void closedPortIsFree() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        assertTrue(TerrainServiceProcessManager.isPortFree(port));
    }

    @Test
    void onlyPythonExecutablesCanBeTerrainServices() {
        assertTrue(TerrainServiceProcessManager.isPython("/home/dev/terrain-bridge/venv/bin/python"));
        assertTrue(TerrainServiceProcessManager.isPython("/usr/bin/python3"));
        assertTrue(TerrainServiceProcessManager.isPython("python"));
        // The leaked services were launched from a shell whose own argv quotes the whole command,
        // so the wrapper looks exactly like the service to any substring match.
        assertFalse(TerrainServiceProcessManager.isPython("/bin/bash"));
        assertFalse(TerrainServiceProcessManager.isPython("/usr/bin/java"));
        assertFalse(TerrainServiceProcessManager.isPython(""));
    }

    @Test
    void portArgumentIsMatchedAsAWholeToken() {
        assertTrue(TerrainServiceProcessManager.runsOnPort(
                List.of("-m", "uvicorn", "bridge.main:app", "--port", "8180"), 8180));
        assertTrue(TerrainServiceProcessManager.runsOnPort(
                List.of("-m", "uvicorn", "bridge.main:app", "--port=8180"), 8180));
        // A different service on a neighbouring port must never be reclaimed.
        assertFalse(TerrainServiceProcessManager.runsOnPort(
                List.of("-m", "uvicorn", "bridge.main:app", "--port", "8181"), 8180));
        // "--port 81801" must not satisfy a request for 8180.
        assertFalse(TerrainServiceProcessManager.runsOnPort(List.of("--port", "81801"), 8180));
        assertFalse(TerrainServiceProcessManager.runsOnPort(List.of("--port"), 8180));
        assertFalse(TerrainServiceProcessManager.runsOnPort(List.of(), 8180));
    }

    @Test
    void healthFromADifferentlySeededServiceIsRejected() {
        TerrainBridgeException e = assertThrows(TerrainBridgeException.class, () ->
                TerrainServiceProcessManager.verifySeed(
                        "{\"status\":\"ok\",\"seed\":1433293152336000383,\"scale\":2}",
                        1711391174661493985L, "terrain-bridge", "http://localhost:8180/health"));
        assertTrue(e.getMessage().contains("1433293152336000383"));
        assertTrue(e.getMessage().contains("1711391174661493985"));
    }

    @Test
    void healthWithoutASeedIsRejected() {
        assertThrows(TerrainBridgeException.class, () -> TerrainServiceProcessManager.verifySeed(
                "{\"status\":\"ok\"}", 42L, "terrain-bridge", "http://localhost:8180/health"));
    }

    @Test
    void matchingSeedPasses() {
        TerrainServiceProcessManager.verifySeed("{\"seed\": -7, \"scale\":2}", -7L, "terrain-bridge", "url");
        // Upstream's /health carries no seed, so it opts out with a null expectation.
        TerrainServiceProcessManager.verifySeed("{\"status\":\"ok\"}", null, "upstream", "url");
    }
}

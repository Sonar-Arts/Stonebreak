package com.openmason.main.systems.io;

import com.openmason.main.systems.io.AssetWriteService.WriteOutcome;
import com.openmason.main.systems.io.AssetWriteService.WriteRequest;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetWriteServiceTest {

    @TempDir
    Path tmp;

    private Path project;
    private Path game;
    private WriteSandbox sandbox;

    @BeforeEach
    void roots() throws IOException {
        project = Files.createDirectories(tmp.resolve("proj"));
        game = Files.createDirectories(tmp.resolve("game"));
        Files.createDirectories(game.resolve("sbo/blocks"));
        sandbox = new WriteSandbox(WriteRoots.of(project, game, tmp.resolve("exports")));
        // No UI: submit() runs inline on the bound thread.
        MainThreadExecutor.bindToCurrentThread();
    }

    private static WriteTarget target(WriteRoot root, boolean exists) {
        return new WriteTarget(WriteKind.OMO, Path.of("/x/y.omo"), root, exists);
    }

    private static WriteRequest req(boolean overwrite) {
        return WriteRequest.of(WriteKind.OMO, "x", false, overwrite, "x");
    }

    @Test
    void policyMatrix() {
        // ASK_ALWAYS asks for everything
        assertTrue(AssetWriteService.mustAsk(target(WriteRoot.PROJECT, false), req(false),
                WritePolicy.ASK_ALWAYS));

        // ASK_RISKY: new file in project → silent; overwrite → ask; game tree → ask
        assertFalse(AssetWriteService.mustAsk(target(WriteRoot.PROJECT, false), req(false),
                WritePolicy.ASK_RISKY));
        assertFalse(AssetWriteService.mustAsk(target(WriteRoot.EXPORTS, false), req(false),
                WritePolicy.ASK_RISKY));
        assertTrue(AssetWriteService.mustAsk(target(WriteRoot.PROJECT, true), req(true),
                WritePolicy.ASK_RISKY));
        assertTrue(AssetWriteService.mustAsk(target(WriteRoot.GAME_RESOURCES, false), req(false),
                WritePolicy.ASK_RISKY));

        // ASK_NEVER_IN_PROJECT: overwrite needs the flag; game tree still asks
        assertFalse(AssetWriteService.mustAsk(target(WriteRoot.PROJECT, true), req(true),
                WritePolicy.ASK_NEVER_IN_PROJECT));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AssetWriteService.mustAsk(target(WriteRoot.PROJECT, true), req(false),
                        WritePolicy.ASK_NEVER_IN_PROJECT));
        assertTrue(e.getMessage().startsWith("file_exists"));
        assertTrue(AssetWriteService.mustAsk(target(WriteRoot.GAME_RESOURCES, false), req(true),
                WritePolicy.ASK_NEVER_IN_PROJECT));
    }

    @Test
    void silentWriteCreatesParentsAndReportsTarget() {
        AssetWriteService svc = new AssetWriteService(null, sandbox, () -> WritePolicy.ASK_RISKY);
        AtomicReference<Path> written = new AtomicReference<>();
        WriteOutcome out = svc.save(WriteRequest.of(WriteKind.OMO, "project:models/Cow", false, false, "Cow"),
                p -> {
                    written.set(p);
                    Files.writeString(p, "omo");
                    return true;
                });
        assertTrue(out.ok(), out.message());
        assertEquals("saved", out.status());
        assertEquals(real(project.resolve("models/Cow.omo")), written.get());
        assertEquals("project", out.root());
        assertEquals(Boolean.FALSE, out.overwritten());
        assertEquals(Boolean.FALSE, out.promptedUser());
        assertTrue(Files.isRegularFile(written.get()));
    }

    @Test
    void writerFailureIsStructured() {
        AssetWriteService svc = new AssetWriteService(null, sandbox, () -> WritePolicy.ASK_RISKY);
        WriteOutcome out = svc.save(WriteRequest.of(WriteKind.OMO, "project:x", false, false, "x"),
                p -> false);
        assertFalse(out.ok());
        assertEquals("failed", out.status());
        assertEquals("write_failed", out.reason());

        WriteOutcome thrown = svc.save(WriteRequest.of(WriteKind.OMO, "project:y", false, false, "y"),
                p -> {
                    throw new IOException("disk full");
                });
        assertEquals("failed", thrown.status());
        assertTrue(thrown.message().contains("disk full"));
    }

    @Test
    void outsideSandboxThrowsBeforeAnyDialog() {
        AssetWriteService svc = new AssetWriteService(null, sandbox, () -> WritePolicy.ASK_RISKY);
        assertThrows(IllegalArgumentException.class, () -> svc.save(
                WriteRequest.of(WriteKind.OMO, tmp.resolve("nope.omo").toString(), false, false, "x"),
                p -> true));
    }

    @Test
    void promptWithoutUiIsDeclinedNotErrored() throws Exception {
        AssetWriteService svc = new AssetWriteService(null, sandbox, () -> WritePolicy.ASK_RISKY);
        // Run off the bound "main" thread: asking from the main thread is a programming error.
        WriteOutcome out = CompletableFuture.supplyAsync(() ->
                svc.save(WriteRequest.of(WriteKind.SBO, "SB_Cow", false, false, "SB_Cow"), p -> true)).get();
        assertFalse(out.ok());
        assertEquals("declined", out.status());
        assertEquals("prompt_unavailable", out.reason());

        WriteOutcome noPath = CompletableFuture.supplyAsync(() ->
                svc.save(WriteRequest.of(WriteKind.OMO, null, false, false, "x"), p -> true)).get();
        assertEquals("prompt_unavailable", noPath.reason());
    }

    @Test
    void askingFromMainThreadIsRejected() {
        AssetWriteService svc = new AssetWriteService(null, sandbox, () -> WritePolicy.ASK_ALWAYS);
        assertThrows(IllegalStateException.class, () -> svc.save(
                WriteRequest.of(WriteKind.OMO, "project:x", false, false, "x"), p -> true));
    }

    @Test
    void inPlaceSaveSkipsPolicy() throws IOException {
        AssetWriteService svc = new AssetWriteService(null, sandbox, () -> WritePolicy.ASK_ALWAYS);
        Path own = Files.writeString(tmp.resolve("anywhere.omo"), "old");
        WriteOutcome out = svc.saveInPlace(WriteKind.OMO, own.toString(), p -> {
            Files.writeString(p, "new");
            return true;
        });
        assertTrue(out.ok());
        assertEquals(Boolean.TRUE, out.inPlace());
        assertEquals("new", Files.readString(own));
        assertEquals("no_current_path", svc.saveInPlace(WriteKind.OMO, null, p -> true).reason());
    }

    @Test
    void targetsListsRootsAndKinds() {
        AssetWriteService svc = new AssetWriteService(null, sandbox, () -> WritePolicy.ASK_RISKY);
        Map<String, Object> t = svc.targets();
        assertEquals("ASK_RISKY", t.get("policy"));
        assertEquals(Boolean.FALSE, t.get("saveSheetAvailable"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roots = (List<Map<String, Object>>) t.get("roots");
        assertEquals(3, roots.size());
        assertEquals(Boolean.TRUE, roots.get(1).get("present"));
        assertEquals(List.of("sbo"), roots.get(1).get("subfolders"));
        assertEquals(Boolean.TRUE, roots.get(2).get("present"), "exports root is created lazily, so it is always offered");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kinds = (List<Map<String, Object>>) t.get("kinds");
        assertEquals(WriteKind.values().length, kinds.size());
        assertEquals("game:sbo/blocks/MyObject.sbo",
                kinds.stream().filter(k -> "sbo".equals(k.get("kind"))).findFirst().orElseThrow().get("example"));
    }

    @Test
    void promptStatusWithoutUi() {
        AssetWriteService svc = new AssetWriteService(null, sandbox, () -> WritePolicy.ASK_RISKY);
        Map<String, Object> s = svc.promptStatus();
        assertEquals(Boolean.FALSE, s.get("pending"));
        assertEquals("none", s.get("dialog"));
        assertNull(s.get("title"));
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}

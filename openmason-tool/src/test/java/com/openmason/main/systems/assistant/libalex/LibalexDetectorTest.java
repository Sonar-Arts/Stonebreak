package com.openmason.main.systems.assistant.libalex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LibalexDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void piConfigWins() throws IOException {
        Path pi = tempDir.resolve("libalex.json");
        Files.writeString(pi, "{\"command\":\"uv\",\"args\":[\"run\",\"libalex\"]}");
        Path claude = tempDir.resolve("claude.json");
        LibalexDetector.LaunchConfig config = LibalexDetector.detect(pi, claude);
        assertEquals(java.util.List.of("uv", "run", "libalex"), config.commandLine());
    }

    @Test
    void claudeFallback() throws IOException {
        Path claude = tempDir.resolve("claude.json");
        Files.writeString(claude,
                "{\"mcpServers\":{\"libalex\":{\"command\":\"uvx\",\"args\":[\"libalex\"]}}}");
        LibalexDetector.LaunchConfig config =
                LibalexDetector.detect(tempDir.resolve("missing.json"), claude);
        assertEquals(java.util.List.of("uvx", "libalex"), config.commandLine());
    }

    @Test
    void absentOrMalformedMeansNotDetected() throws IOException {
        assertNull(LibalexDetector.detect(tempDir.resolve("a.json"), tempDir.resolve("b.json")));
        Path malformed = tempDir.resolve("libalex.json");
        Files.writeString(malformed, "{not json");
        assertNull(LibalexDetector.detect(malformed, tempDir.resolve("b.json")));
        Path noCommand = tempDir.resolve("nc.json");
        Files.writeString(noCommand, "{\"args\":[\"x\"]}");
        assertNull(LibalexDetector.detect(noCommand, tempDir.resolve("b.json")));
    }
}

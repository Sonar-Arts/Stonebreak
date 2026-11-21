package com.stonebreak.world.generation.diffusion.tgmpipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pinned CUDA compiler: only files with the pinned SHA-256 count as installed; nothing else is ever picked up. */
class NvrtcToolTest {

    @TempDir
    Path dir;

    private Map<String, String> pin(String... nameAndContent) throws IOException {
        var out = new java.util.TreeMap<String, String>();
        for (int i = 0; i < nameAndContent.length; i += 2) {
            Path f = dir.resolve(nameAndContent[i]);
            Files.writeString(f, nameAndContent[i + 1]);
            out.put(nameAndContent[i], NvrtcTool.sha256(f));
        }
        return out;
    }

    @Test
    void aCopyMatchingThePinVerifiesAndIsRecorded() throws IOException {
        Map<String, String> pinned = pin("libnvrtc.so.12", "compiler", "libnvrtc-builtins.so.12.9", "builtins");
        assertTrue(NvrtcTool.verify(dir, pinned));
        assertTrue(Files.isRegularFile(dir.resolve(NvrtcTool.VERIFIED)), "record written");
        assertTrue(NvrtcTool.verify(dir, pinned), "and again from the record");
    }

    @Test
    void anAlteredOrMissingFileIsNotInstalled() throws IOException {
        Map<String, String> pinned = pin("libnvrtc.so.12", "compiler", "libnvrtc-builtins.so.12.9", "builtins");
        assertTrue(NvrtcTool.verify(dir, pinned));
        Files.writeString(dir.resolve("libnvrtc.so.12"), "another compiler");
        assertFalse(NvrtcTool.verify(dir, pinned), "different bytes");
        Files.writeString(dir.resolve("libnvrtc.so.12"), "compiler");
        assertTrue(NvrtcTool.verify(dir, pinned), "restored");
        Files.delete(dir.resolve("libnvrtc-builtins.so.12.9"));
        assertFalse(NvrtcTool.verify(dir, pinned), "a pinned file is missing");
    }

    @Test
    void aRecordWhoseSizeAndMtimeMatchIsTrustedWithoutRehashing() throws IOException {
        Map<String, String> pinned = pin("libnvrtc.so.12", "compiler");
        Path lib = dir.resolve("libnvrtc.so.12");
        FileTime mtime = Files.getLastModifiedTime(lib);
        // A record claiming the pinned hash for these exact size + mtime is believed (no rehash on every launch) ...
        Files.writeString(lib, "COMPILER");   // same size, different bytes
        Files.setLastModifiedTime(lib, mtime);
        Files.writeString(dir.resolve(NvrtcTool.VERIFIED),
                "libnvrtc.so.12|" + Files.size(lib) + "|" + mtime.toMillis() + "|" + pinned.get("libnvrtc.so.12") + "\n");
        assertTrue(NvrtcTool.verify(dir, pinned));
        // ... but any change of mtime makes it hash the file, and the bytes no longer match.
        Files.setLastModifiedTime(lib, FileTime.fromMillis(mtime.toMillis() + 1000));
        assertFalse(NvrtcTool.verify(dir, pinned));
    }

    @Test
    void theEnvironmentNeverOverridesThePin() {
        // CENDA_NVRTC (or any system toolkit) must not leak in: only the explicit dev property counts.
        String old = System.clearProperty("stonebreak.terrain.nvrtc");
        try {
            assertNull(NvrtcTool.override());
        } finally {
            if (old != null) System.setProperty("stonebreak.terrain.nvrtc", old);
        }
    }

    @Test
    void thisPlatformsPinNamesTheLibraryTheEngineOpens() {
        NvrtcTool.Wheel w = NvrtcTool.wheel();
        if (w != null) {
            assertTrue(w.files().containsKey(w.library()), w.toString());
            assertEquals(64, w.sha256().length());
            w.files().values().forEach(h -> assertEquals(64, h.length(), h));
        }
    }
}

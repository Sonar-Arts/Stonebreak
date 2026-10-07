package com.openmason.engine.cenda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packaged Cenda library (#282 C11): a shipped game finds it inside its jar and extracts it
 * once to a per-user cache, keyed by its bytes.
 */
class NativeLibraryExtractorTest {

    @Test
    void platformNamesMatchThePomProfiles() {
        assertEquals("linux-x86_64", NativeLibraryExtractor.platform("Linux", "amd64"));
        assertEquals("linux-aarch64", NativeLibraryExtractor.platform("Linux", "aarch64"));
        assertEquals("windows-x86_64", NativeLibraryExtractor.platform("Windows 11", "amd64"));
        assertEquals("macos-aarch64", NativeLibraryExtractor.platform("Mac OS X", "aarch64"));
        assertEquals("macos-x86_64", NativeLibraryExtractor.platform("Mac OS X", "x86_64"));
        assertTrue(NativeLibraryExtractor.resourceName("libcenda_kernels.so").startsWith("natives/"));
    }

    @Test
    void extractsOnceAndReusesTheFile(@TempDir Path cache) throws IOException {
        byte[] lib = "pretend this is an ELF".getBytes();
        AtomicInteger opens = new AtomicInteger();
        NativeLibraryExtractor.Opener opener = r -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(lib);
        };
        Path first = NativeLibraryExtractor.extract("natives/x/lib.so", "lib.so", opener, cache);
        assertArrayEquals(lib, Files.readAllBytes(first));
        assertEquals(cache, first.getParent().getParent(), "<cache>/<hash>/<lib>");
        long modified = Files.getLastModifiedTime(first).toMillis();
        Path again = NativeLibraryExtractor.extract("natives/x/lib.so", "lib.so", opener, cache);
        assertEquals(first, again);
        assertEquals(modified, Files.getLastModifiedTime(again).toMillis(), "reused, not rewritten");
        try (var files = Files.list(first.getParent())) {
            assertEquals(1, files.count(), "no temp files left behind");
        }
    }

    @Test
    void anotherBuildGetsItsOwnDirectory(@TempDir Path cache) throws IOException {
        Path a = NativeLibraryExtractor.extract("r", "lib.so", r -> new ByteArrayInputStream(new byte[]{1, 2}), cache);
        Path b = NativeLibraryExtractor.extract("r", "lib.so", r -> new ByteArrayInputStream(new byte[]{3, 4}), cache);
        assertNotEquals(a, b, "a running game may still map the old library: never overwrite it");
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(a));
    }

    @Test
    void aMissingResourceIsNull(@TempDir Path cache) throws IOException {
        assertNull(NativeLibraryExtractor.extract("natives/none/lib.so", "lib.so", r -> null, cache));
    }

    @Test
    void theBuildPackagesTheLibraryForThisPlatformWhenItWasBuilt() throws IOException {
        // The engine POM copies cenda/build/release/... into natives/<platform>/ at process-resources.
        Path lib = CendaKernels.locateLibrary();
        if (lib == null || !lib.toString().contains("build" + java.io.File.separator + "release")) {
            return; // no release build here: nothing to package
        }
        String resource = NativeLibraryExtractor.resourceName(lib.getFileName().toString());
        try (var in = NativeLibraryExtractor.openResource(resource)) {
            assertTrue(in != null, resource + " is missing from the classpath; check the cenda.natives.dir POM profile");
        }
    }
}

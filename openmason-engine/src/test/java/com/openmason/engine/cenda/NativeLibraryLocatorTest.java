package com.openmason.engine.cenda;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The discovery order a game's own native library shares with the engine's (#386). */
class NativeLibraryLocatorTest {

    private static final String PROP = "openmason.test.locator.path";

    @Test
    void propertyWinsAndIsTheOnlyPlaceLookedWhenSet(@TempDir Path dir) throws Exception {
        Path lib = Files.writeString(dir.resolve("x.so"), "lib");
        NativeLibraryLocator.Spec spec = new NativeLibraryLocator.Spec("never_built_lib", PROP, null,
            List.of(dir.toString()), r -> new ByteArrayInputStream(new byte[]{1}));
        System.setProperty(PROP, lib.toString());
        try {
            assertEquals(lib, NativeLibraryLocator.locate(spec));
            System.setProperty(PROP, dir.resolve("missing.so").toString());
            assertNull(NativeLibraryLocator.locate(spec), "a named but missing file is not silently replaced");
        } finally {
            System.clearProperty(PROP);
        }
    }

    @Test
    void searchesBuildDirsAndTheirConfigSubdirectories(@TempDir Path dir) throws Exception {
        String name = System.mapLibraryName("locator_probe");
        Files.createDirectories(dir.resolve("out/Release"));
        Path lib = Files.writeString(dir.resolve("out/Release").resolve(name), "lib");
        NativeLibraryLocator.Spec spec = new NativeLibraryLocator.Spec("locator_probe", null, null,
            List.of(dir.resolve("nothing").toString(), dir.resolve("out").toString()), null);
        assertEquals(lib.toAbsolutePath().normalize(), NativeLibraryLocator.locate(spec));
    }

    @Test
    void fallsBackToThePackagedCopyThroughTheCallersOpener(@TempDir Path cache) {
        String libName = System.mapLibraryName("locator_packaged_probe");
        String expected = NativeLibraryLocator.packagedResourceName(libName);
        String[] asked = new String[1];
        NativeLibraryLocator.Spec spec = new NativeLibraryLocator.Spec("locator_packaged_probe", null, null,
            List.of(), r -> {
                asked[0] = r;
                return new ByteArrayInputStream(new byte[]{7, 7, 7});
            });
        System.setProperty("cenda.natives.cache", cache.toString());
        try {
            Path p = NativeLibraryLocator.locate(spec);
            assertEquals(expected, asked[0]);
            assertTrue(p != null && p.startsWith(cache) && p.getFileName().toString().equals(libName));
        } finally {
            System.clearProperty("cenda.natives.cache");
        }
    }

    @Test
    void nothingFoundIsNull() {
        assertNull(NativeLibraryLocator.locate(new NativeLibraryLocator.Spec("locator_absent_probe",
            null, null, List.of("does/not/exist"), r -> null)));
    }
}

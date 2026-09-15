package com.openmason.main.systems.assets;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against the real game resource tree (dev checkout) — validates that the
 * unified index actually sees the shipped assets.
 */
class AssetCatalogTest {

    private final AssetCatalog catalog = new AssetCatalog();

    @Test
    void discoversShippedAssets() {
        List<AssetEntry> all = catalog.listAll(true);
        long blocks = all.stream()
                .filter(e -> e.kind() == AssetEntry.Kind.SBO && "block".equals(e.type())).count();
        long items = all.stream()
                .filter(e -> e.kind() == AssetEntry.Kind.SBO && "item".equals(e.type())).count();
        long sbe = all.stream().filter(e -> e.kind() == AssetEntry.Kind.SBE).count();
        assertTrue(blocks >= 40, "expected >=40 block SBOs, saw " + blocks);
        assertTrue(items >= 20, "expected >=20 item SBOs, saw " + items);
        assertTrue(sbe >= 6, "expected >=6 SBE entities, saw " + sbe);
        for (AssetEntry e : all) {
            assertNotNull(e.sourcePath(), e.id() + " has no sourcePath");
            assertTrue(Files.isRegularFile(e.sourcePath()), e.id() + " path missing");
            assertFalse(e.id().isBlank());
        }
    }

    @Test
    void findsBeyondMobsFolder() {
        // sbe/Clothing + sbe/PlayerCustomize must be indexed too.
        List<AssetEntry> all = catalog.listAll(false);
        assertTrue(all.stream().anyMatch(e -> e.folder().startsWith("sbe/")
                        && !e.folder().equals("sbe/Mobs")),
                "no SBE outside sbe/Mobs found — recursive scan broken");
    }

    @Test
    void searchRanksPrefixFirst() {
        AssetCatalog.SearchResult result = catalog.search("door", null, null, 10, 0, false);
        assertTrue(result.total() >= 1, "no door assets found");
        assertTrue(result.entries().stream()
                .allMatch(e -> (e.id() + e.displayName()).toLowerCase().contains("door")));
        AssetCatalog.SearchResult exact = catalog.search("oak_door", null, null, 5, 0, false);
        assertFalse(exact.entries().isEmpty());
        assertEquals("stonebreak:oak_door", exact.entries().get(0).id());
        // Filename and namespace-free spellings must resolve too.
        assertEquals("stonebreak:oak_door", catalog.find("SB_Oak_Door").id());
        assertEquals("stonebreak:oak_door", catalog.find("oak_door").id());
        assertEquals("stonebreak:oak_door", catalog.find("SB_Oak_Door.sbo").id());
    }

    @Test
    void kindAndTypeFiltersApply() {
        AssetCatalog.SearchResult mobs = catalog.search(null, AssetEntry.Kind.SBE, "mob", 50, 0, false);
        assertTrue(mobs.total() >= 4, "expected >=4 mobs, saw " + mobs.total());
        assertTrue(mobs.entries().stream().allMatch(e -> "mob".equals(e.type())));
    }

    @Test
    void pathGuardRejectsEscapes() {
        assertThrows(IllegalArgumentException.class,
                () -> AssetPathGuard.requireAssetPath("/etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> AssetPathGuard.requireAssetPath("../outside.sbo",
                        catalog.scanRoots().toArray(new java.nio.file.Path[0])));
    }

    @Test
    void sanitizeStripsSeparators() {
        assertEquals("a_b_c", AssetPathGuard.sanitizeFileName("a/b\\c", "x"));
        assertEquals("x", AssetPathGuard.sanitizeFileName("...", "x"));
        assertEquals("x", AssetPathGuard.sanitizeFileName(null, "x"));
    }
}

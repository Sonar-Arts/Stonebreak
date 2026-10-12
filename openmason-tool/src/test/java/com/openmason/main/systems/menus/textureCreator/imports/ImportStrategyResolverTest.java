package com.openmason.main.systems.menus.textureCreator.imports;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Import routing by file type: PNGs import at their native size, .OMT always
 * asks (flatten vs. all layers), everything else is refused with a reason.
 */
class ImportStrategyResolverTest {

    private final ImportStrategyResolver resolver = new ImportStrategyResolver();

    @Test
    void pngAutoImportsAtItsNativeSizeWhateverTheDimensions() {
        for (int[] size : new int[][]{{16, 16}, {64, 48}, {37, 5}, {1, 1}}) {
            ImportAction action = resolver.resolve("/art/tex.png", size[0], size[1]);

            assertTrue(action.isAutoImport());
            assertEquals(size[0], action.getTargetSize().getWidth());
            assertEquals(size[1], action.getTargetSize().getHeight());
            assertNull(action.getRejectReason());
        }
    }

    @Test
    void extensionMatchingIgnoresCase() {
        assertTrue(resolver.resolve("C:/Art/TEX.PNG", 8, 8).isAutoImport());
        assertTrue(resolver.resolve("project.Omt", 0, 0).isShowDialog());
    }

    @Test
    void omtAlwaysShowsTheLayerDialog() {
        ImportAction action = resolver.resolve("layers.omt", 16, 16);

        assertTrue(action.isShowDialog());
        assertFalse(action.isAutoImport());
        assertNull(action.getTargetSize());
    }

    @Test
    void otherFilesAreRejectedWithTheExtensionInTheReason() {
        ImportAction jpg = resolver.resolve("photo.jpg", 16, 16);
        assertTrue(jpg.isReject());
        assertEquals("Unsupported file type: jpg", jpg.getRejectReason());

        assertTrue(resolver.resolve("no_extension", 16, 16).isReject());
        assertTrue(resolver.resolve("trailing.", 16, 16).isReject());
        assertTrue(resolver.resolve("archive.png.zip", 16, 16).isReject(), "only the last extension counts");
    }

    @Test
    void actionFactoriesCarryOnlyTheirOwnData() {
        ImportAction dialog = ImportAction.showDialog(30, 20);
        assertEquals(ImportAction.Type.SHOW_DIALOG, dialog.getType());
        assertEquals(30, dialog.getSourceWidth());
        assertEquals(20, dialog.getSourceHeight());

        ImportAction reject = ImportAction.reject("nope");
        assertEquals(ImportAction.Type.REJECT, reject.getType());
        assertFalse(reject.isAutoImport());
        assertFalse(reject.isShowDialog());
        assertNull(reject.getTargetSize());
    }
}

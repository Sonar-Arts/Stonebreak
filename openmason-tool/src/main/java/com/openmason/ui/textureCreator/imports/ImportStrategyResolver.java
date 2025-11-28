package com.openmason.ui.textureCreator.imports;

import com.openmason.ui.textureCreator.TextureCreatorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the appropriate import strategy for files.
 */
public class ImportStrategyResolver {
    private static final Logger logger = LoggerFactory.getLogger(ImportStrategyResolver.class);

    /**
     * Resolve import action for a PNG file.
     */
    public ImportAction resolveForPNG(String filePath, int width, int height) {
        logger.debug("Resolving import strategy for PNG: {} ({}x{})", filePath, width, height);

        // Check for exact match - auto-import without dialog
        if (width == 16 && height == 16) {
            logger.debug("Exact match detected: 16x16 -> auto-import");
            return ImportAction.autoImport(TextureCreatorState.CanvasSize.SIZE_16x16);
        }

        if (width == 64 && height == 48) {
            logger.debug("Exact match detected: 64x48 -> auto-import");
            return ImportAction.autoImport(TextureCreatorState.CanvasSize.SIZE_64x48);
        }

        // Non-standard size - show dialog
        logger.debug("Non-standard size detected: {}x{} -> show dialog", width, height);
        return ImportAction.showDialog(width, height);
    }

    /**
     * Resolve import action for an .OMT file.
     */
    public ImportAction resolveForOMT(String filePath) {
        logger.debug("Resolving import strategy for OMT: {} -> show dialog", filePath);
        // OMT files always show dialog (user chooses flatten vs import all layers)
        return ImportAction.showDialog(0, 0);
    }

    /**
     * Resolve import action based on file extension.
     */
    public ImportAction resolve(String filePath, int width, int height) {
        String extension = getFileExtension(filePath).toLowerCase();

        return switch (extension) {
            case "png" -> resolveForPNG(filePath, width, height);
            case "omt" -> resolveForOMT(filePath);
            default -> {
                logger.warn("Unsupported file type: {}", extension);
                yield ImportAction.reject("Unsupported file type: " + extension);
            }
        };
    }

    /**
     * Get file extension from path.
     */
    private String getFileExtension(String filePath) {
        int lastDot = filePath.lastIndexOf('.');
        if (lastDot == -1 || lastDot == filePath.length() - 1) {
            return "";
        }
        return filePath.substring(lastDot + 1);
    }
}

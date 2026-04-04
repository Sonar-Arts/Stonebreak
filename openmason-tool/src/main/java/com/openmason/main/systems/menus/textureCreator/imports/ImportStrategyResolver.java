package com.openmason.main.systems.menus.textureCreator.imports;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorState;
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

        // Auto-import PNGs at their native resolution
        logger.debug("Auto-importing PNG at native size: {}x{}", width, height);
        return ImportAction.autoImport(new TextureCreatorState.CanvasSize(width, height));
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

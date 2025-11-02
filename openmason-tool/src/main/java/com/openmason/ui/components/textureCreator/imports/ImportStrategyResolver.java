package com.openmason.ui.components.textureCreator.imports;

import com.openmason.ui.components.textureCreator.TextureCreatorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the appropriate import strategy for files.
 * Follows Strategy pattern to determine auto-import vs dialog for different file types.
 *
 * Strategy:
 * - PNG files with exact match dimensions (16x16 or 64x48): auto-import
 * - PNG files with non-standard dimensions: show dialog
 * - .OMT files: always show dialog
 * - Other files: reject
 *
 * @author Open Mason Team
 */
public class ImportStrategyResolver {
    private static final Logger logger = LoggerFactory.getLogger(ImportStrategyResolver.class);

    /**
     * Resolve import action for a PNG file.
     *
     * @param filePath path to the PNG file
     * @param width detected PNG width
     * @param height detected PNG height
     * @return import action to take
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
     * Always shows dialog to let user choose between flatten and import all layers.
     *
     * @param filePath path to the .OMT file
     * @return import action to take
     */
    public ImportAction resolveForOMT(String filePath) {
        logger.debug("Resolving import strategy for OMT: {} -> show dialog", filePath);
        // OMT files always show dialog (user chooses flatten vs import all layers)
        return ImportAction.showDialog(0, 0);
    }

    /**
     * Resolve import action based on file extension.
     * Convenience method that delegates to specific resolvers.
     *
     * @param filePath path to the file
     * @param width detected width (for PNG files, 0 for others)
     * @param height detected height (for PNG files, 0 for others)
     * @return import action to take
     */
    public ImportAction resolve(String filePath, int width, int height) {
        String extension = getFileExtension(filePath).toLowerCase();

        switch (extension) {
            case "png":
                return resolveForPNG(filePath, width, height);
            case "omt":
                return resolveForOMT(filePath);
            default:
                logger.warn("Unsupported file type: {}", extension);
                return ImportAction.reject("Unsupported file type: " + extension);
        }
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

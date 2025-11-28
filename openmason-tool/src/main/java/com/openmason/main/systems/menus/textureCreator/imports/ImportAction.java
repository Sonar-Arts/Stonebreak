package com.openmason.main.systems.menus.textureCreator.imports;

import com.openmason.main.systems.menus.textureCreator.TextureCreatorState;

/**
 * Represents the action to take when importing a file.
 */
public class ImportAction {
    public enum Type {
        /** Automatically import without showing dialog */
        AUTO_IMPORT,
        /** Show dialog for user to choose import options */
        SHOW_DIALOG,
        /** Reject the import (unsupported format or invalid file) */
        REJECT
    }

    private final Type type;
    private final TextureCreatorState.CanvasSize targetSize;
    private final int sourceWidth;
    private final int sourceHeight;
    private final String rejectReason;

    private ImportAction(Type type, TextureCreatorState.CanvasSize targetSize,
                         int sourceWidth, int sourceHeight, String rejectReason) {
        this.type = type;
        this.targetSize = targetSize;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.rejectReason = rejectReason;
    }

    /**
     * Create an auto-import action.
     *
     * @param targetSize the canvas size to import to
     * @return import action for automatic import
     */
    public static ImportAction autoImport(TextureCreatorState.CanvasSize targetSize) {
        return new ImportAction(Type.AUTO_IMPORT, targetSize, 0, 0, null);
    }

    /**
     * Create a show-dialog action.
     *
     * @param sourceWidth width of the source image
     * @param sourceHeight height of the source image
     * @return import action to show dialog
     */
    public static ImportAction showDialog(int sourceWidth, int sourceHeight) {
        return new ImportAction(Type.SHOW_DIALOG, null, sourceWidth, sourceHeight, null);
    }

    /**
     * Create a reject action.
     *
     * @param reason human-readable reason for rejection
     * @return import action for rejection
     */
    public static ImportAction reject(String reason) {
        return new ImportAction(Type.REJECT, null, 0, 0, reason);
    }

    public Type getType() {
        return type;
    }

    public TextureCreatorState.CanvasSize getTargetSize() {
        return targetSize;
    }

    public int getSourceWidth() {
        return sourceWidth;
    }

    public int getSourceHeight() {
        return sourceHeight;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public boolean isAutoImport() {
        return type == Type.AUTO_IMPORT;
    }

    public boolean isShowDialog() {
        return type == Type.SHOW_DIALOG;
    }

    public boolean isReject() {
        return type == Type.REJECT;
    }
}

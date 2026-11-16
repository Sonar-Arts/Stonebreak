package com.stonebreak.openmason.editor;

import com.openmason.api.files.FileType;
import com.openmason.main.systems.io.WriteKind;

import java.util.List;

/**
 * Stonebreak's asset containers as editor file types (OpenMason#88: they were built into the editor
 * until then). SBO and SBE save into the game target's tree; the sub-folder is the target's folder key.
 */
public final class StonebreakFileTypes {

    public static final FileType SBO = FileType.of("sbo", "Stonebreak Object", ".sbo")
            .withSave("target", "sbo.block", "object");

    public static final FileType SBE = FileType.of("sbe", "Stonebreak Entity", ".sbe")
            .withSave("target", "sbe.mob", "entity");

    public static final FileType SBT = FileType.of("sbt", "Stonebreak Texture", ".sbt");

    /** Agent/dialog writes of an SBO. */
    public static final WriteKind SBO_KIND = WriteKind.of(SBO);

    /** Agent/dialog writes of an SBE. */
    public static final WriteKind SBE_KIND = WriteKind.of(SBE);

    private StonebreakFileTypes() {
    }

    /** The containers an asset path may name (the MCP asset tools' sandbox check). */
    public static List<FileType> assetContainers() {
        return List.of(SBO, SBE);
    }
}

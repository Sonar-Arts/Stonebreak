package com.openmason.main.systems.mcp.approval;

import com.openmason.main.systems.io.WriteKind;
import com.openmason.main.systems.io.WriteRoot;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * One "where should this go?" question for the in-app Save Sheet.
 *
 * @param kind                 what is being written (fixes the extension)
 * @param title                short human title ("Save .sbo — requested by Claude")
 * @param agentLabel           who is asking ("MCP client", "Assistant")
 * @param suggestedRoot        root pre-selected in the sheet (null = kind default)
 * @param suggestedSubdir      sub-folder pre-filled under that root ("" = root)
 * @param suggestedName        file name pre-filled (with or without extension)
 * @param detailLines          context lines shown under the title
 * @param overwriteTarget      when the agent named an existing file, that file
 * @param requiresPriorOmoSave true when the write is an SBO/SBE export of an
 *                             unsaved model and the model will be saved first
 * @param timeout              how long the sheet may stay unanswered
 */
public record SaveSheetRequest(WriteKind kind, String title, String agentLabel,
                               WriteRoot suggestedRoot, String suggestedSubdir,
                               String suggestedName, List<String> detailLines,
                               Path overwriteTarget, boolean requiresPriorOmoSave,
                               Duration timeout) {

    public SaveSheetRequest {
        detailLines = detailLines == null ? List.of() : List.copyOf(detailLines);
        suggestedSubdir = suggestedSubdir == null ? "" : suggestedSubdir;
        suggestedName = suggestedName == null ? "" : suggestedName;
        agentLabel = agentLabel == null ? "an agent" : agentLabel;
        timeout = timeout == null ? Duration.ofMinutes(3) : timeout;
    }

    @Override
    public String toString() {
        return title + " [" + kind.id() + "]";
    }
}

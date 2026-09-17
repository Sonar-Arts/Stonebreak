package com.openmason.main.systems.mcp.approval;

/**
 * Single-slot gate behind the in-app Save Sheet ({@code SaveSheetDialog}):
 * an agent needs a file target and the human picks (or refuses) one.
 */
public final class SaveSheetGate extends PromptGate<SaveSheetRequest, SaveSheetResult> {

    public SaveSheetGate() {
        super("Save Sheet", SaveSheetRequest::timeout,
                SaveSheetResult.BUSY, SaveSheetResult.TIMEOUT, SaveSheetResult.DECLINED);
    }
}

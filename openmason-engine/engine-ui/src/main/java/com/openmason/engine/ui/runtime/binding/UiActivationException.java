package com.openmason.engine.ui.runtime.binding;

import com.openmason.engine.format.omui.UiDiagnostic;

import java.util.List;

/** A screen that must not activate on this host, with every reason (#289). */
public final class UiActivationException extends RuntimeException {

    private final transient List<UiDiagnostic> diagnostics;

    public UiActivationException(String documentId, List<UiDiagnostic> diagnostics) {
        super("UI document " + documentId + " cannot activate on this host: "
            + diagnostics.stream().filter(UiDiagnostic::isError).map(UiDiagnostic::toString).toList());
        this.diagnostics = List.copyOf(diagnostics);
    }

    /** Every finding, errors and warnings. */
    public List<UiDiagnostic> diagnostics() {
        return diagnostics;
    }
}

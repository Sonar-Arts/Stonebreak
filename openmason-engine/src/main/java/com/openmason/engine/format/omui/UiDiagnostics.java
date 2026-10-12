package com.openmason.engine.format.omui;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects {@link UiDiagnostic}s across one read/validate/export pass. Not thread-safe; one
 * instance per operation.
 */
public final class UiDiagnostics {

    private final List<UiDiagnostic> items = new ArrayList<>();

    public void add(UiDiagnostic diagnostic) {
        items.add(diagnostic);
    }

    public void error(UiDiagnostic.Code code, String entry, String pointer, String message) {
        add(new UiDiagnostic(UiDiagnostic.Severity.ERROR, code, entry, pointer, message));
    }

    public void warning(UiDiagnostic.Code code, String entry, String pointer, String message) {
        add(new UiDiagnostic(UiDiagnostic.Severity.WARNING, code, entry, pointer, message));
    }

    public void info(UiDiagnostic.Code code, String entry, String pointer, String message) {
        add(new UiDiagnostic(UiDiagnostic.Severity.INFO, code, entry, pointer, message));
    }

    public void addAll(List<UiDiagnostic> diagnostics) {
        items.addAll(diagnostics);
    }

    public boolean hasErrors() {
        for (UiDiagnostic d : items) {
            if (d.isError()) {
                return true;
            }
        }
        return false;
    }

    public List<UiDiagnostic> list() {
        return List.copyOf(items);
    }

    /** @throws UiFormatException carrying every diagnostic collected so far, if any is an error */
    public void throwIfErrors(String what) throws UiFormatException {
        if (hasErrors()) {
            throw new UiFormatException(what, list());
        }
    }
}

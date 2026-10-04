package com.openmason.engine.format.omui;

import java.io.IOException;
import java.io.Serial;
import java.util.List;

/**
 * A UI archive could not be read, upgraded, written or exported. Carries every structured
 * {@link UiDiagnostic} found, not just the first, so an editor can list them all.
 */
public class UiFormatException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<UiDiagnostic> diagnostics;

    public UiFormatException(String what, List<UiDiagnostic> diagnostics) {
        super(summarize(what, diagnostics));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<UiDiagnostic> diagnostics() {
        return diagnostics;
    }

    /** @return true if any error carries {@code code} */
    public boolean has(UiDiagnostic.Code code) {
        for (UiDiagnostic d : diagnostics) {
            if (d.isError() && d.code() == code) {
                return true;
            }
        }
        return false;
    }

    private static String summarize(String what, List<UiDiagnostic> diagnostics) {
        StringBuilder sb = new StringBuilder(what);
        int shown = 0;
        for (UiDiagnostic d : diagnostics) {
            if (!d.isError()) {
                continue;
            }
            sb.append(shown == 0 ? ": " : "; ").append(d);
            if (++shown == 5) {
                sb.append("; ...");
                break;
            }
        }
        return sb.toString();
    }
}

package com.openmason.engine.ui.runtime;

import java.util.Objects;

/**
 * One finding from instantiating, styling or laying out a UI document at runtime (#287).
 * Format-level problems are {@code UiDiagnostic}s from the reader; these are about what a
 * valid document means when it runs: missing components, override targets that moved, a
 * variable that never resolves, a percentage that depends on its own size.
 *
 * @param element stable element key ({@code panel/resume/label}), or {@code ""} for the
 *                document as a whole
 */
public record UiRuntimeDiagnostic(Severity severity, Code code, String element, String message) {

    public enum Severity { ERROR, WARNING, INFO }

    /** Stable codes; tests and tools match on these, never on text. Append only. */
    public enum Code {
        // Widgets and properties
        UNKNOWN_WIDGET,
        UNSUPPORTED_WIDGET_VERSION,
        UNKNOWN_PROPERTY,
        PROPERTY_TYPE,
        CHILDREN_NOT_ALLOWED,
        // Components
        MISSING_COMPONENT,
        NOT_A_COMPONENT,
        RECURSIVE_COMPONENT,
        UNKNOWN_PARAM,
        PARAM_TYPE,
        UNKNOWN_SLOT,
        OVERRIDE_TARGET_MISSING,
        DUPLICATE_ELEMENT_KEY,
        // Styles
        MISSING_STYLESHEET,
        UNRESOLVED_VARIABLE,
        VARIABLE_CYCLE,
        STYLE_VALUE,
        UNKNOWN_STATE,
        // Layout
        CYCLIC_PERCENTAGE,
        CONFLICTING_CONSTRAINTS,
        // Property ownership
        BOUND_PROPERTY_WRITE,
        // Input and text (#288)
        EVENT_HANDLER_FAILED,
        NAV_TARGET_MISSING,
        MISSING_TEXT_KEY,
        INPUT_GATE_BLOCKED,
        // Bindings and host actions (#289)
        UNKNOWN_DATA_SOURCE,
        BINDING_TYPE,
        MISSING_CONVERTER,
        CONVERTER_FAILED,
        BINDING_SOURCE_FAILED,
        BINDING_NOT_WRITABLE,
        INVALID_EDIT,
        LIST_TEMPLATE,
        ACTION_FAILED,
        ACTION_RESULT_MISMATCH,
        ACTION_CALLBACK_FAILED,
        STALE_COMPLETION,
        // Lua code-behind (#292); details in the script runtime's own diagnostics
        SCRIPT_ERROR,
        // Animation (#295): a clip track whose target, property or key values do not fit
        ANIMATION_TRACK,
        // A state machine that cannot run (missing clip, unknown state)
        STATE_MACHINE
    }

    public UiRuntimeDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        element = element == null ? "" : element;
        Objects.requireNonNull(message, "message");
    }

    public static UiRuntimeDiagnostic error(Code code, String element, String message) {
        return new UiRuntimeDiagnostic(Severity.ERROR, code, element, message);
    }

    public static UiRuntimeDiagnostic warning(Code code, String element, String message) {
        return new UiRuntimeDiagnostic(Severity.WARNING, code, element, message);
    }

    @Override
    public String toString() {
        return severity + " " + code + (element.isEmpty() ? "" : " [" + element + "]") + ": " + message;
    }
}

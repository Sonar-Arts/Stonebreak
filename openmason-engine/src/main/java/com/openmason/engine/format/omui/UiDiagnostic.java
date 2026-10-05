package com.openmason.engine.format.omui;

import java.util.Objects;

/**
 * One structured finding from reading, validating, upgrading or exporting a UI archive.
 *
 * @param severity {@link Severity#ERROR} blocks the operation; the others are advisory
 * @param code     stable machine-readable code (tests and tools match on it, never on text)
 * @param entry    archive entry the finding is about ({@code document.json}), or {@code ""}
 *                 for the archive as a whole
 * @param pointer  RFC 6901 JSON pointer inside {@code entry} ({@code /root/children/2/id}),
 *                 or {@code ""}
 * @param message  human-readable detail
 */
public record UiDiagnostic(Severity severity, Code code, String entry, String pointer, String message) {

    public enum Severity { ERROR, WARNING, INFO }

    /** Stable diagnostic codes. Append only; never renumber or rename. */
    public enum Code {
        // Archive container
        NOT_AN_ARCHIVE,
        UNSAFE_ENTRY_PATH,
        DUPLICATE_ENTRY,
        LIMIT_EXCEEDED,
        TRUNCATED_ARCHIVE,
        MISSING_ENTRY,
        UNKNOWN_ENTRY,
        // JSON
        MALFORMED_JSON,
        DUPLICATE_KEY,
        WRONG_TYPE,
        MISSING_FIELD,
        INVALID_VALUE,
        UNKNOWN_ENUM,
        NUMBER_PRECISION,
        UNKNOWN_FIELD_PRESERVED,
        // Versions and features
        UNSUPPORTED_SCHEMA_VERSION,
        NEWER_MINOR_VERSION,
        NEEDS_UPGRADE,
        UNSUPPORTED_REQUIRED_FEATURE,
        UNSUPPORTED_HOST_API,
        UNSUPPORTED_UI_API,
        UNSUPPORTED_LAYOUT_SEMANTICS,
        UNSUPPORTED_WIDGET_VERSION,
        // Identity and references
        INVALID_ID,
        DUPLICATE_ID,
        UNRESOLVED_REFERENCE,
        DEPENDENCY_CYCLE,
        RECURSIVE_COMPONENT,
        ORPHAN_ENTRY,
        // Content
        HASH_MISMATCH,
        BINARY_SCRIPT,
        INVALID_TEXT,
        STALE_DERIVED,
        INCONSISTENT_MANIFEST,
        // Asset resolution, embedding and export planning (#285)
        ASSET_EMBEDDED,
        ASSET_REFRESHED,
        ASSET_EXTRACTED,
        ASSET_RELINKED,
        ASSET_SHADOWED,
        ENTRY_RENAMED,
        ID_REMAPPED,
        UNUSED_DEPENDENCY,
        // Features (#287)
        UNDECLARED_FEATURE,
        // Host activation (#289)
        UNKNOWN_DATA_SOURCE,
        UNDECLARED_HOST_API
    }

    public UiDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        entry = entry == null ? "" : entry;
        pointer = pointer == null ? "" : pointer;
        message = message == null ? "" : message;
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        String where = entry.isEmpty() ? "" : " " + entry + (pointer.isEmpty() ? "" : "#" + pointer);
        return severity + " " + code + where + ": " + message;
    }
}

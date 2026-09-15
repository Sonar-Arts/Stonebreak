package com.openmason.main.systems.assistant.tools;

/** Risk category of a tool, driving the assistant's approval policy. */
public enum ToolCategory {
    /** Pure inspection — no model/canvas/filesystem mutation. */
    READ_ONLY,
    /** Mutates the model/canvas/library but is undoable or tool-owned. */
    MUTATING,
    /** Replaces the user's working set or otherwise needs a human decision. */
    REQUIRES_AUTH
}

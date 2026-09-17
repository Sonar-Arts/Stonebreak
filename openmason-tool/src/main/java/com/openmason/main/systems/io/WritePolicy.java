package com.openmason.main.systems.io;

import java.util.Locale;

/**
 * When an agent-initiated write shows the in-app Save Sheet instead of just
 * happening. Applies identically to the HTTP MCP server and the in-tool
 * assistant. Writes outside every {@link WriteRoot} are refused regardless.
 */
public enum WritePolicy {
    /** Every write asks. */
    ASK_ALWAYS("Ask for every write"),
    /**
     * New files inside the project or exports root write silently (with a
     * status toast); overwrites and anything under game resources ask.
     */
    ASK_RISKY("Ask for overwrites and game resources"),
    /**
     * Like ASK_RISKY, but an overwrite inside the project/exports root is
     * allowed silently when the tool call carries {@code overwrite:true}.
     * Game resources still ask.
     */
    ASK_NEVER_IN_PROJECT("Only ask for game resources");

    private final String label;

    WritePolicy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static WritePolicy fromString(String s, WritePolicy fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static String[] labels() {
        WritePolicy[] v = values();
        String[] out = new String[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i].label;
        }
        return out;
    }
}

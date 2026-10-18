package com.openmason.main.systems.io;

import java.util.Locale;

/**
 * The folders an agent-initiated write may land in. Everything an MCP client
 * or the in-tool assistant writes is confined to one of these; anything else
 * is refused before a dialog is even considered.
 */
public enum WriteRoot {
    /** The folder the open {@code .omp} project lives in (or the default projects dir). */
    PROJECT("project"),
    /**
     * The active game target's resource tree (#380) — the shipped-asset tree. {@code game} (and
     * the enum's old name {@code game_resources}) are the pre-#380 spellings and still parse.
     */
    TARGET("target", "game", "game_resources"),
    /** {@code ~/.openmason/exports} — the tool-owned scratch jail. */
    EXPORTS("exports");

    private final String prefix;
    private final String[] aliases;

    WriteRoot(String prefix, String... aliases) {
        this.prefix = prefix;
        this.aliases = aliases;
    }

    /** The {@code <prefix>:relative/path} spelling accepted by {@link WriteSandbox}. */
    public String prefix() {
        return prefix;
    }

    /** Parse a prefix or alias (case-insensitive); null when unknown. */
    public static WriteRoot fromPrefix(String s) {
        if (s == null) {
            return null;
        }
        String key = s.trim().toLowerCase(Locale.ROOT);
        for (WriteRoot r : values()) {
            if (r.prefix.equals(key) || r.name().toLowerCase(Locale.ROOT).equals(key)) {
                return r;
            }
            for (String alias : r.aliases) {
                if (alias.equals(key)) {
                    return r;
                }
            }
        }
        return null;
    }
}

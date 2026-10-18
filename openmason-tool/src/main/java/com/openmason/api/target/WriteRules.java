package com.openmason.api.target;

/**
 * How the editor may write into a {@link GameTarget}'s resource tree (#380).
 *
 * @param writable      the tree is a {@code target:} write root at all (else it is read-only)
 * @param confirmWrites writes into it ask the user first under the default write policy
 */
public record WriteRules(boolean writable, boolean confirmWrites) {

    /** A game's shipped assets: writable, every write confirmed. */
    public static final WriteRules SHIPPED_ASSETS = new WriteRules(true, true);

    /** Read-only: the target offers no write root. */
    public static final WriteRules READ_ONLY = new WriteRules(false, false);
}

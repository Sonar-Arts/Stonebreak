package com.stonebreak.network;

/**
 * The one username rule (issue #335): 1 to {@value #MAX_LENGTH} ASCII letters, digits,
 * underscores, dots or hyphens (the last two because the old save-name sanitizer kept them, so
 * players saved as {@code Mr.Bob} can still reach their save). The server refuses any other name at handshake, the join screen checks it before
 * connecting, and the host's own name is coerced into it (never refused: that would end its own
 * world). No spaces, brackets or control characters, so a name can never pass for
 * {@code [SERVER]} or break a chat line or a name tag.
 */
public final class PlayerNames {

    public static final int MAX_LENGTH = 16;
    /** The name a blank or wholly invalid one becomes. */
    public static final String DEFAULT = "Player";
    /** What the join screen and the server's refusal say. */
    public static final String RULE =
            "Names are 1-" + MAX_LENGTH + " letters, digits, _ . or -";

    private PlayerNames() {
    }

    public static boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (!isAllowed(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The host's name as the rule allows it: invalid characters dropped, cut to
     * {@value #MAX_LENGTH}, {@link #DEFAULT} when nothing is left.
     */
    public static String forLocalHost(String name) {
        StringBuilder out = new StringBuilder();
        if (name != null) {
            for (int i = 0; i < name.length() && out.length() < MAX_LENGTH; i++) {
                char c = name.charAt(i);
                if (isAllowed(c)) {
                    out.append(c);
                }
            }
        }
        return out.isEmpty() ? DEFAULT : out.toString();
    }

    private static boolean isAllowed(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '.' || c == '-';
    }
}

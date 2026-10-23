package com.stonebreak.network;

/**
 * The one username rule (issue #335): 1 to {@value #MAX_LENGTH} ASCII letters, digits,
 * underscores, dots, hyphens and single spaces between words. The server refuses any other name
 * at handshake, the join screen checks it before connecting, and the host's own name is coerced
 * into it (never refused: that would end its own world). No brackets, other punctuation or
 * control characters, so a name can never pass for {@code [SERVER]} or break a chat line or a
 * name tag; no leading, trailing or doubled spaces, so two names never differ only in spacing.
 *
 * <p>The rule covers what players already have: the old join screen allowed up to 24 characters,
 * and the old save-name sanitizer kept {@code .} and {@code -}, so {@code Mr.Bob} and
 * {@code Bob Smith} still reach their saves.
 */
public final class PlayerNames {

    /** The old join screen's limit, so every name typed there still fits. */
    public static final int MAX_LENGTH = 24;
    /** The name a blank or wholly invalid one becomes. */
    public static final String DEFAULT = "Player";
    /** What the join screen and the server's refusal say. */
    public static final String RULE =
            "Names are 1-" + MAX_LENGTH + " letters, digits, spaces, _ . or -";

    private PlayerNames() {
    }

    public static boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH
                || name.charAt(0) == ' ' || name.charAt(name.length() - 1) == ' '
                || name.contains("  ")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != ' ' && !isAllowed(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The host's name as the rule allows it: invalid characters dropped, runs of spaces
     * collapsed, trimmed, cut to {@value #MAX_LENGTH}, {@link #DEFAULT} when nothing is left.
     */
    public static String forLocalHost(String name) {
        StringBuilder out = new StringBuilder();
        if (name != null) {
            for (int i = 0; i < name.length() && out.length() < MAX_LENGTH; i++) {
                char c = name.charAt(i);
                if (isAllowed(c)) {
                    out.append(c);
                } else if (c == ' ' && !out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
            }
        }
        String coerced = out.toString().strip();
        return coerced.isEmpty() ? DEFAULT : coerced;
    }

    private static boolean isAllowed(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '.' || c == '-';
    }
}

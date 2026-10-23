package com.stonebreak.world.save.repository;

/**
 * File names for remote players' saves, {@code players/<stem>.json} (issue #335). The stem is
 * {@code '@'} followed by a reversible encoding of the username that is collision-free and
 * case-safe: it only ever emits lowercase letters, digits, {@code '-'}, {@code '_'} and
 * {@code '.'}, so two names never share a file even on a case-insensitive file system.
 * <ul>
 *   <li>{@code a-z 0-9 -} stand for themselves;</li>
 *   <li>an uppercase letter is {@code '_'} + the letter in lowercase ({@code Bob} → {@code _bob});</li>
 *   <li>{@code '_'} is {@code "__"};</li>
 *   <li>any other UTF-16 unit is {@code "_."} + four lowercase hex digits.</li>
 * </ul>
 * The {@code '@'} prefix keeps these apart from the old lossy names ({@code [A-Za-z0-9_.-]},
 * the rest replaced by {@code '_'}), which never contain it; a player with no new-style file is
 * still read from the old one ({@link #legacyStem}).
 */
public final class PlayerSaveNames {

    private static final char PREFIX = '@';
    private static final char ESCAPE = '_';
    private static final char UNIT = '.';

    private PlayerSaveNames() {
    }

    /** {@code players/<stem>.json} stem for {@code username}. */
    public static String stem(String username) {
        return PREFIX + encode(username == null ? "" : username);
    }

    public static String encode(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
                out.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                out.append(ESCAPE).append((char) (c - 'A' + 'a'));
            } else if (c == ESCAPE) {
                out.append(ESCAPE).append(ESCAPE);
            } else {
                out.append(ESCAPE).append(UNIT).append(String.format("%04x", (int) c));
            }
        }
        return out.toString();
    }

    /** Inverse of {@link #encode}; throws {@link IllegalArgumentException} on a malformed input. */
    public static String decode(String encoded) {
        StringBuilder out = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c != ESCAPE) {
                out.append(c);
                continue;
            }
            if (++i >= encoded.length()) {
                throw new IllegalArgumentException("dangling escape in '" + encoded + "'");
            }
            char next = encoded.charAt(i);
            if (next >= 'a' && next <= 'z') {
                out.append((char) (next - 'a' + 'A'));
            } else if (next == ESCAPE) {
                out.append(ESCAPE);
            } else if (next == UNIT && i + 4 < encoded.length()) {
                out.append((char) Integer.parseInt(encoded.substring(i + 1, i + 5), 16));
                i += 4;
            } else {
                throw new IllegalArgumentException("bad escape in '" + encoded + "'");
            }
        }
        return out.toString();
    }

    /** The stem the save used before #335 — read only, as a fallback for players saved then. */
    public static String legacyStem(String username) {
        if (username == null || username.isBlank()) {
            return "_anonymous";
        }
        String cleaned = username.replaceAll("[^A-Za-z0-9_.-]", "_");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}

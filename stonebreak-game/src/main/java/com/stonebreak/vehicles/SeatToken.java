package com.stonebreak.vehicles;

/**
 * Wire form of who sits in a replicated seat: {@code "-"} empty, {@code "p<playerId>"} a player,
 * {@code "e<networkId>"} a replicated entity (a mob).
 */
public final class SeatToken {

    public static final String EMPTY = "-";
    public static final int NONE = -1;

    private SeatToken() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static String player(int playerId) {
        return "p" + playerId;
    }

    public static String entity(int networkId) {
        return "e" + networkId;
    }

    /** The player id in {@code token}, or {@link #NONE}. */
    public static int playerId(String token) {
        return idWithPrefix(token, 'p');
    }

    /** The entity network id in {@code token}, or {@link #NONE}. */
    public static int entityId(String token) {
        return idWithPrefix(token, 'e');
    }

    private static int idWithPrefix(String token, char prefix) {
        if (token == null || token.length() < 2 || token.charAt(0) != prefix) {
            return NONE;
        }
        try {
            return Integer.parseInt(token.substring(1));
        } catch (NumberFormatException e) {
            return NONE;
        }
    }
}

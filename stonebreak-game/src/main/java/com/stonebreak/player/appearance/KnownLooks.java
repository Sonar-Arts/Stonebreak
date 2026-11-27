package com.stonebreak.player.appearance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The looks this client knows (#459): every player's, by player id, as the server sent them ({@code PlayerJoinS2C},
 * {@code PlayerAppearanceS2C}) - the local player's own included. Bodies (remote players, decoys, the local player)
 * read it and re-dress when the entry they wear changes ({@link #revision}). Cleared when the session ends.
 */
public final class KnownLooks {

    /** A known look and the change counter it was stored under. */
    public record Entry(PlayerAppearance look, int revision) {}

    private static final Map<Integer, Entry> LOOKS = new ConcurrentHashMap<>();
    private static final AtomicInteger REVISION = new AtomicInteger();

    private KnownLooks() {
    }

    /** Stores {@code look} for {@code playerId} (null = forget it). */
    public static void put(int playerId, PlayerAppearance look) {
        if (look == null) {
            LOOKS.remove(playerId);
        } else {
            LOOKS.put(playerId, new Entry(look, REVISION.incrementAndGet()));
        }
    }

    /** Stores the binary record {@code bytes} for {@code playerId}; an empty or unreadable one is ignored. */
    public static PlayerAppearance putBinary(int playerId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            PlayerAppearance look = PlayerAppearance.read(bytes);
            put(playerId, look);
            return look;
        } catch (RuntimeException e) {
            System.err.println("[LOOK] Ignoring an unreadable look for player " + playerId + ": " + e.getMessage());
            return null;
        }
    }

    /** The look of {@code playerId}, or null when none is known. */
    public static Entry get(int playerId) {
        return LOOKS.get(playerId);
    }

    public static void forget(int playerId) {
        LOOKS.remove(playerId);
    }

    /** Forgets every look (leaving a session). */
    public static void clear() {
        LOOKS.clear();
    }
}

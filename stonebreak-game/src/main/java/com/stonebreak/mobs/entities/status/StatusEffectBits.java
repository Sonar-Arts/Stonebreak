package com.stonebreak.mobs.entities.status;

/**
 * The one wire mapping for {@link StatusEffectType}: an effect's id on the network is its
 * enum ordinal, and a set of active effects travels as a bitset with bit {@code id} set.
 * Every packet that names an effect (the client's status intent, the server's replicated
 * status) goes through here, so the id space has a single owner.
 *
 * <p>The bitset is sent as an unsigned 16-bit field; {@link #WIRE_BITS} is that width. Adding
 * a 17th effect type needs a wider field (and a protocol bump) — a test pins the count.
 */
public final class StatusEffectBits {

    /** Width of the replicated bitset on the wire. */
    public static final int WIRE_BITS = 16;

    private static final StatusEffectType[] TYPES = StatusEffectType.values();

    private StatusEffectBits() {}

    /** Network id of {@code type}. */
    public static int wireId(StatusEffectType type) {
        return type.ordinal();
    }

    /** The effect with network id {@code id}, or null for an unknown id. */
    public static StatusEffectType fromWireId(int id) {
        return id >= 0 && id < TYPES.length ? TYPES[id] : null;
    }

    /** The bit {@code type} occupies in a replicated set. */
    public static int bit(StatusEffectType type) {
        return 1 << wireId(type);
    }

    /** True when {@code bits} has {@code type} set. */
    public static boolean has(int bits, StatusEffectType type) {
        return (bits & bit(type)) != 0;
    }

    /** Drops bits no known effect occupies (hostile/garbled input). */
    public static int sanitize(int bits) {
        return bits & ((1 << TYPES.length) - 1);
    }
}

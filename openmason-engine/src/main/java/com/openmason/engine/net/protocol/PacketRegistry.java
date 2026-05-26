package com.openmason.engine.net.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * Bidirectional id ↔ codec registry, partitioned by ({@link ProtocolPhase},
 * {@link PacketDirection}).
 *
 * <ul>
 *   <li><b>Decode:</b> look up a codec by ({@code phase}, {@code direction}, {@code id}).</li>
 *   <li><b>Encode:</b> look up a packet's id and codec by its concrete class.</li>
 * </ul>
 *
 * <p>Ids are explicit integer constants (written as varints on the wire), leaving gaps
 * for future / dedicated-server-only packets. The registry is populated once at startup
 * (the game module registers all concrete packets) and read-only thereafter, so lookups
 * are thread-safe without synchronization.
 */
public final class PacketRegistry {

    private record Slot(ProtocolPhase phase, PacketDirection direction) {}

    private static final class Bucket {
        final Map<Integer, PacketCodec<?>> codecById = new HashMap<>();
        final Map<Class<?>, Integer> idByClass = new HashMap<>();
        final Map<Class<?>, PacketCodec<?>> codecByClass = new HashMap<>();
    }

    private final Map<Slot, Bucket> buckets = new HashMap<>();

    /**
     * Register a packet type under a phase/direction with an explicit wire id.
     *
     * @throws IllegalArgumentException if the id or type is already registered in that slot
     */
    public <T extends Packet> PacketRegistry register(
            ProtocolPhase phase, PacketDirection direction,
            int id, Class<T> type, PacketCodec<T> codec) {
        Bucket b = buckets.computeIfAbsent(new Slot(phase, direction), k -> new Bucket());
        if (b.codecById.putIfAbsent(id, codec) != null) {
            throw new IllegalArgumentException(
                "Duplicate packet id " + id + " for " + phase + "/" + direction);
        }
        if (b.idByClass.putIfAbsent(type, id) != null) {
            throw new IllegalArgumentException(
                "Duplicate packet type " + type.getName() + " for " + phase + "/" + direction);
        }
        b.codecByClass.put(type, codec);
        return this;
    }

    /** Codec for an inbound packet id, or {@code null} if unregistered in that slot. */
    public PacketCodec<?> codecForId(ProtocolPhase phase, PacketDirection direction, int id) {
        Bucket b = buckets.get(new Slot(phase, direction));
        return b == null ? null : b.codecById.get(id);
    }

    /** Wire id for an outbound packet's class. */
    public int idForClass(ProtocolPhase phase, PacketDirection direction, Class<?> type) {
        Bucket b = buckets.get(new Slot(phase, direction));
        Integer id = (b == null) ? null : b.idByClass.get(type);
        if (id == null) {
            throw new IllegalArgumentException(
                "Unregistered packet " + type.getName() + " for " + phase + "/" + direction);
        }
        return id;
    }

    /** Codec for an outbound packet's class. */
    @SuppressWarnings("unchecked")
    public <T extends Packet> PacketCodec<T> codecForClass(
            ProtocolPhase phase, PacketDirection direction, Class<?> type) {
        Bucket b = buckets.get(new Slot(phase, direction));
        PacketCodec<?> codec = (b == null) ? null : b.codecByClass.get(type);
        if (codec == null) {
            throw new IllegalArgumentException(
                "Unregistered packet " + type.getName() + " for " + phase + "/" + direction);
        }
        return (PacketCodec<T>) codec;
    }
}

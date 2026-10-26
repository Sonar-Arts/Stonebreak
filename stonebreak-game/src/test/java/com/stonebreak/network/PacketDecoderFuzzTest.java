package com.stonebreak.network;

import com.openmason.engine.net.pipeline.PacketDecoder;
import com.openmason.engine.net.pipeline.PipelineAttributes;
import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import com.openmason.engine.net.protocol.PacketDirection;
import com.openmason.engine.net.protocol.PacketRegistry;
import com.openmason.engine.net.protocol.ProtocolPhase;
import com.openmason.engine.net.server.ServerLimits;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.openmason.engine.net.protocol.PacketDirection.CLIENTBOUND;
import static com.openmason.engine.net.protocol.PacketDirection.SERVERBOUND;
import static com.openmason.engine.net.protocol.ProtocolPhase.PLAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Issue #347: hostile bytes against every registered packet, through the inbound chain a server
 * socket has (length framing, then the packet decoder). Random frames, truncated and padded
 * encodings of a real packet, and oversized frames must each be rejected cleanly — a
 * {@link DecoderException} the pipeline turns into a closed connection — or decode to a packet;
 * never hang, throw an {@link Error}, or allocate more than a frame could justify (a codec that
 * trusts a length field from the wire).
 */
@Tag("regression")
class PacketDecoderFuzzTest {

    private static final PacketRegistry REGISTRY = StonebreakProtocol.registry();
    /** A server's inbound frame cap. */
    private static final int MAX_FRAME = ServerLimits.DEFAULTS.maxInboundFrameBytes();
    /** No frame a fuzzer sends justifies allocating more than this while decoding it. */
    private static final long MAX_ALLOCATION_PER_FRAME = 32L << 20;
    private static final int RANDOM_FRAMES_PER_PACKET = 400;

    /**
     * {@code com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes}, reached reflectively:
     * the game's test module does not read {@code jdk.management}, but the platform bean is it.
     */
    private static final java.lang.reflect.Method ALLOCATED_BYTES = allocatedBytesMethod();

    private static java.lang.reflect.Method allocatedBytesMethod() {
        try {
            return Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getCurrentThreadAllocatedBytes");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the allocation check needs HotSpot's thread allocation counter", e);
        }
    }

    private static long allocatedBytes() {
        try {
            return (long) ALLOCATED_BYTES.invoke(ManagementFactory.getThreadMXBean());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ─── random frames ──────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void randomFramesAreRejectedOrDecodedCleanly() {
        Random rnd = new Random(347);
        for (PacketDirection dir : List.of(SERVERBOUND, CLIENTBOUND)) {
            for (Map.Entry<Integer, Class<?>> e : REGISTRY.registeredTypes(PLAY, dir).entrySet()) {
                for (int i = 0; i < RANDOM_FRAMES_PER_PACKET; i++) {
                    int len = i % 10 == 0 ? rnd.nextInt(4096) : rnd.nextInt(48);
                    byte[] body = new byte[len];
                    rnd.nextBytes(body);
                    if (i % 7 == 0) {
                        hostileLengths(body); // varints and ints that claim huge sizes
                    }
                    decodeSafely(dir, e.getValue(), withId(e.getKey(), body));
                }
            }
        }
    }

    @Test
    void unknownPacketIdsAreRejected() {
        for (PacketDirection dir : List.of(SERVERBOUND, CLIENTBOUND)) {
            Set<Integer> known = REGISTRY.registeredTypes(PLAY, dir).keySet();
            int unknown = known.stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
            assertThrows(DecoderException.class, () -> channel(dir).writeInbound(frame(withId(unknown, new byte[4]))));
            assertThrows(DecoderException.class, () -> channel(dir).writeInbound(frame(withId(-1, new byte[0]))));
        }
    }

    // ─── truncated and padded real packets ──────────────────────────────────────

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void truncatedPacketsAreRejectedOrDecodedCleanly() {
        int covered = 0;
        for (PacketDirection dir : List.of(SERVERBOUND, CLIENTBOUND)) {
            for (Map.Entry<Integer, Class<?>> e : REGISTRY.registeredTypes(PLAY, dir).entrySet()) {
                byte[] whole = encodedSample(dir, e.getValue());
                if (whole == null) {
                    continue;
                }
                covered++;
                for (int cut = 0; cut < whole.length; cut++) {
                    byte[] prefix = java.util.Arrays.copyOf(whole, cut);
                    decodeSafely(dir, e.getValue(), withId(e.getKey(), prefix));
                }
            }
        }
        assertSampleCoverage(covered);
    }

    @Test
    void trailingBytesAfterARealPacketAreRejected() {
        int covered = 0;
        List<String> accepted = new ArrayList<>();
        for (PacketDirection dir : List.of(SERVERBOUND, CLIENTBOUND)) {
            for (Map.Entry<Integer, Class<?>> e : REGISTRY.registeredTypes(PLAY, dir).entrySet()) {
                byte[] whole = encodedSample(dir, e.getValue());
                if (whole == null) {
                    continue;
                }
                covered++;
                for (int extra = 1; extra <= 8; extra++) {
                    byte[] padded = java.util.Arrays.copyOf(whole, whole.length + extra);
                    padded[padded.length - 1] = (byte) 0x5A;
                    try {
                        Packet p = decode(dir, withId(e.getKey(), padded));
                        if (p != null) {
                            accepted.add(e.getValue().getSimpleName() + "+" + extra);
                        }
                    } catch (DecoderException rejected) {
                        // expected
                    }
                }
            }
        }
        assertSampleCoverage(covered);
        assertTrue(accepted.isEmpty(), "padded frames decoded as if whole: " + accepted);
    }

    @Test
    void everySampleDecodesBackToItself() {
        int covered = 0;
        for (PacketDirection dir : List.of(SERVERBOUND, CLIENTBOUND)) {
            for (Map.Entry<Integer, Class<?>> e : REGISTRY.registeredTypes(PLAY, dir).entrySet()) {
                Packet sample = sample(e.getValue());
                byte[] whole = sample == null ? null : encode(dir, sample);
                if (whole == null) {
                    continue;
                }
                covered++;
                Packet back = decode(dir, withId(e.getKey(), whole));
                assertEquals(e.getValue(), back.getClass());
            }
        }
        assertSampleCoverage(covered);
    }

    // ─── oversized frames ───────────────────────────────────────────────────────

    @Test
    void aFrameOverTheServersCapIsRejectedWithoutBufferingIt() {
        EmbeddedChannel ch = channel(SERVERBOUND);
        ByteBuf huge = Unpooled.buffer();
        huge.writeInt(MAX_FRAME + 1);
        huge.writeZero(MAX_FRAME + 1);
        assertThrows(TooLongFrameException.class, () -> ch.writeInbound(huge));
        assertNull(ch.readInbound());
    }

    @Test
    void aLengthHeaderClaimingGigabytesIsRejectedAtOnce() {
        EmbeddedChannel ch = channel(SERVERBOUND);
        ByteBuf header = Unpooled.buffer();
        header.writeInt(Integer.MAX_VALUE);
        header.writeZero(16);
        long before = allocatedBytes();
        assertThrows(TooLongFrameException.class, () -> ch.writeInbound(header));
        assertTrue(allocatedBytes() - before < MAX_ALLOCATION_PER_FRAME,
                "the claimed size was never allocated");
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    /** The server-side inbound chain of a TCP channel: length framing, then the packet decoder. */
    private static EmbeddedChannel channel(PacketDirection inbound) {
        EmbeddedChannel ch = new EmbeddedChannel(
                new LengthFieldBasedFrameDecoder(MAX_FRAME, 0, 4, 0, 4),
                new PacketDecoder(REGISTRY, inbound));
        ch.attr(PipelineAttributes.PHASE).set(PLAY);
        return ch;
    }

    private static ByteBuf frame(byte[] payload) {
        ByteBuf buf = Unpooled.buffer(payload.length + 4);
        buf.writeInt(payload.length);
        buf.writeBytes(payload);
        return buf;
    }

    private static byte[] withId(int id, byte[] body) {
        ByteBuf buf = Unpooled.buffer();
        ByteBufIO.writeVarInt(buf, id);
        buf.writeBytes(body);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    /** One frame through the chain: the packet it decoded to, or a {@link DecoderException}. */
    private static Packet decode(PacketDirection dir, byte[] payload) {
        EmbeddedChannel ch = channel(dir);
        ch.writeInbound(frame(payload));
        Packet p = ch.readInbound();
        assertNull(ch.readInbound(), "one frame is at most one packet");
        return p;
    }

    /** Decodes, accepting a clean rejection; fails on any {@link Error} or an oversized allocation. */
    private static void decodeSafely(PacketDirection dir, Class<?> type, byte[] payload) {
        long before = allocatedBytes();
        try {
            decode(dir, payload);
        } catch (DecoderException rejected) {
            // the pipeline closes the connection
        } catch (Throwable t) {
            fail(type.getSimpleName() + " threw " + t + " for " + payload.length + " byte(s)", t);
        }
        long allocated = allocatedBytes() - before;
        if (allocated > MAX_ALLOCATION_PER_FRAME) {
            fail(type.getSimpleName() + " allocated " + allocated + " bytes for a "
                    + payload.length + "-byte frame");
        }
    }

    /** Overwrites a few spots with 0xFF runs: varint and int lengths claiming huge sizes. */
    private static void hostileLengths(byte[] body) {
        for (int at = 0; at + 5 <= body.length; at += 9) {
            body[at] = (byte) 0xFF;
            body[at + 1] = (byte) 0xFF;
            body[at + 2] = (byte) 0xFF;
            body[at + 3] = (byte) 0x7F;
            body[at + 4] = (byte) 0x07;
        }
    }

    private static void assertSampleCoverage(int covered) {
        int registered = REGISTRY.registeredTypes(PLAY, SERVERBOUND).size()
                + REGISTRY.registeredTypes(PLAY, CLIENTBOUND).size();
        assertTrue(covered * 10 >= registered * 9, "only " + covered + " of " + registered
                + " packets have a sample; missing: " + missingSamples());
    }

    private static List<String> missingSamples() {
        List<String> missing = new ArrayList<>();
        for (PacketDirection dir : List.of(SERVERBOUND, CLIENTBOUND)) {
            for (Class<?> type : REGISTRY.registeredTypes(PLAY, dir).values()) {
                if (encodedSample(dir, type) == null) {
                    missing.add(type.getSimpleName());
                }
            }
        }
        return missing;
    }

    // ─── samples: one real instance of every registered packet ──────────────────

    private static byte[] encodedSample(PacketDirection dir, Class<?> type) {
        Packet sample = sample(type);
        return sample == null ? null : encode(dir, sample);
    }

    private static byte[] encode(PacketDirection dir, Packet packet) {
        try {
            PacketCodec<Packet> codec = REGISTRY.codecForClass(PLAY, dir, packet.getClass());
            ByteBuf buf = Unpooled.buffer();
            codec.encode(buf, packet);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } catch (RuntimeException unencodable) {
            return null;
        }
    }

    /** A packet built from each record component's simplest legal value; null when none can be made. */
    private static Packet sample(Class<?> type) {
        Object value = simplest(type, 0);
        return value instanceof Packet p ? p : null;
    }

    private static Object simplest(Class<?> type, int depth) {
        if (depth > 6) {
            return null;
        }
        if (type == int.class || type == Integer.class) return 1;
        if (type == long.class || type == Long.class) return 1L;
        if (type == short.class || type == Short.class) return (short) 1;
        if (type == byte.class || type == Byte.class) return (byte) 1;
        if (type == float.class || type == Float.class) return 1f;
        if (type == double.class || type == Double.class) return 1d;
        if (type == boolean.class || type == Boolean.class) return true;
        if (type == char.class) return 'a';
        if (type == String.class) return "a";
        if (type.isArray()) return Array.newInstance(type.getComponentType(), 0);
        if (type.isEnum()) return type.getEnumConstants()[0];
        if (List.class.isAssignableFrom(type)) return List.of();
        if (Set.class.isAssignableFrom(type)) return Set.of();
        if (Map.class.isAssignableFrom(type)) return Map.of();
        if (type == Optional.class) return Optional.empty();
        try {
            if (type.isRecord()) {
                RecordComponent[] parts = type.getRecordComponents();
                Class<?>[] types = new Class<?>[parts.length];
                Object[] args = new Object[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    types[i] = parts[i].getType();
                    args[i] = simplest(types[i], depth + 1);
                }
                Constructor<?> canonical = type.getDeclaredConstructor(types);
                canonical.setAccessible(true);
                return canonical.newInstance(args);
            }
            Constructor<?> noArgs = type.getDeclaredConstructor();
            noArgs.setAccessible(true);
            return noArgs.newInstance();
        } catch (ReflectiveOperationException | RuntimeException noSample) {
            return null;
        }
    }
}

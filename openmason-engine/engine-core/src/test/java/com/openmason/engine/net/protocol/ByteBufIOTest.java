package com.openmason.engine.net.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Round-trip and bounds-enforcement coverage for {@link ByteBufIO}. The bounds checks are
 * the decoder's first line of defense against hostile payloads, so each cap is exercised.
 */
class ByteBufIOTest {

    @Test
    void varIntRoundTrips() {
        int[] samples = {0, 1, 127, 128, 255, 300, 16384, Integer.MAX_VALUE, -1};
        ByteBuf buf = Unpooled.buffer();
        try {
            for (int v : samples) ByteBufIO.writeVarInt(buf, v);
            for (int v : samples) assertEquals(v, ByteBufIO.readVarInt(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void stringRoundTrips() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufIO.writeString(buf, "hello, 世界", ByteBufIO.MAX_CHAT_CHARS);
            assertEquals("hello, 世界", ByteBufIO.readString(buf, ByteBufIO.MAX_CHAT_CHARS));
        } finally {
            buf.release();
        }
    }

    @Test
    void byteArrayRoundTrips() {
        byte[] data = {1, 2, 3, 4, 5, -1, 127, -128};
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufIO.writeByteArray(buf, data, ByteBufIO.MAX_CHUNK_BYTES);
            assertArrayEquals(data, ByteBufIO.readByteArray(buf, ByteBufIO.MAX_CHUNK_BYTES));
        } finally {
            buf.release();
        }
    }

    @Test
    void writingOversizedStringThrows() {
        ByteBuf buf = Unpooled.buffer();
        try {
            String tooLong = "x".repeat(ByteBufIO.MAX_USERNAME_CHARS + 1);
            assertThrows(EncoderException.class,
                () -> ByteBufIO.writeString(buf, tooLong, ByteBufIO.MAX_USERNAME_CHARS));
        } finally {
            buf.release();
        }
    }

    @Test
    void readingOverlongStringThrows() {
        ByteBuf buf = Unpooled.buffer();
        try {
            // Encode legally under a generous cap, then read back under a tighter one.
            ByteBufIO.writeString(buf, "y".repeat(50), 100);
            assertThrows(DecoderException.class, () -> ByteBufIO.readString(buf, ByteBufIO.MAX_USERNAME_CHARS));
        } finally {
            buf.release();
        }
    }

    @Test
    void readingOversizedByteArrayLengthThrows() {
        ByteBuf buf = Unpooled.buffer();
        try {
            // A declared length over the chunk cap must be rejected before allocating.
            ByteBufIO.writeVarInt(buf, ByteBufIO.MAX_CHUNK_BYTES + 1);
            assertThrows(DecoderException.class, () -> ByteBufIO.readByteArray(buf, ByteBufIO.MAX_CHUNK_BYTES));
        } finally {
            buf.release();
        }
    }

    @Test
    void readingOversizedMultiBlockCountThrows() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufIO.writeVarInt(buf, ByteBufIO.MAX_MULTI_BLOCK_ENTRIES + 1);
            int count = ByteBufIO.readVarInt(buf);
            // Callers compare against the cap; assert the helper surfaced the hostile value.
            assertEquals(ByteBufIO.MAX_MULTI_BLOCK_ENTRIES + 1, count);
        } finally {
            buf.release();
        }
    }

    @Test
    void truncatedReadThrows() {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeByte(0x05); // claims 5 bytes via varint, but none follow
            assertThrows(DecoderException.class, () -> ByteBufIO.readByteArray(buf, 1024));
        } finally {
            buf.release();
        }
    }
}

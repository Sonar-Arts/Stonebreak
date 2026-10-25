package com.openmason.engine.net.protocol.codec;

import com.openmason.engine.cenda.CendaKernels;
import com.openmason.engine.net.protocol.ByteBufIO;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Issue #346: negotiated chunk compression on the wire. */
class WireCompressionTest {

    private static final int MAX = ByteBufIO.MAX_CHUNK_BYTES;

    /** Layered, repetitive bytes — compresses like a chunk payload. */
    private static byte[] terrainLike(int size) {
        byte[] b = new byte[size];
        Random r = new Random(7);
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ((i / 256) % 4 + (r.nextInt(50) == 0 ? 9 : 0));
        }
        return b;
    }

    private static byte[] roundTrip(byte[] raw, int peerMask, int[] codecOut) {
        ByteBuf buf = Unpooled.buffer();
        try {
            WireCompression.writeBlob(buf, raw, MAX, peerMask);
            codecOut[0] = buf.getUnsignedByte(0);
            byte[] back = WireCompression.readBlob(buf, MAX);
            assertEquals(0, buf.readableBytes(), "blob left unread bytes");
            return back;
        } finally {
            buf.release();
        }
    }

    @Test
    void deflateRoundTripsAndShrinks() {
        byte[] raw = terrainLike(8192);
        int[] codec = new int[1];
        ByteBuf buf = Unpooled.buffer();
        WireCompression.writeBlob(buf, raw, MAX, WireCompression.bit(WireCompression.DEFLATE));
        assertTrue(buf.readableBytes() < raw.length / 2, "terrain-like bytes compress well");
        buf.release();
        assertArrayEquals(raw, roundTrip(raw, WireCompression.bit(WireCompression.DEFLATE), codec));
        assertEquals(WireCompression.DEFLATE, codec[0]);
    }

    @Test
    void zstdIsPreferredWhenBothEndsHaveIt() {
        assumeTrue(CendaKernels.isAvailable(), "native kernels not loaded");
        byte[] raw = terrainLike(8192);
        int[] codec = new int[1];
        assertArrayEquals(raw, roundTrip(raw, WireCompression.localDecodeMask(), codec));
        assertEquals(WireCompression.ZSTD, codec[0]);
    }

    @Test
    void aPeerThatDecodesNothingGetsRawBytes() {
        byte[] raw = terrainLike(8192);
        int[] codec = new int[1];
        assertArrayEquals(raw, roundTrip(raw, 0, codec));
        assertEquals(WireCompression.NONE, codec[0]);
    }

    @Test
    void smallAndIncompressibleBlobsStayRaw() {
        int[] codec = new int[1];
        byte[] small = terrainLike(WireCompression.MIN_COMPRESS_BYTES - 1);
        assertArrayEquals(small, roundTrip(small, WireCompression.localDecodeMask(), codec));
        assertEquals(WireCompression.NONE, codec[0]);

        byte[] noise = new byte[4096];
        new Random(1).nextBytes(noise);
        assertArrayEquals(noise, roundTrip(noise, WireCompression.localDecodeMask(), codec));
        assertEquals(WireCompression.NONE, codec[0], "a blob that does not shrink goes raw");
    }

    @Test
    void emptyBlobRoundTrips() {
        int[] codec = new int[1];
        assertArrayEquals(new byte[0], roundTrip(new byte[0], WireCompression.localDecodeMask(), codec));
    }

    @Test
    void aLyingRawLengthIsRefused() {
        byte[] raw = terrainLike(8192);
        byte[] deflated = WireCompression.compress(WireCompression.DEFLATE, raw);
        for (int claimed : new int[]{raw.length - 1, raw.length + 1}) {
            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(WireCompression.DEFLATE);
            ByteBufIO.writeVarInt(buf, claimed);
            ByteBufIO.writeByteArray(buf, deflated, MAX);
            assertThrows(DecoderException.class, () -> WireCompression.readBlob(buf, MAX),
                "claimed " + claimed + " for " + raw.length + " bytes");
            buf.release();
        }
    }

    @Test
    void rawLengthPastTheCapIsRefusedBeforeInflating() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(WireCompression.DEFLATE);
        ByteBufIO.writeVarInt(buf, MAX + 1);
        ByteBufIO.writeByteArray(buf, new byte[]{1, 2, 3}, MAX);
        assertThrows(DecoderException.class, () -> WireCompression.readBlob(buf, MAX));
        buf.release();
    }

    @Test
    void corruptAndUnknownBlobsAreRefused() {
        ByteBuf corrupt = Unpooled.buffer();
        corrupt.writeByte(WireCompression.DEFLATE);
        ByteBufIO.writeVarInt(corrupt, 100);
        ByteBufIO.writeByteArray(corrupt, new byte[]{9, 9, 9, 9}, MAX);
        assertThrows(DecoderException.class, () -> WireCompression.readBlob(corrupt, MAX));
        corrupt.release();

        ByteBuf unknown = Unpooled.buffer();
        unknown.writeByte(7);
        ByteBufIO.writeVarInt(unknown, 4);
        ByteBufIO.writeByteArray(unknown, new byte[]{1, 2, 3, 4}, MAX);
        assertThrows(DecoderException.class, () -> WireCompression.readBlob(unknown, MAX));
        unknown.release();
    }

    @Test
    void chunkPayloadCompressesOncePerCodec() {
        ChunkBlockPayload payload = ChunkBlockPayload.ofEncoded(terrainLike(8192));
        byte[] first = payload.compressed(WireCompression.DEFLATE);
        assertTrue(first != null && first.length < 8192);
        assertSame(first, payload.compressed(WireCompression.DEFLATE), "shared by every connection");
        assertNull(payload.compressed(WireCompression.NONE));

        byte[] noise = new byte[4096];
        new Random(2).nextBytes(noise);
        assertNull(ChunkBlockPayload.ofEncoded(noise).compressed(WireCompression.DEFLATE));
    }
}

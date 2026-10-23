package com.stonebreak.network.packet;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #342: the one network form of an item stack. Every packet that carries a stack and the
 * item-drop spawn metadata go through {@link NetItemStack}, so the stack's state (a filled
 * bucket, a cast rod) survives toss, pickup, give and death drops.
 */
class NetItemStackTest {

    private static final String WATER = ItemType.BUCKET_STATE_WATER;

    private static NetItemStack binaryRoundTrip(NetItemStack s) {
        ByteBuf buf = Unpooled.buffer();
        try {
            NetItemStack.write(buf, s);
            NetItemStack decoded = NetItemStack.read(buf);
            assertEquals(0, buf.readableBytes(), "the codec read exactly what it wrote");
            return decoded;
        } finally {
            buf.release();
        }
    }

    @Test
    void aStackWithoutStateRoundTrips() {
        NetItemStack stick = new NetItemStack(ItemType.STICK.getId(), 64);
        assertEquals(stick, binaryRoundTrip(stick));
        assertNull(binaryRoundTrip(stick).state());
        assertEquals(stick, NetItemStack.fromMetadata(stick.toMetadata()));
    }

    @Test
    void aStackWithStateRoundTrips() {
        NetItemStack water = NetItemStack.of(new ItemStack(ItemType.WOODEN_BUCKET, 1, WATER));
        assertEquals(WATER, binaryRoundTrip(water).state());
        assertEquals(water, NetItemStack.fromMetadata(water.toMetadata()));

        ItemStack back = binaryRoundTrip(water).toItemStack();
        assertSame(ItemType.WOODEN_BUCKET, back.getItem());
        assertEquals(1, back.getCount());
        assertEquals(WATER, back.getState(), "a tossed water bucket comes back full");
    }

    @Test
    void aStateWithSpacesAndColonsSurvivesTheMetadata() {
        // The rod's default state has a space; the metadata state is "the rest of the line".
        NetItemStack rod = new NetItemStack(ItemType.FISHING_ROD.getId(), 1, "Reeled in");
        assertEquals(rod, NetItemStack.fromMetadata(rod.toMetadata()));
        NetItemStack odd = new NetItemStack(7, 2, "a:b");
        assertEquals(odd, NetItemStack.fromMetadata(odd.toMetadata()));
    }

    @Test
    void theEmptyStackRoundTrips() {
        assertEquals(NetItemStack.EMPTY, NetItemStack.of(null));
        assertEquals(NetItemStack.EMPTY, NetItemStack.of(new ItemStack(BlockType.AIR, 0)));
        assertEquals(NetItemStack.EMPTY, binaryRoundTrip(NetItemStack.EMPTY));
        assertTrue(NetItemStack.EMPTY.toItemStack().isEmpty());
    }

    @Test
    void ofWithACountKeepsItemAndState() {
        NetItemStack two = NetItemStack.of(new ItemStack(ItemType.WOODEN_BUCKET, 5, WATER), 2);
        assertEquals(new NetItemStack(ItemType.WOODEN_BUCKET.getId(), 2, WATER), two);
    }

    @Test
    void blankAndOversizedStatesMeanTheDefaultState() {
        assertNull(new NetItemStack(1, 1, "  ").state());
        assertNull(new NetItemStack(1, 1, "x".repeat(NetItemStack.MAX_STATE_CHARS + 1)).state());
        String longest = "x".repeat(NetItemStack.MAX_STATE_CHARS);
        assertEquals(longest, binaryRoundTrip(new NetItemStack(1, 1, longest)).state());
    }

    @Test
    void negativeFieldsAreRefusedOnDecode() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufIO.writeVarInt(buf, 5);
            ByteBufIO.writeVarInt(buf, -1); // a hostile count
            ByteBufIO.writeString(buf, "", NetItemStack.MAX_STATE_CHARS);
            assertThrows(IllegalArgumentException.class, () -> NetItemStack.read(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void anOversizedStateIsRefusedOnDecode() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufIO.writeVarInt(buf, 5);
            ByteBufIO.writeVarInt(buf, 1);
            ByteBufIO.writeVarInt(buf, NetItemStack.MAX_STATE_CHARS * 4 + 1); // byte length past the bound
            buf.writeZero(NetItemStack.MAX_STATE_CHARS * 4 + 1);
            assertThrows(RuntimeException.class, () -> NetItemStack.read(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void aTruncatedStackIsRefusedOnDecode() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufIO.writeVarInt(buf, 5); // id only
            assertThrows(RuntimeException.class, () -> NetItemStack.read(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void malformedMetadataIsEmpty() {
        assertEquals(NetItemStack.EMPTY, NetItemStack.fromMetadata(null));
        assertEquals(NetItemStack.EMPTY, NetItemStack.fromMetadata(""));
        assertEquals(NetItemStack.EMPTY, NetItemStack.fromMetadata("12"));
        assertEquals(NetItemStack.EMPTY, NetItemStack.fromMetadata("x:3"));
        assertEquals(NetItemStack.EMPTY, NetItemStack.fromMetadata("12:-3"));
        // The pre-#342 "id:count" form still reads.
        assertEquals(new NetItemStack(12, 3), NetItemStack.fromMetadata("12:3"));
    }

    @Test
    void anUnknownIdDecodesToAnEmptyStack() {
        assertTrue(new NetItemStack(999_999, 4).toItemStack().isEmpty());
        assertNull(new NetItemStack(999_999, 4).item());
    }
}

package com.stonebreak.network.packet;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.Item;
import com.stonebreak.items.ItemStack;
import com.stonebreak.items.ItemType;
import io.netty.buffer.ByteBuf;

/**
 * The ONE network form of an item stack (issue #342): item id, count and the stack's named
 * SBO state (a filled bucket, a cast fishing rod). Every packet that carries a stack writes
 * it with {@link #write}/{@link #read}, and a drop's spawn metadata with
 * {@link #toMetadata}/{@link #fromMetadata}, so state can no longer fall off on the way
 * (toss, pickup, give, death drops). {@code InventorySyncS2C} keeps its own
 * {@code ItemStackStateCodec} slot tokens, which already carry state.
 *
 * <p>Wire layout: varint id, varint count, varint-length UTF-8 state ({@code ""} = the
 * item's default state). Block and item ids never collide, so the id alone names the item.
 *
 * <p>Immutable: decode into a fresh {@link ItemStack} with {@link #toItemStack()}.
 */
public record NetItemStack(int itemId, int count, String state) {

    /** Longest state name the wire carries (SBO state names are short identifiers). */
    public static final int MAX_STATE_CHARS = 64;

    /** The empty stack: no item, no state. */
    public static final NetItemStack EMPTY = new NetItemStack(0, 0, null);

    /**
     * Normalizes the state: blank means the default state, and a name the wire cannot carry
     * is dropped to the default state rather than failing the packet.
     */
    public NetItemStack {
        if (state != null && (state.isBlank() || state.length() > MAX_STATE_CHARS)) {
            state = null;
        }
    }

    /** A stack in the item's default state. */
    public NetItemStack(int itemId, int count) {
        this(itemId, count, null);
    }

    /** The network form of {@code stack}; {@link #EMPTY} for null/empty. */
    public static NetItemStack of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EMPTY;
        }
        return new NetItemStack(stack.getBlockTypeId(), stack.getCount(), stack.getState());
    }

    /** {@code stack}'s item and state with {@code count} in place of its own count. */
    public static NetItemStack of(ItemStack stack, int count) {
        if (stack == null || stack.isEmpty()) {
            return EMPTY;
        }
        return new NetItemStack(stack.getBlockTypeId(), count, stack.getState());
    }

    /** True when this names no item or no count. */
    public boolean isEmpty() {
        return itemId <= 0 || count <= 0;
    }

    /** The item this id names (items win the lookup, as in the server inventory), or null. */
    public Item item() {
        if (itemId <= 0) {
            return null;
        }
        Item item = ItemType.getById(itemId);
        if (item != null) {
            return item;
        }
        BlockType block = BlockType.getById(itemId);
        return block != BlockType.AIR ? block : null;
    }

    /** A fresh stack (callers mutate stacks in place); empty for an unknown id or no count. */
    public ItemStack toItemStack() {
        Item item = item();
        if (item == null || count <= 0) {
            return new ItemStack(0, 0);
        }
        return new ItemStack(item, count, state);
    }

    // ─── Binary form (packets) ──────────────────────────────────────────────────

    public static void write(ByteBuf out, NetItemStack s) {
        NetItemStack v = s != null ? s : EMPTY;
        ByteBufIO.writeVarInt(out, Math.max(0, v.itemId()));
        ByteBufIO.writeVarInt(out, Math.max(0, v.count()));
        ByteBufIO.writeString(out, v.state(), MAX_STATE_CHARS);
    }

    /** Reads a stack written by {@link #write}; negative fields or an oversized state fail the decode (IllegalArgumentException, like the other codecs). */
    public static NetItemStack read(ByteBuf in) {
        int itemId = ByteBufIO.readVarInt(in);
        int count = ByteBufIO.readVarInt(in);
        if (itemId < 0 || count < 0) {
            throw new IllegalArgumentException("Item stack out of range: id=" + itemId + " count=" + count);
        }
        String state = ByteBufIO.readString(in, MAX_STATE_CHARS);
        return new NetItemStack(itemId, count, state);
    }

    // ─── Text form (entity spawn metadata) ──────────────────────────────────────

    /**
     * {@code id:count} or {@code id:count:state}. The state is the remainder after the
     * second {@code ':'}, so it needs no escaping; a state-less drop reads as before.
     */
    public String toMetadata() {
        String base = itemId + ":" + count;
        return state != null ? base + ":" + state : base;
    }

    /** Parses {@link #toMetadata}; anything malformed is {@link #EMPTY}. */
    public static NetItemStack fromMetadata(String metadata) {
        if (metadata == null) {
            return EMPTY;
        }
        String[] parts = metadata.split(":", 3);
        if (parts.length < 2) {
            return EMPTY;
        }
        int itemId = parseNonNegative(parts[0]);
        int count = parseNonNegative(parts[1]);
        if (itemId < 0 || count < 0) {
            return EMPTY;
        }
        return new NetItemStack(itemId, count, parts.length == 3 ? parts[2] : null);
    }

    private static int parseNonNegative(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return v >= 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

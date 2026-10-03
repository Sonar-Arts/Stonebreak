package com.stonebreak.items;

import com.stonebreak.blocks.BlockType;

import java.util.regex.Pattern;

/**
 * Encodes one {@link ItemStack} as the compact token stored inside per-block state
 * strings ({@code Chunk.blockStates}) and slot intents — shared by every block that
 * holds items (furnace, crafting table) so they all persist stacks identically.
 *
 * <p>Token format: {@code kind:id:count[:state]}, where kind is {@code B} (BlockType) or
 * {@code I} (ItemType) and the optional fourth field is the stack's named SBO state
 * (e.g. a filled bucket). An empty stack is {@code B:0:0}. The state field is only
 * written when it is a plain identifier, so it can never collide with the {@code :},
 * {@code |}, {@code ;} and {@code =} separators the enclosing strings use.
 */
public final class ItemStackStateCodec {

    /** The token for an empty slot. */
    public static final String EMPTY_TOKEN = "B:0:0";

    /** 32 chars keeps a full 9-slot crafting grid under {@code BlockStateS2C}'s 512-char bound. */
    private static final Pattern SAFE_STATE = Pattern.compile("[A-Za-z0-9_.-]{1,32}");

    private ItemStackStateCodec() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** A fresh empty stack (never shared — callers mutate stacks in place). */
    public static ItemStack empty() {
        return new ItemStack(0, 0);
    }

    public static String encode(ItemStack s) {
        if (s == null || s.isEmpty()) return EMPTY_TOKEN;
        Item item = s.getItem();
        char kind = (item instanceof ItemType) ? 'I' : 'B';
        String token = kind + ":" + item.getId() + ":" + s.getCount();
        String state = s.getState();
        return (state != null && SAFE_STATE.matcher(state).matches()) ? token + ":" + state : token;
    }

    /** Parses a token from {@link #encode}; anything malformed or unknown decodes to empty. */
    public static ItemStack decode(String v) {
        if (v == null || v.isEmpty()) return empty();
        String[] parts = v.split(":");
        if (parts.length != 3 && parts.length != 4) return empty();
        char kind = parts[0].isEmpty() ? 'B' : parts[0].charAt(0);
        int id    = parseInt(parts[1]);
        int count = parseInt(parts[2]);
        if (count <= 0 || id <= 0) return empty();
        String state = parts.length == 4 ? parts[3] : null;
        Item item = (kind == 'I') ? ItemType.getById(id) : BlockType.getById(id);
        return item != null ? new ItemStack(item, count, state) : empty();
    }

    /** Clamps an untrusted (client-sent) stack to its max stack size; null/empty → empty. */
    public static ItemStack clamp(ItemStack s) {
        if (s == null || s.isEmpty()) return empty();
        if (s.getCount() > s.getMaxStackSize()) {
            s.setCount(s.getMaxStackSize());
        }
        return s;
    }

    private static int parseInt(String s) {
        // Save data may be missing or corrupted; 0 decodes to an empty slot.
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }
}

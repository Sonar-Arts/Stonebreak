package com.stonebreak.network.packet.player;

import com.openmason.engine.net.protocol.ByteBufIO;
import com.openmason.engine.net.protocol.Packet;
import com.openmason.engine.net.protocol.PacketCodec;
import io.netty.buffer.ByteBuf;

/**
 * Client → server: one atomic slot action on a container screen (furnace, crafting table
 * or the player's own inventory screen). Replaces the old whole-slot-snapshot packets
 * (FurnaceSlotsC2S / WorkbenchSlotsC2S): the server replays the click against its own
 * authoritative state — its {@code ServerInventory} plus the block's
 * {@code FurnaceState}/{@code WorkbenchState} — with its own carried-cursor state, so
 * two players at one container can no longer overwrite each other and output slots are
 * computed server-side.
 *
 * <p>For {@link ContainerKind#INVENTORY} the position is unused (0,0,0).
 *
 * <p>{@code seq} numbers furnace/workbench actions (issue #353, {@code SlotIntentSeq}); the
 * server acknowledges it per recipient in {@code BlockStateS2C.ack}, so an echo built before
 * the action arrived cannot roll back the client's predicted slots. 0 = not numbered (the
 * inventory screen, whose grid is not a block state).
 */
public record ContainerActionC2S(int x, int y, int z,
                                 byte containerKind, byte region, byte slot,
                                 byte originRegion, byte originSlot,
                                 byte action, short count, boolean shift, int seq) implements Packet {

    /** An un-numbered action (seq 0). */
    public ContainerActionC2S(int x, int y, int z, byte containerKind, byte region, byte slot,
                              byte originRegion, byte originSlot,
                              byte action, short count, boolean shift) {
        this(x, y, z, containerKind, region, slot, originRegion, originSlot, action, count, shift, 0);
    }

    /** Actions that carry no drag-origin (no swap-back semantics). */
    public ContainerActionC2S(int x, int y, int z, byte containerKind, byte region, byte slot,
                              byte action, short count, boolean shift) {
        this(x, y, z, containerKind, region, slot, Region.NONE, (byte) -1, action, count, shift);
    }

    /** Which screen the action belongs to. */
    public static final class ContainerKind {
        public static final byte FURNACE = 0;
        public static final byte WORKBENCH = 1;
        public static final byte INVENTORY = 2;
        private ContainerKind() {}
    }

    /**
     * Which slot region the action targets. Player regions are the same across all
     * containers; container regions are the block's own slots.
     */
    public static final class Region {
        /** No drag origin (the action does not return a displaced stack anywhere). */
        public static final byte NONE = -1;
        public static final byte PLAYER_HOTBAR = 0;
        public static final byte PLAYER_MAIN = 1;
        public static final byte FURNACE_ING = 2;
        public static final byte FURNACE_FUEL = 3;
        public static final byte FURNACE_OUT = 4;
        public static final byte WORKBENCH_GRID = 5;
        public static final byte WORKBENCH_OUT = 6;
        public static final byte INV_CRAFT_GRID = 7;
        public static final byte INV_CRAFT_OUT = 8;
        private Region() {}
    }

    /** The atomic transfer to apply. */
    public static final class Action {
        public static final byte TAKE_ALL = 0;        // slot → carried
        public static final byte PUT_ALL = 1;         // carried → slot (slot empty)
        public static final byte MERGE = 2;           // carried → slot, same type, up to max
        public static final byte SWAP = 3;            // carried ↔ slot
        public static final byte SPLIT_HALF = 4;      // slot → carried, ceil(count/2)
        public static final byte TAKE_ONE = 5;        // slot → carried, exactly 1
        public static final byte DROP_ONE = 6;        // carried → slot, exactly 1
        public static final byte QUICK_MOVE = 7;      // shift-click: region rules decide destination
        public static final byte TAKE_OUTPUT = 8;     // output slot: server crafts/smelts, hands out
        public static final byte CRAFT_ALL = 9;       // repeat TAKE_OUTPUT into the player inventory
        public static final byte GATHER = 10;         // double-click: matching stacks → carried
        public static final byte BALANCE = 11;        // middle-click: even out a crafting cell
        public static final byte SORT = 12;           // sort the player inventory
        public static final byte DEPOSIT_CARRIED = 13; // carried → player inventory (screen close)
        public static final byte DROP_CARRIED = 14;    // carried → world drop (toss, debit)
        private Action() {}
    }

    public static final PacketCodec<ContainerActionC2S> CODEC = new PacketCodec<>() {
        @Override
        public void encode(ByteBuf out, ContainerActionC2S p) {
            out.writeInt(p.x());
            out.writeInt(p.y());
            out.writeInt(p.z());
            out.writeByte(p.containerKind());
            out.writeByte(p.region());
            out.writeByte(p.slot());
            out.writeByte(p.originRegion());
            out.writeByte(p.originSlot());
            out.writeByte(p.action());
            out.writeShort(p.count());
            out.writeBoolean(p.shift());
            ByteBufIO.writeVarInt(out, p.seq());
        }

        @Override
        public ContainerActionC2S decode(ByteBuf in) {
            return new ContainerActionC2S(in.readInt(), in.readInt(), in.readInt(),
                in.readByte(), in.readByte(), in.readByte(), in.readByte(), in.readByte(),
                in.readByte(), in.readShort(), in.readBoolean(), ByteBufIO.readVarInt(in));
        }
    };
}

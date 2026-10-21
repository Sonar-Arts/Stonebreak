package com.stonebreak.ui.inventoryScreen.handlers;

import com.stonebreak.network.packet.player.ContainerActionC2S;

/**
 * Where a container screen's slot actions go (issue #334): one atomic
 * {@link ContainerActionC2S} per click, replayed by the server against its own state.
 * The bound container (kind + position) is set when a screen opens; the inventory screen
 * binds kind {@code INVENTORY} at (0,0,0). Tests capture actions instead of sending.
 */
public final class SlotActionSink {

    private byte containerKind = ContainerActionC2S.ContainerKind.INVENTORY;
    private int x, y, z;
    /** Test seam: captures (or replaces) the actual send. Null = send via the session. */
    private java.util.function.Consumer<ContainerActionC2S> capture;

    /** Captures actions instead of sending them (interaction tests). */
    public void setCapture(java.util.function.Consumer<ContainerActionC2S> capture) {
        this.capture = capture;
    }

    /** Binds the container this screen is currently open on. */
    public void bind(byte containerKind, int x, int y, int z) {
        this.containerKind = containerKind;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Sends one slot action with no drag origin. */
    public void send(byte region, byte slot, byte action) {
        send(region, slot, ContainerActionC2S.Region.NONE, (byte) -1, action, false);
    }

    /** Sends one slot action with a shift flag (e.g. QUICK_MOVE) and no drag origin. */
    public void send(byte region, byte slot, byte action, boolean shift) {
        send(region, slot, ContainerActionC2S.Region.NONE, (byte) -1, action, shift);
    }

    /** Sends a slot-less action (GATHER, SORT, DEPOSIT_CARRIED, DROP_CARRIED). */
    public void send(byte action) {
        send(ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0, action);
    }

    /** Sends a slot-less action carrying a count (DROP_CARRIED: the carried stack size). */
    public void sendWithCount(byte action, short count) {
        ContainerActionC2S packet = new ContainerActionC2S(x, y, z, containerKind,
                ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0,
                ContainerActionC2S.Region.NONE, (byte) -1, action, count, false);
        if (capture != null) {
            capture.accept(packet);
        } else {
            com.stonebreak.network.MultiplayerSession.sendContainerAction(x, y, z, containerKind,
                    ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0,
                    ContainerActionC2S.Region.NONE, (byte) -1, action, count, false);
        }
    }

    /** Sends one slot action with a drag origin (swap-back) and a shift flag. */
    public void send(byte region, byte slot, byte originRegion, byte originSlot,
                     byte action, boolean shift) {
        ContainerActionC2S packet = new ContainerActionC2S(x, y, z, containerKind, region, slot,
                originRegion, originSlot, action, (short) 0, shift);
        if (capture != null) {
            capture.accept(packet);
        } else {
            com.stonebreak.network.MultiplayerSession.sendContainerAction(x, y, z, containerKind,
                    region, slot, originRegion, originSlot, action, (short) 0, shift);
        }
    }
}

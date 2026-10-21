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
    /** Told the seq of every numbered action before it is sent (the bound state's markSent). */
    private java.util.function.IntConsumer onNumbered;
    /** Actions dispatched since the last {@link #bind} — only they can fill the server cursor. */
    private int sentSinceBind;
    /** The last action dispatched (-1 = none since bind). */
    private byte lastAction = -1;

    /** Captures actions instead of sending them (interaction tests). */
    public void setCapture(java.util.function.Consumer<ContainerActionC2S> capture) {
        this.capture = capture;
    }

    /**
     * Receives the seq of each furnace/workbench action before it is sent, so the bound
     * display state can ignore echoes built before the server saw it (issue #353).
     */
    public void onNumbered(java.util.function.IntConsumer listener) {
        this.onNumbered = listener;
    }

    /** Binds the container this screen is currently open on. */
    public void bind(byte containerKind, int x, int y, int z) {
        this.containerKind = containerKind;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sentSinceBind = 0;
        this.lastAction = -1;
    }

    /**
     * True when actions go somewhere that replays them (a live session, or a test capture):
     * the server then owns the carried stack, so a toss must ship as DROP_CARRIED instead of
     * a local world drop (issue #334). False in local-only play (no session, battle test),
     * where the screens' local rules are the only ones.
     */
    public boolean live() {
        return capture != null || com.stonebreak.network.MultiplayerSession.isClient();
    }

    /**
     * True when this screen dispatched any action since it was bound: only then can the
     * server's cursor hold a stack the close must deposit (issue #334).
     */
    public boolean hasSentSinceBind() {
        return sentSinceBind > 0;
    }

    /** The last action dispatched since the bind, or -1. */
    public byte lastAction() {
        return lastAction;
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
        dispatch(ContainerActionC2S.Region.PLAYER_HOTBAR, (byte) 0,
                ContainerActionC2S.Region.NONE, (byte) -1, action, count, false);
    }

    /** Sends one slot action with a drag origin (swap-back) and a shift flag. */
    public void send(byte region, byte slot, byte originRegion, byte originSlot,
                     byte action, boolean shift) {
        dispatch(region, slot, originRegion, originSlot, action, (short) 0, shift);
    }

    private void dispatch(byte region, byte slot, byte originRegion, byte originSlot,
                          byte action, short count, boolean shift) {
        sentSinceBind++;
        lastAction = action;
        // Every action is numbered: InventorySyncS2C acknowledges it per player, and for a
        // furnace/workbench BlockStateS2C also acknowledges it per block (issue #353).
        int seq = com.stonebreak.network.SlotIntentSeq.next();
        if (containerKind != ContainerActionC2S.ContainerKind.INVENTORY && onNumbered != null) {
            onNumbered.accept(seq);
        }
        ContainerActionC2S packet = new ContainerActionC2S(x, y, z, containerKind, region, slot,
                originRegion, originSlot, action, count, shift, seq);
        if (capture != null) {
            capture.accept(packet);
        } else {
            com.stonebreak.network.MultiplayerSession.sendContainerAction(packet);
        }
    }
}

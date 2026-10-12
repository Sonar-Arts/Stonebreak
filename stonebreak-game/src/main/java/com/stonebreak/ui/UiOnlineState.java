package com.stonebreak.ui;

import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.runtime.GameUiHost;

import java.util.function.BooleanSupplier;

/**
 * Whether the UI lays out for an online session (#296): the one source the legacy pause menu
 * (Resync World button) and UI documents ({@code session.online} via {@link GameUiHost}) both
 * read, so a fidelity run compares like with like. It follows {@link MultiplayerSession#isOnline}
 * unless overridden; the override changes presentation only, never the network session.
 *
 * <p>Overridden by tests and by {@code -Dstonebreak.autopause=<s>:online}.
 */
public final class UiOnlineState {

    private static volatile BooleanSupplier override;

    private UiOnlineState() {
    }

    public static boolean isOnline() {
        BooleanSupplier o = override;
        return o != null ? o.getAsBoolean() : MultiplayerSession.isOnline();
    }

    /** What {@code mode} means for the UI: the override when set, else host or join. */
    public static boolean isOnline(MultiplayerSession.Mode mode) {
        BooleanSupplier o = override;
        return o != null ? o.getAsBoolean() : mode == MultiplayerSession.Mode.HOST || mode == MultiplayerSession.Mode.JOIN;
    }

    /** Pins the UI's online state (null restores the live session) and republishes {@code session}. */
    public static void override(BooleanSupplier source) {
        override = source;
        GameUiHost.ifPresent(GameUiHost::refreshSession);
    }
}

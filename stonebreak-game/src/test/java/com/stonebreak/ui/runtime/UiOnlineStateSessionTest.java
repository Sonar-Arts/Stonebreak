package com.stonebreak.ui.runtime;

import com.openmason.engine.format.omui.UiValue;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.PauseMenu;
import com.stonebreak.ui.UiOnlineState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The online override (#296) reaches both sides of a pause fidelity run: the legacy menu's
 * Resync button and the {@code session.online} value documents bind to.
 */
class UiOnlineStateSessionTest {

    @AfterEach
    void release() {
        UiOnlineState.override(null);
    }

    @Test
    void overrideFeedsTheLegacyMenuAndTheSessionDataRootAlike() {
        assertEquals(UiValue.FALSE, online(MultiplayerSession.Mode.SINGLEPLAYER));
        assertEquals(UiValue.TRUE, online(MultiplayerSession.Mode.JOIN));

        UiOnlineState.override(() -> true);
        assertEquals(UiValue.TRUE, online(MultiplayerSession.Mode.SINGLEPLAYER));
        assertEquals(true, PauseMenu.isResyncButtonVisible());

        UiOnlineState.override(() -> false);
        assertEquals(UiValue.FALSE, online(MultiplayerSession.Mode.HOST));
        assertEquals(false, PauseMenu.isResyncButtonVisible());
    }

    private static UiValue online(MultiplayerSession.Mode mode) {
        return ((UiValue.Obj) GameUiHost.sessionValue(mode)).get("online");
    }
}

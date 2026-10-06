package com.stonebreak.ui.runtime;

import com.openmason.engine.audio.SoundSystem;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.script.UiScriptServices;
import com.stonebreak.core.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What UI scripts may ask the game for beyond host actions (#292): sounds by id through the game's
 * sound system, and closing their screen. Navigation targets arrive with the screen migrations
 * (#297 onward); until then {@code ui.navigate} reports that the game has none.
 */
public final class GameUiScriptServices implements UiScriptServices {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameUiScriptServices.class);

    private final Runnable close;

    /** @param close what {@code ui.close()} does for this screen (null: log only) */
    public GameUiScriptServices(Runnable close) {
        this.close = close;
    }

    @Override
    public void playSound(String id, UiValue.Obj options) {
        SoundSystem sounds = Game.getSoundSystem();
        if (sounds == null || !sounds.isSoundLoaded(id)) {
            LOGGER.debug("[ui-script] no sound '{}' loaded", id);
            return;
        }
        float volume = options.get("volume") instanceof UiValue.Num n ? (float) n.value() : 1f;
        sounds.playSoundWithVolume(id, volume);
    }

    @Override
    public void requestClose() {
        if (close != null) {
            close.run();
        } else {
            LOGGER.info("[ui-script] a script asked to close its screen; this host has no close action");
        }
    }
}

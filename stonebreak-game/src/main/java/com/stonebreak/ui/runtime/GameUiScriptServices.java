package com.stonebreak.ui.runtime;

import com.openmason.engine.audio.SoundSystem;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.script.UiScriptServices;
import com.stonebreak.core.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What UI scripts may ask the game for beyond host actions (#292): sounds by id through the game's
 * sound system, closing their screen, and navigating to another screen. Close and navigation are
 * requests: the document screen host performs them at the end of the frame, never underneath the
 * script dispatch that asked (C4).
 */
public final class GameUiScriptServices implements UiScriptServices {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameUiScriptServices.class);

    /** Where {@code ui.navigate} goes. */
    @FunctionalInterface
    public interface Navigation {
        /** @return false when there is no such target (the script gets an error) */
        boolean navigate(String target, UiValue.Obj args);

        Navigation NONE = (target, args) -> false;
    }

    private final Runnable close;
    private final Navigation navigation;

    /** @param close what {@code ui.close()} does for this screen (null: log only) */
    public GameUiScriptServices(Runnable close) {
        this(close, Navigation.NONE);
    }

    /**
     * @param close      what {@code ui.close()} requests (null: log only)
     * @param navigation what {@code ui.navigate} requests
     */
    public GameUiScriptServices(Runnable close, Navigation navigation) {
        this.close = close;
        this.navigation = navigation == null ? Navigation.NONE : navigation;
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
    public boolean navigate(String target, UiValue.Obj args) {
        boolean known = navigation.navigate(target, args == null ? new UiValue.Obj(java.util.Map.of()) : args);
        if (!known) {
            LOGGER.info("[ui-script] no navigation target '{}'", target);
        }
        return known;
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

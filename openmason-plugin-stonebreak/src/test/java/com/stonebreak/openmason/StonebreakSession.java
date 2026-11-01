package com.stonebreak.openmason;

import com.openmason.main.systems.target.GameTargets;

import java.util.List;

/** Makes the real Stonebreak target the editor's active one for a test, and removes it again. */
public final class StonebreakSession {

    private StonebreakSession() {
    }

    /** {@link StonebreakGameTarget#create()} as the shared registry's only target, no project open. */
    public static void install() {
        GameTargets.shared().setSource(() -> List.of(StonebreakGameTarget.create()));
        GameTargets.shared().projectClosed();
    }

    /** Empties the shared registry again (no target). */
    public static void clear() {
        GameTargets.shared().setSource(List::of);
        GameTargets.shared().projectClosed();
    }
}

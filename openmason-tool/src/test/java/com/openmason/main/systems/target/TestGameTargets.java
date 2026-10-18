package com.openmason.main.systems.target;

import com.openmason.api.target.GameTarget;
import com.openmason.api.target.ResourceRoot;
import com.openmason.main.plugins.builtin.StonebreakGameTarget;

import java.nio.file.Path;
import java.util.List;

/** Game targets for tests (#380): the real Stonebreak target, or it rooted at a temp tree. */
public final class TestGameTargets {

    private TestGameTargets() {
    }

    /** The Stonebreak target as the plugin contributes it (its tree is this checkout's). */
    public static GameTarget stonebreak() {
        return StonebreakGameTarget.create();
    }

    /** The Stonebreak target (folders, namespace, UI host, deploy layout) with its tree at {@code tree}. */
    public static GameTarget stonebreakAt(Path tree) {
        GameTarget sb = stonebreak();
        return new GameTarget(sb.id(), sb.displayName(), sb.defaultNamespace(), ResourceRoot.at(tree), sb.folders(),
                sb.assetFilePrefix(), sb.writes(), sb.uiDeploy(), sb.uiHost(), null, sb.legacyDefault());
    }

    /** Makes {@code target} the shared registry's only target, active with no project open. */
    public static void installShared(GameTarget target) {
        GameTargets.shared().setSource(() -> List.of(target));
        GameTargets.shared().projectClosed();
    }

    /** {@link #installShared} with the real Stonebreak target. */
    public static void installStonebreak() {
        installShared(stonebreak());
    }

    /** Empties the shared registry again (no target). */
    public static void clearShared() {
        GameTargets.shared().setSource(List::of);
        GameTargets.shared().projectClosed();
    }
}

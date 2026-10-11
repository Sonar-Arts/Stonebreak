package com.stonebreak.ui.worldHud;

import com.stonebreak.core.Game;
import com.stonebreak.rendering.UI.components.DamageNumberRenderer;
import com.stonebreak.rendering.UI.components.DoubtMarkerRenderer;
import com.stonebreak.rendering.UI.components.EnemyAwarenessRenderer;
import com.stonebreak.rendering.UI.components.PlayerNameTagRenderer;
import com.stonebreak.rendering.UI.components.QuarryMarkerRenderer;
import com.stonebreak.rendering.UI.components.StealthHudRenderer;
import com.stonebreak.rendering.UI.components.WorldMarker;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;
import com.stonebreak.ui.runtime.screens.PresentationSlot;
import com.stonebreak.ui.runtime.screens.PresentedDocument;
import com.stonebreak.ui.runtime.screens.ScreenPresentation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The world-anchored HUD in PLAYING (#301): damage numbers, the Ranger's prey and snare markers, the
 * Illusionist's Doubt counts, awareness glyphs, remote players' name tags and the stealth indicator.
 * Shown as the shipped {@code ui/documents/world_hud.sbui}, fed from {@code stonebreak:world.hud} with
 * the markers the legacy renderers collect (one selection and projection rule for both paths), else
 * drawn by those renderers (not shipped, refused, or {@code -Dstonebreak.ui.legacy=world_hud}).
 */
public final class WorldHud {

    /** The shipped document: {@code ui/documents/world_hud.sbui}. */
    public static final String DOCUMENT_ID = "world_hud";

    private final PresentationSlot slot = new PresentationSlot();
    private boolean installed;

    public void setPresentation(ScreenPresentation p) {
        installed = true;
        slot.install(p);
    }

    /** Main thread, every frame: the world HUD shows in PLAYING (the legacy rule). */
    public void sync(boolean visible) {
        if (visible && !installed) {
            setPresentation(new PresentedDocument(DOCUMENT_ID, DocumentScreenHost.get(), DocumentScreen.Options.hud()));
        }
        slot.setVisible(visible);
    }

    /** True while the document, not the legacy renderers, shows the world HUD. */
    public boolean showing() {
        return slot.showing();
    }

    /**
     * Draws the world HUD at its place in the frame (after the crosshair, hotbar and chat): publishes the
     * markers and stealth state and paints the document, or falls back to the legacy renderers.
     */
    public void render(Matrix4f projection, Matrix4f view, int width, int height) {
        if (slot.showing()) {
            GameUiHost.get().worldHud().publish(collect(projection, view, width, height),
                StealthHudRenderer.state(Game.getPlayer()));
            if (slot.paint(width, height)) {
                return;
            }
        }
        DamageNumberRenderer.getInstance().render(projection, view, width, height);
        QuarryMarkerRenderer.getInstance().render(projection, view, width, height);
        DoubtMarkerRenderer.getInstance().render(projection, view, width, height);
        EnemyAwarenessRenderer.getInstance().render(projection, view, width, height);
        PlayerNameTagRenderer.getInstance().render(projection, view, width, height);
        StealthHudRenderer.getInstance().render(width, height);
    }

    /** Every world marker this frame, in the legacy renderers' paint order. */
    public static List<WorldMarker> collect(Matrix4f projection, Matrix4f view, int width, int height) {
        Matrix4f vp = new Matrix4f(projection).mul(view);
        List<WorldMarker> out = new ArrayList<>();
        DamageNumberRenderer.getInstance().collect(vp, width, height, out);
        QuarryMarkerRenderer.getInstance().collect(vp, width, height, out);
        DoubtMarkerRenderer.getInstance().collect(vp, width, height, out);
        EnemyAwarenessRenderer.getInstance().collect(vp, width, height, out);
        PlayerNameTagRenderer.getInstance().collect(vp, width, height, out);
        return out;
    }
}

package com.stonebreak.ui.worldHud;

import com.stonebreak.core.Game;
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
 * the markers the collectors here gather (one selection and projection rule for every kind).
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

    /** Main thread, every frame: the world HUD shows in PLAYING. */
    public void sync(boolean visible) {
        if (visible && !installed) {
            setPresentation(new PresentedDocument(DOCUMENT_ID, DocumentScreenHost.get(), DocumentScreen.Options.hud()));
        }
        slot.setVisible(visible);
    }

    /**
     * Draws the world HUD at its place in the frame (after the crosshair, hotbar and chat): publishes the
     * markers and stealth state and paints the document.
     */
    public void render(Matrix4f projection, Matrix4f view, int width, int height) {
        if (slot.showing()) {
            GameUiHost.get().worldHud().publish(collect(projection, view, width, height),
                StealthHud.state(Game.getPlayer()));
            slot.paint(width, height);
        }
    }

    /** Every world marker this frame, in paint order. */
    public static List<WorldMarker> collect(Matrix4f projection, Matrix4f view, int width, int height) {
        Matrix4f vp = new Matrix4f(projection).mul(view);
        List<WorldMarker> out = new ArrayList<>();
        DamageNumbers.getInstance().collect(vp, width, height, out);
        QuarryMarkers.getInstance().collect(vp, width, height, out);
        DoubtMarkers.getInstance().collect(vp, width, height, out);
        AwarenessMarkers.getInstance().collect(vp, width, height, out);
        NameTags.getInstance().collect(vp, width, height, out);
        return out;
    }
}

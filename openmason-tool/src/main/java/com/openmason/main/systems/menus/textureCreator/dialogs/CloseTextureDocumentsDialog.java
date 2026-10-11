package com.openmason.main.systems.menus.textureCreator.dialogs;

import com.openmason.main.systems.menus.dialogs.ModalDialogs;
import com.openmason.main.systems.menus.textureCreator.TextureCreatorController;
import com.openmason.main.systems.menus.textureCreator.TextureDocument;
import imgui.ImGui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * Closing texture documents with unsaved work: asks "Save Changes" for each standalone document
 * that needs it (Save / Don't Save / Cancel), then closes the whole batch at once. Cancel at any
 * point closes nothing (saves already made stay saved). Face documents and clean documents never
 * ask — see {@link TextureDocument#needsSavePrompt()}.
 *
 * <p>Used by the document tabs and by the Texture workspace's close guard; render every frame
 * from the workspace's panel pass.
 */
public final class CloseTextureDocumentsDialog {

    private static final String POPUP_ID = "Save Changes##textureDocClose";

    private final TextureCreatorController controller;
    /** Saves a document in place or through Save As; true when it is clean afterwards. */
    private final Predicate<TextureDocument> saver;

    private final List<TextureDocument> batch = new ArrayList<>();
    private final Deque<TextureDocument> asking = new ArrayDeque<>();
    private Runnable afterClose;
    private float refCenterX = -1f;
    private float refCenterY = -1f;

    public CloseTextureDocumentsDialog(TextureCreatorController controller, Predicate<TextureDocument> saver) {
        this.controller = controller;
        this.saver = saver;
    }

    /** Centre on this screen point (the viewport holding the Texture workspace). */
    public void setReferenceViewportCenter(float x, float y) {
        this.refCenterX = x;
        this.refCenterY = y;
    }

    /** Whether a close is waiting on the user. */
    public boolean isAsking() {
        return !asking.isEmpty();
    }

    /**
     * Close {@code documents}, asking about unsaved ones first.
     *
     * @param afterClose runs once the documents closed (not when the user cancels); may be null
     * @return true when they closed at once (nothing to ask), false when the dialog is asking
     */
    public boolean request(List<TextureDocument> documents, Runnable afterClose) {
        if (isAsking()) {
            return false; // one question at a time; the user is already answering one
        }
        batch.clear();
        batch.addAll(documents);
        for (TextureDocument d : documents) {
            if (d.needsSavePrompt()) {
                asking.add(d);
            }
        }
        this.afterClose = afterClose;
        if (asking.isEmpty()) {
            finish();
            return true;
        }
        controller.activate(asking.peek()); // show the texture being asked about
        return false;
    }

    public void render() {
        TextureDocument doc = asking.peek();
        if (doc == null) {
            return;
        }
        if (!controller.getDocuments().contains(doc)) {
            // Closed by someone else meanwhile (an agent): nothing left to ask about it.
            asking.poll();
            advance();
            return;
        }
        ModalDialogs.openIfNeeded(POPUP_ID);
        ModalDialogs.Anchor anchor = refCenterX >= 0 && refCenterY >= 0
                ? new ModalDialogs.Anchor(refCenterX, refCenterY) : null;
        if (ModalDialogs.begin(POPUP_ID, anchor, 380)) {
            ImGui.text("Save changes to \"" + doc.title() + "\" before closing?");
            ImGui.textDisabled("Your changes are lost if you don't save them.");
            if (asking.size() > 1) {
                ImGui.textDisabled((asking.size() - 1) + " more unsaved texture(s) after this one.");
            }
            ModalDialogs.buttonsBegin();
            if (ModalDialogs.primary("Save", true)) {
                if (saver.test(doc)) {
                    asking.poll();
                    ModalDialogs.close();
                    advance();
                }
                // Still dirty: the user backed out of Save As; keep asking about this texture.
            } else if (ModalDialogs.secondary("Don't Save")) {
                asking.poll();
                ModalDialogs.close();
                advance();
            } else if (ModalDialogs.cancel()) {
                asking.clear();
                batch.clear();
                afterClose = null;
                ModalDialogs.close();
            }
            ModalDialogs.end();
        }
    }

    private void advance() {
        TextureDocument next = asking.peek();
        if (next == null) {
            finish();
        } else if (controller.getDocuments().contains(next)) {
            controller.activate(next);
        }
    }

    private void finish() {
        List<TextureDocument> closing = List.copyOf(batch);
        Runnable then = afterClose;
        batch.clear();
        afterClose = null;
        controller.closeDocuments(closing, TextureCreatorController.CloseReason.USER);
        if (then != null) {
            then.run();
        }
    }
}

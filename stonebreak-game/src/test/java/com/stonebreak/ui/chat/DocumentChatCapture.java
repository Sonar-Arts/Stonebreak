package com.stonebreak.ui.chat;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.LegacyUiClock;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.RecordingServices;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.providers.ChatEmojiProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The candidate side of the chat's fidelity gate (#301): the shipped {@code ui/documents/chat.sbui} on a
 * {@link DocumentStage}, its {@code stonebreak:chat} data read from the same {@link ChatFixtures} chat.
 * Not a test class.
 */
public final class DocumentChatCapture implements MigrationGate.Renderer {

    private final SbuiArchive shipped;

    public DocumentChatCapture(SbuiArchive shipped) {
        this.shipped = shipped;
    }

    public static DocumentChatCapture shipped() throws java.io.IOException {
        return new DocumentChatCapture(GameUiDocuments.readScreen(ChatDocument.DOCUMENT_ID));
    }

    /** Services serving {@code chat}; its actions run on it, and are recorded. */
    public static RecordingServices services(ChatSystem chat) {
        return new RecordingServices() {
            @Override
            public ChatSystem chatSystem() {
                return chat;
            }
        };
    }

    public static DocumentStage stage(SbuiArchive sbui, ChatSystem chat, int w, int h, float uiScale) throws Exception {
        return new DocumentStage(sbui, w, h, uiScale, services(chat), MultiplayerSession.Mode.SINGLEPLAYER,
            Map.of(ChatEmojiProvider.ID, new ChatEmojiProvider()));
    }

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        FidelityCase.Viewport vp = c.viewport();
        ChatSystem chat = ChatFixtures.chat(c.variant());
        try (DocumentStage stage = stage(shipped, chat, vp.width(), vp.height(), vp.uiScale())) {
            // the stage's raster pins its own clock: back to the chat's instant (fades, the GIF's frame)
            LegacyUiClock.pin(ChatFixtures.NOW, LegacyUiClock.DEFAULT_SEED);
            stage.host.chat().poll();
            stage.settle();
            stage.paint();
            Map<String, float[]> rects = new LinkedHashMap<>();
            UiRect root = stage.rect("root");
            rects.put("window", new float[]{root.x(), root.y(), root.width(), root.height()});
            if (chat.isOpen()) {
                for (String n : new String[]{"panel", "input-well"}) {
                    UiRect r = stage.rect(n);
                    rects.put(n, new float[]{r.x(), r.y(), r.width(), r.height()});
                }
            }
            return new MigrationGate.Capture(stage.raster.capture(), rects);
        } catch (Exception e) {
            throw new IllegalStateException("could not capture the chat document for " + c.id(), e);
        } finally {
            LegacyUiClock.release();
        }
    }
}

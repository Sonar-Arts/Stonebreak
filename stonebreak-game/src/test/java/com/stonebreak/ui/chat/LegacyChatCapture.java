package com.stonebreak.ui.chat;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.stonebreak.ui.LegacyUiClock;
import com.stonebreak.ui.fidelity.LegacyUiRaster;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The legacy side of the chat's fidelity gate (#301): {@link SkijaChatRenderer} drawing a
 * {@link ChatFixtures} chat on a {@link LegacyUiRaster}. Geometry: the panel and the input well as its
 * {@code Layout} places them (open chats). Not a test class.
 */
public final class LegacyChatCapture implements MigrationGate.Renderer {

    @Override
    public MigrationGate.Capture render(FidelityCase c) {
        int w = c.viewport().width();
        int h = c.viewport().height();
        try (LegacyUiRaster raster = new LegacyUiRaster(w, h, c.viewport().uiScale())) {
            ChatSystem chat = ChatFixtures.chat(c.variant());
            SkijaChatRenderer renderer = new SkijaChatRenderer(raster.backend());
            renderer.render(chat, w, h);
            return new MigrationGate.Capture(raster.capture(), rects(chat, w, h, c.viewport().uiScale()));
        } finally {
            LegacyUiClock.release();
        }
    }

    /** The open panel and input well, by SkijaChatRenderer.Layout's arithmetic. */
    static Map<String, float[]> rects(ChatSystem chat, int w, int h, float s) {
        Map<String, float[]> out = new LinkedHashMap<>();
        out.put("window", new float[]{0, 0, w, h});
        if (chat.isOpen()) {
            float chatX = 20f * s;
            float lh = 20f * s;
            float pad = 10f * s;
            float inH = 25f * s;
            float inM = 10f * s;
            float panelH = (10 * lh) + inH + inM + pad * 2f;
            float panelX = chatX - pad;
            float panelW = w * 0.4f + pad * 2f;
            out.put("panel", new float[]{panelX, h - panelH, panelW, panelH});
            float eX = panelX + panelW - pad - inH;
            out.put("input-well", new float[]{chatX - 5f, h - inH - inM, eX - chatX - 9f + 10f, inH});
        }
        return out;
    }
}

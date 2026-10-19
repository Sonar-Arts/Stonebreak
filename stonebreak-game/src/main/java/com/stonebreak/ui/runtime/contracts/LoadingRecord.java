package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataType;
import com.stonebreak.ui.LoadingScreen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The world loading screen's progress for its document ({@code stonebreak:screen.loading} 2: #299, v2 the load's
 * phases): the running phase's name, its detail line ("Chunks 37/81", empty for none), the bar's fraction as drawn
 * (the tracker's estimate, eased) and its percentage written as the legacy screen writes it. A record so the host
 * republishes only when something changed.
 */
public record LoadingRecord(String stage, String detail, float progress) {

    public static final DataType.Obj TYPE = DataType.object("stage", DataType.string(), "detail", DataType.string(),
        "progress", DataType.number(), "percent", DataType.string());

    public static final LoadingRecord NONE = new LoadingRecord("", "", 0f);

    public static LoadingRecord of(LoadingScreen screen) {
        if (screen == null || !screen.isVisible()) {
            return NONE;
        }
        String stage = screen.getCurrentStageName();
        String detail = screen.getCurrentSubStage();
        return new LoadingRecord(stage == null ? "" : stage, detail == null ? "" : detail.trim(), screen.getProgress());
    }

    public UiValue.Obj value() {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("stage", UiValue.of(stage));
        m.put("detail", UiValue.of(detail));
        m.put("progress", SettingsContract.exact(progress));
        m.put("percent", UiValue.of(LoadingScreen.percentText(progress)));
        return new UiValue.Obj(m);
    }
}

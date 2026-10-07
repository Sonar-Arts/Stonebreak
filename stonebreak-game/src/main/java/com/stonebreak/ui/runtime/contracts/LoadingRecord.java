package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.DataType;
import com.stonebreak.ui.LoadingScreen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The world loading screen's progress for its document ({@code stonebreak:screen.loading}, #299):
 * the stage the generator last reported, the bar's fraction (stage index out of the fixed stage list,
 * as the legacy bar shows it) and its percentage written as the legacy screen writes it. A record so
 * the host republishes only when the stage changed.
 */
public record LoadingRecord(String stage, float progress) {

    public static final DataType.Obj TYPE = DataType.object("stage", DataType.string(), "progress", DataType.number(),
        "percent", DataType.string());

    public static final LoadingRecord NONE = new LoadingRecord("", 0f);

    public static LoadingRecord of(LoadingScreen screen) {
        if (screen == null || !screen.isVisible()) {
            return NONE;
        }
        String stage = screen.getCurrentStageName();
        return new LoadingRecord(stage == null ? "" : stage, screen.getProgress());
    }

    public UiValue.Obj value() {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("stage", UiValue.of(stage));
        m.put("progress", SettingsContract.exact(progress));
        m.put("percent", UiValue.of(LoadingScreen.percentText(progress)));
        return new UiValue.Obj(m);
    }
}

package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.AnimEvent;
import com.openmason.engine.format.omui.UiAnimationClip.AnimKey;
import com.openmason.engine.format.omui.UiAnimationClip.AnimTrack;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.format.omui.UiEasing;
import com.openmason.engine.format.omui.UiStyleSheet;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiDocs;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiRuntimeContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Terse clip, transition and instance builders for #295 tests. Not a test class (no {@code Test} affix). */
final class AnimDocs {

    private AnimDocs() {
    }

    static AnimKey key(double time, Object value) {
        return key(time, value, UiEasing.LINEAR);
    }

    static AnimKey key(double time, Object value, UiEasing easing) {
        return new AnimKey(time, UiDocs.value(value), easing, Map.of());
    }

    static AnimTrack track(String target, String property, AnimKey... keys) {
        return new AnimTrack(target, property, List.of(keys), Map.of());
    }

    static UiAnimationClip clip(String id, double duration, LoopMode loop, AnimTrack... tracks) {
        return new UiAnimationClip(id, duration, loop, List.of(tracks), List.of(), Map.of());
    }

    static UiAnimationClip withEvents(UiAnimationClip c, Object... timeName) {
        List<AnimEvent> events = new ArrayList<>();
        for (int i = 0; i < timeName.length; i += 2) {
            events.add(new AnimEvent(((Number) timeName[i]).doubleValue(), (String) timeName[i + 1], Map.of()));
        }
        return new UiAnimationClip(c.id(), c.duration(), c.loop(), c.tracks(), events, Map.of());
    }

    /** A rule with transitions: {@code rule(".a:hover", List.of(tr("opacity", 0.2)), "opacity", 0.5)}. */
    static UiStyleSheet.StyleRule rule(String selector, List<UiStyleSheet.StyleTransition> transitions, Object... kv) {
        Map<String, UiValue> style = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            style.put((String) kv[i], UiDocs.value(kv[i + 1]));
        }
        return new UiStyleSheet.StyleRule(selector, style, transitions, Map.of());
    }

    static UiStyleSheet.StyleTransition tr(String property, double duration) {
        return tr(property, duration, UiEasing.LINEAR, 0);
    }

    static UiStyleSheet.StyleTransition tr(String property, double duration, UiEasing easing, double delay) {
        return new UiStyleSheet.StyleTransition(property, duration, easing, delay, Map.of());
    }

    /** Instantiates {@code doc} on the basic context with styles resolved (no native layout needed). */
    static UiDocumentInstance run(OmuiArchive doc) {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        ui.resolveStyles();
        return ui;
    }

    /** One host frame of {@code dt} seconds of UI time, then the cascade. */
    static void frame(UiDocumentInstance ui, double dt) {
        ui.advanceClock(dt);
        ui.resolveStyles();
    }

    static UiValue shown(UiDocumentInstance ui, String key, String property) {
        return ui.find(key).computedStyle().get(property);
    }

    static double num(UiDocumentInstance ui, String key, String property) {
        UiValue v = shown(ui, key, property);
        return v instanceof UiValue.Num n ? n.value() : Double.NaN;
    }

    /** Records completions and events. */
    static final class Log implements UiAnimator.Listener {
        final List<String> lines = new ArrayList<>();

        @Override
        public void finished(long token, boolean stopped) {
            lines.add(stopped ? "stopped" : "done");
        }

        @Override
        public void event(long token, String name) {
            lines.add("event:" + name);
        }
    }
}

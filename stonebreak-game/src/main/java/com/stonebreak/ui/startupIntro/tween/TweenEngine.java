package com.stonebreak.ui.startupIntro.tween;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class TweenEngine {

    private final List<Tween> active = new ArrayList<>();

    public FloatTween tweenFloat(float start, float end, float duration, EasingType easing) {
        FloatTween t = new FloatTween(start, end, duration, easing);
        active.add(t);
        return t;
    }

    public void update(float deltaTime) {
        Iterator<Tween> it = active.iterator();
        while (it.hasNext()) {
            Tween t = it.next();
            t.update(deltaTime);
            if (t.isComplete()) it.remove();
        }
    }

    public void clear() {
        active.clear();
    }
}

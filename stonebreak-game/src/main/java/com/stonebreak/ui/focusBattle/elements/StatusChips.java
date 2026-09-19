package com.stonebreak.ui.focusBattle.elements;

import com.stonebreak.battle.api.BattleStatus;
import com.stonebreak.battle.api.StatusView;
import com.stonebreak.rendering.UI.masonryUI.MBadge;
import com.stonebreak.ui.focusBattle.BattlePalette;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a combatant's statuses into {@link MBadge} chips for an {@code MChipRow}: the status label
 * (with its stack count), the whole seconds remaining for timed ones, in the status's
 * {@link BattlePalette#status colour}. One instance per chip row; the badges are rebuilt only when
 * what they say changes, not every frame.
 */
public final class StatusChips {

    /** What one chip says. {@code timer} is empty for statuses that last until consumed. */
    record Spec(String label, String timer, int color) {}

    private List<Spec> shownSpecs = List.of();
    private List<MBadge> shown = List.of();

    /** The chips for this frame, in priority order (the row drops from the tail when space runs out). */
    public List<MBadge> chipsFor(List<StatusView> statuses, boolean surgeQueued) {
        List<Spec> specs = specsFor(statuses, surgeQueued);
        if (!specs.equals(shownSpecs)) {
            List<MBadge> built = new ArrayList<>(specs.size());
            for (Spec spec : specs) {
                built.add(new MBadge(spec.label()).outlined(spec.color()).trailing(spec.timer()));
            }
            shownSpecs = specs;
            shown = built;
        }
        return shown;
    }

    /**
     * The queued Martial Surge first (the queued-hit count is authoritative, so a SURGE status the
     * model also lists is not shown twice), then the statuses in model order.
     */
    static List<Spec> specsFor(List<StatusView> statuses, boolean surgeQueued) {
        List<Spec> specs = new ArrayList<>();
        if (surgeQueued) {
            // No hit count: a queued surge adds a different number of blows to a Strike than to a Flurry.
            specs.add(new Spec(BattleStatus.SURGE.label() + " ready", "", BattlePalette.FOCUS));
        }
        if (statuses == null) return specs;
        for (StatusView s : statuses) {
            if (s == null || s.status() == null) continue;
            if (surgeQueued && s.status() == BattleStatus.SURGE) continue;
            String label = s.stacks() > 1 ? s.status().label() + " x" + s.stacks() : s.status().label();
            // Ceil, so a chip never reads "0s" while the status is still active.
            String timer = s.timed() ? (int) Math.ceil(s.remainingSeconds()) + "s" : "";
            specs.add(new Spec(label, timer, BattlePalette.status(s.status())));
        }
        return specs;
    }
}

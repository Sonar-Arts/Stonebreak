package com.openmason.engine.ui.runtime.input;

import com.openmason.engine.ui.masonry.MKeys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The player's bindings from keys and controller buttons to {@link UiAction}s (#288).
 * Immutable; {@link #withKeys}/{@link #withButtons} return a remapped copy, and
 * {@link #toWire}/{@link #fromWire} round-trip it through settings. Every action-hint glyph is
 * read from here ({@link ActionHints}), so remapping a key changes the hints with it.
 *
 * <p><b>Matching.</b> A {@link KeyChord} with explicit modifiers matches only exactly those
 * (Shift+Tab is "previous", Tab alone is "next"); a chord with {@link KeyChord#ANY_MODIFIERS}
 * matches whatever is held. Exact chords are tried first. Lock-key bits (Caps/Num Lock) are
 * ignored.
 */
public final class UiActionMap {

    /** A key with the modifiers it needs. */
    public record KeyChord(int key, int modifiers) {
        /** Matches with any modifiers held. */
        public static final int ANY_MODIFIERS = -1;

        public static KeyChord any(int key) {
            return new KeyChord(key, ANY_MODIFIERS);
        }

        public static KeyChord exact(int key, int modifiers) {
            return new KeyChord(key, modifiers & MODIFIER_MASK);
        }

        boolean matches(int k, int mods) {
            return key == k && (modifiers == ANY_MODIFIERS || modifiers == (mods & MODIFIER_MASK));
        }
    }

    /** Shift, Control, Alt, Super; Caps/Num Lock are not modifiers for bindings. */
    public static final int MODIFIER_MASK = MKeys.MOD_SHIFT | MKeys.MOD_CONTROL | MKeys.MOD_ALT | MKeys.MOD_SUPER;

    private static final UiActionMap DEFAULTS = createDefaults();

    private final Map<UiAction, List<KeyChord>> keys;
    private final Map<UiAction, List<Integer>> buttons;

    private UiActionMap(Map<UiAction, List<KeyChord>> keys, Map<UiAction, List<Integer>> buttons) {
        EnumMap<UiAction, List<KeyChord>> k = new EnumMap<>(UiAction.class);
        keys.forEach((a, l) -> {
            if (!l.isEmpty()) {
                k.put(a, List.copyOf(l)); // unbound == absent, so equality ignores how it got unbound
            }
        });
        EnumMap<UiAction, List<Integer>> b = new EnumMap<>(UiAction.class);
        buttons.forEach((a, l) -> {
            if (!l.isEmpty()) {
                b.put(a, List.copyOf(l));
            }
        });
        this.keys = Collections.unmodifiableMap(k);
        this.buttons = Collections.unmodifiableMap(b);
    }

    /**
     * Arrows navigate, Tab/Shift+Tab move through the tab order, Enter/keypad Enter/Space
     * submit, Escape cancels, Page Up/Down page; controller: D-pad navigates, bumpers move
     * through the tab order, A submits, B cancels.
     */
    public static UiActionMap defaults() {
        return DEFAULTS;
    }

    private static UiActionMap createDefaults() {
        Map<UiAction, List<KeyChord>> k = new EnumMap<>(UiAction.class);
        k.put(UiAction.NAVIGATE_UP, List.of(KeyChord.any(MKeys.KEY_UP)));
        k.put(UiAction.NAVIGATE_DOWN, List.of(KeyChord.any(MKeys.KEY_DOWN)));
        k.put(UiAction.NAVIGATE_LEFT, List.of(KeyChord.any(MKeys.KEY_LEFT)));
        k.put(UiAction.NAVIGATE_RIGHT, List.of(KeyChord.any(MKeys.KEY_RIGHT)));
        k.put(UiAction.NEXT, List.of(KeyChord.exact(MKeys.KEY_TAB, 0)));
        k.put(UiAction.PREVIOUS, List.of(KeyChord.exact(MKeys.KEY_TAB, MKeys.MOD_SHIFT)));
        k.put(UiAction.SUBMIT, List.of(KeyChord.any(MKeys.KEY_ENTER), KeyChord.any(MKeys.KEY_KP_ENTER),
            KeyChord.exact(MKeys.KEY_SPACE, 0)));
        k.put(UiAction.CANCEL, List.of(KeyChord.any(MKeys.KEY_ESCAPE)));
        k.put(UiAction.PAGE_UP, List.of(KeyChord.any(MKeys.KEY_PAGE_UP)));
        k.put(UiAction.PAGE_DOWN, List.of(KeyChord.any(MKeys.KEY_PAGE_DOWN)));
        Map<UiAction, List<Integer>> b = new EnumMap<>(UiAction.class);
        b.put(UiAction.NAVIGATE_UP, List.of(GamepadButtons.DPAD_UP));
        b.put(UiAction.NAVIGATE_DOWN, List.of(GamepadButtons.DPAD_DOWN));
        b.put(UiAction.NAVIGATE_LEFT, List.of(GamepadButtons.DPAD_LEFT));
        b.put(UiAction.NAVIGATE_RIGHT, List.of(GamepadButtons.DPAD_RIGHT));
        b.put(UiAction.NEXT, List.of(GamepadButtons.RIGHT_BUMPER));
        b.put(UiAction.PREVIOUS, List.of(GamepadButtons.LEFT_BUMPER));
        b.put(UiAction.SUBMIT, List.of(GamepadButtons.A));
        b.put(UiAction.CANCEL, List.of(GamepadButtons.B));
        return new UiActionMap(k, b);
    }

    /** The action {@code key} with {@code modifiers} triggers, or null. */
    public UiAction actionForKey(int key, int modifiers) {
        UiAction loose = null;
        for (Map.Entry<UiAction, List<KeyChord>> e : keys.entrySet()) {
            for (KeyChord c : e.getValue()) {
                if (c.matches(key, modifiers)) {
                    if (c.modifiers() != KeyChord.ANY_MODIFIERS) {
                        return e.getKey();
                    }
                    if (loose == null) {
                        loose = e.getKey();
                    }
                }
            }
        }
        return loose;
    }

    /** The action a controller button triggers, or null. */
    public UiAction actionForButton(int button) {
        for (Map.Entry<UiAction, List<Integer>> e : buttons.entrySet()) {
            if (e.getValue().contains(button)) {
                return e.getKey();
            }
        }
        return null;
    }

    public List<KeyChord> keys(UiAction action) {
        return keys.getOrDefault(action, List.of());
    }

    public List<Integer> buttons(UiAction action) {
        return buttons.getOrDefault(action, List.of());
    }

    /** A copy with {@code action} bound to exactly {@code chords} (empty unbinds). */
    public UiActionMap withKeys(UiAction action, List<KeyChord> chords) {
        Map<UiAction, List<KeyChord>> k = new EnumMap<>(UiAction.class);
        k.putAll(keys);
        k.put(action, chords);
        return new UiActionMap(k, buttons);
    }

    /** A copy with {@code action} bound to exactly {@code buttonIds} (empty unbinds). */
    public UiActionMap withButtons(UiAction action, List<Integer> buttonIds) {
        Map<UiAction, List<Integer>> b = new EnumMap<>(UiAction.class);
        b.putAll(buttons);
        b.put(action, buttonIds);
        return new UiActionMap(keys, b);
    }

    // ── settings round trip ─────────────────────────────────────────────────

    /**
     * {@code {"ui.submit": ["key:257", "key:32+exact", "pad:0"], ...}}: {@code key:<glfw>} with
     * any modifiers, {@code key:<glfw>+<mods>} for an exact chord ({@code shift}, {@code ctrl},
     * {@code alt}, {@code super}, joined by {@code +}; {@code exact} alone = no modifiers), and
     * {@code pad:<button>}.
     */
    public Map<String, List<String>> toWire() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (UiAction a : UiAction.values()) {
            List<String> list = new ArrayList<>();
            for (KeyChord c : keys(a)) {
                list.add("key:" + c.key() + (c.modifiers() == KeyChord.ANY_MODIFIERS ? "" : "+" + mods(c.modifiers())));
            }
            for (int b : buttons(a)) {
                list.add("pad:" + b);
            }
            out.put(a.id(), list);
        }
        return out;
    }

    /**
     * Reads {@link #toWire} output. Actions missing from {@code wire} keep their defaults, so a
     * settings file from an older build gains new actions bound; unknown actions and malformed
     * entries are skipped.
     */
    public static UiActionMap fromWire(Map<String, List<String>> wire) {
        UiActionMap map = defaults();
        if (wire == null) {
            return map;
        }
        for (Map.Entry<String, List<String>> e : wire.entrySet()) {
            UiAction action = UiAction.fromId(e.getKey());
            if (action == null || e.getValue() == null) {
                continue;
            }
            List<KeyChord> chords = new ArrayList<>();
            List<Integer> pads = new ArrayList<>();
            for (String entry : e.getValue()) {
                parse(entry, chords, pads);
            }
            if (!e.getValue().isEmpty() && chords.isEmpty() && pads.isEmpty()) {
                continue; // nothing readable: keep the default rather than unbind (cancel must stay reachable)
            }
            map = map.withKeys(action, chords).withButtons(action, pads);
        }
        return map;
    }

    private static void parse(String entry, List<KeyChord> chords, List<Integer> pads) {
        try {
            if (entry.startsWith("pad:")) {
                pads.add(Integer.parseInt(entry.substring(4)));
            } else if (entry.startsWith("key:")) {
                String[] parts = entry.substring(4).split("\\+");
                int key = Integer.parseInt(parts[0]);
                if (parts.length == 1) {
                    chords.add(KeyChord.any(key));
                } else {
                    int mods = 0;
                    for (int i = 1; i < parts.length; i++) {
                        int bit = switch (parts[i].toLowerCase(Locale.ROOT)) {
                            case "shift" -> MKeys.MOD_SHIFT;
                            case "ctrl" -> MKeys.MOD_CONTROL;
                            case "alt" -> MKeys.MOD_ALT;
                            case "super" -> MKeys.MOD_SUPER;
                            case "exact" -> 0;
                            default -> -1;
                        };
                        if (bit < 0) {
                            return; // an unknown modifier: skip the entry instead of guessing a chord
                        }
                        mods |= bit;
                    }
                    chords.add(KeyChord.exact(key, mods));
                }
            }
        } catch (NumberFormatException ignored) {
            // malformed entries are skipped (see fromWire)
        }
    }

    static String mods(int mods) {
        if (mods == 0) {
            return "exact";
        }
        List<String> names = new ArrayList<>();
        if ((mods & MKeys.MOD_SHIFT) != 0) {
            names.add("shift");
        }
        if ((mods & MKeys.MOD_CONTROL) != 0) {
            names.add("ctrl");
        }
        if ((mods & MKeys.MOD_ALT) != 0) {
            names.add("alt");
        }
        if ((mods & MKeys.MOD_SUPER) != 0) {
            names.add("super");
        }
        return String.join("+", names);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UiActionMap m && m.keys.equals(keys) && m.buttons.equals(buttons);
    }

    @Override
    public int hashCode() {
        return keys.hashCode() * 31 + buttons.hashCode();
    }
}

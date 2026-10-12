package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataCollection;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.masonry.MStyle;
import com.stonebreak.ui.chat.ChatMessage;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.ui.chat.chatSystem.commands.ChatCommand;
import com.stonebreak.ui.chat.emoji.ChatEmoji;
import com.stonebreak.ui.chat.emoji.ChatEmojiSystem;
import com.stonebreak.ui.chat.emoji.EmojiGroup;
import com.stonebreak.ui.chat.emoji.EmojiType;
import com.stonebreak.ui.chat.emoji.GifEmojiType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The chat ({@code stonebreak:chat} 1, #301): what {@link ChatSystem} (still the controller: keyboard
 * editing, completion, history, commands, timing) shows, as the legacy renderer drew it.
 * <ul>
 *   <li>root {@code chat}: {@code open}, {@code tab} (chat, commands), {@code display} (the input with
 *       its blinking {@code _}), {@code input}, {@code ghost} (the completion after the caret),
 *       {@code scroll}/{@code maxScroll} (lines), {@code commandScroll}/{@code maxCommandScroll},
 *       {@code commandCount}, {@code pickerOpen};</li>
 *   <li>collection {@code chatLines} (identity {@code line}), bottom to top (the legacy paint order: a
 *       line's emoji reaches into the line above, whose backdrop covers it): each line's {@code color},
 *       its closed-chat {@code shadow}, its open-chat backdrop {@code fill} and divider
 *       ({@code divider}, {@code dividerColor}: a new message starts below), already faded, and its
 *       {@code runs} (text, or an {@code emoji} id for a {@code [token]}); a faded line is left out;</li>
 *   <li>collection {@code chatCommands} (identity {@code command}): {@code label} ({@code /name}) and
 *       {@code description}, by name;</li>
 *   <li>root {@code emojiPicker}: {@code sections} (Favorites, Recent, then each group), each with its
 *       {@code emojis} ({@code emoji} id, {@code favorite}).</li>
 * </ul>
 * Actions {@code stonebreak:chat.tab {tab}}, {@code .command {name}} (fills {@code /name } and shows
 * the chat tab, as the legacy buttons did), {@code .scroll {delta}}, {@code .scroll-to {offset}},
 * {@code .emoji-toggle}, {@code .emoji-insert {emoji}}, {@code .emoji-favorite {emoji}},
 * {@code .picker-close}; each refuses unless the chat is open.
 */
public final class ChatContracts {

    public static final HostContract CHAT = HostContract.of("stonebreak:chat", 1);

    public static final DataType.Obj STATE = DataType.object("open", DataType.bool(), "tab", DataType.string(),
        "display", DataType.string(), "input", DataType.string(), "ghost", DataType.string(),
        "scroll", DataType.integer(), "maxScroll", DataType.integer(), "commandScroll", DataType.integer(),
        "maxCommandScroll", DataType.integer(), "commandCount", DataType.integer(), "pickerOpen", DataType.bool());
    private static final DataType.ListOf RUNS = DataType.list(DataType.object("run", DataType.string(),
        "text", DataType.string(), "emoji", DataType.string()), "run");
    public static final DataType.ListOf LINES = DataType.list(DataType.object("line", DataType.string(),
        "color", DataType.string(), "shadow", DataType.string(), "fill", DataType.string(),
        "divider", DataType.bool(), "dividerColor", DataType.string(), "runs", RUNS), "line");
    public static final DataType.ListOf COMMANDS = DataType.list(DataType.object("command", DataType.string(),
        "label", DataType.string(), "description", DataType.string()), "command");
    private static final DataType.ListOf EMOJIS = DataType.list(DataType.object("emoji", DataType.string(),
        "favorite", DataType.bool()), "emoji");
    public static final DataType.Obj PICKER = DataType.object("sections", DataType.list(DataType.object(
        "section", DataType.string(), "label", DataType.string(), "empty", DataType.bool(), "emojis", EMOJIS),
        "section"));

    private static final int TRANSPARENT = 0;

    /** The game behind the chat. */
    public interface Services {
        /** The game's chat, or null. */
        default ChatSystem chatSystem() {
            return null;
        }
    }

    private final Services services;
    private final DataCell state;
    private final DataCollection lines = new DataCollection(LINES);
    private final DataCollection commands = new DataCollection(COMMANDS);
    private final DataCell picker;
    private UiValue lastState;
    private List<UiValue> lastLines = List.of();
    private List<UiValue> lastCommands = List.of();
    private UiValue lastPicker;

    public ChatContracts(UiHost ui, Services services) {
        this.services = services;
        state = ui.data().register("chat", new DataCell(STATE, state(null)), CHAT);
        ui.data().register("chatLines", lines, CHAT);
        ui.data().register("chatCommands", commands, CHAT);
        picker = ui.data().register("emojiPicker", new DataCell(PICKER, picker(null)), CHAT);
        action(ui, "tab", DataType.object("tab", DataType.string()), (c, a) -> {
            c.setCurrentTab("commands".equals(str(a, "tab")) ? ChatSystem.ChatTab.COMMANDS : ChatSystem.ChatTab.CHAT);
            return null;
        });
        action(ui, "command", DataType.object("name", DataType.string()), (c, a) -> {
            c.setInput("/" + str(a, "name") + " ");
            c.setCurrentTab(ChatSystem.ChatTab.CHAT);
            return null;
        });
        action(ui, "scroll", DataType.object("delta", DataType.number()), (c, a) -> {
            c.handleScroll(num(a, "delta"));
            return null;
        });
        action(ui, "scroll-to", DataType.object("offset", DataType.number()), (c, a) -> {
            boolean chat = c.getCurrentTab() == ChatSystem.ChatTab.CHAT;
            int delta = (int) num(a, "offset") - (chat ? c.getScrollOffset() : c.getCommandScrollOffset());
            int sign = delta > 0 ? 1 : -1; // the legacy drag steps the scroll one line at a time
            for (int i = 0; i < Math.abs(delta); i++) {
                c.handleScroll(sign);
            }
            return null;
        });
        action(ui, "emoji-toggle", null, (c, a) -> {
            c.toggleEmojiPicker();
            return null;
        });
        action(ui, "emoji-insert", DataType.object("emoji", DataType.string()), (c, a) -> {
            ChatEmoji e = emoji(str(a, "emoji"));
            if (e == null) {
                return "no emoji " + str(a, "emoji");
            }
            c.insertEmoji(e);
            return null;
        });
        action(ui, "emoji-favorite", DataType.object("emoji", DataType.string()), (c, a) -> {
            ChatEmoji e = emoji(str(a, "emoji"));
            if (e == null) {
                return "no emoji " + str(a, "emoji");
            }
            c.getEmojiSystem().toggleFavorite(e);
            return null;
        });
        action(ui, "picker-close", null, (c, a) -> {
            c.closeEmojiPicker();
            return null;
        });
    }

    /** UI thread: right before the chat paints (lines fade on the wall clock), and once per frame. */
    public void poll() {
        ChatSystem c = services.chatSystem();
        UiValue s = state(c);
        if (!s.equals(lastState)) {
            lastState = s;
            state.set(s);
        }
        List<UiValue> l = lines(c);
        if (!l.equals(lastLines)) {
            lastLines = l;
            lines.setAll(l);
        }
        List<UiValue> cmds = commands(c);
        if (!cmds.equals(lastCommands)) {
            lastCommands = cmds;
            commands.setAll(cmds);
        }
        UiValue p = picker(c == null ? null : c.getEmojiSystem());
        if (!p.equals(lastPicker)) {
            lastPicker = p;
            picker.set(p);
        }
    }

    /** True while the chat has anything to show (open, or a closed chat's lines still fading). */
    public static boolean shows(ChatSystem c) {
        return c != null && (c.isOpen() || !c.getVisibleMessages().isEmpty());
    }

    private static UiValue state(ChatSystem c) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        boolean open = c != null && c.isOpen();
        m.put("open", UiValue.of(open));
        m.put("tab", UiValue.of(c != null && c.getCurrentTab() == ChatSystem.ChatTab.COMMANDS ? "commands" : "chat"));
        m.put("display", UiValue.of(c == null ? "" : c.getDisplayInput()));
        m.put("input", UiValue.of(c == null ? "" : c.getCurrentInput()));
        m.put("ghost", UiValue.of(c == null ? "" : c.getGhostText()));
        m.put("scroll", UiValue.of(c == null ? 0 : c.getScrollOffset()));
        m.put("maxScroll", UiValue.of(c == null ? 0 : c.getMaxScroll()));
        m.put("commandScroll", UiValue.of(c == null ? 0 : c.getCommandScrollOffset()));
        m.put("maxCommandScroll", UiValue.of(c == null ? 0 : c.getMaxCommandScroll()));
        m.put("commandCount", UiValue.of(c == null ? 0 : c.getCommandExecutor().getCommands().size()));
        m.put("pickerOpen", UiValue.of(c != null && c.isEmojiPickerOpen()));
        return new UiValue.Obj(m);
    }

    /** The visible lines, faded and coloured exactly as the legacy renderer drew them, bottom to top. */
    static List<UiValue> lines(ChatSystem c) {
        if (c == null) {
            return List.of();
        }
        boolean open = c.isOpen();
        List<ChatMessage> visible = c.getVisibleMessages();
        List<UiValue> bottomUp = new ArrayList<>();
        long previousId = -1L;
        for (int i = visible.size() - 1; i >= 0; i--) {
            ChatMessage msg = visible.get(i);
            float alpha = msg.getAlpha(open);
            if (alpha <= 0f) {
                continue;
            }
            boolean divider = open && previousId != -1L && previousId != msg.getMessageId();
            Map<String, UiValue> m = new LinkedHashMap<>();
            m.put("line", UiValue.of(Integer.toHexString(System.identityHashCode(msg))));
            m.put("color", CrosshairContracts.hex(argb(msg.getColor(), alpha)));
            m.put("shadow", CrosshairContracts.hex(open ? TRANSPARENT
                : withAlpha(MStyle.TEXT_SHADOW, alpha * 0.6f)));
            m.put("fill", CrosshairContracts.hex(open
                ? withAlpha(MStyle.PANEL_FILL_DEEP, alpha * 0.35f) : TRANSPARENT));
            m.put("divider", UiValue.of(divider));
            m.put("dividerColor", CrosshairContracts.hex(divider
                ? withAlpha(MStyle.PANEL_HIGHLIGHT, alpha * 0.45f) : TRANSPARENT));
            m.put("runs", runs(msg.getText()));
            bottomUp.add(new UiValue.Obj(m));
            previousId = msg.getMessageId();
        }
        return bottomUp;
    }

    /** Text and {@code [token]} emoji runs, found exactly as the legacy {@code drawMixedLine} finds them. */
    static UiValue runs(String text) {
        List<UiValue> out = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int nextStart = -1;
            ChatEmoji next = null;
            for (ChatEmoji e : allEmojis()) {
                int idx = text.indexOf(e.getToken(), pos);
                if (idx >= 0 && (nextStart < 0 || idx < nextStart)) {
                    nextStart = idx;
                    next = e;
                }
            }
            if (nextStart < 0) {
                out.add(run(out.size(), text.substring(pos), ""));
                break;
            }
            if (nextStart > pos) {
                out.add(run(out.size(), text.substring(pos, nextStart), ""));
            }
            out.add(run(out.size(), "", next.getId()));
            pos = nextStart + next.getToken().length();
        }
        return new UiValue.Arr(out);
    }

    private static UiValue run(int index, String text, String emoji) {
        return new UiValue.Obj(Map.of("run", UiValue.of(String.valueOf(index)), "text", UiValue.of(text),
            "emoji", UiValue.of(emoji)));
    }

    private static List<UiValue> commands(ChatSystem c) {
        if (c == null) {
            return List.of();
        }
        List<Map.Entry<String, ChatCommand>> sorted = new ArrayList<>(c.getCommandExecutor().getCommands().entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        List<UiValue> out = new ArrayList<>(sorted.size());
        for (Map.Entry<String, ChatCommand> e : sorted) {
            String desc = e.getValue().getDescription();
            out.add(new UiValue.Obj(Map.of("command", UiValue.of(e.getKey()), "label", UiValue.of("/" + e.getKey()),
                "description", UiValue.of(desc == null ? "" : desc))));
        }
        return out;
    }

    /** Favorites, Recent, then each group with emojis: the legacy picker's sections. */
    static UiValue picker(ChatEmojiSystem state) {
        List<ChatEmoji> favorites = state == null ? List.of() : new ArrayList<>(state.getFavorites());
        List<ChatEmoji> recent = state == null ? List.of() : state.getRecentlyUsed();
        List<UiValue> sections = new ArrayList<>();
        sections.add(section("favorites", "Favorites", favorites, state));
        sections.add(section("recent", "Recent", recent, state));
        for (EmojiGroup group : EmojiGroup.values()) {
            List<ChatEmoji> in = allEmojis().stream().filter(e -> e.getGroup() == group).toList();
            if (!in.isEmpty()) {
                sections.add(section(group.name().toLowerCase(java.util.Locale.ROOT), group.label, in, state));
            }
        }
        return new UiValue.Obj(Map.of("sections", new UiValue.Arr(sections)));
    }

    private static UiValue section(String id, String label, List<ChatEmoji> emojis, ChatEmojiSystem state) {
        List<UiValue> cells = new ArrayList<>(emojis.size());
        for (ChatEmoji e : emojis) {
            cells.add(new UiValue.Obj(Map.of("emoji", UiValue.of(e.getId()),
                "favorite", UiValue.of(state != null && state.getFavorites().contains(e)))));
        }
        return new UiValue.Obj(Map.of("section", UiValue.of(id), "label", UiValue.of(label),
            "empty", UiValue.of(emojis.isEmpty()), "emojis", new UiValue.Arr(cells)));
    }

    private static List<ChatEmoji> allEmojis() {
        List<ChatEmoji> all = new ArrayList<>();
        Collections.addAll(all, EmojiType.values());
        Collections.addAll(all, GifEmojiType.values());
        return all;
    }

    /** The emoji with {@code id}, or null. */
    public static ChatEmoji emoji(String id) {
        for (ChatEmoji e : allEmojis()) {
            if (e.getId().equals(id)) {
                return e;
            }
        }
        return null;
    }

    private void action(UiHost ui, String name, DataType.Obj params,
                        java.util.function.BiFunction<ChatSystem, UiValue.Obj, String> rule) {
        ui.actions().register(ActionSpec.of(CHAT.id() + "." + name, CHAT, params, DataType.ANY), (args, ctx) -> {
            ChatSystem c = services.chatSystem();
            String problem = c == null || !c.isOpen() ? "the chat is not open"
                : rule.apply(c, args instanceof UiValue.Obj o ? o : new UiValue.Obj(Map.of()));
            poll();
            return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
                : CompletableFuture.failedFuture(new IllegalStateException(problem));
        });
    }

    private static String str(UiValue.Obj a, String k) {
        return a.get(k) instanceof UiValue.Str s ? s.value() : "";
    }

    private static double num(UiValue.Obj a, String k) {
        return a.get(k) instanceof UiValue.Num n ? n.value() : 0;
    }

    /** A message colour at {@code alphaScale} (ARGB), each channel rounded and clamped. */
    static int argb(float[] rgba, float alphaScale) {
        int a = clamp255(Math.round(rgba[3] * alphaScale * 255f));
        int r = clamp255(Math.round(rgba[0] * 255f));
        int g = clamp255(Math.round(rgba[1] * 255f));
        int b = clamp255(Math.round(rgba[2] * 255f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** {@code argb} with its alpha scaled by {@code alphaScale}, rounded and clamped. */
    static int withAlpha(int argb, float alphaScale) {
        int a = clamp255(Math.round(((argb >>> 24) & 0xFF) * alphaScale));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}

package com.stonebreak.ui.runtime.contracts;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.ActionSpec;
import com.openmason.engine.ui.data.DataCell;
import com.openmason.engine.ui.data.DataType;
import com.openmason.engine.ui.data.HostContract;
import com.openmason.engine.ui.data.UiHost;
import com.stonebreak.ui.multiplayerMenu.HostWorldScreen;
import com.stonebreak.ui.multiplayerMenu.JoinWorldScreen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The multiplayer screens' host contracts (#299): the menu's three choices, the host-a-world form
 * and the join form. The legacy screen objects stay the controllers (their validation, Settings
 * persistence and session calls run unchanged); these roots mirror their state and these actions
 * call them.
 *
 * <table>
 *   <caption>Contracts</caption>
 *   <tr><td>{@code stonebreak:screen.multiplayer} 1</td><td>{@code .host}, {@code .join}, {@code .back}</td></tr>
 *   <tr><td>{@code stonebreak:screen.host-world} 1</td><td>root {@code hostWorld} ({@code worlds} = the first
 *       eight, {@code selected}, {@code port}, {@code status}, {@code empty}); {@code .select {index}},
 *       {@code .start {port}} → {@code {status}}, {@code .back}</td></tr>
 *   <tr><td>{@code stonebreak:screen.join-world} 1</td><td>root {@code joinWorld} ({@code host}, {@code port},
 *       {@code username}, {@code status}); {@code .connect {host, port, username}} → {@code {status, username}},
 *       {@code .back}</td></tr>
 * </table>
 */
public final class MultiplayerContracts {

    public static final HostContract MENU = HostContract.of("stonebreak:screen.multiplayer", 1);
    public static final HostContract HOST = HostContract.of("stonebreak:screen.host-world", 1);
    public static final HostContract JOIN = HostContract.of("stonebreak:screen.join-world", 1);

    /** The most worlds the host list shows (the legacy screen has no scrolling). */
    public static final int MAX_WORLDS = 8;

    static final DataType.Obj WORLD = DataType.object("id", DataType.string(), "index", DataType.integer(),
        "name", DataType.string(), "selected", DataType.bool());
    public static final DataType.Obj HOST_TYPE = DataType.object("worlds", DataType.list(WORLD, "id"),
        "selected", DataType.integer(), "port", DataType.string(), "status", DataType.string(), "empty", DataType.bool());
    public static final DataType.Obj JOIN_TYPE = DataType.object("host", DataType.string(), "port", DataType.string(),
        "username", DataType.string(), "status", DataType.string());

    /** The game behind these screens; every method returns null on success or why it refused. */
    public interface Services {
        /** {@code host}, {@code join} or {@code back} from the multiplayer menu. */
        default String multiplayerChoice(String choice) {
            return "no multiplayer menu is showing";
        }

        /** Back from the host or join screen to the multiplayer menu. */
        default String multiplayerBack() {
            return "no multiplayer screen is showing";
        }

        /** The showing host-a-world screen (its state), or null. */
        default HostWorldScreen hostWorld() {
            return null;
        }

        default String hostSelect(int index) {
            return "no host screen is showing";
        }

        /** @return the status line after Start Hosting (empty once hosting started) */
        default String hostStart(String port) {
            throw new IllegalStateException("no host screen is showing");
        }

        /** The showing join screen (its state), or null. */
        default JoinWorldScreen joinWorld() {
            return null;
        }

        /** @return the status line after Connect */
        default String joinConnect(String host, String port, String username) {
            throw new IllegalStateException("no join screen is showing");
        }
    }

    private final Services services;
    private final DataCell host;
    private final DataCell join;
    private UiValue lastHost;
    private UiValue lastJoin;

    public MultiplayerContracts(UiHost ui, Services services) {
        this.services = services;
        host = ui.data().register("hostWorld", new DataCell(HOST_TYPE, hostValue(null)), HOST);
        join = ui.data().register("joinWorld", new DataCell(JOIN_TYPE, joinValue(null)), JOIN);
        for (String choice : List.of("host", "join", "back")) {
            action(ui, ActionSpec.of(MENU.id() + "." + choice, MENU, null, DataType.ANY),
                args -> refusal(services.multiplayerChoice(choice)));
        }
        action(ui, ActionSpec.of(HOST.id() + ".back", HOST, null, DataType.ANY), args -> refusal(services.multiplayerBack()));
        action(ui, ActionSpec.of(JOIN.id() + ".back", JOIN, null, DataType.ANY), args -> refusal(services.multiplayerBack()));
        action(ui, ActionSpec.of(HOST.id() + ".select", HOST, DataType.object("index", DataType.integer()), DataType.ANY)
            .withReentrancy(ActionSpec.Reentrancy.PARALLEL), args -> refusal(services.hostSelect(integer(args, "index"))));
        action(ui, ActionSpec.of(HOST.id() + ".start", HOST, DataType.object("port", DataType.string()),
            DataType.object("status", DataType.string())), args -> {
                String status = services.hostStart(str(args, "port"));
                return CompletableFuture.completedFuture(new UiValue.Obj(Map.of("status", UiValue.of(status))));
            });
        action(ui, ActionSpec.of(JOIN.id() + ".connect", JOIN, DataType.object("host", DataType.string(),
            "port", DataType.string(), "username", DataType.string()), DataType.object("status", DataType.string(),
            "username", DataType.string())), args -> {
                String status = services.joinConnect(str(args, "host"), str(args, "port"), str(args, "username"));
                JoinWorldScreen screen = services.joinWorld();
                String user = screen != null ? screen.userText() : str(args, "username");
                return CompletableFuture.completedFuture(new UiValue.Obj(Map.of("status", UiValue.of(status),
                    "username", UiValue.of(user == null ? "" : user))));
            });
    }

    /** UI thread, once per frame: republishes the screens' state when it changed. */
    public void poll() {
        UiValue h = hostValue(services.hostWorld());
        if (!h.equals(lastHost)) {
            lastHost = h;
            host.set(h);
        }
        UiValue j = joinValue(services.joinWorld());
        if (!j.equals(lastJoin)) {
            lastJoin = j;
            join.set(j);
        }
    }

    static UiValue.Obj hostValue(HostWorldScreen s) {
        List<UiValue> rows = new ArrayList<>();
        List<String> worlds = s == null ? List.of() : s.worlds();
        for (int i = 0; i < Math.min(worlds.size(), MAX_WORLDS); i++) {
            rows.add(new UiValue.Obj(Map.of("id", UiValue.of(worlds.get(i)), "index", UiValue.of(i),
                "name", UiValue.of(worlds.get(i)), "selected", UiValue.of(i == s.selectedWorld()))));
        }
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("worlds", new UiValue.Arr(rows));
        m.put("selected", UiValue.of(s == null ? -1 : s.selectedWorld()));
        m.put("port", UiValue.of(s == null || s.portText() == null ? "" : s.portText()));
        m.put("status", UiValue.of(s == null ? "" : s.statusMessage()));
        m.put("empty", UiValue.of(worlds.isEmpty()));
        return new UiValue.Obj(m);
    }

    static UiValue.Obj joinValue(JoinWorldScreen s) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        m.put("host", UiValue.of(s == null || s.hostText() == null ? "" : s.hostText()));
        m.put("port", UiValue.of(s == null || s.portText() == null ? "" : s.portText()));
        m.put("username", UiValue.of(s == null || s.userText() == null ? "" : s.userText()));
        m.put("status", UiValue.of(s == null ? "" : s.statusMessage()));
        return new UiValue.Obj(m);
    }

    private interface Rule {
        CompletableFuture<UiValue> run(UiValue.Obj args);
    }

    private void action(UiHost ui, ActionSpec spec, Rule rule) {
        ui.actions().register(spec, (args, ctx) -> {
            try {
                CompletableFuture<UiValue> f = rule.run(args);
                poll(); // the document sees the result before the next frame
                return f;
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    private static CompletableFuture<UiValue> refusal(String problem) {
        return problem == null ? CompletableFuture.completedFuture(UiValue.NULL)
            : CompletableFuture.failedFuture(new IllegalStateException(problem));
    }

    private static String str(UiValue.Obj args, String k) {
        return args != null && args.get(k) instanceof UiValue.Str s ? s.value() : "";
    }

    private static int integer(UiValue.Obj args, String k) {
        return args != null && args.get(k) instanceof UiValue.Num n ? (int) n.value() : -1;
    }
}

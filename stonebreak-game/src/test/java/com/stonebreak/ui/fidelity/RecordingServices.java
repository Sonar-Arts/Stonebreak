package com.stonebreak.ui.fidelity;

import com.openmason.engine.format.omui.UiValue;
import com.stonebreak.ui.runtime.GameUiHost;
import com.stonebreak.ui.runtime.contracts.SettingsContract;
import com.stonebreak.ui.runtime.contracts.StatsRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Host services for document fidelity stages (#299): every screen action is accepted and only
 * remembered, by its host action id, in call order. Not a test class.
 */
public class RecordingServices implements GameUiHost.Services {

    public final List<String> calls = new ArrayList<>();

    /** The main menu ({@code mainMenu}: splash and title motion), or null. */
    public com.stonebreak.ui.MainMenu mainMenu;

    @Override
    public com.stonebreak.ui.MainMenu mainMenu() {
        return mainMenu;
    }

    /** The window the stage lays out in (the main menu's splash pivot). */
    public int[] window = {1920, 1080};

    @Override
    public int[] menuWindow() {
        return window;
    }

    @Override
    public float menuScale() {
        return com.stonebreak.config.Settings.getInstance().getUiScale();
    }

    @Override
    public String mainMenuChoose(int index) {
        calls.add("stonebreak:screen.main-menu." + com.stonebreak.ui.runtime.contracts.MainMenuContracts.CHOICES.get(index));
        return null;
    }

    @Override
    public String mainMenuTitle() {
        calls.add("stonebreak:screen.main-menu.title");
        return null;
    }

    /** The multiplayer screens' state ({@code hostWorld} / {@code joinWorld}), or null. */
    public com.stonebreak.ui.multiplayerMenu.HostWorldScreen hostWorld;
    public com.stonebreak.ui.multiplayerMenu.JoinWorldScreen joinWorld;
    /** What a host/join Back records (the screen the stage shows). */
    public String multiplayerBack = "stonebreak:screen.host-world.back";

    @Override
    public String multiplayerChoice(String choice) {
        calls.add("stonebreak:screen.multiplayer." + choice);
        return null;
    }

    @Override
    public String multiplayerBack() {
        calls.add(multiplayerBack);
        return null;
    }

    @Override
    public com.stonebreak.ui.multiplayerMenu.HostWorldScreen hostWorld() {
        return hostWorld;
    }

    @Override
    public String hostSelect(int index) {
        calls.add("stonebreak:screen.host-world.select " + index);
        return hostWorld != null && hostWorld.selectWorld(index) ? null : "no world row " + index;
    }

    @Override
    public String hostStart(String port) {
        calls.add("stonebreak:screen.host-world.start");
        return "";
    }

    @Override
    public com.stonebreak.ui.multiplayerMenu.JoinWorldScreen joinWorld() {
        return joinWorld;
    }

    @Override
    public String joinConnect(String host, String port, String username) {
        calls.add("stonebreak:screen.join-world.connect");
        return "";
    }

    /** The world select screen ({@code worldSelect}), or null. */
    public com.stonebreak.ui.worldSelect.WorldSelectScreen worldSelect;
    /**
     * World select actions that also run on {@link #worldSelect} (the rest are only recorded: they
     * would change the game state, touch the save folder or open a file browser). Pointer-rest
     * reports ({@code hover}, {@code hover-card}) are applied but never recorded.
     */
    public java.util.Set<String> worldSelectLive = new java.util.HashSet<>(
        java.util.List.of("select", "hover", "hover-card", "wheel", "move"));

    @Override
    public com.stonebreak.ui.worldSelect.WorldSelectScreen worldSelect() {
        return worldSelect;
    }

    @Override
    public String worldSelectAction(String name, double arg) {
        if (!name.startsWith("hover")) {
            boolean numeric = com.stonebreak.ui.runtime.contracts.WorldSelectContracts.NUMERIC.containsKey(name);
            calls.add("stonebreak:screen.world-select." + name + (numeric ? " " + (int) arg : ""));
        }
        return worldSelectLive.contains(name)
            ? com.stonebreak.ui.runtime.contracts.WorldSelectContracts.perform(worldSelect, name, arg) : null;
    }

    /** The settings menu ({@code settingsMenu}), or null. */
    public com.stonebreak.ui.settingsMenu.SettingsMenu settingsMenu;
    /**
     * Settings actions that also run on {@link #settingsMenu} (none by default: a press would flip
     * values or change the game state). Presses, scrollbar grabs, keys and wheel ticks are recorded
     * ({@code press <target>}, {@code key <code>}); drags and releases are not.
     */
    public java.util.Set<String> settingsLive = new java.util.HashSet<>();

    @Override
    public com.stonebreak.ui.settingsMenu.SettingsMenu settingsMenu() {
        return settingsMenu;
    }

    @Override
    public String settingsAction(String name, UiValue.Obj args) {
        String id = "stonebreak:screen.settings." + name;
        switch (name) {
            case "press" -> calls.add(id + " " + ((UiValue.Str) args.get("target")).value());
            case "key" -> calls.add(id + " " + (int) ((UiValue.Num) args.get("key")).value());
            case "scrollbar", "wheel" -> calls.add(id);
            default -> { }
        }
        return settingsLive.contains(name)
            ? com.stonebreak.ui.runtime.contracts.SettingsMenuContracts.perform(settingsMenu, name, args) : null;
    }

    /** What {@code stonebreak:screen.loading} publishes. */
    public com.stonebreak.ui.runtime.contracts.LoadingRecord loading =
        com.stonebreak.ui.runtime.contracts.LoadingRecord.NONE;

    @Override
    public com.stonebreak.ui.runtime.contracts.LoadingRecord loading() {
        return loading;
    }

    /** The open glossary ({@code stonebreak:screen.glossary}), or null. */
    public com.stonebreak.ui.glossaryScreen.GlossaryScreen glossary;

    /** What {@code stonebreak:player.stats} publishes. */
    public StatsRecord stats = StatsRecord.NONE;

    @Override public void resume() { calls.add("stonebreak:screen.pause.resume"); }
    @Override public void openStatistics() { calls.add("stonebreak:screen.pause.statistics"); }
    @Override public void openGlossary() { calls.add("stonebreak:screen.pause.glossary"); }
    @Override public void openSettings() { calls.add("stonebreak:screen.pause.settings"); }
    @Override public void quitToMenu() { calls.add("stonebreak:screen.pause.quit"); }

    @Override
    public int resync() {
        calls.add("stonebreak:network.resync");
        return 7;
    }

    @Override
    public String respawn() {
        calls.add("stonebreak:screen.death.respawn");
        return null;
    }

    @Override
    public StatsRecord stats() {
        return stats;
    }

    @Override
    public com.stonebreak.ui.glossaryScreen.GlossaryScreen glossaryScreen() {
        return glossary;
    }

    @Override
    public String closeGlossary() {
        calls.add("stonebreak:screen.glossary.back");
        return null;
    }

    @Override
    public String closeStatistics() {
        calls.add("stonebreak:screen.statistics.back");
        return null;
    }

    @Override
    public UiValue.Obj settings() {
        return SettingsContract.read(com.stonebreak.config.Settings.getInstance());
    }

    @Override
    public void applySettings(UiValue.Obj value) {
        calls.add("stonebreak:settings.apply");
    }
}

package com.stonebreak.ui.multiplayerMenu;

import com.stonebreak.config.Settings;
import com.stonebreak.network.MultiplayerSession;
import com.stonebreak.ui.worldSelect.managers.WorldDiscoveryManager;
import com.stonebreak.world.save.model.WorldData;

import java.util.List;
import java.util.Random;

/**
 * Host-a-world screen: pick an existing world from the list, set a TCP port, and start the
 * integrated server. Shown as the shipped UI document {@code host_world}; this keeps the screen's
 * state, which the UI host publishes, and the rules its actions run ({@link #selectWorld},
 * {@link #startHosting(String)}).
 */
public final class HostWorldScreen {

    private static final int MAX_VISIBLE_WORLDS = 8;

    private final WorldDiscoveryManager discovery = new WorldDiscoveryManager();

    private List<String> worlds = List.of();
    private int selectedWorld = -1;
    private String portText = "25565";
    private String statusMessage = "";

    /** Where {@link #onShow} finds the worlds (the save folder; fixtures pin a list). */
    private java.util.function.Supplier<List<String>> worldSource = discovery::discoverWorlds;

    public void setWorldSource(java.util.function.Supplier<List<String>> source) {
        this.worldSource = source == null ? discovery::discoverWorlds : source;
    }

    // ── state the UI host publishes and its actions change (#299) ──

    public List<String> worlds() { return worlds; }
    public int selectedWorld() { return selectedWorld; }
    public String portText() { return portText; }
    public String statusMessage() { return statusMessage; }

    /** @return false when there is no such row (only the first {@value #MAX_VISIBLE_WORLDS} are shown) */
    public boolean selectWorld(int index) {
        if (index < 0 || index >= Math.min(worlds.size(), MAX_VISIBLE_WORLDS)) {
            return false;
        }
        selectedWorld = index;
        return true;
    }

    /**
     * Start Hosting with the port as typed (digits only, at most 5, as the field accepts them).
     *
     * @return the status line the screen shows afterwards ("" once hosting started)
     */
    public String startHosting(String typedPort) {
        StringBuilder digits = new StringBuilder();
        for (char ch : (typedPort == null ? "" : typedPort).toCharArray()) {
            if (ch >= '0' && ch <= '9' && digits.length() < 5) {
                digits.append(ch);
            }
        }
        portText = digits.toString();
        statusMessage = "";
        startHosting();
        return statusMessage;
    }

    /** Sets the port field (fixtures). */
    public void setPortText(String text) { portText = text; }

    public void onShow() {
        worlds = worldSource.get();
        selectedWorld = worlds.isEmpty() ? -1 : 0;
        portText = String.valueOf(Settings.getInstance().getMultiplayerPort());
        statusMessage = "";
    }

    private void startHosting() {
        if (selectedWorld < 0 || selectedWorld >= worlds.size()) {
            statusMessage = "Pick a world first.";
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            statusMessage = "Invalid port (1-65535).";
            return;
        }
        Settings.getInstance().setMultiplayerPort(port);
        Settings.getInstance().saveSettings();

        String worldName = worlds.get(selectedWorld);
        WorldData wd = discovery.getWorldData(worldName);
        long seed = (wd != null && wd.getSeed() != 0) ? wd.getSeed() : new Random().nextLong();

        // Two-world model: start the integrated server (Local + TCP) which loads/persists the
        // authoritative world, then the in-process local client which builds the render world.
        MultiplayerSession.startHosting(worldName, seed, port);
    }
}

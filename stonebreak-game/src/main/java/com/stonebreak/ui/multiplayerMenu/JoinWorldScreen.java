package com.stonebreak.ui.multiplayerMenu;

import com.stonebreak.config.Settings;
import com.stonebreak.network.MultiplayerSession;

/**
 * Join screen: enter host:port + username, then connect. Shown as the shipped UI document
 * {@code join_world}; this keeps the fields' last values, which the UI host publishes, and the rule
 * its Connect action runs ({@link #connect(String, String, String)}).
 */
public final class JoinWorldScreen {

    private String hostText;
    private String portText;
    private String userText;
    private String statusMessage = "";

    // ── state the UI host publishes and its actions change (#299) ──

    public String hostText() { return hostText; }
    public String portText() { return portText; }
    public String userText() { return userText; }
    public String statusMessage() { return statusMessage; }

    /** Sets the fields as typed (fixtures). */
    public void setFields(String host, String port, String user) {
        hostText = host;
        portText = port;
        userText = user;
    }

    /**
     * Connect with the fields as typed, cut to what the fields accept (host 64, port 5 digits,
     * username 24).
     *
     * @return the status line the screen shows afterwards
     */
    public String connect(String host, String port, String user) {
        hostText = clip(host, 64, false);
        portText = clip(port, 5, true);
        userText = clip(user, 24, false);
        statusMessage = "";
        connect();
        return statusMessage;
    }

    private static String clip(String text, int max, boolean digitsOnly) {
        StringBuilder out = new StringBuilder();
        for (char ch : (text == null ? "" : text).toCharArray()) {
            if (out.length() >= max) break;
            if (ch < 32 || ch == 127 || digitsOnly && (ch < '0' || ch > '9')) continue;
            out.append(ch);
        }
        return out.toString();
    }

    public void onShow() {
        Settings s = Settings.getInstance();
        hostText = s.getLastJoinHost();
        portText = String.valueOf(s.getMultiplayerPort());
        userText = s.getMultiplayerUsername();
        statusMessage = "";
    }

    private void connect() {
        int port;
        try {
            port = Integer.parseInt(portText.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            statusMessage = "Invalid port.";
            return;
        }
        if (hostText.isBlank()) { statusMessage = "Host required."; return; }
        if (userText.isBlank()) userText = "Player";

        Settings s = Settings.getInstance();
        s.setLastJoinHost(hostText);
        s.setMultiplayerPort(port);
        s.setMultiplayerUsername(userText);
        s.saveSettings();

        try {
            MultiplayerSession.joinServer(hostText.trim(), port, userText.trim());
            statusMessage = "Connecting to " + hostText + ":" + port + " ...";
        } catch (Exception ex) {
            statusMessage = "Connect failed: " + ex.getMessage();
        }
    }
}

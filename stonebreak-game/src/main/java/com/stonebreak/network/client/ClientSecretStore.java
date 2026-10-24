package com.stonebreak.network.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;

/**
 * The join secrets servers issued this client (issue #335), one per {@code host:port} and name,
 * in {@code multiplayer_secrets.properties} next to {@code settings.json}. Presented in the next
 * handshake to the same server so a reconnect may take over its own half-dead session; see
 * {@code NameClaims} on the server. Not a credential: losing the file only means waiting for
 * the old session to time out.
 */
public final class ClientSecretStore {

    private static final ClientSecretStore DEFAULT =
            new ClientSecretStore(Paths.get("multiplayer_secrets.properties"));

    private final Path file;

    public ClientSecretStore(Path file) {
        this.file = file;
    }

    public static ClientSecretStore defaultStore() {
        return DEFAULT;
    }

    /** The secret {@code host:port} issued {@code username}, or "" when there is none. */
    public synchronized String get(String host, int port, String username) {
        return load().getProperty(key(host, port, username), "");
    }

    /** Remembers {@code secret}; a failed write only costs the takeover convenience. */
    public synchronized void put(String host, int port, String username, String secret) {
        Properties props = load();
        props.setProperty(key(host, port, username), secret);
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "Stonebreak join secrets (not passwords) - see ClientSecretStore");
        } catch (IOException e) {
            System.err.println("[NET] Could not save the join secret: " + e.getMessage());
        }
    }

    private Properties load() {
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("[NET] Could not read join secrets: " + e.getMessage());
            }
        }
        return props;
    }

    /** Host names are case-insensitive; player names are not (the server tells Bob from bob). */
    static String key(String host, int port, String username) {
        return host.trim().toLowerCase(Locale.ROOT) + ":" + port + "/" + username;
    }
}

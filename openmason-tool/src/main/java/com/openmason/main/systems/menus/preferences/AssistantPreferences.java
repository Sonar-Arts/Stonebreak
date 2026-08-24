package com.openmason.main.systems.menus.preferences;

import com.openmason.main.systems.assistant.llm.AssistantSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Assistant configuration store — its own properties file at
 * {@code ~/.openmason/assistant.properties}, deliberately OUT of the
 * repo-adjacent {@code preferences.properties} (the endpoint/API key are
 * per-machine and must never end up committed).
 */
public final class AssistantPreferences {

    private static final Logger logger = LoggerFactory.getLogger(AssistantPreferences.class);
    private static final Path FILE =
            Path.of(System.getProperty("user.home"), ".openmason", "assistant.properties");

    private static final String KEY_ENDPOINT = "assistant.endpoint";
    private static final String KEY_API_KEY = "assistant.api.key";
    private static final String KEY_TEMPERATURE = "assistant.temperature";
    private static final String KEY_MAX_ITERATIONS = "assistant.max.tool.iterations";
    private static final String KEY_CONTEXT_OVERRIDE = "assistant.context.tokens.override";
    private static final String KEY_PROMPT_EXTRAS = "assistant.prompt.extras";
    private static final String KEY_POLICY_READ = "assistant.approval.readonly";
    private static final String KEY_POLICY_MUTATING = "assistant.approval.mutating";
    private static final String KEY_POLICY_AUTH = "assistant.approval.requiresauth";
    private static final String KEY_LIBALEX_ENABLED = "assistant.libalex.enabled";
    private static final String KEY_AUTO_COMPACT = "assistant.context.auto.compact";

    private static final Object LOCK = new Object();
    private static Properties cached;

    private AssistantPreferences() {
    }

    /** Current settings snapshot (defaults where unset). */
    public static AssistantSettings read() {
        Properties p = load();
        AssistantSettings d = AssistantSettings.defaults();
        return new AssistantSettings(
                p.getProperty(KEY_ENDPOINT, d.endpoint()),
                p.getProperty(KEY_API_KEY, d.apiKey()),
                parseFloat(p.getProperty(KEY_TEMPERATURE), d.temperature()),
                parseInt(p.getProperty(KEY_MAX_ITERATIONS), d.maxToolIterations()),
                parseLong(p.getProperty(KEY_CONTEXT_OVERRIDE), d.contextTokensOverride()),
                p.getProperty(KEY_PROMPT_EXTRAS, d.promptExtras()),
                AssistantSettings.ApprovalPolicy.fromString(
                        p.getProperty(KEY_POLICY_READ), d.readOnlyPolicy()),
                AssistantSettings.ApprovalPolicy.fromString(
                        p.getProperty(KEY_POLICY_MUTATING), d.mutatingPolicy()),
                AssistantSettings.ApprovalPolicy.fromString(
                        p.getProperty(KEY_POLICY_AUTH), d.requiresAuthPolicy()),
                Boolean.parseBoolean(p.getProperty(KEY_AUTO_COMPACT,
                        String.valueOf(d.autoCompact()))));
    }

    /** Persist a full settings snapshot (called from the Preferences Apply). */
    public static void write(AssistantSettings s, boolean libalexEnabled) {
        synchronized (LOCK) {
            Properties p = load();
            p.setProperty(KEY_ENDPOINT, s.endpoint());
            p.setProperty(KEY_API_KEY, s.apiKey());
            p.setProperty(KEY_TEMPERATURE, String.valueOf(s.temperature()));
            p.setProperty(KEY_MAX_ITERATIONS, String.valueOf(s.maxToolIterations()));
            p.setProperty(KEY_CONTEXT_OVERRIDE, String.valueOf(s.contextTokensOverride()));
            p.setProperty(KEY_PROMPT_EXTRAS, s.promptExtras() == null ? "" : s.promptExtras());
            p.setProperty(KEY_POLICY_READ, s.readOnlyPolicy().name());
            p.setProperty(KEY_POLICY_MUTATING, s.mutatingPolicy().name());
            p.setProperty(KEY_POLICY_AUTH, s.requiresAuthPolicy().name());
            p.setProperty(KEY_AUTO_COMPACT, String.valueOf(s.autoCompact()));
            p.setProperty(KEY_LIBALEX_ENABLED, String.valueOf(libalexEnabled));
            try {
                Files.createDirectories(FILE.getParent());
                try (OutputStream out = Files.newOutputStream(FILE)) {
                    p.store(out, "Open Mason assistant settings (per-machine)");
                }
            } catch (IOException e) {
                logger.warn("Could not save assistant settings: {}", e.toString());
            }
        }
    }

    public static boolean libalexEnabled() {
        return Boolean.parseBoolean(load().getProperty(KEY_LIBALEX_ENABLED, "true"));
    }

    private static Properties load() {
        synchronized (LOCK) {
            if (cached == null) {
                cached = new Properties();
                if (Files.isRegularFile(FILE)) {
                    try (InputStream in = Files.newInputStream(FILE)) {
                        cached.load(in);
                    } catch (IOException e) {
                        logger.warn("Could not read assistant settings: {}", e.toString());
                    }
                }
            }
            return cached;
        }
    }

    private static float parseFloat(String v, float fallback) {
        try {
            return v == null ? fallback : Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String v, int fallback) {
        try {
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String v, long fallback) {
        try {
            return v == null ? fallback : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

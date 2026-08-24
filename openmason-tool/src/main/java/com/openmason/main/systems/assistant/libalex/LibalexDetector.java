package com.openmason.main.systems.assistant.libalex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects a libalex installation from its launch configs — the same contract
 * the pi extension uses. Absence or malformed config means NOT detected and
 * the libalex-backed features stay unregistered (auto-disable).
 *
 * <p>Probe order: {@code ~/.pi/agent/libalex.json} ({command, args}) then
 * {@code ~/.claude.json} → {@code mcpServers.libalex.command/args}.
 */
public final class LibalexDetector {

    private static final Logger logger = LoggerFactory.getLogger(LibalexDetector.class);

    /** A resolved launch command. */
    public record LaunchConfig(List<String> commandLine) {
    }

    private LibalexDetector() {
    }

    /** Detect and return the launch config, or null when not detected. */
    public static LaunchConfig detect() {
        String home = System.getProperty("user.home");
        return detect(Path.of(home, ".pi", "agent", "libalex.json"),
                Path.of(home, ".claude.json"));
    }

    /** Testable overload with explicit config paths. */
    static LaunchConfig detect(Path piConfig, Path claudeConfig) {
        ObjectMapper mapper = new ObjectMapper();
        LaunchConfig fromPi = fromPiConfig(mapper, piConfig);
        if (fromPi != null) {
            return fromPi;
        }
        return fromClaudeConfig(mapper, claudeConfig);
    }

    private static LaunchConfig fromPiConfig(ObjectMapper mapper, Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(file));
            return toConfig(root.path("command"), root.path("args"));
        } catch (Exception e) {
            logger.info("libalex pi config unreadable ({}): {}", file, e.toString());
            return null;
        }
    }

    private static LaunchConfig fromClaudeConfig(ObjectMapper mapper, Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode server = mapper.readTree(Files.readString(file))
                    .path("mcpServers").path("libalex");
            if (server.isMissingNode()) {
                return null;
            }
            return toConfig(server.path("command"), server.path("args"));
        } catch (Exception e) {
            logger.info("libalex claude config unreadable ({}): {}", file, e.toString());
            return null;
        }
    }

    private static LaunchConfig toConfig(JsonNode command, JsonNode args) {
        if (!command.isTextual() || command.asText().isBlank()) {
            return null;
        }
        List<String> line = new ArrayList<>();
        line.add(command.asText());
        if (args.isArray()) {
            for (JsonNode arg : args) {
                if (!arg.isTextual()) {
                    return null; // malformed — treat as not detected
                }
                line.add(arg.asText());
            }
        }
        return new LaunchConfig(List.copyOf(line));
    }
}

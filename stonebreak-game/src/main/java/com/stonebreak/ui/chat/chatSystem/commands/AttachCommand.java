package com.stonebreak.ui.chat.chatSystem.commands;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.mobs.entities.LivingEntity;
import com.stonebreak.mobs.sbe.EntityAttachments;
import com.stonebreak.mobs.sbe.SbeAttachmentPoint;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityLoader;
import com.stonebreak.mobs.sbe.SbeEntityRegistry;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.stonebreak.player.Player;
import com.stonebreak.ui.chat.chatSystem.ChatMessageManager;
import com.stonebreak.ui.chat.chatSystem.commands.util.ChatColors;
import com.stonebreak.ui.chat.chatSystem.commands.util.CommandValidator;

import java.nio.file.Path;
import java.util.List;

/**
 * Debug command for the attachment point (socket) system: mounts a model on a
 * named socket of the local player or the nearest SBE mob, purely visually
 * (not persisted, not replicated). Accepts {@code .omo}, {@code .sbe}, or
 * {@code .sbo} assets, resolved from disk first, then from the game classpath
 * (so files dropped into {@code src/main/resources/} work directly).
 *
 * <pre>
 *   /attach                        - list the target's socket names
 *   /attach &lt;socket&gt; &lt;path&gt;        - attach a .omo/.sbe/.sbo to a socket
 *   /attach clear                  - remove all attachments from the target
 *   ... [self|mob]                 - optional trailing target (default: self)
 * </pre>
 */
public class AttachCommand implements ChatCommand {

    private static final float MOB_SEARCH_RADIUS = 8f;

    @Override
    public void execute(String[] args, ChatMessageManager messageManager) {
        if (!CommandValidator.validateCheatCommand(messageManager)) {
            return;
        }

        // "clear" and the socket form take an optional trailing self|mob.
        String targetArg = args.length > 0 ? args[args.length - 1].toLowerCase() : "self";
        boolean explicitTarget = targetArg.equals("self") || targetArg.equals("mob");
        Target target = resolveTarget(explicitTarget ? targetArg : "self", messageManager);
        if (target == null) {
            return;
        }
        int effectiveArgs = explicitTarget ? args.length - 1 : args.length;

        if (effectiveArgs == 0) {
            listSockets(target, messageManager);
            return;
        }

        if (args[0].equalsIgnoreCase("clear")) {
            boolean removed = EntityAttachments.detach(target.key(), null);
            messageManager.addMessage(removed
                    ? "Cleared attachments on " + target.label()
                    : "No attachments on " + target.label(),
                    removed ? ChatColors.GREEN : ChatColors.YELLOW);
            return;
        }

        if (effectiveArgs < 2) {
            messageManager.addMessage("Usage: /attach <socket> <path .omo|.sbe|.sbo> [self|mob]",
                    ChatColors.YELLOW);
            return;
        }

        String socketName = args[0];
        // The chat tokenizer splits on spaces, so a path containing spaces
        // arrives as several args — rejoin everything between the socket name
        // and the (already-stripped) trailing target selector.
        String assetPath = String.join(" ",
                java.util.Arrays.copyOfRange(args, 1, effectiveArgs));
        SbeEntityAsset accessory;
        try {
            accessory = loadAccessory(assetPath);
        } catch (Exception e) {
            messageManager.addMessage("Failed to load model: " + e.getMessage(), ChatColors.RED);
            return;
        }

        if (findSocket(target.asset(), socketName) == null) {
            messageManager.addMessage("Unknown socket '" + socketName + "' on " + target.label()
                    + " — /attach lists sockets", ChatColors.RED);
            return;
        }

        EntityAttachments.attach(target.key(), socketName, accessory);
        messageManager.addMessage("Attached " + assetPath + " to '" + socketName + "' on "
                + target.label(), ChatColors.GREEN);
    }

    /**
     * Resolve an accessory path: an existing file on disk wins; otherwise the
     * path is tried as a game classpath resource (assets shipped under
     * {@code src/main/resources/}, e.g. {@code /sbe/Clothing/SB_Tophat.sbe}).
     */
    private static SbeEntityAsset loadAccessory(String path) {
        Path file = Path.of(path);
        if (java.nio.file.Files.exists(file)) {
            return SbeEntityLoader.loadAttachable(file);
        }
        String resource = path.startsWith("/") ? path : "/" + path;
        if (SbeEntityLoader.class.getResource(resource) != null) {
            return SbeEntityLoader.loadAttachableResource(resource);
        }
        throw new IllegalStateException("not found on disk (" + path
                + ") or on the classpath (" + resource + ")");
    }

    /** The attachment key, the host asset (for socket lookup), and a chat label. */
    private record Target(Object key, SbeEntityAsset asset, String label) {}

    private Target resolveTarget(String targetArg, ChatMessageManager messageManager) {
        if (targetArg.equals("mob")) {
            LivingEntity mob = nearestSbeMob();
            if (mob == null) {
                messageManager.addMessage("No SBE mob within " + (int) MOB_SEARCH_RADIUS
                        + " blocks", ChatColors.RED);
                return null;
            }
            SbeEntityAsset asset = SbeEntityRegistry.get(mob.getType().getSbeObjectId());
            return new Target(mob, asset, mob.getType().name().toLowerCase());
        }
        SbeEntityAsset asset = SbeEntityRegistry.get(
                EntityType.REMOTE_PLAYER.getSbeObjectId());
        if (asset == null) {
            messageManager.addMessage("Player model not loaded", ChatColors.RED);
            return null;
        }
        return new Target(EntityAttachments.LOCAL_PLAYER, asset, "you");
    }

    private LivingEntity nearestSbeMob() {
        Player player = Game.getPlayer();
        EntityManager entityManager = Game.getEntityManager();
        if (player == null || entityManager == null) {
            return null;
        }
        LivingEntity nearest = null;
        float nearestDistSq = MOB_SEARCH_RADIUS * MOB_SEARCH_RADIUS;
        for (Entity entity : entityManager.getEntitiesInRange(player.getPosition(),
                MOB_SEARCH_RADIUS)) {
            if (!(entity instanceof LivingEntity mob) || mob.getAI() == null
                    || mob.getType().getSbeObjectId() == null) {
                continue;
            }
            float distSq = (float) entity.getPosition().distanceSquared(player.getPosition());
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = mob;
            }
        }
        return nearest;
    }

    private void listSockets(Target target, ChatMessageManager messageManager) {
        SbeModelGeometry geometry = target.asset() == null ? null
                : target.asset().geometryFor(SbeEntityAsset.DEFAULT_VARIANT);
        List<SbeAttachmentPoint> sockets = geometry == null ? List.of()
                : geometry.attachmentPoints();
        if (sockets.isEmpty()) {
            messageManager.addMessage("No sockets on " + target.label()
                    + " (author them in Open Mason)", ChatColors.YELLOW);
            return;
        }
        StringBuilder names = new StringBuilder();
        for (SbeAttachmentPoint socket : sockets) {
            if (!names.isEmpty()) names.append(", ");
            names.append(socket.name());
        }
        messageManager.addMessage("Sockets on " + target.label() + ": " + names, ChatColors.GREEN);
    }

    private static SbeAttachmentPoint findSocket(SbeEntityAsset asset, String socketName) {
        SbeModelGeometry geometry = asset == null ? null
                : asset.geometryFor(SbeEntityAsset.DEFAULT_VARIANT);
        if (geometry == null) return null;
        for (SbeAttachmentPoint socket : geometry.attachmentPoints()) {
            if (socket.name().equalsIgnoreCase(socketName)) {
                return socket;
            }
        }
        return null;
    }

    @Override
    public String getName() {
        return "attach";
    }

    @Override
    public String getDescription() {
        return "Attach a model to a socket (/attach <socket> <path .omo|.sbe|.sbo> [self|mob], "
                + "/attach clear, /attach to list sockets)";
    }

    @Override
    public boolean requiresCheats() {
        return true;
    }

    @Override
    public List<String> getAutocompleteSuggestions(String[] args, String currentArg) {
        // Arg 1: "clear" or a socket name on the player model.
        if (args.length == 0) {
            java.util.List<String> out = new java.util.ArrayList<>();
            addIfPrefixed(out, "clear", currentArg);
            SbeEntityAsset playerAsset = SbeEntityRegistry.get(
                    EntityType.REMOTE_PLAYER.getSbeObjectId());
            SbeModelGeometry geometry = playerAsset == null ? null
                    : playerAsset.geometryFor(SbeEntityAsset.DEFAULT_VARIANT);
            if (geometry != null) {
                for (SbeAttachmentPoint socket : geometry.attachmentPoints()) {
                    addIfPrefixed(out, socket.name(), currentArg);
                }
            }
            return out;
        }

        // Arg 2 after "clear", or arg 3: target selector.
        if (args[0].equalsIgnoreCase("clear") || args.length >= 2) {
            java.util.List<String> out = new java.util.ArrayList<>();
            addIfPrefixed(out, "self", currentArg);
            addIfPrefixed(out, "mob", currentArg);
            return out;
        }

        // Arg 2: attachable asset path — bundled classpath assets + filesystem.
        return assetPathSuggestions(currentArg);
    }

    private static void addIfPrefixed(List<String> out, String candidate, String prefix) {
        if (candidate.toLowerCase().startsWith(prefix.toLowerCase())) {
            out.add(candidate);
        }
    }

    private static final int MAX_PATH_SUGGESTIONS = 30;

    /**
     * Suggest attachable asset paths: every {@code .omo}/{@code .sbe}/{@code .sbo}
     * bundled on the game classpath (scanned once, cached), plus filesystem
     * completion when the typed prefix contains a path separator. Filesystem
     * entries win the dedupe since {@code /attach} resolves disk paths first.
     */
    private static List<String> assetPathSuggestions(String currentArg) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();

        if (currentArg.contains("/") || currentArg.contains("\\")) {
            out.addAll(filesystemSuggestions(currentArg));
        }
        for (String resource : classpathAssets()) {
            if (out.size() >= MAX_PATH_SUGGESTIONS) break;
            if (resource.toLowerCase().startsWith(currentArg.toLowerCase())) {
                out.add(resource);
            }
        }
        return List.copyOf(out);
    }

    /** Complete against the directory the user is typing into on disk. */
    private static List<String> filesystemSuggestions(String currentArg) {
        try {
            Path typed = Path.of(currentArg);
            Path dir;
            String namePrefix;
            if (currentArg.endsWith("/") || currentArg.endsWith("\\")) {
                dir = typed;
                namePrefix = "";
            } else {
                dir = typed.getParent();
                namePrefix = typed.getFileName().toString().toLowerCase();
            }
            if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
                return List.of();
            }

            java.util.List<String> out = new java.util.ArrayList<>();
            try (var entries = java.nio.file.Files.list(dir)) {
                java.util.Iterator<Path> it = entries.sorted().iterator();
                while (it.hasNext() && out.size() < MAX_PATH_SUGGESTIONS) {
                    Path entry = it.next();
                    String name = entry.getFileName().toString();
                    if (!name.toLowerCase().startsWith(namePrefix)) continue;
                    if (java.nio.file.Files.isDirectory(entry)) {
                        out.add(entry + "/");
                    } else if (hasAttachableExtension(name)) {
                        out.add(entry.toString());
                    }
                }
            }
            return out;
        } catch (Exception e) {
            return List.of(); // malformed path mid-typing — no suggestions
        }
    }

    /** Bundled attachable assets, scanned once from the game's code source (jar or classes dir). */
    private static volatile List<String> classpathAssetsCache;

    private static List<String> classpathAssets() {
        List<String> cached = classpathAssetsCache;
        if (cached != null) {
            return cached;
        }
        java.util.List<String> found = new java.util.ArrayList<>();
        try {
            java.net.URL location = AttachCommand.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            Path source = Path.of(location.toURI());
            if (java.nio.file.Files.isDirectory(source)) {
                // IDE / exploded run: resources live under the classes directory.
                try (var stream = java.nio.file.Files.walk(source)) {
                    stream.filter(java.nio.file.Files::isRegularFile)
                            .map(p -> "/" + source.relativize(p).toString().replace('\\', '/'))
                            .filter(AttachCommand::hasAttachableExtension)
                            .forEach(found::add);
                }
            } else {
                // Shaded jar: scan its entries.
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(source.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry entry = entries.nextElement();
                        if (!entry.isDirectory() && hasAttachableExtension(entry.getName())) {
                            found.add("/" + entry.getName());
                        }
                    }
                }
            }
            java.util.Collections.sort(found);
        } catch (Exception e) {
            // Exotic classloader setup — autocomplete degrades to filesystem only.
        }
        classpathAssetsCache = List.copyOf(found);
        return classpathAssetsCache;
    }

    private static boolean hasAttachableExtension(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".omo") || lower.endsWith(".sbe") || lower.endsWith(".sbo");
    }
}

package com.stonebreak.core.services;

import com.openmason.engine.audio.SoundSystem;
import com.stonebreak.audio.MusicManager;
import com.stonebreak.audio.PlayerSounds;
import com.stonebreak.audio.emitters.SoundEmitterManager;
import com.stonebreak.crafting.CraftingManager;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.input.InputHandler;
import com.stonebreak.input.MouseCaptureManager;
import com.stonebreak.mobs.entities.EntityManager;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.ui.DebugOverlay;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.util.MemoryLeakDetector;
import com.stonebreak.world.TimeOfDay;
import com.stonebreak.world.World;

/**
 * Service locator: the registry of subsystem references that {@link com.stonebreak.core.Game}
 * hands out through its static {@code getX()} accessors. Game owns exactly one instance and
 * delegates to it, so callers keep using {@code Game.getWorld()} etc. unchanged.
 * <p>
 * {@code world}/{@code player}/{@code entityManager} are volatile: the "ClientWorld-Build"
 * thread swaps them while the main thread, render path and world-update executor read them —
 * without safe publication a reader could observe a torn mix of the old session's manager with
 * the new session's world during a reconnect.
 */
public final class GameServices {

    private volatile World world;
    private volatile Player player;
    private volatile EntityManager entityManager;
    private Renderer renderer;
    private BlockTextureArray textureAtlas;
    private InputHandler inputHandler;
    private MouseCaptureManager mouseCaptureManager;
    private SoundSystem soundSystem;
    private PlayerSounds playerSounds;
    private ChatSystem chatSystem;
    private CraftingManager craftingManager;
    private SmeltingManager smeltingManager;
    private SoundEmitterManager soundEmitterManager;
    private MusicManager musicManager;
    private MemoryLeakDetector memoryLeakDetector;
    private DebugOverlay debugOverlay;
    private TimeOfDay timeOfDay;

    public World world() { return world; }
    public void setWorld(World world) { this.world = world; }

    public Player player() { return player; }
    public void setPlayer(Player player) { this.player = player; }

    public EntityManager entityManager() { return entityManager; }
    public void setEntityManager(EntityManager entityManager) { this.entityManager = entityManager; }

    public Renderer renderer() { return renderer; }
    public void setRenderer(Renderer renderer) { this.renderer = renderer; }

    public BlockTextureArray textureAtlas() { return textureAtlas; }
    public void setTextureAtlas(BlockTextureArray textureAtlas) { this.textureAtlas = textureAtlas; }

    public InputHandler inputHandler() { return inputHandler; }
    public void setInputHandler(InputHandler inputHandler) { this.inputHandler = inputHandler; }

    public MouseCaptureManager mouseCaptureManager() { return mouseCaptureManager; }
    public void setMouseCaptureManager(MouseCaptureManager m) { this.mouseCaptureManager = m; }

    public SoundSystem soundSystem() { return soundSystem; }
    public void setSoundSystem(SoundSystem soundSystem) { this.soundSystem = soundSystem; }

    public PlayerSounds playerSounds() { return playerSounds; }
    public void setPlayerSounds(PlayerSounds playerSounds) { this.playerSounds = playerSounds; }

    public ChatSystem chatSystem() { return chatSystem; }
    public void setChatSystem(ChatSystem chatSystem) { this.chatSystem = chatSystem; }

    public CraftingManager craftingManager() { return craftingManager; }
    public void setCraftingManager(CraftingManager craftingManager) { this.craftingManager = craftingManager; }

    public SmeltingManager smeltingManager() { return smeltingManager; }
    public void setSmeltingManager(SmeltingManager smeltingManager) { this.smeltingManager = smeltingManager; }

    public SoundEmitterManager soundEmitterManager() { return soundEmitterManager; }
    public void setSoundEmitterManager(SoundEmitterManager m) { this.soundEmitterManager = m; }

    public MusicManager musicManager() { return musicManager; }
    public void setMusicManager(MusicManager musicManager) { this.musicManager = musicManager; }

    public MemoryLeakDetector memoryLeakDetector() { return memoryLeakDetector; }
    public void setMemoryLeakDetector(MemoryLeakDetector d) { this.memoryLeakDetector = d; }

    public DebugOverlay debugOverlay() { return debugOverlay; }
    public void setDebugOverlay(DebugOverlay debugOverlay) { this.debugOverlay = debugOverlay; }

    public TimeOfDay timeOfDay() { return timeOfDay; }
    public void setTimeOfDay(TimeOfDay timeOfDay) { this.timeOfDay = timeOfDay; }
}

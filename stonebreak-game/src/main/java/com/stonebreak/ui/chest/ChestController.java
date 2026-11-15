package com.stonebreak.ui.chest;

import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.container.ChestRegistry;
import com.stonebreak.blocks.container.ChestState;
import com.stonebreak.core.Game;
import com.stonebreak.items.ItemStack;
import com.stonebreak.network.packet.player.ContainerActionC2S;
import com.stonebreak.ui.inventoryScreen.handlers.SlotActionSink;

import java.util.function.Predicate;

/**
 * Coordinates the chest UI for one open chest. It does not own the slots — the world's
 * {@link ChestRegistry} does; this binds the screen to them, keeps the action sink addressed to
 * that chest, and closes the screen if the chest is broken while it is open.
 */
public class ChestController {

    private final Game game;
    private ChestInputManager inputManager;
    private ChestState state;
    private boolean visible;

    /** Where slot actions go: the server, through the multiplayer session (issue #334). */
    private final SlotActionSink actions = new SlotActionSink();
    private Predicate<BlockPos> chestAt = ChestController::worldHasChest;

    public ChestController(Game game) {
        this.game = game;
    }

    void setInputManager(ChestInputManager inputManager) {
        this.inputManager = inputManager;
    }

    public ChestInputManager getInputManager() {
        return inputManager;
    }

    /** Binds the UI to the chest at {@code pos} and shows it. */
    public void open(BlockPos pos) {
        ChestRegistry registry = game != null && Game.getWorld() != null ? Game.getWorld().getChestRegistry() : null;
        bind(registry != null ? registry.getOrCreate(pos) : new ChestState(pos));
    }

    void bind(ChestState chest) {
        this.state = chest;
        this.visible = true;
        actions.bind(ContainerActionC2S.ContainerKind.CHEST, chest.getPos().x(), chest.getPos().y(), chest.getPos().z());
        // Numbered actions: echoes built before the server saw one keep our slots (#353).
        actions.onNumbered(chest::markSent);
        if (inputManager != null) {
            inputManager.bindActions(actions);
        }
        chest.consumePreEchoSlots(); // an echo that landed while closed is not an edit of ours
    }

    public void close() {
        if (inputManager != null) {
            inputManager.handleCloseWithDraggedItems();
        }
        this.visible = false;
        this.state = null;
    }

    public boolean isVisible() {
        return visible;
    }

    public void update(float deltaTime) {
        if (visible && state != null && !chestAt.test(state.getPos())) {
            abandonBrokenChest();
        }
    }

    /** The open chest's live slots, or null while none is open. */
    public ItemStack[] slots() {
        return state != null ? state.getSlots() : null;
    }

    public ItemStack getSlot(int i) {
        ItemStack[] s = slots();
        return s != null && i >= 0 && i < s.length ? s[i] : new ItemStack(0, 0);
    }

    public void setSlot(int i, ItemStack stack) {
        ItemStack[] s = slots();
        if (s != null && i >= 0 && i < s.length) {
            s[i] = stack == null ? new ItemStack(0, 0) : stack;
        }
    }

    /** Someone broke the chest while it was open: its slots are dead; hand back only the cursor stack. */
    void abandonBrokenChest() {
        this.state = null;
        if (inputManager != null) {
            inputManager.returnCarriedToPlayer();
        }
        if (game != null) {
            game.closeChestScreen();
        } else {
            close();
        }
    }

    public void handleCloseRequest() {
        game.closeChestScreen();
    }

    private static boolean worldHasChest(BlockPos p) {
        com.stonebreak.world.World world = Game.getWorld();
        return world == null || world.getBlockAt(p.x(), p.y(), p.z()) == BlockType.CHEST;
    }

    /** Test seam: what counts as the chest still standing. */
    void setChestAt(Predicate<BlockPos> test) {
        this.chestAt = test;
    }

    /** The container-action sink this screen's slot rules emit through (test seam). */
    SlotActionSink actions() {
        return actions;
    }
}

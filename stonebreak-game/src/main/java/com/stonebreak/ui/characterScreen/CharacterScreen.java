package com.stonebreak.ui.characterScreen;

import com.stonebreak.input.InputHandler;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;

/**
 * Facade for the Character Screen.
 * Mirrors InventoryScreen's delegation model: facade → controller → render coordinator.
 */
public class CharacterScreen {

  /** The shipped document: {@code ui/documents/character_sheet.sbui} (#301). */
  public static final String DOCUMENT_ID = "character_sheet";

  private final CharacterController controller;
  private final CharacterRenderCoordinator coordinator;
  private final com.stonebreak.ui.runtime.screens.PresentationSlot presentation =
      new com.stonebreak.ui.runtime.screens.PresentationSlot();

  public CharacterScreen(Player player, Renderer renderer, InputHandler inputHandler) {
    this(player.getCharacterStats(), renderer, inputHandler);
  }

  /** A sheet over {@code stats} (tests: no player). */
  public CharacterScreen(CharacterStats stats, Renderer renderer, InputHandler inputHandler) {
    this.controller = new CharacterController();
    this.coordinator = new CharacterRenderCoordinator(renderer, inputHandler, stats, controller);
    this.controller.setRenderCoordinator(coordinator);
  }

  /** The legacy renderer and the tab renderers' view state (the document's seams, #301). */
  public CharacterRenderCoordinator coordinator() {
    return coordinator;
  }

  /** Installs (or, with null, removes) the document presentation; the legacy renderer is the default. */
  public void setPresentation(com.stonebreak.ui.runtime.screens.ScreenPresentation p) {
    presentation.install(p);
  }

  /** The document shows while the sheet is open in CHARACTER_SHEET_UI (GameStateController.setState). */
  public void syncPresentation(com.stonebreak.core.GameState state) {
    // the world may be built on another thread (as InventoryScreen.syncPresentation)
    com.stonebreak.ui.runtime.GameUiHost.onUiThread(() -> presentation.setVisible(
        state == com.stonebreak.core.GameState.CHARACTER_SHEET_UI && controller.isVisible()));
  }

  /** True while the document, not the legacy renderer, shows the sheet. */
  public boolean presentationShowing() {
    return presentation.showing();
  }

  public CharacterController getController() {
    return controller;
  }

  public void toggleVisibility() {
    controller.toggleVisibility();
    com.stonebreak.core.Game game = com.stonebreak.core.Game.getInstance();
    if (game != null) syncPresentation(game.getState()); // a close that keeps the state still hides it
  }

  public boolean isVisible() {
    return controller.isVisible();
  }

  public void render(int screenWidth, int screenHeight) {
    if (presentation.showing()) {
      com.stonebreak.ui.runtime.GameUiHost host = com.stonebreak.ui.runtime.GameUiHost.get();
      if (host != null) host.characterSheet().poll(); // the sheet as of this frame
      if (presentation.paint(screenWidth, screenHeight)) return;
    }
    controller.render(screenWidth, screenHeight);
  }

  public void handleMouseInput(int screenWidth, int screenHeight) {
    if (presentation.showing()) return; // the document takes the pointer (#301)
    controller.handleMouseInput(screenWidth, screenHeight);
  }

  public void handleScroll(float deltaY) {
    if (presentation.showing()) return; // and the wheel
    controller.handleScroll(deltaY);
  }
}

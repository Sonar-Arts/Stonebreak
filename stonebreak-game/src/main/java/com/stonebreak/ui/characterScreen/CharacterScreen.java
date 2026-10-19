package com.stonebreak.ui.characterScreen;

import com.openmason.engine.ui.masonry.MFonts;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.Renderer;

/**
 * Facade for the Character Screen: the {@link CharacterController} holds the sheet's state and rules,
 * the shipped document {@value #DOCUMENT_ID} (#301) draws it and takes the pointer and the wheel.
 */
public class CharacterScreen {

  /** The shipped document: {@code ui/documents/character_sheet.sbui} (#301). */
  public static final String DOCUMENT_ID = "character_sheet";

  private final CharacterController controller;
  private final com.stonebreak.ui.runtime.screens.PresentationSlot presentation =
      new com.stonebreak.ui.runtime.screens.PresentationSlot();

  public CharacterScreen(Player player, Renderer renderer) {
    this(player.getCharacterStats(), renderer);
  }

  /** A sheet over {@code stats} (tests: no player); its text is measured with the renderer's UI fonts. */
  public CharacterScreen(CharacterStats stats, Renderer renderer) {
    this.controller = new CharacterController(stats, new MFonts(renderer.getSkijaBackend()));
  }

  /** Installs (or, with null, removes) the document presentation. */
  public void setPresentation(com.stonebreak.ui.runtime.screens.ScreenPresentation p) {
    presentation.install(p);
  }

  /** The document shows while the sheet is open in CHARACTER_SHEET_UI (GameStateController.setState). */
  public void syncPresentation(com.stonebreak.core.GameState state) {
    // the world may be built on another thread (as InventoryScreen.syncPresentation)
    com.stonebreak.ui.runtime.GameUiHost.onUiThread(() -> presentation.setVisible(
        state == com.stonebreak.core.GameState.CHARACTER_SHEET_UI && controller.isVisible()));
  }

  /** True while the document shows the sheet. */
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

  public void setVisible(boolean visible) {
    controller.setVisible(visible);
  }

  public boolean isVisible() {
    return controller.isVisible();
  }

  /** Publishes the sheet as of this frame, then paints its document. */
  public void render(int screenWidth, int screenHeight) {
    if (!presentation.showing()) return;
    com.stonebreak.ui.runtime.GameUiHost host = com.stonebreak.ui.runtime.GameUiHost.get();
    if (host != null) host.characterSheet().poll();
    presentation.paint(screenWidth, screenHeight);
  }
}

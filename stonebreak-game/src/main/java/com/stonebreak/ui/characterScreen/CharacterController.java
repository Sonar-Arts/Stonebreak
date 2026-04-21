package com.stonebreak.ui.characterScreen;

import com.stonebreak.rpg.CharacterPanelTab;

/**
 * Manages visibility and delegates rendering/input for the Character Screen.
 * Mirrors the minimal controller pattern used by InventoryController.
 */
public class CharacterController {

  private boolean visible = false;
  private CharacterRenderCoordinator renderCoordinator;
  private CharacterPanelTab activeTab = CharacterPanelTab.OVERVIEW;

  public CharacterController() {}

  public void setRenderCoordinator(CharacterRenderCoordinator rc) {
    this.renderCoordinator = rc;
  }

  public void toggleVisibility() {
    this.visible = !this.visible;
  }

  public boolean isVisible() {
    return visible;
  }

  public void setVisible(boolean v) {
    this.visible = v;
  }

  public CharacterPanelTab getActiveTab() {
    return activeTab;
  }

  public void setActiveTab(CharacterPanelTab tab) {
    this.activeTab = tab;
  }

  public void render(int screenWidth, int screenHeight) {
    if (!visible || renderCoordinator == null) {
      return;
    }
    renderCoordinator.render(screenWidth, screenHeight);
  }

  public void handleMouseInput(int screenWidth, int screenHeight) {
    if (!visible || renderCoordinator == null) {
      return;
    }
    renderCoordinator.handleMouseInput(screenWidth, screenHeight);
  }

  public void handleScroll(float deltaY) {
    if (!visible || renderCoordinator == null) {
      return;
    }
    renderCoordinator.handleScroll(deltaY);
  }
}

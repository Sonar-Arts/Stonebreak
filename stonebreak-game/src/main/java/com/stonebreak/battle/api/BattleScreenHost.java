package com.stonebreak.battle.api;

/** What the HUD may ask of the battle's owner. Implemented by the stage coordinator. */
public interface BattleScreenHost {
    /** Any confirm press during the intro. */
    void skipIntro();
    /** Result panel: start the same encounter again. */
    void retry();
    /** Result panel: drop to free-roam inside the arena. */
    void exploreArena();
    /** Result panel: leave the arena and return to the overworld. */
    void returnToWorld();
    /** Escape at the root menu. */
    void openPauseMenu();
}

package com.stonebreak.core;

public enum GameState {
    MAIN_MENU,
    WORLD_SELECT,    // State for world selection screen
    LOADING,         // State for world generation loading screen
    PLAYING,
    PAUSED,
    SETTINGS,
    WORKBENCH_UI,
    INVENTORY_UI,    // State for when inventory is open
    RECIPE_BOOK_UI   // State for Recipe Book UI
}
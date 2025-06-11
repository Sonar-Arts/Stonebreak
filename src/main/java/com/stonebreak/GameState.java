package com.stonebreak;

public enum GameState {
    MAIN_MENU,
    LOADING,         // State for world generation loading screen
    PLAYING,
    PAUSED,
    SETTINGS,
    WORKBENCH_UI,
    INVENTORY_UI,    // State for when inventory is open
    RECIPE_BOOK_UI   // State for Recipe Book UI
}
package com.stonebreak.core;

public enum GameState {
    STARTUP_INTRO,       // Sonar Arts boot animation, runs once before MAIN_MENU
    MAIN_MENU,
    WORLD_SELECT,        // State for world selection screen
    CHARACTER_CREATION,  // Character creation screen (shown before terrain mapper for new worlds)
    TERRAIN_MAPPER,      // Terrain preview + world creation screen
    LOADING,             // State for world generation loading screen
    PLAYING,
    PAUSED,
    SETTINGS,
    MULTIPLAYER_MENU,    // Multiplayer top-level menu (Host / Join / Back)
    HOST_WORLD_SELECT,   // Pick a world to host + configure port
    JOIN_WORLD_SCREEN,   // Enter host:port + username and connect
    WORKBENCH_UI,
    INVENTORY_UI,    // State for when inventory is open
    RECIPE_BOOK_UI,   // State for Recipe Book UI
    CHARACTER_SHEET_UI // State for when character screen is open
}
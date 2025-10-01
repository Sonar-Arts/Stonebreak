public class TestGame {
    public void startWorldGeneration(String worldName, long seed) {
        System.out.println("Starting world generation for: " + worldName + " with seed: " + seed);

        if (loadingScreen != null) {
            loadingScreen.show(); // This sets state to LOADING

            // Trigger world loading/generation in a separate thread
            new Thread(() -> performWorldLoadingOrGeneration(worldName, seed)).start();
        }
    }
    
    /**
     * Performs initial world generation with progress updates.
     * This runs in a background thread while the loading screen is displayed.
     */
    private void performInitialWorldGeneration() {
        try {
            // Update progress through the loading screen
            if (loadingScreen != null) {
                loadingScreen.updateProgress("Initializing Noise System");
}

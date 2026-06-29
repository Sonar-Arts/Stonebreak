package com.stonebreak.ui.mainMenu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SplashTextManager {
    private static final Logger logger = LoggerFactory.getLogger(SplashTextManager.class);

    private static SplashTextManager instance;
    private final List<String> splashTexts;
    private final Random random;
    private String lastSplashText = null;

    private SplashTextManager() {
        this.splashTexts = new ArrayList<>();
        this.random = new Random();
        loadSplashTexts();
    }

    public static SplashTextManager getInstance() {
        if (instance == null) {
            instance = new SplashTextManager();
        }
        return instance;
    }

    private void loadSplashTexts() {
        String[] resourcePaths = {
                "ui/mainMenu/splash_text.json",  // Root resources directory (simple path)
                "ui/mainMenu/splash_text.json"  // Original nested path
        };

        InputStream inputStream = null;

        for (String resourcePath : resourcePaths) {
            logger.debug("Attempting to load splash text from: {}", resourcePath);

            // First try: Class-specific class loader
            inputStream = SplashTextManager.class.getClassLoader().getResourceAsStream(resourcePath);

            // Second try: Class itself with leading slash
            if (inputStream == null) {
                inputStream = SplashTextManager.class.getResourceAsStream("/" + resourcePath);
            }

            // Third try: System class loader as fallback
            if (inputStream == null) {
                inputStream = ClassLoader.getSystemClassLoader().getResourceAsStream(resourcePath);
            }

            // Fourth try: Thread context class loader
            if (inputStream == null) {
                inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
            }

            if (inputStream != null) {
                logger.debug("Successfully found splash text file at: {}", resourcePath);
                break;
            }
        }

        if (inputStream == null) {
            logger.warn("Could not find splash_text.json at any of the attempted paths using any class loader — using default splash text");
            splashTexts.add("Welcome to Stonebreak!");
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> data = mapper.readValue(inputStream, new TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            List<String> loadedTexts = (List<String>) data.get("splashTexts");

            if (loadedTexts != null && !loadedTexts.isEmpty()) {
                splashTexts.addAll(loadedTexts);
                logger.debug("Successfully loaded {} splash texts from JSON", loadedTexts.size());
            } else {
                logger.warn("JSON file loaded but contained no splash texts, using default");
                splashTexts.add("Welcome to Stonebreak!");
            }

        } catch (IOException e) {
            logger.error("Error loading splash texts", e);
            splashTexts.add("Welcome to Stonebreak!");
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                // Ignore close errors
            }
        }
    }

    public String getRandomSplashText() {
        if (splashTexts.isEmpty()) {
            return "Welcome to Stonebreak!";
        }

        // If we only have one splash text, just return it
        if (splashTexts.size() == 1) {
            String text = splashTexts.get(0);
            lastSplashText = text;
            return text;
        }

        // Avoid returning the same text twice in a row
        String newText;
        int attempts = 0;
        do {
            newText = splashTexts.get(random.nextInt(splashTexts.size()));
            attempts++;
        } while (newText.equals(lastSplashText) && attempts < 10);

        lastSplashText = newText;
        logger.debug("Selected splash text: '{}'", newText);
        return newText;
    }

    public List<String> getAllSplashTexts() {
        return new ArrayList<>(splashTexts);
    }

    public int getTotalSplashTextCount() {
        return splashTexts.size();
    }
}
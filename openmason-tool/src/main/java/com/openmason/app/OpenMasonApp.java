package com.openmason.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;

/**
 * Main JavaFX application class for OpenMason tool.
 * Provides professional 3D model development environment with 1:1 rendering parity to Stonebreak.
 */
public class OpenMasonApp extends Application {
    
    private static final Logger logger = LoggerFactory.getLogger(OpenMasonApp.class);
    
    private static final String APP_TITLE = "OpenMason - Professional 3D Model Development Tool";
    private static final int MIN_WIDTH = 1200;
    private static final int MIN_HEIGHT = 800;
    private static final int DEFAULT_WIDTH = 1600;
    private static final int DEFAULT_HEIGHT = 1000;
    
    private Stage primaryStage;
    private AppConfig appConfig;
    private AppLifecycle appLifecycle;
    
    /**
     * JavaFX application entry point.
     */
    @Override
    public void start(Stage primaryStage) throws Exception {
        this.primaryStage = primaryStage;
        
        logger.info("Starting OpenMason application...");
        
        try {
            // Initialize application configuration
            appConfig = new AppConfig();
            appLifecycle = new AppLifecycle();
            
            // Set up primary stage
            setupPrimaryStage();
            
            // Load main UI
            loadMainInterface();
            
            // Initialize application lifecycle
            appLifecycle.onApplicationStarted();
            
            // Show the application
            primaryStage.show();
            
            logger.info("OpenMason application started successfully");
            
        } catch (Exception e) {
            logger.error("Failed to start OpenMason application", e);
            showErrorAndExit("Failed to start application: " + e.getMessage());
        }
    }
    
    /**
     * Configure the primary stage with window properties.
     */
    private void setupPrimaryStage() {
        primaryStage.setTitle(APP_TITLE);
        primaryStage.setMinWidth(MIN_WIDTH);
        primaryStage.setMinHeight(MIN_HEIGHT);
        primaryStage.setWidth(DEFAULT_WIDTH);
        primaryStage.setHeight(DEFAULT_HEIGHT);
        
        // Center the window on screen
        primaryStage.centerOnScreen();
        
        // Handle application close request
        primaryStage.setOnCloseRequest(event -> {
            event.consume(); // Prevent default close
            handleApplicationExit();
        });
    }
    
    /**
     * Load the main FXML interface.
     */
    private void loadMainInterface() throws IOException {
        URL fxmlResource = getClass().getResource("/fxml/main-window.fxml");
        if (fxmlResource == null) {
            throw new IOException("Could not find main-window.fxml resource");
        }
        
        FXMLLoader loader = new FXMLLoader(fxmlResource);
        Parent root = loader.load();
        
        // Create scene with dark theme
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        
        // Load dark theme CSS
        URL darkThemeCss = getClass().getResource("/css/dark-theme.css");
        if (darkThemeCss != null) {
            scene.getStylesheets().add(darkThemeCss.toExternalForm());
        } else {
            logger.warn("Could not find dark-theme.css resource");
        }
        
        // Load icons CSS
        URL iconsCss = getClass().getResource("/css/icons.css");
        if (iconsCss != null) {
            scene.getStylesheets().add(iconsCss.toExternalForm());
        } else {
            logger.warn("Could not find icons.css resource");
        }
        
        primaryStage.setScene(scene);
    }
    
    /**
     * Handle application exit with proper cleanup.
     */
    private void handleApplicationExit() {
        try {
            logger.info("Shutting down OpenMason application...");
            
            if (appLifecycle != null) {
                appLifecycle.onApplicationShutdown();
            }
            
            Platform.exit();
            System.exit(0);
            
        } catch (Exception e) {
            logger.error("Error during application shutdown", e);
            System.exit(1);
        }
    }
    
    /**
     * Show error dialog and exit application.
     */
    private void showErrorAndExit(String message) {
        logger.error("Fatal error: {}", message);
        Platform.exit();
        System.exit(1);
    }
    
    /**
     * JavaFX application stop callback.
     */
    @Override
    public void stop() throws Exception {
        logger.info("OpenMason application stopped");
        super.stop();
    }
    
    /**
     * Main method - application entry point.
     */
    public static void main(String[] args) {
        // Check for validation test argument
        if (args.length > 0 && "--validate".equals(args[0])) {
            runValidationTest();
            return;
        }
        
        // Set system properties for better rendering
        System.setProperty("prism.lcdtext", "false");
        System.setProperty("prism.subpixeltext", "false");
        
        logger.info("Launching OpenMason with args: {}", String.join(" ", args));
        
        try {
            launch(args);
        } catch (Exception e) {
            logger.error("Failed to launch OpenMason application", e);
            System.exit(1);
        }
    }
    
    /**
     * Run validation test for Stonebreak integration
     */
    private static void runValidationTest() {
        try {
            com.openmason.test.SimpleValidationTest.runValidation();
        } catch (Exception e) {
            System.err.println("Validation test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
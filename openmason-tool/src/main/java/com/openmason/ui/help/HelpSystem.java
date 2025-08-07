package com.openmason.ui.help;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Comprehensive help system with interactive tutorials, context-sensitive help,
 * searchable documentation, and getting started wizard.
 */
public class HelpSystem {
    
    private static final Logger logger = LoggerFactory.getLogger(HelpSystem.class);
    
    // Singleton instance
    private static HelpSystem instance;
    
    // Help content registry
    private final Map<String, HelpTopic> topics = new ConcurrentHashMap<>();
    private final Map<String, Tutorial> tutorials = new ConcurrentHashMap<>();
    private final Map<String, ContextHelp> contextHelp = new ConcurrentHashMap<>();
    private final ObservableList<HelpCategory> categories = FXCollections.observableArrayList();
    
    // State management
    private final BooleanProperty helpVisible = new SimpleBooleanProperty(false);
    private final StringProperty currentContext = new SimpleStringProperty("");
    private final ObjectProperty<HelpTopic> currentTopic = new SimpleObjectProperty<>();
    
    // UI components
    private HelpBrowser helpBrowser;
    private TutorialSystem tutorialSystem;
    private ContextHelpProvider contextHelpProvider;
    
    /**
     * Help topic definition
     */
    public static class HelpTopic {
        private final String id;
        private final String title;
        private final String categoryId;
        private final String content;
        private final List<String> keywords;
        private final List<String> relatedTopics;
        private final int priority;
        private boolean featured;
        
        public HelpTopic(String id, String title, String categoryId, String content) {
            this.id = id;
            this.title = title;
            this.categoryId = categoryId;
            this.content = content;
            this.keywords = new ArrayList<>();
            this.relatedTopics = new ArrayList<>();
            this.priority = 0;
            this.featured = false;
        }
        
        // Getters and setters
        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getCategoryId() { return categoryId; }
        public String getContent() { return content; }
        public List<String> getKeywords() { return keywords; }
        public List<String> getRelatedTopics() { return relatedTopics; }
        public int getPriority() { return priority; }
        public boolean isFeatured() { return featured; }
        public HelpTopic setFeatured(boolean featured) { this.featured = featured; return this; }
        
        public HelpTopic addKeyword(String keyword) { keywords.add(keyword.toLowerCase()); return this; }
        public void addRelatedTopic(String topicId) { relatedTopics.add(topicId); }
    }
    
    /**
     * Help category for organization
     */
    public static class HelpCategory {
        private final String id;
        private final String name;
        private final String description;
        private final String iconPath;
        private final int order;
        
        public HelpCategory(String id, String name, String description, String iconPath, int order) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.iconPath = iconPath;
            this.order = order;
        }
        
        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getIconPath() { return iconPath; }
        public int getOrder() { return order; }
    }
    
    /**
     * Interactive tutorial definition
     */
    public static class Tutorial {
        private final String id;
        private final String title;
        private final String description;
        private final List<TutorialStep> steps;
        private final TutorialDifficulty difficulty;
        private final int estimatedMinutes;
        
        public Tutorial(String id, String title, String description, TutorialDifficulty difficulty, int estimatedMinutes) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.difficulty = difficulty;
            this.estimatedMinutes = estimatedMinutes;
            this.steps = new ArrayList<>();
        }
        
        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public List<TutorialStep> getSteps() { return steps; }
        public TutorialDifficulty getDifficulty() { return difficulty; }
        public int getEstimatedMinutes() { return estimatedMinutes; }
        
        public void addStep(TutorialStep step) { steps.add(step); }
    }
    
    /**
     * Tutorial step with instructions and validation
     */
    public static class TutorialStep {
        private final String title;
        private final String instruction;
        private final String targetElement;
        private final Consumer<Void> action;
        private final TutorialStepValidator validator;
        private boolean optional;
        
        public TutorialStep(String title, String instruction, String targetElement,
                          Consumer<Void> action, TutorialStepValidator validator) {
            this.title = title;
            this.instruction = instruction;
            this.targetElement = targetElement;
            this.action = action;
            this.validator = validator;
            this.optional = false;
        }
        
        public String getTitle() { return title; }
        public String getInstruction() { return instruction; }
        public String getTargetElement() { return targetElement; }
        public Consumer<Void> getAction() { return action; }
        public TutorialStepValidator getValidator() { return validator; }
        public boolean isOptional() { return optional; }
        public void setOptional(boolean optional) { this.optional = optional; }
    }
    
    /**
     * Tutorial difficulty levels
     */
    public enum TutorialDifficulty {
        BEGINNER("Beginner", "Perfect for first-time users"),
        INTERMEDIATE("Intermediate", "For users familiar with basic features"),
        ADVANCED("Advanced", "For experienced users and power features");
        
        private final String displayName;
        private final String description;
        
        TutorialDifficulty(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }
    
    /**
     * Tutorial step validator interface
     */
    @FunctionalInterface
    public interface TutorialStepValidator {
        boolean isCompleted();
    }
    
    /**
     * Context-sensitive help definition
     */
    public static class ContextHelp {
        private final String contextId;
        private final String title;
        private final String quickHelp;
        private final String detailedHelpTopicId;
        private final List<String> quickTips;
        
        public ContextHelp(String contextId, String title, String quickHelp, String detailedHelpTopicId) {
            this.contextId = contextId;
            this.title = title;
            this.quickHelp = quickHelp;
            this.detailedHelpTopicId = detailedHelpTopicId;
            this.quickTips = new ArrayList<>();
        }
        
        public String getContextId() { return contextId; }
        public String getTitle() { return title; }
        public String getQuickHelp() { return quickHelp; }
        public String getDetailedHelpTopicId() { return detailedHelpTopicId; }
        public List<String> getQuickTips() { return quickTips; }
        
        public void addQuickTip(String tip) { quickTips.add(tip); }
    }
    
    private HelpSystem() {
        initializeCategories();
        initializeHelpContent();
        initializeTutorials();
        initializeContextHelp();
        
        this.tutorialSystem = new TutorialSystem();
        this.contextHelpProvider = new ContextHelpProvider();
        
        logger.info("Help system initialized with {} topics, {} tutorials, {} context help items",
                   topics.size(), tutorials.size(), contextHelp.size());
    }
    
    public static synchronized HelpSystem getInstance() {
        if (instance == null) {
            instance = new HelpSystem();
        }
        return instance;
    }
    
    /**
     * Initialize help categories
     */
    private void initializeCategories() {
        categories.addAll(Arrays.asList(
            new HelpCategory("getting-started", "Getting Started", "Essential information for new users", "/icons/getting-started.png", 1),
            new HelpCategory("interface", "User Interface", "Understanding OpenMason's interface", "/icons/interface.png", 2),
            new HelpCategory("modeling", "3D Modeling", "Working with 3D models and textures", "/icons/modeling.png", 3),
            new HelpCategory("navigation", "Navigation", "Viewport and camera controls", "/icons/navigation.png", 4),
            new HelpCategory("tools", "Tools & Features", "Advanced tools and features", "/icons/tools.png", 5),
            new HelpCategory("customization", "Customization", "Personalizing your workspace", "/icons/customization.png", 6),
            new HelpCategory("troubleshooting", "Troubleshooting", "Common issues and solutions", "/icons/troubleshooting.png", 7),
            new HelpCategory("reference", "Reference", "Complete feature reference", "/icons/reference.png", 8)
        ));
    }
    
    /**
     * Initialize help content topics
     */
    private void initializeHelpContent() {
        // Getting Started topics
        addHelpTopic(new HelpTopic("welcome", "Welcome to OpenMason", "getting-started",
            loadHelpContent("welcome.html")))
            .addKeyword("welcome").addKeyword("introduction").setFeatured(true);
        
        addHelpTopic(new HelpTopic("first-steps", "First Steps", "getting-started",
            loadHelpContent("first-steps.html")))
            .addKeyword("first").addKeyword("steps").addKeyword("start").setFeatured(true);
        
        addHelpTopic(new HelpTopic("interface-overview", "Interface Overview", "interface",
            loadHelpContent("interface-overview.html")))
            .addKeyword("interface").addKeyword("overview").addKeyword("ui").setFeatured(true);
        
        // Interface topics
        addHelpTopic(new HelpTopic("model-browser", "Model Browser", "interface",
            loadHelpContent("model-browser.html")))
            .addKeyword("model").addKeyword("browser").addKeyword("panel");
        
        addHelpTopic(new HelpTopic("property-panel", "Property Panel", "interface",
            loadHelpContent("property-panel.html")))
            .addKeyword("property").addKeyword("panel").addKeyword("properties");
        
        addHelpTopic(new HelpTopic("viewport", "3D Viewport", "interface",
            loadHelpContent("viewport.html")))
            .addKeyword("viewport").addKeyword("3d").addKeyword("view");
        
        addHelpTopic(new HelpTopic("toolbar", "Toolbar", "interface",
            loadHelpContent("toolbar.html")))
            .addKeyword("toolbar").addKeyword("buttons").addKeyword("tools");
        
        addHelpTopic(new HelpTopic("status-bar", "Status Bar", "interface",
            loadHelpContent("status-bar.html")))
            .addKeyword("status").addKeyword("bar").addKeyword("information");
        
        // Modeling topics
        addHelpTopic(new HelpTopic("loading-models", "Loading Models", "modeling",
            loadHelpContent("loading-models.html")))
            .addKeyword("load").addKeyword("open").addKeyword("import").addKeyword("model");
        
        addHelpTopic(new HelpTopic("texture-variants", "Texture Variants", "modeling",
            loadHelpContent("texture-variants.html")))
            .addKeyword("texture").addKeyword("variant").addKeyword("material").addKeyword("skin");
        
        addHelpTopic(new HelpTopic("model-validation", "Model Validation", "modeling",
            loadHelpContent("model-validation.html")))
            .addKeyword("validate").addKeyword("validation").addKeyword("check");
        
        // Navigation topics
        addHelpTopic(new HelpTopic("camera-controls", "Camera Controls", "navigation",
            loadHelpContent("camera-controls.html")))
            .addKeyword("camera").addKeyword("control").addKeyword("navigation").addKeyword("zoom");
        
        addHelpTopic(new HelpTopic("view-modes", "View Modes", "navigation",
            loadHelpContent("view-modes.html")))
            .addKeyword("view").addKeyword("mode").addKeyword("wireframe").addKeyword("solid");
        
        // Tools topics
        addHelpTopic(new HelpTopic("keyboard-shortcuts", "Keyboard Shortcuts", "tools",
            loadHelpContent("keyboard-shortcuts.html")))
            .addKeyword("keyboard").addKeyword("shortcut").addKeyword("hotkey").addKeyword("key");
        
        addHelpTopic(new HelpTopic("performance-monitor", "Performance Monitor", "tools",
            loadHelpContent("performance-monitor.html")))
            .addKeyword("performance").addKeyword("monitor").addKeyword("fps").addKeyword("memory");
        
        // Customization topics
        addHelpTopic(new HelpTopic("themes", "Themes & Appearance", "customization",
            loadHelpContent("themes.html")))
            .addKeyword("theme").addKeyword("appearance").addKeyword("color").addKeyword("style");
        
        addHelpTopic(new HelpTopic("docking-panels", "Docking Panels", "customization",
            loadHelpContent("docking-panels.html")))
            .addKeyword("docking").addKeyword("panel").addKeyword("layout").addKeyword("workspace");
        
        // Troubleshooting topics
        addHelpTopic(new HelpTopic("common-issues", "Common Issues", "troubleshooting",
            loadHelpContent("common-issues.html")))
            .addKeyword("issue").addKeyword("problem").addKeyword("troubleshoot").addKeyword("error");
        
        addHelpTopic(new HelpTopic("performance-tips", "Performance Tips", "troubleshooting",
            loadHelpContent("performance-tips.html")))
            .addKeyword("performance").addKeyword("slow").addKeyword("lag").addKeyword("optimize");
        
        // Reference topics
        addHelpTopic(new HelpTopic("menu-reference", "Menu Reference", "reference",
            loadHelpContent("menu-reference.html")))
            .addKeyword("menu").addKeyword("reference").addKeyword("command");
        
        addHelpTopic(new HelpTopic("file-formats", "File Formats", "reference",
            loadHelpContent("file-formats.html")))
            .addKeyword("file").addKeyword("format").addKeyword("json").addKeyword("export");
        
        // Set up related topics
        setupRelatedTopics();
    }
    
    /**
     * Initialize interactive tutorials
     */
    private void initializeTutorials() {
        // Getting Started Tutorial
        Tutorial gettingStarted = new Tutorial("getting-started-tutorial", 
            "Getting Started with OpenMason",
            "Learn the basics of OpenMason in this comprehensive introduction tutorial.",
            TutorialDifficulty.BEGINNER, 10);
        
        gettingStarted.addStep(new TutorialStep(
            "Welcome to OpenMason",
            "Welcome to OpenMason! This tutorial will guide you through the essential features. Click Next to continue.",
            "main-window",
            null,
            () -> true
        ));
        
        gettingStarted.addStep(new TutorialStep(
            "Interface Overview",
            "OpenMason has three main areas: Model Browser (left), 3D Viewport (center), and Property Panel (right). Take a moment to familiarize yourself with the layout.",
            "main-split-pane",
            null,
            () -> true
        ));
        
        gettingStarted.addStep(new TutorialStep(
            "Load Your First Model",
            "Let's load a model. Click the 'Open Model' button in the toolbar or use Ctrl+O.",
            "btnOpenModel",
            v -> {
                // Simulate opening file dialog
                logger.info("Tutorial: Opening model...");
            },
            () -> {
                // Check if a model is loaded
                return true; // Simplified for demo
            }
        ));
        
        gettingStarted.addStep(new TutorialStep(
            "Explore Texture Variants",
            "Once a model is loaded, you can explore different texture variants in the Property Panel. Try changing the texture variant dropdown.",
            "cmbTextureVariant",
            null,
            () -> true
        ));
        
        gettingStarted.addStep(new TutorialStep(
            "Navigate the 3D Viewport",
            "Use your mouse to navigate: Left-click and drag to rotate, right-click and drag to pan, scroll wheel to zoom.",
            "viewportContainer",
            null,
            () -> true
        ));
        
        gettingStarted.addStep(new TutorialStep(
            "Congratulations!",
            "You've completed the getting started tutorial! You now know the basics of OpenMason. Explore the help system for more detailed information.",
            "main-window",
            null,
            () -> true
        ));
        
        tutorials.put(gettingStarted.getId(), gettingStarted);
        
        // Advanced Modeling Tutorial
        Tutorial advancedModeling = new Tutorial("advanced-modeling-tutorial",
            "Advanced Modeling Techniques",
            "Learn advanced modeling and texturing techniques in OpenMason.",
            TutorialDifficulty.ADVANCED, 20);
        
        tutorials.put(advancedModeling.getId(), advancedModeling);
        
        // Customization Tutorial
        Tutorial customization = new Tutorial("customization-tutorial",
            "Customizing Your Workspace",
            "Learn how to customize OpenMason's interface, themes, and shortcuts.",
            TutorialDifficulty.INTERMEDIATE, 15);
        
        tutorials.put(customization.getId(), customization);
    }
    
    /**
     * Initialize context-sensitive help
     */
    private void initializeContextHelp() {
        // Model Browser context help
        ContextHelp modelBrowserHelp = new ContextHelp(
            "model-browser",
            "Model Browser",
            "The Model Browser shows available models and allows you to select different variants. Double-click a model to load it.",
            "model-browser"
        );
        modelBrowserHelp.addQuickTip("Use the search field to quickly find specific models");
        modelBrowserHelp.addQuickTip("Filter by category using the dropdown");
        contextHelp.put(modelBrowserHelp.getContextId(), modelBrowserHelp);
        
        // Property Panel context help
        ContextHelp propertyPanelHelp = new ContextHelp(
            "property-panel",
            "Property Panel",
            "The Property Panel shows details about the selected model and allows you to modify its appearance and properties.",
            "property-panel"
        );
        propertyPanelHelp.addQuickTip("Change texture variants using the dropdown");
        propertyPanelHelp.addQuickTip("Adjust model rotation and scale with the sliders");
        contextHelp.put(propertyPanelHelp.getContextId(), propertyPanelHelp);
        
        // 3D Viewport context help
        ContextHelp viewportHelp = new ContextHelp(
            "viewport",
            "3D Viewport",
            "The 3D Viewport displays your model in real-time. Use mouse controls to navigate around the model.",
            "viewport"
        );
        viewportHelp.addQuickTip("Left-click and drag to rotate the camera");
        viewportHelp.addQuickTip("Right-click and drag to pan the view");
        viewportHelp.addQuickTip("Scroll wheel to zoom in and out");
        viewportHelp.addQuickTip("Press Home to reset the camera view");
        contextHelp.put(viewportHelp.getContextId(), viewportHelp);
        
        // Toolbar context help
        ContextHelp toolbarHelp = new ContextHelp(
            "toolbar",
            "Toolbar",
            "The toolbar provides quick access to common operations like opening models, saving, and view controls.",
            "toolbar"
        );
        toolbarHelp.addQuickTip("Hover over buttons to see tooltips with keyboard shortcuts");
        toolbarHelp.addQuickTip("Toggle wireframe mode for better model analysis");
        contextHelp.put(toolbarHelp.getContextId(), toolbarHelp);
    }
    
    /**
     * Set up related topics links
     */
    private void setupRelatedTopics() {
        // Interface topics are related to each other
        topics.get("interface-overview").addRelatedTopic("model-browser");
        topics.get("interface-overview").addRelatedTopic("property-panel");
        topics.get("interface-overview").addRelatedTopic("viewport");
        
        topics.get("model-browser").addRelatedTopic("loading-models");
        topics.get("property-panel").addRelatedTopic("texture-variants");
        topics.get("viewport").addRelatedTopic("camera-controls");
        
        // Navigation topics
        topics.get("camera-controls").addRelatedTopic("view-modes");
        topics.get("view-modes").addRelatedTopic("camera-controls");
        
        // Customization topics
        topics.get("themes").addRelatedTopic("docking-panels");
        topics.get("docking-panels").addRelatedTopic("themes");
        topics.get("keyboard-shortcuts").addRelatedTopic("customization");
    }
    
    /**
     * Add a help topic to the registry
     */
    private HelpTopic addHelpTopic(HelpTopic topic) {
        topics.put(topic.getId(), topic);
        return topic;
    }
    
    /**
     * Load help content from resources
     */
    private String loadHelpContent(String filename) {
        try {
            InputStream stream = getClass().getResourceAsStream("/help/" + filename);
            if (stream != null) {
                return new String(stream.readAllBytes());
            }
        } catch (Exception e) {
            logger.warn("Failed to load help content: {}", filename, e);
        }
        
        // Return default content if file not found
        return generateDefaultHelpContent(filename);
    }
    
    /**
     * Generate default help content for missing files
     */
    private String generateDefaultHelpContent(String filename) {
        String topicName = filename.replace(".html", "").replace("-", " ");
        topicName = Character.toUpperCase(topicName.charAt(0)) + topicName.substring(1);
        
        return String.format("""
            <html>
            <head><title>%s</title></head>
            <body>
            <h1>%s</h1>
            <p>This help topic is under development. More detailed information will be available in future updates.</p>
            <p>In the meantime, you can:</p>
            <ul>
            <li>Explore the feature through the user interface</li>
            <li>Check the tooltips for additional guidance</li>
            <li>Try the interactive tutorials</li>
            </ul>
            </body>
            </html>
            """, topicName, topicName);
    }
    
    /**
     * Show the help browser
     */
    public void showHelp() {
        showHelp(null);
    }
    
    /**
     * Show help for a specific topic
     */
    public void showHelp(String topicId) {
        if (helpBrowser == null) {
            helpBrowser = new HelpBrowser(this);
        }
        
        if (topicId != null && topics.containsKey(topicId)) {
            helpBrowser.showTopic(topics.get(topicId));
        }
        
        helpBrowser.show();
        helpVisible.set(true);
        
        logger.info("Showed help system" + (topicId != null ? " for topic: " + topicId : ""));
    }
    
    /**
     * Hide the help browser
     */
    public void hideHelp() {
        if (helpBrowser != null) {
            helpBrowser.hide();
        }
        helpVisible.set(false);
    }
    
    /**
     * Show context-sensitive help
     */
    public void showContextHelp(String contextId) {
        ContextHelp help = contextHelp.get(contextId);
        if (help != null) {
            if (contextHelpProvider == null) {
                contextHelpProvider = new ContextHelpProvider();
            }
            contextHelpProvider.showContextHelp(help);
            currentContext.set(contextId);
        }
    }
    
    /**
     * Start an interactive tutorial
     */
    public void startTutorial(String tutorialId) {
        Tutorial tutorial = tutorials.get(tutorialId);
        if (tutorial != null) {
            if (tutorialSystem == null) {
                tutorialSystem = new TutorialSystem();
            }
            tutorialSystem.startTutorial(tutorial);
            logger.info("Started tutorial: {}", tutorial.getTitle());
        }
    }
    
    /**
     * Search help topics
     */
    public List<HelpTopic> searchTopics(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>(topics.values());
        }
        
        String searchQuery = query.toLowerCase().trim();
        List<HelpTopic> results = new ArrayList<>();
        
        for (HelpTopic topic : topics.values()) {
            int score = 0;
            
            // Title match (highest priority)
            if (topic.getTitle().toLowerCase().contains(searchQuery)) {
                score += 10;
            }
            
            // Keyword match
            for (String keyword : topic.getKeywords()) {
                if (keyword.contains(searchQuery)) {
                    score += 5;
                }
            }
            
            // Content match (lower priority)
            if (topic.getContent().toLowerCase().contains(searchQuery)) {
                score += 1;
            }
            
            if (score > 0) {
                results.add(topic);
            }
        }
        
        // Sort by relevance (score) and then by title
        results.sort((a, b) -> {
            // Calculate scores for sorting
            int scoreA = calculateTopicScore(a, searchQuery);
            int scoreB = calculateTopicScore(b, searchQuery);
            
            if (scoreA != scoreB) {
                return Integer.compare(scoreB, scoreA); // Higher score first
            }
            return a.getTitle().compareTo(b.getTitle());
        });
        
        logger.debug("Found {} help topics matching query: {}", results.size(), query);
        return results;
    }
    
    /**
     * Calculate topic relevance score for search
     */
    private int calculateTopicScore(HelpTopic topic, String query) {
        int score = 0;
        
        if (topic.getTitle().toLowerCase().contains(query)) score += 10;
        if (topic.isFeatured()) score += 5;
        
        for (String keyword : topic.getKeywords()) {
            if (keyword.contains(query)) score += 3;
        }
        
        if (topic.getContent().toLowerCase().contains(query)) score += 1;
        
        return score;
    }
    
    /**
     * Get featured help topics
     */
    public List<HelpTopic> getFeaturedTopics() {
        return topics.values().stream()
                .filter(HelpTopic::isFeatured)
                .sorted(Comparator.comparing(HelpTopic::getTitle))
                .toList();
    }
    
    /**
     * Get topics in a specific category
     */
    public List<HelpTopic> getTopicsInCategory(String categoryId) {
        return topics.values().stream()
                .filter(topic -> categoryId.equals(topic.getCategoryId()))
                .sorted(Comparator.comparing(HelpTopic::getTitle))
                .toList();
    }
    
    /**
     * Show getting started wizard
     */
    public void showGettingStartedWizard() {
        // This would show a step-by-step wizard for new users
        startTutorial("getting-started-tutorial");
        logger.info("Showed getting started wizard");
    }
    
    /**
     * Show keyboard shortcuts reference
     */
    public void showKeyboardShortcuts() {
        showHelp("keyboard-shortcuts");
    }
    
    /**
     * Show what's new dialog
     */
    public void showWhatsNew() {
        // This would show recent changes and new features
        showHelp("whats-new");
    }
    
    // Property getters
    public boolean isHelpVisible() { return helpVisible.get(); }
    public BooleanProperty helpVisibleProperty() { return helpVisible; }
    
    public String getCurrentContext() { return currentContext.get(); }
    public StringProperty currentContextProperty() { return currentContext; }
    
    public HelpTopic getCurrentTopic() { return currentTopic.get(); }
    public ObjectProperty<HelpTopic> currentTopicProperty() { return currentTopic; }
    
    public ObservableList<HelpCategory> getCategories() { return categories; }
    public Collection<HelpTopic> getAllTopics() { return topics.values(); }
    public Collection<Tutorial> getAllTutorials() { return tutorials.values(); }
    
    public HelpTopic getTopic(String topicId) { return topics.get(topicId); }
    public Tutorial getTutorial(String tutorialId) { return tutorials.get(tutorialId); }
    public ContextHelp getContextHelp(String contextId) { return contextHelp.get(contextId); }
    
    /**
     * Clean up resources
     */
    public void dispose() {
        if (helpBrowser != null) {
            helpBrowser.close();
        }
        if (tutorialSystem != null) {
            tutorialSystem.dispose();
        }
        if (contextHelpProvider != null) {
            contextHelpProvider.dispose();
        }
        
        logger.info("Help system disposed");
    }
}
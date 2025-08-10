package com.openmason.ui.help;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Core help system providing structured documentation, tutorials, and context-sensitive help.
 * 
 * This system manages:
 * - Help topics organized by categories
 * - Interactive tutorials with difficulty levels
 * - Context-sensitive help for UI elements
 * - Search functionality across all content
 * - Navigation history and bookmarks
 */
public class HelpSystem {
    
    private static final Logger logger = LoggerFactory.getLogger(HelpSystem.class);
    
    // Content storage
    private final Map<String, HelpCategory> categories = new LinkedHashMap<>();
    private final Map<String, HelpTopic> topics = new HashMap<>();
    private final Map<String, Tutorial> tutorials = new HashMap<>();
    private final Map<String, ContextHelp> contextHelp = new HashMap<>();
    
    // Featured content
    private final List<String> featuredTopicIds = new ArrayList<>();
    
    /**
     * Represents a help category for organizing topics.
     */
    public static class HelpCategory {
        private final String id;
        private final String name;
        private final String description;
        private final List<String> topicIds;
        private final int displayOrder;
        
        public HelpCategory(String id, String name, String description, int displayOrder) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.displayOrder = displayOrder;
            this.topicIds = new ArrayList<>();
        }
        
        // Getters
        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public List<String> getTopicIds() { return new ArrayList<>(topicIds); }
        public int getDisplayOrder() { return displayOrder; }
        
        // Package-private mutators for HelpSystem
        void addTopicId(String topicId) { topicIds.add(topicId); }
        void removeTopicId(String topicId) { topicIds.remove(topicId); }
    }
    
    /**
     * Represents an individual help topic.
     */
    public static class HelpTopic {
        private final String id;
        private final String title;
        private final String content;
        private final String categoryId;
        private final List<String> relatedTopicIds;
        private final List<String> tags;
        private final long lastModified;
        
        public HelpTopic(String id, String title, String content, String categoryId) {
            this.id = id;
            this.title = title;
            this.content = content;
            this.categoryId = categoryId;
            this.relatedTopicIds = new ArrayList<>();
            this.tags = new ArrayList<>();
            this.lastModified = System.currentTimeMillis();
        }
        
        // Getters
        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getContent() { return content; }
        public String getCategoryId() { return categoryId; }
        public List<String> getRelatedTopics() { return new ArrayList<>(relatedTopicIds); }
        public List<String> getTags() { return new ArrayList<>(tags); }
        public long getLastModified() { return lastModified; }
        
        // Package-private mutators for HelpSystem
        void addRelatedTopic(String topicId) { relatedTopicIds.add(topicId); }
        void addTag(String tag) { tags.add(tag); }
    }
    
    /**
     * Represents an interactive tutorial.
     */
    public static class Tutorial {
        private final String id;
        private final String title;
        private final String description;
        private final TutorialDifficulty difficulty;
        private final int estimatedMinutes;
        private final List<TutorialStep> steps;
        private final List<String> prerequisites;
        
        public Tutorial(String id, String title, String description, 
                       TutorialDifficulty difficulty, int estimatedMinutes) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.difficulty = difficulty;
            this.estimatedMinutes = estimatedMinutes;
            this.steps = new ArrayList<>();
            this.prerequisites = new ArrayList<>();
        }
        
        // Getters
        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public TutorialDifficulty getDifficulty() { return difficulty; }
        public int getEstimatedMinutes() { return estimatedMinutes; }
        public List<String> getSteps() { 
            return steps.stream().map(TutorialStep::getContent).collect(Collectors.toList());
        }
        public List<TutorialStep> getStepObjects() { return new ArrayList<>(steps); }
        public List<String> getPrerequisites() { return new ArrayList<>(prerequisites); }
        
        // Package-private mutators for HelpSystem
        void addStep(TutorialStep step) { steps.add(step); }
        void addPrerequisite(String prerequisite) { prerequisites.add(prerequisite); }
    }
    
    /**
     * Represents a tutorial step.
     */
    public static class TutorialStep {
        private final String title;
        private final String content;
        private final String actionDescription;
        private final boolean requiresUserAction;
        
        public TutorialStep(String title, String content, String actionDescription, boolean requiresUserAction) {
            this.title = title;
            this.content = content;
            this.actionDescription = actionDescription;
            this.requiresUserAction = requiresUserAction;
        }
        
        public String getTitle() { return title; }
        public String getContent() { return content; }
        public String getActionDescription() { return actionDescription; }
        public boolean requiresUserAction() { return requiresUserAction; }
    }
    
    /**
     * Tutorial difficulty levels.
     */
    public enum TutorialDifficulty {
        BEGINNER("Beginner", "Easy to follow tutorials for newcomers", 1), 
        INTERMEDIATE("Intermediate", "Moderate difficulty tutorials", 2), 
        ADVANCED("Advanced", "Complex tutorials for experienced users", 3);
        
        private final String displayName;
        private final String description;
        private final int level;
        
        TutorialDifficulty(String displayName, String description, int level) {
            this.displayName = displayName;
            this.description = description;
            this.level = level;
        }
        
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public int getLevel() { return level; }
    }
    
    /**
     * Context-sensitive help for UI elements.
     */
    public static class ContextHelp {
        private final String contextId;
        private final String quickHelp;
        private final String detailedHelp;
        private final String relatedTopicId;
        
        public ContextHelp(String contextId, String quickHelp, String detailedHelp, String relatedTopicId) {
            this.contextId = contextId;
            this.quickHelp = quickHelp;
            this.detailedHelp = detailedHelp;
            this.relatedTopicId = relatedTopicId;
        }
        
        public String getContextId() { return contextId; }
        public String getContent() { return detailedHelp; }
        public String getQuickHelp() { return quickHelp; }
        public String getDetailedHelp() { return detailedHelp; }
        public String getRelatedTopicId() { return relatedTopicId; }
    }
    
    /**
     * Initialize the help system with default content.
     */
    public void initialize() {
        logger.info("Initializing help system...");
        
        try {
            loadDefaultContent();
            logger.info("Help system initialized successfully with {} categories, {} topics, {} tutorials", 
                       categories.size(), topics.size(), tutorials.size());
        } catch (Exception e) {
            logger.error("Failed to initialize help system", e);
            throw new RuntimeException("Help system initialization failed", e);
        }
    }
    
    /**
     * Load default help content.
     */
    private void loadDefaultContent() {
        // Create default categories
        addCategory(new HelpCategory("getting-started", "Getting Started", 
                                   "Essential information for new users", 1));
        addCategory(new HelpCategory("modeling", "3D Modeling", 
                                   "Creating and editing 3D models", 2));
        addCategory(new HelpCategory("texturing", "Texture System", 
                                   "Working with textures and materials", 3));
        addCategory(new HelpCategory("viewport", "Viewport Controls", 
                                   "Navigating and controlling the 3D viewport", 4));
        addCategory(new HelpCategory("troubleshooting", "Troubleshooting", 
                                   "Common issues and solutions", 5));
        
        // Add default topics
        addDefaultTopics();
        
        // Add default tutorials
        addDefaultTutorials();
        
        // Add context help
        addDefaultContextHelp();
    }
    
    private void addDefaultTopics() {
        // Getting Started topics
        HelpTopic overview = new HelpTopic("overview", "OpenMason Overview", 
            "# Welcome to OpenMason\n\nOpenMason is a professional 3D model and texture development tool...", 
            "getting-started");
        addTopic(overview);
        featuredTopicIds.add("overview");
        
        HelpTopic shortcuts = new HelpTopic("keyboard-shortcuts", "Keyboard Shortcuts", 
            "# Keyboard Shortcuts\n\n## Navigation\n- **Mouse Drag**: Rotate camera\n- **Scroll**: Zoom in/out\n- **Shift+Drag**: Pan camera", 
            "getting-started");
        addTopic(shortcuts);
        featuredTopicIds.add("keyboard-shortcuts");
        
        // Troubleshooting topics
        HelpTopic commonIssues = new HelpTopic("common-issues", "Common Issues", 
            "# Common Issues and Solutions\n\n## Model Loading Problems\n- Check file format compatibility\n- Verify file permissions", 
            "troubleshooting");
        addTopic(commonIssues);
        featuredTopicIds.add("common-issues");
    }
    
    private void addDefaultTutorials() {
        // Beginner tutorial
        Tutorial gettingStarted = new Tutorial("getting-started-tutorial", 
            "Getting Started with OpenMason", 
            "Learn the basics of using OpenMason for 3D model development",
            TutorialDifficulty.BEGINNER, 15);
        
        gettingStarted.addStep(new TutorialStep("Welcome", 
            "Welcome to OpenMason! This tutorial will guide you through the basics.", 
            "Click Next to continue", false));
        gettingStarted.addStep(new TutorialStep("Open a Model", 
            "Let's start by opening a 3D model file.", 
            "Use File > Open Model to load a model", true));
        gettingStarted.addStep(new TutorialStep("Navigate the Viewport", 
            "Learn to navigate around your model using mouse controls.", 
            "Drag to rotate, scroll to zoom", true));
        
        addTutorial(gettingStarted);
    }
    
    private void addDefaultContextHelp() {
        addContextHelp(new ContextHelp("model-loader", 
            "Load 3D models from various file formats", 
            "The model loader supports JSON model definitions and can import models with textures and animations.",
            "model-loading"));
        
        addContextHelp(new ContextHelp("texture-variants", 
            "Switch between different texture variants", 
            "Texture variants allow you to preview different visual styles for the same model.",
            "texture-system"));
    }
    
    // Public API methods
    
    public void addCategory(HelpCategory category) {
        categories.put(category.getId(), category);
    }
    
    public void addTopic(HelpTopic topic) {
        topics.put(topic.getId(), topic);
        
        // Add to category if it exists
        HelpCategory category = categories.get(topic.getCategoryId());
        if (category != null) {
            category.addTopicId(topic.getId());
        }
    }
    
    public void addTutorial(Tutorial tutorial) {
        tutorials.put(tutorial.getId(), tutorial);
    }
    
    public void addContextHelp(ContextHelp help) {
        contextHelp.put(help.getContextId(), help);
    }
    
    public Collection<HelpCategory> getCategories() { 
        return categories.values().stream()
                        .sorted(Comparator.comparingInt(HelpCategory::getDisplayOrder))
                        .collect(Collectors.toList());
    }
    
    public List<HelpTopic> getTopicsInCategory(String categoryId) { 
        HelpCategory category = categories.get(categoryId);
        if (category == null) return new ArrayList<>();
        
        return category.getTopicIds().stream()
                      .map(topics::get)
                      .filter(Objects::nonNull)
                      .collect(Collectors.toList());
    }
    
    public Collection<Tutorial> getAllTutorials() { 
        return tutorials.values();
    }
    
    public List<HelpTopic> getFeaturedTopics() { 
        return featuredTopicIds.stream()
                              .map(topics::get)
                              .filter(Objects::nonNull)
                              .collect(Collectors.toList());
    }
    
    public HelpTopic getTopic(String id) { 
        return topics.get(id);
    }
    
    public ContextHelp getContextHelp(String contextId) { 
        return contextHelp.get(contextId);
    }
    
    public Tutorial getTutorial(String tutorialId) {
        return tutorials.get(tutorialId);
    }
    
    public void startTutorial(String tutorialId) { 
        Tutorial tutorial = tutorials.get(tutorialId);
        if (tutorial != null) {
            logger.info("Starting tutorial: {} ({})", tutorial.getTitle(), tutorialId);
            // Tutorial execution would be handled by a separate TutorialManager
        } else {
            logger.warn("Tutorial not found: {}", tutorialId);
        }
    }
    
    public List<HelpTopic> searchTopics(String query) { 
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        String lowerQuery = query.toLowerCase().trim();
        
        return topics.values().stream()
                    .filter(topic -> 
                        topic.getTitle().toLowerCase().contains(lowerQuery) ||
                        topic.getContent().toLowerCase().contains(lowerQuery) ||
                        topic.getTags().stream().anyMatch(tag -> tag.toLowerCase().contains(lowerQuery)))
                    .sorted(Comparator.comparing(HelpTopic::getTitle))
                    .collect(Collectors.toList());
    }
    
    /**
     * Get help system statistics.
     */
    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("categories", categories.size());
        stats.put("topics", topics.size());
        stats.put("tutorials", tutorials.size());
        stats.put("contextHelp", contextHelp.size());
        return stats;
    }
    
    /**
     * Clear all help content.
     */
    public void clear() {
        categories.clear();
        topics.clear();
        tutorials.clear();
        contextHelp.clear();
        featuredTopicIds.clear();
        logger.info("Help system content cleared");
    }
    
    /**
     * Dispose of help system resources.
     */
    public void dispose() {
        clear();
        logger.info("Help system disposed");
    }
}
package com.openmason.ui.help;

import com.openmason.ui.icons.IconTextureManager;
import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImString;
import imgui.type.ImBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dear ImGui-based help browser with markdown rendering, searchable documentation,
 * and context-sensitive help system.
 * 
 * Features:
 * - Tabbed navigation (Topics, Tutorials)
 * - Full-text search with preview
 * - Navigation history (back/forward)
 * - Markdown content rendering
 * - Context-sensitive help tooltips
 * - Interactive tutorials
 * - Responsive layout with resizable panels
 * 
 * Architecture:
 * - Uses separate HelpSystem for content management
 * - MarkdownRenderer for rich text display
 * - ContextHelpRenderer for tooltips and inline help
 * - Modular design for easy extension
 */
public class ImGuiHelpBrowser {
    
    private static final Logger logger = LoggerFactory.getLogger(ImGuiHelpBrowser.class);
    
    // Constants
    private static final int MAX_HISTORY_SIZE = 50;
    private static final float DEFAULT_WINDOW_WIDTH = 1000.0f;
    private static final float DEFAULT_WINDOW_HEIGHT = 700.0f;
    private static final float MIN_LEFT_PANEL_WIDTH = 200.0f;
    private static final float MAX_LEFT_PANEL_WIDTH = 500.0f;
    
    // Core components
    private final HelpSystem helpSystem;
    private final MarkdownRenderer markdownRenderer;
    private final ContextHelpRenderer contextHelpRenderer;
    private final IconTextureManager iconManager;
    
    // Window state
    private final ImBoolean windowVisible = new ImBoolean(false);
    private boolean isFirstFrame = true;
    private boolean isInitialized = false;
    
    // Navigation state
    private final Deque<HelpSystem.HelpTopic> navigationHistory = new ArrayDeque<>();
    private final Deque<HelpSystem.HelpTopic> forwardHistory = new ArrayDeque<>();
    private HelpSystem.HelpTopic currentTopic;
    
    // Search state
    private final ImString searchQuery = new ImString(512);
    private List<HelpSystem.HelpTopic> searchResults = new ArrayList<>();
    private boolean isSearchActive = false;
    private String lastSearchQuery = "";
    
    // UI state
    private int selectedCategoryIndex = 0;
    private int selectedTopicIndex = -1;
    private int selectedTutorialIndex = -1;
    private float leftPanelWidth = 300.0f;
    private String currentTabName = "Topics"; // Track current tab for state persistence
    
    // Error handling
    private String lastError = null;
    private long lastErrorTime = 0;
    
    /**
     * Create a new help browser with the specified help system.
     * 
     * @param helpSystem The help system to use for content
     */
    public ImGuiHelpBrowser(HelpSystem helpSystem) {
        if (helpSystem == null) {
            throw new IllegalArgumentException("Help system cannot be null");
        }
        
        this.helpSystem = helpSystem;
        this.markdownRenderer = new MarkdownRenderer(this::handleTopicLinkClick);
        this.contextHelpRenderer = new ContextHelpRenderer();
        this.iconManager = IconTextureManager.getInstance();
        
        // Initialize help system if not already done
        try {
            if (helpSystem.getCategories().isEmpty()) {
                helpSystem.initialize();
            }
            this.isInitialized = true;
            logger.info("ImGui help browser initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize help browser", e);
            setError("Failed to initialize help system: " + e.getMessage());
        }
    }
    
    /**
     * Check if help browser is properly initialized.
     * 
     * @return true if all components are initialized and ready
     */
    public boolean isInitialized() {
        return isInitialized && helpSystem != null && markdownRenderer != null && contextHelpRenderer != null;
    }
    
    /**
     * Apply theme to help browser
     */
    public void applyTheme(String themeId) {
        try {
            logger.info("Applying theme '{}' to help browser", themeId);
            
            // Theme application for help browser would involve:
            // - Updating syntax highlighting colors for markdown
            // - Adjusting window background colors
            // - Modifying link and header colors
            // - Updating tooltip styling
            
            // For now, we'll just log the theme change
            // In a full implementation, you might:
            // 1. Update color schemes for markdown rendering
            // 2. Refresh cached content with new styling
            // 3. Apply theme to custom UI elements
            
        } catch (Exception e) {
            logger.error("Failed to apply theme '{}' to help browser", themeId, e);
        }
    }
    
    /**
     * Show the help browser window.
     */
    public void show() {
        if (!isInitialized) {
            logger.warn("Cannot show help browser - not properly initialized");
            return;
        }
        
        windowVisible.set(true);
        isFirstFrame = true;
        logger.debug("Help browser shown");
    }
    
    /**
     * Hide the help browser window.
     */
    public void hide() {
        windowVisible.set(false);
        logger.debug("Help browser hidden");
    }
    
    /**
     * Toggle the help browser visibility.
     */
    public void toggle() {
        if (isVisible()) {
            hide();
        } else {
            show();
        }
    }
    
    /**
     * Render the help browser window.
     * Call this method every frame when the browser should be visible.
     */
    public void render() {
        if (!windowVisible.get() || !isInitialized) {
            return;
        }
        
        try {
            // Set up window on first frame
            if (isFirstFrame) {
                setupWindow();
                isFirstFrame = false;
            }
            
            // Apply window styling
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 12, 10);
            ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8, 6);
            
            // Begin main window
            int windowFlags = ImGuiWindowFlags.MenuBar | ImGuiWindowFlags.NoCollapse;
            if (ImGui.begin("OpenMason Help Browser", windowVisible, windowFlags)) {
                
                // Show error if present
                if (lastError != null) {
                    renderError();
                }
                
                renderMenuBar();
                ImGui.separator();
                renderMainContent();
            }
            ImGui.end();
            
            ImGui.popStyleVar(2);
            
            // Handle window close
            if (!windowVisible.get()) {
                onWindowClosed();
            }
            
        } catch (Exception e) {
            logger.error("Error rendering help browser", e);
            setError("Rendering error: " + e.getMessage());
        }
    }
    
    /**
     * Render the menu bar with navigation and search.
     */
    private void renderMenuBar() {
        if (ImGui.beginMenuBar()) {
            
            // Navigation buttons
            boolean canGoBack = !navigationHistory.isEmpty();
            boolean canGoForward = !forwardHistory.isEmpty();
            
            // Back button
            if (!canGoBack) {
                ImGui.pushStyleVar(ImGuiStyleVar.Alpha, 0.5f);
                ImGui.button("← Back");
                ImGui.popStyleVar();
            } else {
                if (ImGui.button("← Back")) {
                    navigateBack();
                }
            }
            
            if (ImGui.isItemHovered() && canGoBack) {
                contextHelpRenderer.showContextHelpImmediate(
                    "Go back to: " + navigationHistory.peekLast().getTitle());
            }
            
            ImGui.sameLine();
            
            // Forward button
            if (!canGoForward) {
                ImGui.pushStyleVar(ImGuiStyleVar.Alpha, 0.5f);
                ImGui.button("→ Forward");
                ImGui.popStyleVar();
            } else {
                if (ImGui.button("→ Forward")) {
                    navigateForward();
                }
            }
            
            if (ImGui.isItemHovered() && canGoForward) {
                contextHelpRenderer.showContextHelpImmediate(
                    "Go forward to: " + forwardHistory.peekLast().getTitle());
            }
            
            ImGui.sameLine();
            
            // Home button
            if (ImGui.button("🏠 Home")) {
                showHome();
            }
            
            if (ImGui.isItemHovered()) {
                contextHelpRenderer.showContextHelpImmediate("Return to the home page");
            }
            
            ImGui.sameLine();
            ImGui.separator();
            ImGui.sameLine();
            
            // Current topic indicator
            if (currentTopic != null) {
                ImGui.textColored(0.7f, 0.7f, 0.7f, 1.0f, "Viewing: " + currentTopic.getTitle());
                ImGui.sameLine();
                ImGui.separator();
                ImGui.sameLine();
            }
            
            // Search box
            ImGui.setNextItemWidth(250);
            if (ImGui.inputTextWithHint("##search", "Search help topics...", searchQuery)) {
                performSearch();
            }
            
            if (ImGui.isItemHovered()) {
                contextHelpRenderer.showContextHelpImmediate(
                    "Search through all help topics and tutorials. Press Enter to search.");
            }
            
            ImGui.sameLine();
            if (ImGui.button("🔍") || ImGui.isKeyPressed(ImGui.getKeyIndex(imgui.flag.ImGuiKey.Enter))) {
                performSearch();
            }
            
            // Clear search button
            if (isSearchActive) {
                ImGui.sameLine();
                if (ImGui.button("✖")) {
                    searchQuery.set("");
                    isSearchActive = false;
                    searchResults.clear();
                    lastSearchQuery = "";
                }
                
                if (ImGui.isItemHovered()) {
                    contextHelpRenderer.showContextHelpImmediate("Clear search results");
                }
            }
            
            ImGui.endMenuBar();
        }
    }
    
    /**
     * Set up the window on first frame.
     */
    private void setupWindow() {
        // Center window on screen
        float displayWidth = ImGui.getIO().getDisplaySizeX();
        float displayHeight = ImGui.getIO().getDisplaySizeY();
        
        ImGui.setNextWindowSize(DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
        ImGui.setNextWindowPos(
            (displayWidth - DEFAULT_WINDOW_WIDTH) * 0.5f,
            (displayHeight - DEFAULT_WINDOW_HEIGHT) * 0.5f
        );
    }
    
    /**
     * Render error message if present.
     */
    private void renderError() {
        contextHelpRenderer.showErrorHelp("Help Browser Error", lastError);
        
        ImGui.sameLine();
        if (ImGui.button("Clear")) {
            clearError();
        }
        
        ImGui.spacing();
    }
    
    /**
     * Render main content area with resizable panels.
     */
    private void renderMainContent() {
        // Use table for better layout control
        if (ImGui.beginTable("help_layout", 2, ImGuiTableFlags.Resizable | ImGuiTableFlags.BordersInnerV)) {
            
            // Set up columns
            ImGui.tableSetupColumn("Navigation", ImGuiTableColumnFlags.WidthFixed, leftPanelWidth);
            ImGui.tableSetupColumn("Content", ImGuiTableColumnFlags.WidthStretch);
            
            ImGui.tableNextRow();
            
            // Left panel - Navigation
            ImGui.tableNextColumn();
            renderNavigationPanel();
            
            // Right panel - Content
            ImGui.tableNextColumn();
            renderContentPanel();
            
            // Update panel width based on table column width
            leftPanelWidth = Math.max(MIN_LEFT_PANEL_WIDTH, 
                            Math.min(MAX_LEFT_PANEL_WIDTH, ImGui.getColumnWidth(0)));
            
            ImGui.endTable();
        }
    }
    
    /**
     * Render navigation panel
     */
    private void renderNavigationPanel() {
        if (ImGui.beginTabBar("NavTabs")) {
            
            if (ImGui.beginTabItem("Topics")) {
                renderTopicsNavigation();
                ImGui.endTabItem();
            }
            
            if (ImGui.beginTabItem("Tutorials")) {
                renderTutorialsNavigation();
                ImGui.endTabItem();
            }
            
            ImGui.endTabBar();
        }
    }
    
    /**
     * Render topics navigation tree
     */
    private void renderTopicsNavigation() {
        List<HelpSystem.HelpCategory> categories = new ArrayList<>(helpSystem.getCategories());
        
        for (int categoryIndex = 0; categoryIndex < categories.size(); categoryIndex++) {
            HelpSystem.HelpCategory category = categories.get(categoryIndex);
            
            boolean categoryOpen = ImGui.treeNodeEx(
                category.getName(),
                ImGuiTreeNodeFlags.DefaultOpen | ImGuiTreeNodeFlags.SpanFullWidth
            );
            
            if (categoryOpen) {
                List<HelpSystem.HelpTopic> topics = helpSystem.getTopicsInCategory(category.getId());
                
                for (int topicIndex = 0; topicIndex < topics.size(); topicIndex++) {
                    HelpSystem.HelpTopic topic = topics.get(topicIndex);
                    
                    boolean isSelected = currentTopic == topic;
                    
                    if (ImGui.selectable(topic.getTitle(), isSelected)) {
                        showTopic(topic);
                    }
                    
                    // Context help on hover
                    if (ImGui.isItemHovered()) {
                        contextHelpRenderer.showContextHelp(
                            topic.getId(),
                            "Click to view " + topic.getTitle() + " help topic."
                        );
                    }
                }
                
                ImGui.treePop();
            }
        }
    }
    
    /**
     * Render tutorials navigation
     */
    private void renderTutorialsNavigation() {
        Collection<HelpSystem.Tutorial> allTutorials = helpSystem.getAllTutorials();
        
        // Group by difficulty
        Map<HelpSystem.TutorialDifficulty, List<HelpSystem.Tutorial>> tutorialsByDifficulty = 
            allTutorials.stream().collect(Collectors.groupingBy(HelpSystem.Tutorial::getDifficulty));
        
        for (HelpSystem.TutorialDifficulty difficulty : HelpSystem.TutorialDifficulty.values()) {
            List<HelpSystem.Tutorial> tutorials = tutorialsByDifficulty.get(difficulty);
            if (tutorials == null || tutorials.isEmpty()) continue;
            
            if (ImGui.treeNodeEx(difficulty.getDisplayName(), ImGuiTreeNodeFlags.DefaultOpen)) {
                ImGui.textDisabled(difficulty.getDescription());
                ImGui.spacing();
                
                for (HelpSystem.Tutorial tutorial : tutorials) {
                    if (ImGui.selectable(tutorial.getTitle())) {
                        showTutorial(tutorial);
                    }
                    
                    // Show tutorial metadata
                    ImGui.sameLine();
                    ImGui.textDisabled(String.format("(%d min)", tutorial.getEstimatedMinutes()));
                    
                    if (ImGui.isItemHovered()) {
                        ImGui.beginTooltip();
                        ImGui.text(tutorial.getDescription());
                        ImGui.endTooltip();
                    }
                }
                
                ImGui.treePop();
            }
        }
    }
    
    /**
     * Render the main content panel.
     */
    private void renderContentPanel() {
        if (ImGui.beginChild("ContentPanel", 0, 0, false, 
                           ImGuiWindowFlags.AlwaysVerticalScrollbar)) {
            
            // Add some padding
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 16, 12);
            
            try {
                if (isSearchActive && !searchResults.isEmpty()) {
                    renderSearchResults();
                } else if (isSearchActive && searchResults.isEmpty() && !searchQuery.get().trim().isEmpty()) {
                    renderNoSearchResults();
                } else if (currentTopic != null) {
                    renderTopicContent();
                } else {
                    renderHomeContent();
                }
            } catch (Exception e) {
                logger.error("Error rendering content panel", e);
                contextHelpRenderer.showErrorHelp("Content Error", "Failed to render content: " + e.getMessage());
            }
            
            ImGui.popStyleVar();
        }
        ImGui.endChild();
    }
    
    /**
     * Render search results
     */
    private void renderSearchResults() {
        ImGui.text(String.format("Search results for \"%s\":", searchQuery.get()));
        ImGui.separator();
        ImGui.spacing();
        
        for (HelpSystem.HelpTopic topic : searchResults) {
            if (ImGui.selectable(topic.getTitle())) {
                showTopic(topic);
                isSearchActive = false;
            }
            
            ImGui.indent();
            ImGui.textDisabled(getTopicPreview(topic.getContent()));
            ImGui.unindent();
            ImGui.spacing();
        }
    }
    
    /**
     * Render topic content
     */
    private void renderTopicContent() {
        // Topic title
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0, 10);
        ImGui.text(currentTopic.getTitle());
        ImGui.separator();
        ImGui.popStyleVar();
        
        ImGui.spacing();
        
        // Render markdown content
        markdownRenderer.renderMarkdown(currentTopic.getContent());
        
        // Related topics
        if (!currentTopic.getRelatedTopics().isEmpty()) {
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
            ImGui.text("Related Topics:");
            
            for (String relatedId : currentTopic.getRelatedTopics()) {
                HelpSystem.HelpTopic related = helpSystem.getTopic(relatedId);
                if (related != null) {
                    if (ImGui.button("▶ " + related.getTitle())) {
                        showTopic(related);
                    }
                }
            }
        }
    }
    
    /**
     * Render home page content
     */
    private void renderHomeContent() {
        ImGui.text("Welcome to OpenMason Help");
        ImGui.separator();
        ImGui.spacing();
        
        ImGui.textWrapped("Find answers, learn new skills, and get the most out of OpenMason.");
        ImGui.spacing();
        
        // Featured topics
        ImGui.text("Getting Started:");
        ImGui.spacing();
        
        List<HelpSystem.HelpTopic> featured = helpSystem.getFeaturedTopics();
        for (HelpSystem.HelpTopic topic : featured) {
            if (ImGui.button("📖 " + topic.getTitle())) {
                showTopic(topic);
            }
            
            ImGui.indent();
            ImGui.textWrapped(getTopicPreview(topic.getContent()));
            ImGui.unindent();
            ImGui.spacing();
        }
        
        ImGui.separator();
        ImGui.spacing();
        
        // Quick actions
        ImGui.text("Quick Actions:");
        ImGui.spacing();
        
        if (ImGui.button("🎓 Start Interactive Tutorial")) {
            helpSystem.startTutorial("getting-started-tutorial");
        }
        
        ImGui.sameLine();
        
        if (ImGui.button("⌨️ Keyboard Shortcuts")) {
            HelpSystem.HelpTopic shortcuts = helpSystem.getTopic("keyboard-shortcuts");
            if (shortcuts != null) showTopic(shortcuts);
        }
        
        ImGui.sameLine();
        
        if (ImGui.button("🔧 Troubleshooting")) {
            HelpSystem.HelpTopic troubleshooting = helpSystem.getTopic("common-issues");
            if (troubleshooting != null) showTopic(troubleshooting);
        }
    }
    
    /**
     * Show a specific help topic.
     * 
     * @param topic The topic to display
     */
    public void showTopic(HelpSystem.HelpTopic topic) {
        if (topic == null) {
            logger.warn("Cannot show null topic");
            return;
        }
        
        try {
            // Add to navigation history only if it's different from current
            if (currentTopic == null || !topic.getId().equals(currentTopic.getId())) {
                addToHistory(topic);
            }
            
            currentTopic = topic;
            isSearchActive = false;
            
            // Clear any errors
            clearError();
            
            // Show the window if not visible
            if (!isVisible()) {
                show();
            }
            
            logger.debug("Showing help topic: {} ({})", topic.getTitle(), topic.getId());
            
        } catch (Exception e) {
            logger.error("Error showing topic: " + topic.getId(), e);
            setError("Failed to show topic: " + e.getMessage());
        }
    }
    
    /**
     * Show tutorial content
     */
    private void showTutorial(HelpSystem.Tutorial tutorial) {
        if (tutorial == null) return;
        
        // For now, show tutorial as a topic
        // In a full implementation, this would open an interactive tutorial
        ImGui.openPopup("Tutorial: " + tutorial.getTitle());
        
        if (ImGui.beginPopupModal("Tutorial: " + tutorial.getTitle())) {
            ImGui.text(tutorial.getDescription());
            ImGui.spacing();
            
            ImGui.text(String.format("Difficulty: %s", tutorial.getDifficulty().getDisplayName()));
            ImGui.text(String.format("Estimated time: %d minutes", tutorial.getEstimatedMinutes()));
            ImGui.text(String.format("Steps: %d", tutorial.getSteps().size()));
            
            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();
            
            if (ImGui.button("Start Tutorial")) {
                helpSystem.startTutorial(tutorial.getId());
                ImGui.closeCurrentPopup();
            }
            
            ImGui.sameLine();
            
            if (ImGui.button("Cancel")) {
                ImGui.closeCurrentPopup();
            }
            
            ImGui.endPopup();
        }
    }
    
    /**
     * Show the home page.
     */
    private void showHome() {
        if (currentTopic != null) {
            addToHistory(currentTopic);
        }
        currentTopic = null;
        isSearchActive = false;
        clearError();
        logger.debug("Showing home page");
    }
    
    /**
     * Perform search for help topics.
     */
    private void performSearch() {
        String query = searchQuery.get().trim();
        
        // Don't search if query is the same as last time
        if (query.equals(lastSearchQuery)) {
            return;
        }
        
        lastSearchQuery = query;
        
        if (query.isEmpty()) {
            isSearchActive = false;
            searchResults.clear();
            return;
        }
        
        try {
            searchResults = helpSystem.searchTopics(query);
            isSearchActive = true;
            
            logger.debug("Search for '{}' returned {} results", query, searchResults.size());
            
        } catch (Exception e) {
            logger.error("Error performing search: " + query, e);
            setError("Search error: " + e.getMessage());
            searchResults.clear();
            isSearchActive = false;
        }
    }
    
    /**
     * Navigate back in history.
     */
    private void navigateBack() {
        if (navigationHistory.isEmpty()) return;
        
        try {
            // Add current topic to forward history
            if (currentTopic != null) {
                forwardHistory.addLast(currentTopic);
            }
            
            // Get previous topic
            currentTopic = navigationHistory.removeLast();
            isSearchActive = false;
            
            logger.debug("Navigated back to: {}", currentTopic.getTitle());
            
        } catch (Exception e) {
            logger.error("Error navigating back", e);
            setError("Navigation error: " + e.getMessage());
        }
    }
    
    /**
     * Navigate forward in history.
     */
    private void navigateForward() {
        if (forwardHistory.isEmpty()) return;
        
        try {
            // Add current topic to back history
            if (currentTopic != null) {
                navigationHistory.addLast(currentTopic);
            }
            
            // Get next topic
            currentTopic = forwardHistory.removeLast();
            isSearchActive = false;
            
            logger.debug("Navigated forward to: {}", currentTopic.getTitle());
            
        } catch (Exception e) {
            logger.error("Error navigating forward", e);
            setError("Navigation error: " + e.getMessage());
        }
    }
    
    /**
     * Add a topic to the navigation history.
     */
    private void addToHistory(HelpSystem.HelpTopic topic) {
        if (topic == null) return;
        
        // Clear forward history when adding new topic
        forwardHistory.clear();
        
        // Add current topic to back history if we have one
        if (currentTopic != null && !currentTopic.getId().equals(topic.getId())) {
            navigationHistory.addLast(currentTopic);
        }
        
        // Limit history size
        while (navigationHistory.size() >= MAX_HISTORY_SIZE) {
            navigationHistory.removeFirst();
        }
        
        logger.debug("Added to history: {} (history size: {})", topic.getTitle(), navigationHistory.size());
    }
    
    /**
     * Get topic content preview
     */
    private String getTopicPreview(String content) {
        if (content == null || content.isEmpty()) {
            return "No preview available.";
        }
        
        // Strip HTML/markdown tags for preview
        String plainText = content.replaceAll("<[^>]+>", "")
                                 .replaceAll("#+ ", "")
                                 .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                                 .replaceAll("\\*([^*]+)\\*", "$1")
                                 .trim();
        
        if (plainText.length() > 120) {
            return plainText.substring(0, 117) + "...";
        }
        
        return plainText;
    }
    
    /**
     * Show context help tooltip
     */
    public void showContextHelp(String contextId) {
        HelpSystem.ContextHelp contextHelp = helpSystem.getContextHelp(contextId);
        if (contextHelp != null) {
            contextHelpRenderer.showContextHelp(contextId, contextHelp.getQuickHelp());
        }
    }
    
    /**
     * Dispose of all resources and clean up state.
     */
    public void dispose() {
        try {
            // Clear navigation state
            navigationHistory.clear();
            forwardHistory.clear();
            currentTopic = null;
            
            // Clear search state
            searchResults.clear();
            isSearchActive = false;
            searchQuery.set("");
            lastSearchQuery = "";
            
            // Reset UI state
            windowVisible.set(false);
            isFirstFrame = true;
            selectedCategoryIndex = 0;
            selectedTopicIndex = -1;
            selectedTutorialIndex = -1;
            leftPanelWidth = 300.0f;
            currentTabName = "Topics";
            
            // Clear error state
            clearError();
            
            // Clear renderer state
            if (contextHelpRenderer != null) {
                contextHelpRenderer.clearTooltipState();
            }
            
            // Mark as not initialized
            isInitialized = false;
            
            logger.info("Help browser disposed and resources cleaned up");
            
        } catch (Exception e) {
            logger.error("Error during help browser disposal", e);
        }
    }
    
    /**
     * Handle link clicks from markdown content.
     */
    private void handleTopicLinkClick(String url) {
        if (url.startsWith("topic:")) {
            String topicId = url.substring(6);
            HelpSystem.HelpTopic topic = helpSystem.getTopic(topicId);
            if (topic != null) {
                showTopic(topic);
            } else {
                logger.warn("Topic not found: {}", topicId);
                setError("Topic not found: " + topicId);
            }
        }
    }
    
    /**
     * Render "no search results" message.
     */
    private void renderNoSearchResults() {
        ImGui.spacing();
        contextHelpRenderer.showInlineHelp("No Results Found", 
            "No help topics found for '" + searchQuery.get() + "'. Try different keywords or browse categories.");
        
        ImGui.spacing();
        if (ImGui.button("Browse All Topics")) {
            showHome();
        }
    }
    
    /**
     * Handle window closed event.
     */
    private void onWindowClosed() {
        // Save current state if needed
        logger.debug("Help browser window closed");
    }
    
    /**
     * Set an error message.
     */
    private void setError(String error) {
        this.lastError = error;
        this.lastErrorTime = System.currentTimeMillis();
        logger.error("Help browser error: {}", error);
    }
    
    /**
     * Clear any error message.
     */
    private void clearError() {
        this.lastError = null;
        this.lastErrorTime = 0;
    }
    
    // Getters and status methods
    
    public boolean isVisible() {
        return windowVisible.get();
    }
    
    public HelpSystem.HelpTopic getCurrentTopic() {
        return currentTopic;
    }
    
    
    /**
     * Start a tutorial by tutorial ID.
     */
    public void startTutorial(String tutorialId) {
        if (!isInitialized) {
            logger.warn("Cannot start tutorial - not initialized");
            return;
        }
        
        if (tutorialId == null || tutorialId.trim().isEmpty()) {
            logger.warn("Cannot start tutorial - invalid ID");
            return;
        }
        
        try {
            HelpSystem.Tutorial tutorial = helpSystem.getTutorial(tutorialId);
            if (tutorial != null) {
                helpSystem.startTutorial(tutorialId);
                show(); // Show the help browser
                logger.info("Started tutorial: {} ({})", tutorial.getTitle(), tutorialId);
            } else {
                logger.warn("Tutorial not found: {}", tutorialId);
                setError("Tutorial not found: " + tutorialId);
            }
            
        } catch (Exception e) {
            logger.error("Failed to start tutorial: " + tutorialId, e);
            setError("Failed to start tutorial: " + e.getMessage());
        }
    }
    
    /**
     * Show a specific help topic by ID.
     */
    public void showTopicById(String topicId) {
        if (topicId == null || topicId.trim().isEmpty()) {
            logger.warn("Cannot show topic - invalid ID");
            return;
        }
        
        HelpSystem.HelpTopic topic = helpSystem.getTopic(topicId);
        if (topic != null) {
            showTopic(topic);
        } else {
            logger.warn("Topic not found: {}", topicId);
            setError("Topic not found: " + topicId);
        }
    }
    
    // Status and information methods
    
    public String getStatusInfo() {
        if (!isInitialized) {
            return "Help Browser - Not initialized";
        }
        
        try {
            Map<String, Integer> stats = helpSystem.getStatistics();
            return String.format("Help Browser - Categories: %d, Topics: %d, Tutorials: %d, History: %d, Search: %s", 
                               stats.getOrDefault("categories", 0),
                               stats.getOrDefault("topics", 0),
                               stats.getOrDefault("tutorials", 0),
                               navigationHistory.size(),
                               isSearchActive ? "active (" + searchResults.size() + " results)" : "inactive");
        } catch (Exception e) {
            logger.error("Error getting status info", e);
            return "Help Browser - Status unavailable";
        }
    }
    
    public String getSearchQuery() {
        return searchQuery.get();
    }
    
    public int getSearchResultsCount() {
        return searchResults.size();
    }
    
    public boolean isSearchActive() {
        return isSearchActive;
    }
    
    public boolean hasError() {
        return lastError != null;
    }
    
    public String getLastError() {
        return lastError;
    }
    
    public int getHistorySize() {
        return navigationHistory.size();
    }
    
    public int getForwardHistorySize() {
        return forwardHistory.size();
    }
    
    public String getCurrentTabName() {
        return currentTabName;
    }
    
    public void setCurrentTabName(String tabName) {
        this.currentTabName = tabName != null ? tabName : "Topics";
    }
}
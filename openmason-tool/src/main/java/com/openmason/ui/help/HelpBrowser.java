package com.openmason.ui.help;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Professional help browser with searchable documentation, categorized topics,
 * tutorial access, and responsive design.
 */
public class HelpBrowser extends Stage {
    
    private static final Logger logger = LoggerFactory.getLogger(HelpBrowser.class);
    
    private final HelpSystem helpSystem;
    
    // UI Components
    private TextField searchField;
    private TreeView<HelpItem> topicTree;
    private ListView<HelpSystem.Tutorial> tutorialsList;
    private WebView contentView;
    private WebEngine webEngine;
    private TabPane mainTabs;
    private Label statusLabel;
    private Button backButton;
    private Button forwardButton;
    private Button homeButton;
    
    // Navigation state
    private final java.util.List<HelpSystem.HelpTopic> navigationHistory = new java.util.ArrayList<>();
    private int currentHistoryIndex = -1;
    
    /**
     * Help item wrapper for tree view
     */
    public static class HelpItem {
        private final String id;
        private final String title;
        private final HelpItemType type;
        private final Object data;
        
        public enum HelpItemType {
            CATEGORY, TOPIC, TUTORIAL
        }
        
        public HelpItem(String id, String title, HelpItemType type, Object data) {
            this.id = id;
            this.title = title;
            this.type = type;
            this.data = data;
        }
        
        public String getId() { return id; }
        public String getTitle() { return title; }
        public HelpItemType getType() { return type; }
        public Object getData() { return data; }
        
        @Override
        public String toString() { return title; }
    }
    
    public HelpBrowser(HelpSystem helpSystem) {
        this.helpSystem = helpSystem;
        
        initStyle(StageStyle.DECORATED);
        setTitle("OpenMason Help");
        setWidth(1000);
        setHeight(700);
        setMinWidth(800);
        setMinHeight(600);
        
        initializeUI();
        setupEventHandlers();
        loadInitialContent();
        
        logger.info("Help browser initialized");
    }
    
    private void initializeUI() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("help-browser");
        
        // Header with search and navigation
        root.setTop(createHeaderSection());
        
        // Main content area
        root.setCenter(createMainContentArea());
        
        // Status bar
        root.setBottom(createStatusBar());
        
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/css/dark-theme.css").toExternalForm());
        
        // Add help-specific styles
        scene.getStylesheets().add(getClass().getResource("/css/help-browser.css").toExternalForm());
        
        setScene(scene);
    }
    
    private VBox createHeaderSection() {
        VBox header = new VBox(10);
        header.setPadding(new Insets(15));
        header.getStyleClass().add("help-header");
        
        // Title and navigation
        HBox titleBox = new HBox(15);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        
        Label titleLabel = new Label("OpenMason Help");
        titleLabel.getStyleClass().addAll("help-title", "h2");
        
        // Navigation buttons
        HBox navButtons = new HBox(5);
        navButtons.setAlignment(Pos.CENTER_LEFT);
        
        backButton = new Button("←");
        backButton.getStyleClass().addAll("nav-button", "back-button");
        backButton.setTooltip(new Tooltip("Go back"));
        backButton.setDisable(true);
        
        forwardButton = new Button("→");
        forwardButton.getStyleClass().addAll("nav-button", "forward-button");
        forwardButton.setTooltip(new Tooltip("Go forward"));
        forwardButton.setDisable(true);
        
        homeButton = new Button("🏠");
        homeButton.getStyleClass().addAll("nav-button", "home-button");
        homeButton.setTooltip(new Tooltip("Go to home"));
        
        navButtons.getChildren().addAll(backButton, forwardButton, homeButton);
        
        titleBox.getChildren().addAll(titleLabel, navButtons);
        
        // Search box
        HBox searchBox = new HBox(10);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        
        Label searchLabel = new Label("Search:");
        searchLabel.getStyleClass().add("search-label");
        
        searchField = new TextField();
        searchField.setPromptText("Search help topics...");
        searchField.getStyleClass().add("help-search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        
        Button searchButton = new Button("Search");
        searchButton.getStyleClass().addAll("primary-button", "search-button");
        
        searchBox.getChildren().addAll(searchLabel, searchField, searchButton);
        
        header.getChildren().addAll(titleBox, searchBox);
        return header;
    }
    
    private SplitPane createMainContentArea() {
        SplitPane mainSplit = new SplitPane();
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.setDividerPositions(0.3);
        mainSplit.getStyleClass().add("help-main-split");
        
        // Left panel with navigation
        VBox leftPanel = createNavigationPanel();
        
        // Right panel with content
        VBox rightPanel = createContentPanel();
        
        mainSplit.getItems().addAll(leftPanel, rightPanel);
        return mainSplit;
    }
    
    private VBox createNavigationPanel() {
        VBox navPanel = new VBox();
        navPanel.getStyleClass().add("help-navigation-panel");
        
        // Navigation tabs
        TabPane navTabs = new TabPane();
        navTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        navTabs.getStyleClass().add("navigation-tabs");
        
        // Topics tab
        Tab topicsTab = new Tab("Topics");
        topicsTab.setContent(createTopicsNavigationContent());
        topicsTab.getStyleClass().add("topics-tab");
        
        // Tutorials tab
        Tab tutorialsTab = new Tab("Tutorials");
        tutorialsTab.setContent(createTutorialsNavigationContent());
        tutorialsTab.getStyleClass().add("tutorials-tab");
        
        navTabs.getTabs().addAll(topicsTab, tutorialsTab);
        
        navPanel.getChildren().add(navTabs);
        VBox.setVgrow(navTabs, Priority.ALWAYS);
        
        return navPanel;
    }
    
    private ScrollPane createTopicsNavigationContent() {
        // Create tree view for help topics
        topicTree = new TreeView<>();
        topicTree.getStyleClass().add("help-topics-tree");
        topicTree.setShowRoot(false);
        
        // Build topic tree
        TreeItem<HelpItem> root = new TreeItem<>();
        
        for (HelpSystem.HelpCategory category : helpSystem.getCategories()) {
            TreeItem<HelpItem> categoryItem = new TreeItem<>(
                new HelpItem(category.getId(), category.getName(), 
                           HelpItem.HelpItemType.CATEGORY, category));
            
            // Add topics in this category
            List<HelpSystem.HelpTopic> categoryTopics = helpSystem.getTopicsInCategory(category.getId());
            for (HelpSystem.HelpTopic topic : categoryTopics) {
                TreeItem<HelpItem> topicItem = new TreeItem<>(
                    new HelpItem(topic.getId(), topic.getTitle(), 
                               HelpItem.HelpItemType.TOPIC, topic));
                categoryItem.getChildren().add(topicItem);
            }
            
            categoryItem.setExpanded(true);
            root.getChildren().add(categoryItem);
        }
        
        topicTree.setRoot(root);
        
        ScrollPane scrollPane = new ScrollPane(topicTree);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("topics-scroll");
        
        return scrollPane;
    }
    
    private ScrollPane createTutorialsNavigationContent() {
        tutorialsList = new ListView<>();
        tutorialsList.getStyleClass().add("tutorials-list");
        
        // Custom cell factory for tutorials
        tutorialsList.setCellFactory(listView -> new ListCell<HelpSystem.Tutorial>() {
            @Override
            protected void updateItem(HelpSystem.Tutorial tutorial, boolean empty) {
                super.updateItem(tutorial, empty);
                
                if (empty || tutorial == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    VBox content = new VBox(2);
                    content.getStyleClass().add("tutorial-cell-content");
                    
                    Label titleLabel = new Label(tutorial.getTitle());
                    titleLabel.getStyleClass().add("tutorial-title");
                    
                    Label detailsLabel = new Label(String.format("%s • %d minutes", 
                        tutorial.getDifficulty().getDisplayName(), 
                        tutorial.getEstimatedMinutes()));
                    detailsLabel.getStyleClass().add("tutorial-details");
                    
                    Label descLabel = new Label(tutorial.getDescription());
                    descLabel.getStyleClass().add("tutorial-description");
                    descLabel.setWrapText(true);
                    
                    content.getChildren().addAll(titleLabel, detailsLabel, descLabel);
                    setGraphic(content);
                    setText(null);
                }
            }
        });
        
        // Load tutorials
        tutorialsList.setItems(FXCollections.observableArrayList(helpSystem.getAllTutorials()));
        
        ScrollPane scrollPane = new ScrollPane(tutorialsList);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("tutorials-scroll");
        
        return scrollPane;
    }
    
    private VBox createContentPanel() {
        VBox contentPanel = new VBox();
        contentPanel.getStyleClass().add("help-content-panel");
        
        // Content area with web view
        contentView = new WebView();
        contentView.getStyleClass().add("help-content-view");
        webEngine = contentView.getEngine();
        
        // Apply custom CSS to web content
        webEngine.setUserStyleSheetLocation(
            getClass().getResource("/css/help-content.css").toExternalForm());
        
        contentPanel.getChildren().add(contentView);
        VBox.setVgrow(contentView, Priority.ALWAYS);
        
        return contentPanel;
    }
    
    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setPadding(new Insets(5, 15, 5, 15));
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("help-status-bar");
        
        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("help-status-label");
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        
        // Quick action buttons
        Button tutorialsButton = new Button("Start Tutorial");
        tutorialsButton.getStyleClass().addAll("secondary-button", "small-button");
        tutorialsButton.setOnAction(e -> showTutorialsList());
        
        Button shortcutsButton = new Button("Keyboard Shortcuts");
        shortcutsButton.getStyleClass().addAll("secondary-button", "small-button");
        shortcutsButton.setOnAction(e -> helpSystem.showKeyboardShortcuts());
        
        statusBar.getChildren().addAll(statusLabel, tutorialsButton, shortcutsButton);
        return statusBar;
    }
    
    private void setupEventHandlers() {
        // Search functionality
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                performSearch();
            }
        });
        
        // Navigation buttons
        backButton.setOnAction(e -> navigateBack());
        forwardButton.setOnAction(e -> navigateForward());
        homeButton.setOnAction(e -> showHome());
        
        // Topic tree selection
        topicTree.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (newItem != null && newItem.getValue().getType() == HelpItem.HelpItemType.TOPIC) {
                HelpSystem.HelpTopic topic = (HelpSystem.HelpTopic) newItem.getValue().getData();
                showTopic(topic);
            }
        });
        
        // Tutorial list selection
        tutorialsList.getSelectionModel().selectedItemProperty().addListener((obs, oldTutorial, newTutorial) -> {
            if (newTutorial != null) {
                showTutorial(newTutorial);
            }
        });
        
        // Web engine state changes
        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            switch (newState) {
                case RUNNING:
                    statusLabel.setText("Loading...");
                    break;
                case SUCCEEDED:
                    statusLabel.setText("Ready");
                    break;
                case FAILED:
                    statusLabel.setText("Failed to load content");
                    break;
            }
        });
        
        // Window close handler
        setOnCloseRequest(e -> {
            helpSystem.hideHelp();
        });
    }
    
    private void loadInitialContent() {
        showHome();
    }
    
    /**
     * Show the home page
     */
    public void showHome() {
        String homeContent = generateHomePageContent();
        webEngine.loadContent(homeContent);
        statusLabel.setText("Welcome to OpenMason Help");
        
        // Clear navigation selection
        topicTree.getSelectionModel().clearSelection();
        tutorialsList.getSelectionModel().clearSelection();
    }
    
    /**
     * Show a specific help topic
     */
    public void showTopic(HelpSystem.HelpTopic topic) {
        if (topic == null) return;
        
        // Add to navigation history
        addToHistory(topic);
        
        // Load topic content
        String content = enhanceTopicContent(topic);
        webEngine.loadContent(content);
        
        statusLabel.setText("Viewing: " + topic.getTitle());
        logger.debug("Showed help topic: {}", topic.getTitle());
    }
    
    /**
     * Show tutorial information
     */
    private void showTutorial(HelpSystem.Tutorial tutorial) {
        if (tutorial == null) return;
        
        String content = generateTutorialPageContent(tutorial);
        webEngine.loadContent(content);
        
        statusLabel.setText("Tutorial: " + tutorial.getTitle());
    }
    
    /**
     * Perform search
     */
    private void performSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            showHome();
            return;
        }
        
        List<HelpSystem.HelpTopic> results = helpSystem.searchTopics(query);
        String content = generateSearchResultsContent(query, results);
        webEngine.loadContent(content);
        
        statusLabel.setText(String.format("Found %d results for: %s", results.size(), query));
    }
    
    /**
     * Show tutorials list
     */
    private void showTutorialsList() {
        // Switch to tutorials tab
        if (mainTabs != null) {
            mainTabs.getSelectionModel().select(1);
        }
        
        String content = generateTutorialsPageContent();
        webEngine.loadContent(content);
        statusLabel.setText("Available Tutorials");
    }
    
    /**
     * Navigate back in history
     */
    private void navigateBack() {
        if (currentHistoryIndex > 0) {
            currentHistoryIndex--;
            HelpSystem.HelpTopic topic = navigationHistory.get(currentHistoryIndex);
            showTopicWithoutHistory(topic);
            updateNavigationButtons();
        }
    }
    
    /**
     * Navigate forward in history  
     */
    private void navigateForward() {
        if (currentHistoryIndex < navigationHistory.size() - 1) {
            currentHistoryIndex++;
            HelpSystem.HelpTopic topic = navigationHistory.get(currentHistoryIndex);
            showTopicWithoutHistory(topic);
            updateNavigationButtons();
        }
    }
    
    /**
     * Add topic to navigation history
     */
    private void addToHistory(HelpSystem.HelpTopic topic) {
        // Remove any forward history
        if (currentHistoryIndex < navigationHistory.size() - 1) {
            navigationHistory.subList(currentHistoryIndex + 1, navigationHistory.size()).clear();
        }
        
        // Add new topic
        navigationHistory.add(topic);
        currentHistoryIndex = navigationHistory.size() - 1;
        
        updateNavigationButtons();
    }
    
    /**
     * Show topic without adding to history (for navigation)
     */
    private void showTopicWithoutHistory(HelpSystem.HelpTopic topic) {
        String content = enhanceTopicContent(topic);
        webEngine.loadContent(content);
        statusLabel.setText("Viewing: " + topic.getTitle());
    }
    
    /**
     * Update navigation button states
     */
    private void updateNavigationButtons() {
        backButton.setDisable(currentHistoryIndex <= 0);
        forwardButton.setDisable(currentHistoryIndex >= navigationHistory.size() - 1);
    }
    
    /**
     * Generate home page content
     */
    private String generateHomePageContent() {
        StringBuilder html = new StringBuilder();
        html.append(getHtmlHeader("OpenMason Help"));
        
        html.append("<body><div class='container'>");
        html.append("<h1>Welcome to OpenMason Help</h1>");
        html.append("<p>Find answers, learn new skills, and get the most out of OpenMason.</p>");
        
        // Featured topics
        html.append("<h2>Getting Started</h2>");
        html.append("<div class='featured-topics'>");
        
        List<HelpSystem.HelpTopic> featured = helpSystem.getFeaturedTopics();
        for (HelpSystem.HelpTopic topic : featured) {
            html.append(String.format(
                "<div class='topic-card'>" +
                "<h3><a href='#' onclick='showTopic(\"%s\")'>%s</a></h3>" +
                "<p>%s</p>" +
                "</div>",
                topic.getId(), topic.getTitle(), getTopicPreview(topic.getContent())
            ));
        }
        
        html.append("</div>");
        
        // Quick links
        html.append("<h2>Quick Links</h2>");
        html.append("<div class='quick-links'>");
        html.append("<a href='#' onclick='startTutorial()' class='quick-link'>Interactive Tutorials</a>");
        html.append("<a href='#' onclick='showShortcuts()' class='quick-link'>Keyboard Shortcuts</a>");
        html.append("<a href='#' onclick='showTroubleshooting()' class='quick-link'>Troubleshooting</a>");
        html.append("</div>");
        
        html.append("</div></body></html>");
        
        return html.toString();
    }
    
    /**
     * Enhance topic content with navigation and related topics
     */
    private String enhanceTopicContent(HelpSystem.HelpTopic topic) {
        StringBuilder html = new StringBuilder();
        html.append(getHtmlHeader(topic.getTitle()));
        
        html.append("<body><div class='container'>");
        
        // Breadcrumb navigation
        HelpSystem.HelpCategory category = helpSystem.getCategories().stream()
            .filter(cat -> cat.getId().equals(topic.getCategoryId()))
            .findFirst().orElse(null);
        
        if (category != null) {
            html.append("<nav class='breadcrumb'>");
            html.append("<a href='#' onclick='showHome()'>Home</a> > ");
            html.append("<span>").append(category.getName()).append("</span> > ");
            html.append("<span class='current'>").append(topic.getTitle()).append("</span>");
            html.append("</nav>");
        }
        
        // Topic content
        html.append(topic.getContent());
        
        // Related topics
        if (!topic.getRelatedTopics().isEmpty()) {
            html.append("<h3>Related Topics</h3>");
            html.append("<ul class='related-topics'>");
            
            for (String relatedId : topic.getRelatedTopics()) {
                HelpSystem.HelpTopic related = helpSystem.getTopic(relatedId);
                if (related != null) {
                    html.append(String.format(
                        "<li><a href='#' onclick='showTopic(\"%s\")'>%s</a></li>",
                        related.getId(), related.getTitle()
                    ));
                }
            }
            
            html.append("</ul>");
        }
        
        html.append("</div></body></html>");
        
        return html.toString();
    }
    
    /**
     * Generate search results content
     */
    private String generateSearchResultsContent(String query, List<HelpSystem.HelpTopic> results) {
        StringBuilder html = new StringBuilder();
        html.append(getHtmlHeader("Search Results"));
        
        html.append("<body><div class='container'>");
        html.append(String.format("<h1>Search Results for \"%s\"</h1>", query));
        
        if (results.isEmpty()) {
            html.append("<p>No results found. Try different keywords or browse topics by category.</p>");
        } else {
            html.append(String.format("<p>Found %d result(s):</p>", results.size()));
            html.append("<div class='search-results'>");
            
            for (HelpSystem.HelpTopic topic : results) {
                html.append(String.format(
                    "<div class='search-result'>" +
                    "<h3><a href='#' onclick='showTopic(\"%s\")'>%s</a></h3>" +
                    "<p>%s</p>" +
                    "</div>",
                    topic.getId(), topic.getTitle(), getTopicPreview(topic.getContent())
                ));
            }
            
            html.append("</div>");
        }
        
        html.append("</div></body></html>");
        
        return html.toString();
    }
    
    /**
     * Generate tutorial page content
     */
    private String generateTutorialPageContent(HelpSystem.Tutorial tutorial) {
        StringBuilder html = new StringBuilder();
        html.append(getHtmlHeader(tutorial.getTitle()));
        
        html.append("<body><div class='container'>");
        html.append(String.format("<h1>%s</h1>", tutorial.getTitle()));
        html.append(String.format("<p class='tutorial-meta'>%s • %d minutes • %d steps</p>", 
            tutorial.getDifficulty().getDisplayName(), 
            tutorial.getEstimatedMinutes(),
            tutorial.getSteps().size()));
        
        html.append(String.format("<p>%s</p>", tutorial.getDescription()));
        
        html.append("<h2>Tutorial Steps</h2>");
        html.append("<ol class='tutorial-steps'>");
        
        for (int i = 0; i < tutorial.getSteps().size(); i++) {
            HelpSystem.TutorialStep step = tutorial.getSteps().get(i);
            html.append(String.format(
                "<li><strong>%s</strong><br>%s</li>",
                step.getTitle(), step.getInstruction()
            ));
        }
        
        html.append("</ol>");
        
        html.append(String.format(
            "<button onclick='startTutorial(\"%s\")' class='start-tutorial-btn'>Start Interactive Tutorial</button>",
            tutorial.getId()
        ));
        
        html.append("</div></body></html>");
        
        return html.toString();
    }
    
    /**
     * Generate tutorials overview content
     */
    private String generateTutorialsPageContent() {
        StringBuilder html = new StringBuilder();
        html.append(getHtmlHeader("Tutorials"));
        
        html.append("<body><div class='container'>");
        html.append("<h1>Interactive Tutorials</h1>");
        html.append("<p>Learn OpenMason step-by-step with our interactive tutorials.</p>");
        
        // Group tutorials by difficulty
        for (HelpSystem.TutorialDifficulty difficulty : HelpSystem.TutorialDifficulty.values()) {
            List<HelpSystem.Tutorial> difficultyTutorials = helpSystem.getAllTutorials().stream()
                .filter(t -> t.getDifficulty() == difficulty)
                .collect(Collectors.toList());
            
            if (!difficultyTutorials.isEmpty()) {
                html.append(String.format("<h2>%s</h2>", difficulty.getDisplayName()));
                html.append(String.format("<p>%s</p>", difficulty.getDescription()));
                html.append("<div class='tutorials-grid'>");
                
                for (HelpSystem.Tutorial tutorial : difficultyTutorials) {
                    html.append(String.format(
                        "<div class='tutorial-card'>" +
                        "<h3>%s</h3>" +
                        "<p>%s</p>" +
                        "<p class='tutorial-duration'>%d minutes</p>" +
                        "<button onclick='startTutorial(\"%s\")'>Start Tutorial</button>" +
                        "</div>",
                        tutorial.getTitle(), tutorial.getDescription(), 
                        tutorial.getEstimatedMinutes(), tutorial.getId()
                    ));
                }
                
                html.append("</div>");
            }
        }
        
        html.append("</div></body></html>");
        
        return html.toString();
    }
    
    /**
     * Get HTML header with CSS
     */
    private String getHtmlHeader(String title) {
        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>%s</title>
                <meta charset="UTF-8">
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif; 
                           line-height: 1.6; color: #e0e0e0; background: #2b2b2b; margin: 0; padding: 20px; }
                    .container { max-width: 800px; margin: 0 auto; }
                    h1, h2, h3 { color: #ffffff; }
                    a { color: #0099ff; text-decoration: none; }
                    a:hover { text-decoration: underline; }
                    .breadcrumb { margin-bottom: 20px; padding: 10px; background: #3c3c3c; border-radius: 4px; }
                    .featured-topics, .search-results { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 20px; }
                    .topic-card, .search-result { padding: 15px; background: #3c3c3c; border-radius: 6px; }
                    .quick-links { display: flex; gap: 15px; flex-wrap: wrap; }
                    .quick-link { padding: 10px 20px; background: #0099ff; color: white; border-radius: 4px; }
                    .tutorials-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 20px; }
                    .tutorial-card { padding: 20px; background: #3c3c3c; border-radius: 6px; }
                    .tutorial-meta { font-style: italic; color: #b0b0b0; }
                    .start-tutorial-btn { padding: 10px 20px; background: #5cdb5c; color: white; border: none; border-radius: 4px; cursor: pointer; }
                    code { background: #1e1e1e; padding: 2px 6px; border-radius: 3px; font-family: 'Consolas', monospace; }
                    pre { background: #1e1e1e; padding: 15px; border-radius: 6px; overflow-x: auto; }
                    .related-topics { list-style-type: none; padding: 0; }
                    .related-topics li { padding: 5px 0; }
                </style>
                <script>
                    function showTopic(topicId) { 
                        console.log('Show topic: ' + topicId); 
                        // This would be handled by the JavaFX WebEngine
                    }
                    function showHome() { 
                        console.log('Show home'); 
                    }
                    function startTutorial(tutorialId) { 
                        console.log('Start tutorial: ' + tutorialId); 
                    }
                </script>
            </head>
            """, title);
    }
    
    /**
     * Get topic preview text
     */
    private String getTopicPreview(String content) {
        // Extract plain text preview from HTML content
        String plainText = content.replaceAll("<[^>]+>", "").trim();
        if (plainText.length() > 150) {
            return plainText.substring(0, 150) + "...";
        }
        return plainText;
    }
}
package com.openmason.main.systems.menus.mainHub.state;

import com.openmason.main.systems.menus.mainHub.model.NavigationItem;
import com.openmason.main.systems.menus.mainHub.model.ProjectTemplate;
import com.openmason.main.systems.menus.mainHub.model.RecentProject;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralized state for Hub interface.
 * Manages current view, selections, and UI state with observer pattern.
 */
public class HubState {

    private NavigationItem.ViewType currentView = NavigationItem.ViewType.TEMPLATES;
    private NavigationItem selectedNavItem;
    private ProjectTemplate selectedTemplate;
    private RecentProject selectedRecentProject;
    private String searchQuery = "";
    private final List<StateChangeListener> listeners = new ArrayList<>();

    // State change listener interface
    public interface StateChangeListener {
        void onStateChanged(HubState state);
    }

    private void notifyListeners() {
        for (StateChangeListener listener : listeners) {
            listener.onStateChanged(this);
        }
    }

    // Getters and Setters

    public NavigationItem.ViewType getCurrentView() {
        return currentView;
    }

    public void setCurrentView(NavigationItem.ViewType currentView) {
        if (this.currentView != currentView) {
            this.currentView = currentView;
            notifyListeners();
        }
    }

    public NavigationItem getSelectedNavItem() {
        return selectedNavItem;
    }

    public void setSelectedNavItem(NavigationItem selectedNavItem) {
        if (this.selectedNavItem != selectedNavItem) {
            this.selectedNavItem = selectedNavItem;
            notifyListeners();
        }
    }

    public ProjectTemplate getSelectedTemplate() {
        return selectedTemplate;
    }

    public void setSelectedTemplate(ProjectTemplate selectedTemplate) {
        if (this.selectedTemplate != selectedTemplate) {
            this.selectedTemplate = selectedTemplate;
            notifyListeners();
        }
    }

    public RecentProject getSelectedRecentProject() {
        return selectedRecentProject;
    }

    public void setSelectedRecentProject(RecentProject selectedRecentProject) {
        if (this.selectedRecentProject != selectedRecentProject) {
            this.selectedRecentProject = selectedRecentProject;
            notifyListeners();
        }
    }

    public String getSearchQuery() {
        return searchQuery;
    }

    public void setSearchQuery(String searchQuery) {
        if (!this.searchQuery.equals(searchQuery)) {
            this.searchQuery = searchQuery != null ? searchQuery : "";
            notifyListeners();
        }
    }

    /**
     * Clear selection when switching views.
     */
    public void clearSelection() {
        selectedTemplate = null;
        selectedRecentProject = null;
        notifyListeners();
    }
}

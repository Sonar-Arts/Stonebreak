package com.openmason.main.systems.menus.mainHub.model;

/**
 * View-model for one project in the hub's merged "Projects" list: a project
 * discovered by scanning the base projects folder and/or remembered in the
 * recent-projects list.
 *
 * @param project       the underlying (possibly synthesized) recent-project entry
 * @param external      true when the project lives outside the base projects folder
 *                      (known only from the recents list)
 * @param missingOnDisk true when the recents list references a file that no longer exists
 */
public record HubProjectEntry(RecentProject project, boolean external, boolean missingOnDisk) {
}

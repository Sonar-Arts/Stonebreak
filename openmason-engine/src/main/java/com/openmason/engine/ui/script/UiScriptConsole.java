package com.openmason.engine.ui.script;

import java.util.ArrayDeque;
import java.util.List;

/**
 * The script log of one screen (#292): {@code ui.log}/{@code print}, warnings, errors and
 * lifecycle notes, newest last, bounded. The editor's script pane shows it; tests read it.
 */
public final class UiScriptConsole {

    public enum Level { INFO, WARN, ERROR }

    public record Entry(double time, Level level, String source, String message) {
        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%8.3f %-5s %s: %s", time, level, source, message);
        }
    }

    private static final int CAPACITY = 500;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private int revision;

    synchronized void add(double time, Level level, String source, String message) {
        if (entries.size() == CAPACITY) {
            entries.removeFirst();
        }
        entries.addLast(new Entry(time, level, source, message));
        revision++;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Changes whenever an entry is added or the log is cleared. */
    public synchronized int revision() {
        return revision;
    }

    public synchronized void clear() {
        entries.clear();
        revision++;
    }
}

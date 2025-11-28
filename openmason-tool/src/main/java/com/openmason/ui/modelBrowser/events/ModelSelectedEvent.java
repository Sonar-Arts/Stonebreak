package com.openmason.ui.modelBrowser.events;

/**
 * Event fired when an entity model is selected in the Model Browser.
 *
 * <p>This immutable event class encapsulates the model selection action,
 * following the Single Responsibility Principle by only carrying event data.</p>
 */
public final class ModelSelectedEvent {

    private final String modelName;
    private final long timestamp;

    /**
     * Creates a new model selection event.
     *
     * @param modelName The name of the model that was selected
     */
    public ModelSelectedEvent(String modelName) {
        this.modelName = modelName;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Gets the selected model name.
     *
     * @return The selected model name
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * Gets the timestamp when this event was created.
     *
     * @return The timestamp in milliseconds since epoch
     */
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "ModelSelectedEvent{" +
                "modelName='" + modelName + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}

package com.openmason.ui.modelBrowser.events;

/**
 * Event fired when an entity model is selected in the Model Browser.
 */
public final class ModelSelectedEvent {

    private final String modelName;
    private final long timestamp;

    /**
     * Creates a new model selection event.
     */
    public ModelSelectedEvent(String modelName) {
        this.modelName = modelName;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Gets the selected model name.
     */
    public String getModelName() {
        return modelName;
    }

    @Override
    public String toString() {
        return "ModelSelectedEvent{" +
                "modelName='" + modelName + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}

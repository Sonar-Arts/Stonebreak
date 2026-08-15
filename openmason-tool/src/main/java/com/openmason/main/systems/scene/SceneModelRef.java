package com.openmason.main.systems.scene;

import com.openmason.engine.rendering.viewer.scene.ModelHandle;

import java.nio.file.Path;

/**
 * A model the open scene places, together with how it was resolved.
 *
 * <p>Identified by a <b>session id</b> that is stable for as long as the scene is open.
 * Deliberately not the content hash the file format uses: editing a model changes its
 * hash, and re-keying live instances mid-session is exactly the bug that decoupling
 * avoids. The serializer maps session ids onto content ids at save time, in one place.
 */
public final class SceneModelRef {

    private final String sessionId;
    private final String sourceName;

    private Path sourcePath;
    private String relativePath;
    private byte[] embeddedBytes;
    private ModelHandle handle;
    private ResolutionStatus status;

    public SceneModelRef(String sessionId, String sourceName, Path sourcePath,
                         String relativePath, byte[] embeddedBytes,
                         ModelHandle handle, ResolutionStatus status) {
        this.sessionId = sessionId;
        this.sourceName = sourceName;
        this.sourcePath = sourcePath;
        this.relativePath = relativePath;
        this.embeddedBytes = embeddedBytes;
        this.handle = handle;
        this.status = status;
    }

    public String sessionId() { return sessionId; }

    /** Original file name, used when importing the embedded copy into a project. */
    public String sourceName() { return sourceName; }

    /** Absolute path this model resolved from, or null when it came from embedded bytes. */
    public Path sourcePath() { return sourcePath; }
    public void setSourcePath(Path sourcePath) { this.sourcePath = sourcePath; }

    /** Project-root-relative reference stored in the file. */
    public String relativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

    /** The copy carried inside the .omsc; kept so the model can be re-embedded or imported. */
    public byte[] embeddedBytes() { return embeddedBytes; }
    public void setEmbeddedBytes(byte[] embeddedBytes) { this.embeddedBytes = embeddedBytes; }

    /** The loaded model, or null when {@link ResolutionStatus#MISSING}. */
    public ModelHandle handle() { return handle; }
    public void setHandle(ModelHandle handle) { this.handle = handle; }

    public ResolutionStatus status() { return status; }
    public void setStatus(ResolutionStatus status) { this.status = status; }
}

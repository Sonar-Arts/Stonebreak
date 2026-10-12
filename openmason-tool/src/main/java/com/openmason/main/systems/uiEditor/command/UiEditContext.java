package com.openmason.main.systems.uiEditor.command;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.ui.assets.ProjectFolder;
import com.openmason.engine.ui.assets.edit.AssetEdit;
import com.openmason.main.systems.uiEditor.document.UiTree;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The working state a {@link UiCommand} edits: a document snapshot, the selection (element
 * keys) and the project writes made so far. The history snapshots it before and commits it
 * after, so commands just replace values and never have to undo themselves.
 */
public final class UiEditContext {

    private OmuiArchive doc;
    private final Set<String> selection;
    private final ProjectFolder folder;
    private final List<AssetEdit> assetEdits = new ArrayList<>();

    public UiEditContext(OmuiArchive doc, Collection<String> selection, ProjectFolder folder) {
        this.doc = doc;
        this.selection = new LinkedHashSet<>(selection);
        this.folder = folder;
    }

    public OmuiArchive doc() {
        return doc;
    }

    public void setDoc(OmuiArchive next) {
        doc = next;
    }

    public UiNode root() {
        return doc.document().root();
    }

    /** Replaces the tree, keeping every other document field. */
    public void setRoot(UiNode root) {
        UiDocument d = doc.document();
        doc = doc.withDocument(new UiDocument(root, d.styleSheets(), d.codeBehind(), d.component(), d.unknown()));
    }

    public void setDocument(UiDocument d) {
        doc = doc.withDocument(d);
    }

    /** The node with {@code id} or a command error naming it. */
    public UiNode require(String id) throws UiCommandException {
        UiNode n = UiTree.find(root(), id);
        if (n == null) {
            throw new UiCommandException("No element '" + id + "' in the document");
        }
        return n;
    }

    public Set<String> selection() {
        return selection;
    }

    public void select(Collection<String> keys) {
        selection.clear();
        selection.addAll(keys);
    }

    /** The project the document belongs to, or null (untitled outside a project). */
    public ProjectFolder folder() {
        return folder;
    }

    /**
     * Performs an asset edit's project writes now and takes its document. The history keeps the
     * edit so undo can revert the writes; a later failure in the same command reverts them too.
     */
    public void applyAssetEdit(AssetEdit edit) throws UiCommandException {
        if (!edit.writes().isEmpty() && folder == null) {
            throw new UiCommandException(edit.label() + " needs an open project");
        }
        try {
            doc = edit.writes().isEmpty() ? edit.after() : edit.apply(folder);
        } catch (IOException e) {
            throw new UiCommandException(edit.label() + " failed: " + e.getMessage(), e);
        }
        assetEdits.add(edit);
    }

    public List<AssetEdit> assetEdits() {
        return List.copyOf(assetEdits);
    }

    /** Reverts the project writes made so far (the command failed). */
    void rollbackWrites() {
        for (int i = assetEdits.size() - 1; i >= 0; i--) {
            try {
                if (!assetEdits.get(i).writes().isEmpty()) {
                    assetEdits.get(i).undo(folder);
                }
            } catch (IOException ignored) {
                // best effort: the write that failed is reported by the command itself
            }
        }
        assetEdits.clear();
    }
}

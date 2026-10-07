package com.openmason.engine.ui.script;

import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.binding.UiBinder;
import com.openmason.engine.ui.runtime.binding.UiConverters;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;

/**
 * Hosting Lua code-behind on a {@link UiDocumentView} (#292): the one sequence the game window
 * and the Open Mason preview both use, so scripts behave identically in both.
 */
public final class UiScripts {

    private UiScripts() {
    }

    /**
     * Loads the document's code-behind, binds the view to {@code host} with the scripts'
     * converters (then {@code fallback}'s), attaches the scope and input router, runs
     * {@code on_open} and attaches the runtime to the view: {@code view.frame(dt)} drives
     * {@code update(dt)} and {@code view.close()} runs {@code on_close} before the binder and the
     * document close. A view that is already bound keeps its binder.
     *
     * @param host     the data/action host, or null for a document without host access
     * @param fallback converters for names no script declares (Java hosts and tests), or null
     * @throws com.openmason.engine.cenda.CendaLuaUnavailableException when the document has
     *         code-behind and the Lua host cannot load: loud, never a silent skip
     */
    public static UiScriptRuntime open(UiDocumentView view, UiHost host, UiConverters fallback,
                                       UiScriptOptions options, UiScriptServices services) {
        UiScriptRuntime rt = UiScriptRuntime.load(view.instance(), options, services);
        try {
            if (host != null && view.binder() == null) {
                UiConverters converters = fallback == null ? rt.converters() : rt.converters().or(fallback);
                view.bind(UiBinder.open(view.instance(), host, converters));
            }
            rt.attach(view.binder() == null ? null : view.binder().scope(), view.input());
            view.extend(rt);
            rt.open();
            return rt;
        } catch (RuntimeException e) {
            rt.close();
            throw e;
        }
    }

    /** The script runtime attached to {@code view}, or null. */
    public static UiScriptRuntime of(UiDocumentView view) {
        for (UiDocumentView.Extension e : view.extensions()) {
            if (e instanceof UiScriptRuntime rt) {
                return rt;
            }
        }
        return null;
    }
}

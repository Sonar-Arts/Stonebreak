package com.openmason.engine.ui.assets;

import com.openmason.engine.format.omui.UiDiagnostic;
import com.openmason.engine.format.omui.UiHostProfile;
import com.openmason.engine.format.sbui.SbuiArchive;

import java.util.List;

/**
 * Can this host run this export? Host requirements (actions, data contracts, widget
 * providers) are reported separately from transported artwork: shipping a texture does not
 * ship the Java code a document calls, so a host lacking a required contract refuses the
 * document instead of claiming it is portable.
 *
 * @param host   findings of {@link UiHostProfile#check} against the export's requirement union
 * @param assets findings of resolving every dependency through the host's sources
 */
public record HostCompatibility(List<UiDiagnostic> host, List<UiDiagnostic> assets) {

    public HostCompatibility {
        host = List.copyOf(host);
        assets = List.copyOf(assets);
    }

    public static HostCompatibility check(SbuiArchive sbui, UiHostProfile profile, List<? extends AssetSource> sources) {
        Resolution r = AssetResolver.forExport(sbui, sources).resolveAll();
        return new HostCompatibility(profile.check(sbui.manifest()), r.diagnostics());
    }

    /** True when the host offers every required contract and every required asset resolves. */
    public boolean runnable() {
        return host.stream().noneMatch(UiDiagnostic::isError) && assets.stream().noneMatch(UiDiagnostic::isError);
    }
}

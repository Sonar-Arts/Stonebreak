package com.openmason.engine.format.omui;

import java.util.List;

/**
 * The runtime contracts a document (OMUI manifest) or a game export (SBUI manifest, as the
 * union over its embedded documents) declares. Checked against a {@link UiHostProfile}
 * before instantiation; the format reader itself only enforces {@link #requires()}.
 */
public interface UiRequirements {

    int uiApi();

    String layoutSemantics();

    List<String> requires();

    List<UiManifest.HostRequirement> hostApis();

    List<UiManifest.HostRequirement> providers();
}

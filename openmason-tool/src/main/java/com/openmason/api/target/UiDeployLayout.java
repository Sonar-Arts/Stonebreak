package com.openmason.api.target;

import java.util.Objects;

/**
 * Where a {@link GameTarget} reads deployed UI from, relative to its resource tree (C14, #380):
 * screens at {@code <documents>/<screen>.sbui}, their shared assets under {@code <shared>/}.
 *
 * @param documents folder of shipped screens ({@code ui/documents})
 * @param shared    folder of shared UI assets ({@code ui/shared})
 */
public record UiDeployLayout(String documents, String shared) {

    public UiDeployLayout {
        documents = trimSlashes(Objects.requireNonNull(documents, "documents"));
        shared = trimSlashes(Objects.requireNonNull(shared, "shared"));
    }

    /** Where the game reads shipped screen {@code id} from. */
    public String screenPath(String id) {
        return join(documents, id + ".sbui");
    }

    /** Where a shared asset file ({@code <ns>/<path><ext>}) is deployed. */
    public String sharedPath(String fileName) {
        return join(shared, fileName);
    }

    private static String join(String folder, String rel) {
        return folder.isEmpty() ? rel : folder + "/" + rel;
    }

    private static String trimSlashes(String s) {
        String out = s.replace('\\', '/');
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}

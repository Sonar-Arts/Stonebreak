package com.openmason.main.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives the process its own Windows taskbar identity (AppUserModelID) so the taskbar
 * adopts the tool's window icon instead of the host {@code java.exe} icon. No-op off Windows.
 */
public final class WindowsTaskbarIdentity {

    private static final Logger logger = LoggerFactory.getLogger(WindowsTaskbarIdentity.class);

    private static final String APP_USER_MODEL_ID = "OpenMason.VoxelToolset";

    private WindowsTaskbarIdentity() {
    }

    /**
     * Give this process its own Windows taskbar identity (AppUserModelID).
     *
     * <p>Without an explicit AppUserModelID, a Java app's taskbar button is grouped under the
     * host {@code java.exe}/{@code javaw.exe} process, so Windows shows the launcher's icon there
     * even though {@link WindowIcon#apply(long)} correctly sets the window's title-bar / Alt-Tab icon.
     * Calling shell32 {@code SetCurrentProcessExplicitAppUserModelID} early — before the window
     * is created — makes the taskbar adopt our window icon instead.</p>
     *
     * <p>Uses the Java FFM API (Java 22+, stable in 25); no-op and non-fatal off Windows or if
     * the symbol is unavailable.</p>
     */
    public static void apply() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            java.lang.foreign.Linker linker = java.lang.foreign.Linker.nativeLinker();
            java.lang.foreign.SymbolLookup shell32 =
                    java.lang.foreign.SymbolLookup.libraryLookup("shell32", arena);
            java.lang.foreign.MemorySegment fn = shell32
                    .find("SetCurrentProcessExplicitAppUserModelID")
                    .orElseThrow(() -> new IllegalStateException("SetCurrentProcessExplicitAppUserModelID not found"));

            java.lang.invoke.MethodHandle handle = linker.downcallHandle(fn,
                    java.lang.foreign.FunctionDescriptor.of(
                            java.lang.foreign.ValueLayout.JAVA_INT,   // HRESULT
                            java.lang.foreign.ValueLayout.ADDRESS));  // PCWSTR appId

            // UTF-16LE, null-terminated wide string.
            byte[] utf16 = APP_USER_MODEL_ID.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            java.lang.foreign.MemorySegment appId = arena.allocate(utf16.length + 2L);
            java.lang.foreign.MemorySegment.copy(utf16, 0, appId, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, utf16.length);

            int hr = (int) handle.invoke(appId);
            if (hr != 0) {
                logger.warn("SetCurrentProcessExplicitAppUserModelID returned HRESULT 0x{}", Integer.toHexString(hr));
            } else {
                logger.info("Windows AppUserModelID set to '{}'", APP_USER_MODEL_ID);
            }
        } catch (Throwable t) {
            logger.warn("Could not set Windows AppUserModelID (taskbar icon may use host process icon)", t);
        }
    }
}

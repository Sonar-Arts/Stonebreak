package com.stonebreak.core.window;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.glfw.GLFW.GLFW_ANY_PLATFORM;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_COCOA;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WAYLAND;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_WIN32;
import static org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11;
import static org.lwjgl.glfw.GLFW.glfwGetPlatform;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwInitHint;
import static org.lwjgl.glfw.GLFW.glfwPlatformSupported;

/**
 * Chooses and starts GLFW's display backend.
 *
 * <p>The whole reason this is a separate step is an NVIDIA constraint: the backend has to be picked
 * before {@code glfwInit()} and without ever touching a GL/EGL context, because initializing EGL on
 * Wayland and then using X11/GLX in the same process makes the driver reject {@code glXMakeCurrent}
 * with BadAccess. So the driver version is read context-free from {@code /proc/driver/nvidia/version}
 * — the same version the F3 debug overlay shows via {@code GL_VERSION}.</p>
 */
public final class DisplayBackend {

    private static final Logger logger = LoggerFactory.getLogger(DisplayBackend.class);

    /**
     * NVIDIA driver versions whose native-Wayland EGL path is broken badly enough that we switch to
     * the X11 (XWayland) backend instead. Every other driver stays on native Wayland.
     */
    private static final Set<String> WAYLAND_BLOCKED_NVIDIA_DRIVERS = Set.of(
            "595.71.05",
            // Open Kernel Module build; same libEGL_nvidia SIGSEGV as 595.71.05.
            "595.84"
    );

    /**
     * Extracts the dotted driver version (e.g. "595.71.05", "550.120") from the "NVRM version:" line
     * of /proc/driver/nvidia/version. It is the first dotted number on that line, which works across
     * driver flavors that word the line differently ("... Kernel Module  595.71.05 ..." for the
     * proprietary module, "... Open Kernel Module for x86_64  610.43.02 ..." for the open module).
     */
    private static final Pattern NVIDIA_DRIVER_VERSION_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)+)");

    private static final Path NVIDIA_VERSION_FILE = Path.of("/proc/driver/nvidia/version");

    private DisplayBackend() {
    }

    /**
     * Installs the GLFW error callback, selects the display backend and initializes GLFW.
     *
     * @throws IllegalStateException if GLFW cannot be initialized on any platform
     */
    public static void initialize() {
        GLFWErrorCallback.createPrint(System.err).set();

        boolean pinnedX11 = selectBackend();
        if (!glfwInit()) {
            // A pinned X11 backend can still fail if no X display is reachable;
            // recover by letting GLFW auto-select any available platform.
            if (!pinnedX11) {
                throw new IllegalStateException("Unable to initialize GLFW");
            }
            logger.warn("[Display] X11 init failed (no reachable X display?); reverting to the default platform.");
            glfwInitHint(GLFW_PLATFORM, GLFW_ANY_PLATFORM);
            if (!glfwInit()) {
                throw new IllegalStateException("Unable to initialize GLFW");
            }
        }
        logger.info("[Display] GLFW platform: {}", platformName());
    }

    /** True when GLFW selected the native Wayland backend at init. */
    public static boolean isWayland() {
        return glfwGetPlatform() == GLFW_PLATFORM_WAYLAND;
    }

    /**
     * Sets the GLFW platform init hint on Linux. GLFW otherwise auto-selects native Wayland under a
     * Wayland session; we keep that unless the loaded NVIDIA driver is known-broken, in which case we
     * pin X11. Override with {@code -Dstonebreak.glfw.platform=wayland|x11|any} (default {@code auto}).
     *
     * @return true if the X11 backend was pinned, so the caller can recover if {@code glfwInit()} fails
     */
    private static boolean selectBackend() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return false;
        }

        String preference = System.getProperty("stonebreak.glfw.platform", "auto").trim().toLowerCase();
        if (preference.equals("wayland") || preference.equals("any")) {
            return false; // honor the explicit request; skip driver detection
        }

        boolean wantX11 = preference.equals("x11");
        String reason = "requested via -Dstonebreak.glfw.platform=x11";
        if (!wantX11) {
            String driver = detectNvidiaDriverVersion();
            if (driver != null) {
                logger.info("[Display] Detected NVIDIA driver version: {}", driver);
                if (WAYLAND_BLOCKED_NVIDIA_DRIVERS.contains(driver)) {
                    wantX11 = true;
                    reason = "NVIDIA driver " + driver + " has a broken Wayland EGL path";
                }
            }
        }

        if (!wantX11) {
            return false;
        }
        if (!glfwPlatformSupported(GLFW_PLATFORM_X11)) {
            logger.warn("[Display] Wanted X11 ({}) but the X11 backend is unavailable; using the default platform.",
                    reason);
            return false;
        }
        logger.info("[Display] Pinning GLFW backend to X11: {}.", reason);
        glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_X11);
        return true;
    }

    /**
     * Reads the loaded NVIDIA driver version from {@code /proc/driver/nvidia/version} WITHOUT creating
     * a GL/EGL context. Returns null when the file is absent (no proprietary NVIDIA driver, or nouveau)
     * or the version cannot be parsed. Safe to call before {@code glfwInit()}.
     */
    private static String detectNvidiaDriverVersion() {
        try {
            if (!Files.isReadable(NVIDIA_VERSION_FILE)) {
                return null;
            }
            return parseNvidiaDriverVersion(Files.readAllLines(NVIDIA_VERSION_FILE));
        } catch (Exception e) {
            logger.debug("Could not read NVIDIA driver version: {}", e.toString());
            return null;
        }
    }

    /**
     * Parses the driver version (e.g. "595.71.05") from the lines of
     * {@code /proc/driver/nvidia/version}. Returns null if no NVRM version line is present.
     */
    public static String parseNvidiaDriverVersion(Iterable<String> procVersionLines) {
        for (String line : procVersionLines) {
            // Only the NVRM line carries the driver version; other lines (e.g. the
            // GCC version) also contain dotted numbers we must not pick up.
            //   "NVRM version: NVIDIA UNIX x86_64 Kernel Module  595.71.05  Wed ..."
            //   "NVRM version: NVIDIA UNIX Open Kernel Module for x86_64  610.43.02  ..."
            if (!line.contains("NVRM")) {
                continue;
            }
            Matcher matcher = NVIDIA_DRIVER_VERSION_PATTERN.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static String platformName() {
        return switch (glfwGetPlatform()) {
            case GLFW_PLATFORM_WAYLAND -> "Wayland";
            case GLFW_PLATFORM_X11 -> "X11";
            case GLFW_PLATFORM_WIN32 -> "Win32";
            case GLFW_PLATFORM_COCOA -> "Cocoa";
            default -> "unknown";
        };
    }
}

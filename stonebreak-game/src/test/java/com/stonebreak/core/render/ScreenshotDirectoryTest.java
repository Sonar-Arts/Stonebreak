package com.stonebreak.core.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class ScreenshotDirectoryTest {

    private static final String HOME = "/home/alex";
    private static final Path HOME_DIR = Paths.get(HOME);

    private static Path resolve(String override, String os, Map<String, String> env,
                                Map<Path, List<String>> files, String regValue, boolean dirsExist) {
        Function<Path, Optional<List<String>>> read = p -> Optional.ofNullable(files.get(p));
        return ScreenshotDirectory.resolve(override, os, HOME, env::get, read,
                () -> Optional.ofNullable(regValue), p -> dirsExist);
    }

    private static Path linux(Map<String, String> env, Map<Path, List<String>> files) {
        return resolve(null, "Linux", env, files, null, true);
    }

    @Test
    void overrideWins() {
        assertEquals(Paths.get("/tmp/shots"),
                resolve(" /tmp/shots ", "Linux", Map.of(), Map.of(), null, true));
    }

    @Test
    void linuxReadsXdgPicturesRelativeToHome() {
        Path config = HOME_DIR.resolve(".config/user-dirs.dirs");
        Path result = linux(Map.of(), Map.of(config, List.of(
                "# comment",
                "XDG_DESKTOP_DIR=\"$HOME/Desktop\"",
                "XDG_PICTURES_DIR=\"$HOME/Bilder\"")));
        assertEquals(HOME_DIR.resolve("Bilder/Stonebreak"), result);
    }

    @Test
    void linuxHonoursXdgConfigHomeAndAbsoluteValues() {
        Path config = Paths.get("/cfg/user-dirs.dirs");
        Path result = linux(Map.of("XDG_CONFIG_HOME", "/cfg"),
                Map.of(config, List.of("XDG_PICTURES_DIR=\"/mnt/photos\"")));
        assertEquals(Paths.get("/mnt/photos/Stonebreak"), result);
    }

    @Test
    void linuxFallsBackToHomePictures() {
        assertEquals(HOME_DIR.resolve("Pictures/Stonebreak"), linux(Map.of(), Map.of()));
        Path config = HOME_DIR.resolve(".config/user-dirs.dirs");
        // "$HOME/" means the Pictures dir is disabled
        assertEquals(HOME_DIR.resolve("Pictures/Stonebreak"),
                linux(Map.of(), Map.of(config, List.of("XDG_PICTURES_DIR=\"$HOME/\""))));
    }

    @Test
    void missingConfiguredFolderFallsBackToHomePictures() {
        Path config = HOME_DIR.resolve(".config/user-dirs.dirs");
        Path result = resolve(null, "Linux", Map.of(),
                Map.of(config, List.of("XDG_PICTURES_DIR=\"$HOME/Gone\"")), null, false);
        assertEquals(HOME_DIR.resolve("Pictures/Stonebreak"), result);
    }

    @Test
    void macUsesHomePictures() {
        assertEquals(HOME_DIR.resolve("Pictures/Stonebreak"),
                resolve(null, "Mac OS X", Map.of(), Map.of(), null, true));
    }

    @Test
    void windowsExpandsRegistryValue() {
        Path result = resolve(null, "Windows 11", Map.of("OneDrive", "/od"), Map.of(),
                "%OneDrive%/Pictures", true);
        assertEquals(Paths.get("/od/Pictures/Stonebreak"), result);
    }

    @Test
    void windowsWithoutRegistryUsesHomePictures() {
        assertEquals(HOME_DIR.resolve("Pictures/Stonebreak"),
                resolve(null, "Windows 10", Map.of(), Map.of(), null, true));
    }

    @Test
    void parsesRegQueryOutput() {
        List<String> out = List.of(
                "",
                "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders",
                "    My Pictures    REG_EXPAND_SZ    %USERPROFILE%\\OneDrive\\Pictures",
                "");
        assertEquals(Optional.of("%USERPROFILE%\\OneDrive\\Pictures"), ScreenshotDirectory.parseRegQuery(out));
        assertEquals("C:\\Users\\Al\\Pictures",
                ScreenshotDirectory.expandWindowsEnv("%USERPROFILE%\\Pictures", Map.of("USERPROFILE", "C:\\Users\\Al")::get));
        assertEquals("%NOPE%\\x", ScreenshotDirectory.expandWindowsEnv("%NOPE%\\x", k -> null));
    }
}

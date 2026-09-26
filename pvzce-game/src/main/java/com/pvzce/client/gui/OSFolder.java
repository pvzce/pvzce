package com.pvzce.client.gui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Opening a folder in the desktop's own file manager.
 *
 * <p>One answer for "show me this directory", because it is the first thing in this game that
 * hands a <em>path</em> to the operating system - the only existing call is {@code Desktop.browse}
 * on a URL, from the mod list. Two ways in, and both are needed:
 *
 * <ul>
 *   <li>{@link java.awt.Desktop} is the portable one, but it throws outright on a headless JVM and
 *       is unsupported on many minimal Linux setups - and this game runs under GLFW with no AWT
 *       window of its own, so "AWT is available" is a question rather than an assumption;</li>
 *   <li>the platform's own opener ({@code xdg-open} / {@code open} / {@code explorer}) is what
 *       actually works on those setups, and it is a process rather than a toolkit.</li>
 * </ul>
 *
 * <p>Failure is reported rather than thrown: a player who cannot open a folder has lost nothing
 * they cannot do by hand, and the page says where the folder is either way.
 */
public final class OSFolder {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Desktop");

    private OSFolder() {
    }

    /**
     * Asks the desktop to show {@code directory}.
     *
     * @return true when something was launched; false when neither route worked
     */
    public static boolean open(Path directory) {
        if (directory == null) {
            return false;
        }
        Path absolute = directory.toAbsolutePath();
        if (openWithDesktop(absolute)) {
            return true;
        }
        return openWithPlatformCommand(absolute);
    }

    private static boolean openWithDesktop(Path directory) {
        try {
            java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
            if (!desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                return false;
            }
            desktop.open(directory.toFile());
            return true;
        } catch (Throwable e) {
            // HeadlessException, UnsupportedOperationException, IOException, and whatever a
            // particular desktop throws instead: all of them mean "try the other route".
            LOGGER.debug("Desktop.open is unavailable for {}: {}", directory, e.toString());
            return false;
        }
    }

    private static boolean openWithPlatformCommand(Path directory) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] command;
        if (os.contains("win")) {
            // explorer wants a backslash path and takes the exit code personally; it is started
            // for its side effect only.
            command = new String[] {"explorer", directory.toString()};
        } else if (os.contains("mac")) {
            command = new String[] {"open", directory.toString()};
        } else {
            command = new String[] {"xdg-open", directory.toString()};
        }
        try {
            new ProcessBuilder(command).redirectErrorStream(true).start();
            return true;
        } catch (Exception e) {
            LOGGER.warn("Could not open {} with {}: {}", directory, command[0], e.toString());
            return false;
        }
    }
}

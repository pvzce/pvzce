package com.pvzce.client;

import com.pvzce.client.config.PvzceClientConfig;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWNativeWayland;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** GLFW window + OpenGL 3.2 core context. */
public final class PvzceWindow implements AutoCloseable {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Window");
    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 240;
    private static final int[][] RESOLUTION_PRESETS = {
            {1280, 720},
            {1600, 900},
            {1920, 1080},
            {2560, 1440}
    };

    /** A windowed/fullscreen resolution entry shown by the video settings. */
    public record Resolution(int width, int height, boolean nativeMode) {
        public String label() {
            return width + "×" + height + (nativeMode ? "（原生）" : "");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Resolution other
                    && other.width == width && other.height == height && other.nativeMode == nativeMode;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(width) * 31 + Integer.hashCode(height);
        }
    }

    private long handle;
    private final ConcurrentLinkedQueue<Integer> typedChars = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Integer> pressedKeys = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Integer> mouseButtons = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Double> scrollAmounts = new ConcurrentLinkedQueue<>();
    private volatile double cursorX;
    private volatile double cursorY;
    private volatile int width;
    private volatile int height;
    private int windowedX;
    private int windowedY;
    private int windowedWidth;
    private int windowedHeight;
    private int preferredWidth;
    private int preferredHeight;
    private boolean fullscreen;

    public PvzceWindow(String title, PvzceClientConfig config) {
        this.preferredWidth = Math.max(MIN_WIDTH, config.windowWidth());
        this.preferredHeight = Math.max(MIN_HEIGHT, config.windowHeight());
        this.windowedWidth = preferredWidth;
        this.windowedHeight = preferredHeight;
        this.width = preferredWidth;
        this.height = preferredHeight;

        GLFWErrorCallback.createPrint(System.err).set();
        String forcedPlatform = System.getProperty("pvzce.platform");
        boolean forcedWayland = "wayland".equalsIgnoreCase(forcedPlatform);
        boolean forcedX11 = "x11".equalsIgnoreCase(forcedPlatform);
        // A Wayland session gets the native Wayland backend, and that is not a preference: it is the
        // only place touch can work at all. GLFW's Wayland backend is the one with a wl_touch device
        // (the game reads it itself, see client.input.wayland), while X11/XWayland never turns a
        // finger into mouse events - so on a Wayland desktop, XWayland means an unclickable game.
        boolean preferWayland = forcedWayland
                || (System.getenv("WAYLAND_DISPLAY") != null && !forcedX11);
        boolean preferX11 = !preferWayland
                && (System.getenv("DISPLAY") != null || forcedX11);
        if (preferWayland) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_WAYLAND);
        } else if (preferX11) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
        }
        if (!GLFW.glfwInit()) {
            // The hinted platform is not there (no XWayland, no Wayland): let GLFW pick what exists.
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_ANY_PLATFORM);
            if (!GLFW.glfwInit()) {
                throw new IllegalStateException("Unable to initialize GLFW");
            }
        }
        LOGGER.info("GLFW platform = {}{}", GLFW.glfwGetPlatform(),
                preferWayland ? " (Wayland 会话：触控需要它)" : "");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);

        handle = GLFW.glfwCreateWindow(preferredWidth, preferredHeight, title, MemoryUtil.NULL, MemoryUtil.NULL);
        if (handle == MemoryUtil.NULL) {
            throw new IllegalStateException("Failed to create GLFW window");
        }
        GLFW.glfwSetKeyCallback(handle, keyCallback());
        GLFW.glfwSetCharCallback(handle, charCallback());
        GLFW.glfwSetMouseButtonCallback(handle, mouseButtonCallback());
        GLFW.glfwSetCursorPosCallback(handle, (window, x, y) -> {
            cursorX = x;
            cursorY = y;
        });
        GLFW.glfwSetScrollCallback(handle, (window, x, y) -> scrollAmounts.add(y));
        GLFW.glfwSetFramebufferSizeCallback(handle, (window, w, h) -> {
            width = Math.max(1, w);
            height = Math.max(1, h);
        });

        GLFW.glfwMakeContextCurrent(handle);
        setVsync(config.vsync());
        GL.createCapabilities();
        if (config.fullscreen()) {
            setFullscreen(true);
        }
        GL20.glViewport(0, 0, width, height);
        GLFW.glfwShowWindow(handle);
    }

    public boolean shouldClose() {
        return GLFW.glfwWindowShouldClose(handle);
    }

    public void pollEvents() {
        GLFW.glfwPollEvents();
    }

    public void swapBuffers() {
        GLFW.glfwSwapBuffers(handle);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * Moves the pointer to a point in window pixels, measured from the top-left.
     *
     * <p>That is the pair the platform reports back through the cursor callback, which is
     * where {@link #cursorX()}/{@link #cursorY()} come from - so warping to a point and then
     * reading the cursor gives the same point, and every hover path behaves as it would for a
     * player. Used by the smoke harness to photograph hover states. On a display whose window
     * and framebuffer pixels differ (a scaled desktop), the pointer lands at the framebuffer
     * coordinate, which is what the game reads.
     */
    public void warpCursor(double x, double y) {
        // Recorded here as well as asked for: a window that is not focused does not get its
        // pointer moved (Wayland ignores the request entirely), and the one caller is a
        // screenshot harness that needs the pointer to be *there*, not merely requested. The
        // platform call still happens, so a focused window works exactly as it would if a
        // player moved the mouse.
        setPointerPosition(x, y);
        GLFW.glfwSetCursorPos(handle, x, y);
    }

    /**
     * Records where the pointer is without asking the platform to move it.
     *
     * <p>For the touch layer: a finger has no cursor to warp, but everything that follows "where the
     * pointer is" - the placement ghost, the highlighted cell, a card's name on hover - should follow
     * the last touch. On Wayland {@code glfwSetCursorPos} is ignored anyway, so a touch source has
     * nothing to gain from calling {@link #warpCursor}.
     */
    public void setPointerPosition(double x, double y) {
        cursorX = x;
        cursorY = y;
    }

    /** The GLFW window handle, for the native layers that need it. */
    public long handle() {
        return handle;
    }

    /** The platform GLFW chose: {@code GLFW_PLATFORM_WAYLAND} and friends. */
    public int platform() {
        return GLFW.glfwGetPlatform();
    }

    /**
     * The Wayland surface this window draws into, or {@code 0} when the platform is not Wayland.
     *
     * <p>The touch source needs it to tell "a finger on the game" from "a finger on GLFW's own
     * fallback decoration frame" - a {@code wl_touch.down} reports whichever surface it hit.
     */
    public long waylandSurface() {
        return GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND
                ? GLFWNativeWayland.glfwGetWaylandWindow(handle) : 0L;
    }

    /** The Wayland display connection GLFW is using, or {@code 0} when the platform is not Wayland. */
    public long waylandDisplay() {
        return GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND
                ? GLFWNativeWayland.glfwGetWaylandDisplay() : 0L;
    }

    public double cursorX() {
        return cursorX;
    }

    public double cursorY() {
        return cursorY;
    }

    public Integer pollTypedChar() {
        return typedChars.poll();
    }

    public Integer pollKey() {
        return pressedKeys.poll();
    }

    public Integer pollMouseButton() {
        return mouseButtons.poll();
    }

    public Double pollScroll() {
        return scrollAmounts.poll();
    }

    public void requestClose() {
        GLFW.glfwSetWindowShouldClose(handle, true);
    }

    public boolean isKeyDown(int key) {
        return GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
    }

    public boolean isMouseButtonDown(int button) {
        return GLFW.glfwGetMouseButton(handle, button) == GLFW.GLFW_PRESS;
    }

    /** Applies the video-settings vsync value immediately. */
    public void setVsync(boolean vsync) {
        GLFW.glfwSwapInterval(vsync ? 1 : 0);
    }

    /**
     * Applies the video-settings resolution. In fullscreen mode the current
     * fullscreen mode is re-created at the new size; otherwise the window is
     * resized in place.
     */
    public void setResolution(int newWidth, int newHeight) {
        if (newWidth < MIN_WIDTH || newHeight < MIN_HEIGHT) {
            return;
        }
        preferredWidth = newWidth;
        preferredHeight = newHeight;
        if (fullscreen) {
            enterFullscreen();
        } else {
            windowedWidth = newWidth;
            windowedHeight = newHeight;
            GLFW.glfwSetWindowSize(handle, newWidth, newHeight);
        }
    }

    public int preferredWidth() {
        return preferredWidth;
    }

    public int preferredHeight() {
        return preferredHeight;
    }

    /** F11: switch between windowed and fullscreen; returns the new state. */
    public boolean toggleFullscreen() {
        return setFullscreen(!fullscreen);
    }

    public boolean setFullscreen(boolean wantFullscreen) {
        if (wantFullscreen == fullscreen) {
            return fullscreen;
        }
        try {
            if (wantFullscreen) {
                rememberWindowedState();
                enterFullscreen();
            } else {
                leaveFullscreen();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Fullscreen change unavailable on this platform", e);
            fullscreen = false;
            GLFW.glfwMaximizeWindow(handle);
        }
        return fullscreen;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    /** Resolution entries for the current primary monitor, smallest first. */
    public List<Resolution> availableResolutions() {
        List<Resolution> modes = new ArrayList<>();
        long monitor = GLFW.glfwGetPrimaryMonitor();
        GLFWVidMode nativeMode = monitor == MemoryUtil.NULL ? null : largestVideoMode(monitor);
        if (nativeMode == null) {
            modes.add(new Resolution(preferredWidth, preferredHeight, true));
            return modes;
        }
        for (int[] preset : RESOLUTION_PRESETS) {
            if (preset[0] <= nativeMode.width() && preset[1] <= nativeMode.height()) {
                modes.add(new Resolution(preset[0], preset[1], false));
            }
        }
        Resolution nativeResolution = new Resolution(nativeMode.width(), nativeMode.height(), true);
        if (modes.stream().noneMatch(m -> m.width() == nativeResolution.width()
                && m.height() == nativeResolution.height())) {
            modes.add(nativeResolution);
        }
        if (modes.isEmpty()) {
            modes.add(nativeResolution);
        }
        return modes;
    }

    public Resolution currentResolution() {
        for (Resolution resolution : availableResolutions()) {
            if (resolution.width() == preferredWidth && resolution.height() == preferredHeight) {
                return resolution;
            }
        }
        return new Resolution(preferredWidth, preferredHeight, false);
    }

    private void rememberWindowedState() {
        windowedWidth = width;
        windowedHeight = height;
        if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer x = stack.mallocInt(1);
                IntBuffer y = stack.mallocInt(1);
                GLFW.glfwGetWindowPos(handle, x, y);
                windowedX = x.get(0);
                windowedY = y.get(0);
            }
        } else {
            windowedX = 0;
            windowedY = 0;
        }
    }

    private void enterFullscreen() {
        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor == MemoryUtil.NULL) {
            throw new IllegalStateException("No monitor available");
        }
        int refreshRate = GLFW.GLFW_DONT_CARE;
        GLFWVidMode mode = findVideoMode(monitor, preferredWidth, preferredHeight);
        if (mode != null) {
            refreshRate = mode.refreshRate();
        }
        GLFW.glfwSetWindowMonitor(handle, monitor, 0, 0, preferredWidth, preferredHeight, refreshRate);
        width = preferredWidth;
        height = preferredHeight;
        fullscreen = true;
    }

    /**
     * The largest mode advertised by the monitor, not the monitor's current
     * mode (entering 720p fullscreen would otherwise make every larger preset
     * disappear from the video settings list).
     */
    private static GLFWVidMode largestVideoMode(long monitor) {
        if (monitor == MemoryUtil.NULL) {
            return null;
        }
        GLFWVidMode.Buffer modes = GLFW.glfwGetVideoModes(monitor);
        if (modes == null) {
            return GLFW.glfwGetVideoMode(monitor);
        }
        GLFWVidMode largest = null;
        for (int i = 0; i < modes.limit(); i++) {
            GLFWVidMode mode = modes.get(i);
            if (largest == null || mode.width() * mode.height() > largest.width() * largest.height()) {
                largest = mode;
            }
        }
        return largest;
    }

    private static GLFWVidMode findVideoMode(long monitor, int width, int height) {
        if (monitor == MemoryUtil.NULL) {
            return null;
        }
        GLFWVidMode.Buffer modes = GLFW.glfwGetVideoModes(monitor);
        if (modes == null) {
            GLFWVidMode current = GLFW.glfwGetVideoMode(monitor);
            return current != null && current.width() == width && current.height() == height ? current : null;
        }
        for (int i = 0; i < modes.limit(); i++) {
            GLFWVidMode mode = modes.get(i);
            if (mode.width() == width && mode.height() == height) {
                return mode;
            }
        }
        return null;
    }

    private void leaveFullscreen() {
        GLFW.glfwSetWindowMonitor(handle, MemoryUtil.NULL, windowedX, windowedY, windowedWidth, windowedHeight, 0);
        width = windowedWidth;
        height = windowedHeight;
        fullscreen = false;
    }

    private GLFWKeyCallback keyCallback() {
        return new GLFWKeyCallback() {
            @Override
            public void invoke(long window, int key, int scancode, int action, int mods) {
                // Only real presses are queued; held-key repetition is handled
                // per-frame by PvzceClient so Backspace speed is controllable.
                if (action == GLFW.GLFW_PRESS) {
                    pressedKeys.add(key);
                }
            }
        };
    }

    private GLFWCharCallback charCallback() {
        return new GLFWCharCallback() {
            @Override
            public void invoke(long window, int codepoint) {
                typedChars.add(codepoint);
            }
        };
    }

    private GLFWMouseButtonCallback mouseButtonCallback() {
        return new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW.GLFW_PRESS) {
                    mouseButtons.add(button);
                }
            }
        };
    }

    @Override
    public void close() {
        if (handle != MemoryUtil.NULL) {
            GLFW.glfwDestroyWindow(handle);
            handle = MemoryUtil.NULL;
            GLFW.glfwTerminate();
        }
    }
}

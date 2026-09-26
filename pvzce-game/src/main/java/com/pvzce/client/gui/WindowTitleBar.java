package com.pvzce.client.gui;

import com.pvzce.client.PvzceClient;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The window's own title bar, drawn by the game, for desktops that give it none.
 *
 * <p><b>Why this exists.</b> On Wayland GLFW hands window decorations to libdecor, and libdecor's
 * GTK plugin refuses to initialise when {@code getpid() != gettid()} - which for a JVM is always,
 * because the java launcher creates the VM on a fresh pthread rather than the process's first
 * thread (JDK-6316197). With no plugin, libdecor reports "falling back on no decorations" and the
 * window comes up with no title bar, no close button and no way to move it. GNOME cannot help
 * either: Mutter does not advertise {@code zxdg_decoration_manager_v1} (verified on the machine
 * this was written on - it is not in the registry), so there is no server-side decoration to fall
 * back to. Every other client on that desktop draws its own decorations; so does this one.
 *
 * <p><b>It is not a Wayland-only view.</b> The rule is "does the desktop draw a frame for me",
 * which is what {@code PvzceWindow.frameInsets()} answers: non-zero on X11 and Windows, all zero on
 * a Wayland window without libdecor. So on a desktop that does decorate, this class draws nothing
 * and swallows no clicks.
 *
 * <p><b>What it covers.</b> The three buttons every desktop puts in a title bar - minimize,
 * maximize/restore and close - and a double click on the empty part of the bar to toggle
 * maximization, which is the other gesture every desktop's title bar has. Deliberately not covered:
 * <ul>
 *   <li><b>Moving the window by dragging the bar.</b> Everywhere else that is
 *       {@code glfwSetWindowPos}, but a Wayland client may not set its own position - GLFW rejects
 *       the call outright there ("The platform does not support setting the window position") - and
 *       the two ways a Wayland client <em>can</em> do it are closed to us: libdecor's
 *       {@code libdecor_frame_move} needs a libdecor frame we never got (that is the whole reason
 *       this class exists), and {@code xdg_toplevel.move} needs GLFW's own {@code xdg_toplevel},
 *       which GLFW keeps private and does not hand out. Reading it out of GLFW's window struct by
 *       guessed offset would be reaching into another library's private memory from a game loop;
 *       a wrong guess is a SIGSEGV, so it is not done. Mutter's own Alt+F7 move works instead.</li>
 *   <li><b>Edge resize.</b> The window is {@code GLFW_RESIZABLE}, so the compositor's own resize
 *       affordances and shortcuts still apply.</li>
 *   <li><b>The window menu</b> (right-click on a title bar).</li>
 * </ul>
 *
 * <p><b>Where it sits.</b> The bar is painted over the top of the frame rather than reserving a
 * strip of the GUI: the game's layout already keeps its top edge clear (the sun counter and the
 * card bar start below it), so carving the space out would move every screen's geometry for the
 * sake of a 30-pixel strip. Painting last, and claiming the pointer before anything else in
 * {@code PvzceClient}'s dispatch, keeps the two from meeting.
 */
public final class WindowTitleBar {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Window");
    /** GUI pixels per unit of bar height; the bar scales with the rest of the UI. */
    private static final int GUI_UNITS_PER_HEIGHT = 240;
    /** Below this the bar's text and glyphs stop being legible, above it it is a billboard. */
    private static final int MIN_HEIGHT = 18;
    private static final int MAX_HEIGHT = 46;
    /** Button square/width against the bar height: Windows' caption buttons are 46x32. */
    private static final float BUTTON_WIDTH_OF_HEIGHT = 1.4375F;
    /** Title text height against the bar: leaves a margin above and below the caps. */
    private static final float TEXT_OF_HEIGHT = 0.46F;
    private static final float BACKGROUND_R = 0.08F;
    private static final float BACKGROUND_G = 0.09F;
    private static final float BACKGROUND_B = 0.11F;
    private static final float BACKGROUND_A = 0.88F;
    private static final float SEPARATOR_A = 0.30F;
    private static final float TITLE_R = 0.93F;
    private static final float TITLE_G = 0.94F;
    private static final float TITLE_B = 0.96F;
    private static final float BUTTON_HOVER_R = 1F;
    private static final float BUTTON_HOVER_G = 1F;
    private static final float BUTTON_HOVER_B = 1F;
    private static final float BUTTON_HOVER_A = 0.16F;
    private static final float BUTTON_DOWN_A = 0.30F;
    private static final float CLOSE_HOVER_R = 0.90F;
    private static final float CLOSE_HOVER_G = 0.22F;
    private static final float CLOSE_HOVER_B = 0.21F;
    private static final float CLOSE_HOVER_A = 0.92F;
    private static final float GLYPH_A = 0.92F;
    /** Above the game's own HUD (0.4ish), below nothing: this is window chrome. */
    private static final float Z = 0.90F;

    /** The three buttons, left to right in the order Windows and GNOME use. */
    public enum Button {
        MINIMIZE, MAXIMIZE, CLOSE
    }

    /**
     * One frame's bar geometry, in logical GUI pixels.
     *
     * <p>A value rather than fields on the bar, so the arithmetic that decides where a button is can
     * be asserted without a window: the bar scales with the GUI height and the three buttons are
     * anchored to the <em>right</em> edge, which is exactly the kind of thing that silently mirrors
     * when someone changes a coordinate convention. {@code y} is measured downwards from the top,
     * the way the pointer callback reports it - so the bar spans {@code [0, height)} while the same
     * bar is drawn from {@code surfaceHeight - height} upwards, and that flip lives in one place
     * ({@code WindowTitleBar.render}).
     */
    public record Layout(int surfaceWidth, int surfaceHeight, int height, int buttonWidth) {
        /** Bar height from the GUI height, clamped so the text stays legible at any scale. */
        public static Layout of(int surfaceWidth, int surfaceHeight) {
            int height = Math.clamp(surfaceHeight / GUI_UNITS_PER_HEIGHT, MIN_HEIGHT, MAX_HEIGHT);
            return new Layout(surfaceWidth, surfaceHeight, height,
                    Math.max(1, Math.round(height * BUTTON_WIDTH_OF_HEIGHT)));
        }

        /** The buttons' left edges, in {@link Button} order; all widths are {@link #buttonWidth}. */
        public int buttonLeft(Button button) {
            int fromRight = Button.values().length - button.ordinal();
            return surfaceWidth - fromRight * buttonWidth;
        }

        /** The button a point is over, or {@code null} for the empty part of the bar. */
        public Button buttonAt(double x, double y) {
            if (!contains(x, y)) {
                return null;
            }
            for (Button button : Button.values()) {
                int left = buttonLeft(button);
                if (x >= left && x < left + buttonWidth) {
                    return button;
                }
            }
            return null;
        }

        /** Whether the point is on the bar at all - button or empty strip. */
        public boolean contains(double x, double y) {
            return x >= 0 && x < surfaceWidth && y >= 0 && y < height;
        }
    }

    private final PvzceClient client;
    private final TitleBarGesture gesture = new TitleBarGesture();

    public WindowTitleBar(PvzceClient client) {
        this.client = client;
    }

    /**
     * Whether this window has to draw its own title bar this frame.
     *
     * <p>False while fullscreen (there is no window to decorate) and false when the desktop draws a
     * frame of its own, which is what {@code frameInsets()} reports as non-zero.
     */
    public boolean visible() {
        if (client == null || client.window() == null || client.window().isFullscreen()) {
            return false;
        }
        int[] insets = client.window().frameInsets();
        return insets[0] == 0 && insets[1] == 0 && insets[2] == 0 && insets[3] == 0;
    }

    /**
     * This frame's geometry, or {@code null} when there is no bar to lay out.
     *
     * <p>Computed from the live frame rather than cached by {@link #render()}: input arrives before
     * the first paint of a resized window, and a hit test against last frame's width would put the
     * close button somewhere it is not.
     */
    public Layout layout() {
        return visible() ? Layout.of(client.guiWidth(), client.guiHeight()) : null;
    }

    /** Which button a GUI point is over, or {@code null}. */
    public Button buttonAt(double guiX, double guiY) {
        Layout layout = layout();
        return layout == null ? null : layout.buttonAt(guiX, guiY);
    }

    /**
     * Whether the bar owns this point, and therefore whether a press here is window chrome rather
     * than a click into the game.
     */
    public boolean contains(double guiX, double guiY) {
        Layout layout = layout();
        return layout != null && layout.contains(guiX, guiY);
    }

    /** Tracks the pointer for hover feedback; the client calls this once a frame. */
    public void mouseMoved(double guiX, double guiY) {
        gesture.hover(layout(), guiX, guiY);
    }

    /**
     * Whether the bar is in the middle of a press it owns.
     *
     * <p>The drag path asks this so a sweep that started on the bar cannot drag a card, a sun or a
     * mower out from under it: the press was chrome, so the whole gesture is chrome.
     */
    public boolean dragging() {
        return gesture.dragging();
    }

    /**
     * Handles a press on the bar.
     *
     * @return true when the bar consumed it and the game must not see it
     */
    public boolean mousePressed(double guiX, double guiY) {
        return gesture.press(layout(), guiX, guiY, GLFW.glfwGetTime());
    }

    /**
     * Handles the release that ends a press the bar consumed.
     *
     * @return true when the bar consumed it
     */
    public boolean mouseReleased(double guiX, double guiY) {
        boolean owned = gesture.dragging() || contains(guiX, guiY);
        switch (gesture.release(layout(), guiX, guiY)) {
            case MINIMIZE -> activate(Button.MINIMIZE);
            case MAXIMIZE -> activate(Button.MAXIMIZE);
            case CLOSE -> activate(Button.CLOSE);
            case TOGGLE_MAXIMIZED -> {
                LOGGER.info("标题栏：双击空白处");
                activate(Button.MAXIMIZE);
            }
            case NONE -> {
            }
        }
        return owned;
    }

    /** Runs one button's action. */
    public void activate(Button button) {
        // One line per press: "the bar is there but its buttons do nothing" is otherwise
        // indistinguishable from "the widget never received the click", and this is the boundary
        // between the two. Maximize reads the state back afterwards, because a compositor is free
        // to refuse the request and "asked to maximize" is not "is maximized".
        LOGGER.info("标题栏：按下 {}", button);
        switch (button) {
            case MINIMIZE -> client.window().minimize();
            case MAXIMIZE -> {
                if (client.window().isMaximized()) {
                    client.window().restore();
                } else {
                    client.window().maximize();
                }
                LOGGER.info("标题栏：最大化={}", client.window().isMaximized());
            }
            case CLOSE -> client.window().requestClose();
        }
    }

    /** Draws the bar across the top of the frame. The GUI projection must already be active. */
    public void render() {
        Layout layout = layout();
        if (layout == null) {
            return;
        }
        // GUI Y grows upwards, the pointer's Y grows downwards, and the bar is at the top in both:
        // that flip is the whole reason the drawing works from `bottom` while the hit tests work
        // from `height`.
        float bottom = layout.surfaceHeight() - layout.height();

        client.drawSolid(0F, bottom, layout.surfaceWidth(), layout.height(), Z,
                BACKGROUND_R, BACKGROUND_G, BACKGROUND_B, BACKGROUND_A);
        client.drawSolid(0F, bottom, layout.surfaceWidth(), 1F, Z + 0.001F,
                TITLE_R, TITLE_G, TITLE_B, SEPARATOR_A);

        renderTitle(layout, bottom);
        renderButtons(layout, bottom);
    }

    private void renderTitle(Layout layout, float bottom) {
        String title = client.window().title();
        if (title == null || title.isEmpty()) {
            return;
        }
        float height = layout.height();
        float scale = height * TEXT_OF_HEIGHT / Math.max(1F, client.fonts().body().lineHeight(1F));
        float maxWidth = layout.surfaceWidth() - (float) layout.buttonWidth() * Button.values().length
                - height * 0.75F;
        if (maxWidth <= 0F) {
            return;
        }
        float textWidth = client.fonts().body().width(title, scale);
        if (textWidth > maxWidth) {
            scale *= maxWidth / textWidth;
        }
        float x = height * 0.5F;
        float y = bottom + (height - client.fonts().body().lineHeight(scale)) / 2F;
        client.fonts().body().draw(title, x, y, scale, TITLE_R, TITLE_G, TITLE_B, GLYPH_A);
    }

    private void renderButtons(Layout layout, float bottom) {
        for (Button button : Button.values()) {
            int x = layout.buttonLeft(button);
            boolean isPressed = gesture.pressed() == button;
            if (button == gesture.hovered() || isPressed) {
                boolean close = button == Button.CLOSE;
                float alpha = isPressed ? BUTTON_DOWN_A
                        : close ? CLOSE_HOVER_A : BUTTON_HOVER_A;
                client.drawSolid(x, bottom, layout.buttonWidth(), layout.height(), Z + 0.002F,
                        close ? CLOSE_HOVER_R : BUTTON_HOVER_R,
                        close ? CLOSE_HOVER_G : BUTTON_HOVER_G,
                        close ? CLOSE_HOVER_B : BUTTON_HOVER_B,
                        alpha);
            }
            renderGlyph(layout, button, x, bottom);
        }
    }

    /**
     * The button's icon, drawn from rectangles rather than from glyphs.
     *
     * <p>Not text on purpose: the game's fonts are content (a resource pack can swap them), and a
     * close button whose X comes out as a missing-glyph box is worse than no close button. Four
     * {@code drawSolid} calls cannot break that way.
     */
    private void renderGlyph(Layout layout, Button button, int x, float bottom) {
        float height = layout.height();
        float stroke = Math.max(1F, Math.round(height / 12F));
        float size = Math.max(4F, Math.round(height * 0.30F));
        float centerX = x + layout.buttonWidth() / 2F;
        float centerY = bottom + height / 2F;
        float left = centerX - size / 2F;
        float iconBottom = centerY - size / 2F;
        switch (button) {
            case CLOSE -> {
                // Two diagonals as stair-steps: horizontal strips of decreasing offset.
                int steps = (int) size;
                for (int step = 0; step < steps; step++) {
                    float at = (float) step / Math.max(1, steps - 1);
                    client.drawSolid(left + size * at, iconBottom + size * at, stroke, stroke,
                            Z + 0.003F, TITLE_R, TITLE_G, TITLE_B, GLYPH_A);
                    client.drawSolid(left + size * (1F - at), iconBottom + size * at, stroke, stroke,
                            Z + 0.003F, TITLE_R, TITLE_G, TITLE_B, GLYPH_A);
                }
            }
            case MINIMIZE -> client.drawSolid(left, centerY - stroke / 2F, size, stroke,
                    Z + 0.003F, TITLE_R, TITLE_G, TITLE_B, GLYPH_A);
            case MAXIMIZE -> {
                if (client.window().isMaximized()) {
                    // "Restore": two overlapping squares, the back one offset up and right.
                    float offset = Math.max(2F, size * 0.28F);
                    outline(left + offset, iconBottom + offset, size - offset, size - offset, stroke);
                    outline(left, iconBottom, size - offset, size - offset, stroke);
                } else {
                    outline(left, iconBottom, size, size, stroke);
                }
            }
        }
    }

    /** A hollow square, as four thin bars. */
    private void outline(float x, float y, float width, float height, float stroke) {
        client.drawSolid(x, y, width, stroke, Z + 0.003F, TITLE_R, TITLE_G, TITLE_B, GLYPH_A);
        client.drawSolid(x, y + height - stroke, width, stroke, Z + 0.003F,
                TITLE_R, TITLE_G, TITLE_B, GLYPH_A);
        client.drawSolid(x, y, stroke, height, Z + 0.003F, TITLE_R, TITLE_G, TITLE_B, GLYPH_A);
        client.drawSolid(x + width - stroke, y, stroke, height, Z + 0.003F,
                TITLE_R, TITLE_G, TITLE_B, GLYPH_A);
    }
}

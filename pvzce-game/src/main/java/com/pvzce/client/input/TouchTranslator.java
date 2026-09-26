package com.pvzce.client.input;

/**
 * One finger, turned into the same pointer stream a mouse produces.
 *
 * <p>This is the whole of the touch semantics on a platform that does not hand us mouse events for
 * a finger (Wayland: GLFW has no touch API at all, so the events are read from {@code wl_touch} by
 * {@code client.input.wayland.WaylandTouch}). What the gestures <em>mean</em> is deliberately not
 * decided here: a press, a drag and a release go into the very same entry points the frame loop
 * uses for a mouse, so {@link PointerGesture} decides tap-versus-scroll and every screen keeps
 * behaving exactly as it does for a mouse - the board's sweep-to-collect, dragging a card onto a
 * cell, the 500 ms mower hold and the six declared scroll regions all come along for free.
 *
 * <p>Only one finger is followed. The game is played with one hand: a second finger arriving
 * mid-gesture is ignored rather than restarting the press, because a restart would turn "two
 * fingers on the lawn" into a click on the second one. The compositor's {@code cancel} (it can take
 * a touch away at any time, for instance when a system gesture wins) is treated as letting go, so a
 * cancelled gesture cannot leave the game holding a card forever.
 */
public final class TouchTranslator {
    private static final int NO_FINGER = -1;

    /** Where the translated events go; {@code PvzceClient} routes them exactly like mouse input. */
    public interface Pointer {
        void press(double x, double y);

        void drag(double x, double y);

        void release();
    }

    private final Pointer pointer;
    private int activeId = NO_FINGER;

    public TouchTranslator(Pointer pointer) {
        this.pointer = pointer;
    }

    /** A finger going down. Ignored while another finger is already down. */
    public void down(int id, double x, double y) {
        if (activeId != NO_FINGER) {
            return;
        }
        activeId = id;
        pointer.press(x, y);
    }

    /** A finger moving. Ignored for any finger that is not the one being followed. */
    public void motion(int id, double x, double y) {
        if (id != activeId) {
            return;
        }
        pointer.drag(x, y);
    }

    /** A finger coming up. */
    public void up(int id) {
        if (id != activeId) {
            return;
        }
        activeId = NO_FINGER;
        pointer.release();
    }

    /** The compositor took the touch away: let go, whatever state the gesture was in. */
    public void cancel() {
        if (activeId == NO_FINGER) {
            return;
        }
        activeId = NO_FINGER;
        pointer.release();
    }

    /** True while a finger is being followed. */
    public boolean touching() {
        return activeId != NO_FINGER;
    }
}

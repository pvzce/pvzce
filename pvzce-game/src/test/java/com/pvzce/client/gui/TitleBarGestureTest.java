package com.pvzce.client.gui;

import com.pvzce.client.gui.TitleBarGesture.Action;
import com.pvzce.client.gui.WindowTitleBar.Button;
import com.pvzce.client.gui.WindowTitleBar.Layout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a click on the client-drawn title bar means, without a window.
 *
 * <p>These are the rules a real desktop's title bar has and a hand-rolled one forgets. None of them
 * is visible on screen until someone tries it by hand, and two of them are the difference between
 * "the window is usable" and "the window is stuck": a press that never becomes an action because
 * the release was not delivered, and a press that also presses whatever is underneath it.
 */
class TitleBarGestureTest {
    private static final int GUI_WIDTH = 1280;
    private static final int GUI_HEIGHT = 720;
    private static final Layout LAYOUT = Layout.of(GUI_WIDTH, GUI_HEIGHT);

    private static final double MIDDLE = LAYOUT.height() / 2.0;
    private static final double EMPTY_X = 400;
    private static final double CLOSE_X =
            LAYOUT.buttonLeft(Button.CLOSE) + LAYOUT.buttonWidth() / 2.0;
    private static final double MINIMIZE_X =
            LAYOUT.buttonLeft(Button.MINIMIZE) + LAYOUT.buttonWidth() / 2.0;

    private final TitleBarGesture gesture = new TitleBarGesture();

    private Action click(double x, double y, double at) {
        assertTrue(gesture.press(LAYOUT, x, y, at), "the bar must own a press on itself");
        return gesture.release(LAYOUT, x, y);
    }

    @Test
    void aClickOnCloseCloses() {
        assertEquals(Action.CLOSE, click(CLOSE_X, MIDDLE, 0.0));
    }

    @Test
    void aClickOnMinimizeMinimizes() {
        assertEquals(Action.MINIMIZE, click(MINIMIZE_X, MIDDLE, 0.0));
    }

    @Test
    void theActionNeedsAPressAndAReleaseOnTheSameButton() {
        // The first version of the bar acted on press. That works for a mouse and is wrong for
        // everyone who ever pressed a close button, changed their mind and slid off it.
        gesture.press(LAYOUT, CLOSE_X, MIDDLE, 0.0);
        assertEquals(Action.NONE, gesture.release(LAYOUT, MINIMIZE_X, MIDDLE),
                "releasing over a neighbour is not that neighbour's action");
        assertFalse(gesture.dragging(), "and the gesture is over");
    }

    @Test
    void aReleaseWithNoPressOnTheBarIsNotOurs() {
        // e.g. a press inside the game that ended above the bar: it must not close the window.
        assertEquals(Action.NONE, gesture.release(LAYOUT, CLOSE_X, MIDDLE));
    }

    @Test
    void aPressOnTheBarIsSwallowed() {
        assertTrue(gesture.press(LAYOUT, EMPTY_X, MIDDLE, 0.0));
        assertFalse(gesture.press(LAYOUT, EMPTY_X, LAYOUT.activationHeight(), 0.0),
                "below the band that answers is the game's");
    }

    @Test
    void draggingIsTrueForTheWholeGestureIncludingTheRelease() {
        // The drag path reads this to decide whether the game sees a pointer move. It has to stay
        // true across the release itself, or a sweep that started on the bar drags a card on its
        // last frame.
        assertFalse(gesture.dragging());
        gesture.press(LAYOUT, EMPTY_X, MIDDLE, 0.0);
        assertTrue(gesture.dragging(), "a press on the empty strip owns the sweep too");
        gesture.release(LAYOUT, EMPTY_X + 200, MIDDLE);
        assertFalse(gesture.dragging());
    }

    @Test
    void twoQuickClicksOnTheEmptyStripToggleMaximization() {
        assertEquals(Action.NONE, click(EMPTY_X, MIDDLE, 0.0));
        assertEquals(Action.TOGGLE_MAXIMIZED, click(EMPTY_X, MIDDLE, 0.2));
    }

    @Test
    void twoSlowClicksAreTwoClicks() {
        click(EMPTY_X, MIDDLE, 0.0);
        assertEquals(Action.NONE, click(EMPTY_X, MIDDLE,
                TitleBarGesture.DOUBLE_CLICK_SECONDS + 0.05));
    }

    @Test
    void aDoubleClickAcrossTheBarDoesNotCount() {
        // Two clicks 600 GUI units apart are not a double click even if they are fast: the desktop
        // rule is "the same spot", and without the distance check a player clicking two different
        // things on the bar would maximize the window.
        click(EMPTY_X, MIDDLE, 0.0);
        assertEquals(Action.NONE, click(EMPTY_X + 600, MIDDLE, 0.2));
    }

    @Test
    void aDoubleClickOnAButtonIsStillThatButton() {
        // Close is not a toggle, and the second click of a double click must not be upgraded into
        // "toggle maximization" just because it was fast.
        assertEquals(Action.CLOSE, click(CLOSE_X, MIDDLE, 0.0));
        assertEquals(Action.CLOSE, click(CLOSE_X, MIDDLE, 0.1));
    }

    @Test
    void hoverFollowsThePointerAndClearsWhenTheBarGoesAway() {
        gesture.hover(LAYOUT, CLOSE_X, MIDDLE);
        assertEquals(Button.CLOSE, gesture.hovered());
        gesture.hover(LAYOUT, EMPTY_X, MIDDLE);
        assertEquals(null, gesture.hovered(), "the empty strip is not a button");
        gesture.press(LAYOUT, CLOSE_X, MIDDLE, 0.0);
        gesture.hover(null, CLOSE_X, MIDDLE);
        assertEquals(null, gesture.pressed(), "no bar means no press to remember");
    }
}

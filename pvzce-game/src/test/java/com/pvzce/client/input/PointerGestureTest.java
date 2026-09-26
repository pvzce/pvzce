package com.pvzce.client.input;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tap/swipe split, and in particular the one thing it exists to prevent.
 *
 * <p>Touch arrives as mouse input, so a tap needs no code - but a swipe that is turned into a scroll
 * would also fire a click where the finger went down, because this project delivers clicks on
 * <em>press</em> and has no movement threshold anywhere. These tests pin the rule that makes the
 * swipe safe (inside a declared region the click waits for the finger to come up) and the rule that
 * keeps everything else untouched (outside one, the press is delivered immediately and every frame's
 * drag is forwarded).
 */
class PointerGestureTest {
    private static final ScrollRegion VERTICAL = ScrollRegion.mirrorsWheel(ScrollRegion.Axis.VERTICAL, 20);
    private static final ScrollRegion VERTICAL_DRAGGING =
            ScrollRegion.dragsContent(ScrollRegion.Axis.VERTICAL, 20);

    private final List<String> events = new ArrayList<>();

    private PointerGesture gesture(ScrollRegion region) {
        return gesture(region, true);
    }

    private PointerGesture gesture(ScrollRegion region, boolean enabled) {
        return new PointerGesture(
                (guiX, guiY) -> region,
                (guiX, guiY, button) -> events.add("click " + guiX + "," + guiY + " button " + button),
                (guiX, guiY, amount) -> events.add(
                        (amount > 0 ? "scroll +" : "scroll -") + " at " + guiX + "," + guiY),
                enabled);
    }

    private List<String> clicks() {
        return events.stream().filter(event -> event.startsWith("click")).toList();
    }

    private List<String> scrolls() {
        return events.stream().filter(event -> event.startsWith("scroll")).toList();
    }

    @Test
    void aTapInAScrollRegionIsDeliveredWhereTheFingerWentDown() {
        PointerGesture gesture = gesture(VERTICAL);

        assertTrue(gesture.press(0, 100, 50, true), "the gesture owns a press inside a region");
        assertEquals(List.of(), events, "nothing is delivered while the finger is still down");
        assertTrue(gesture.released(), "the gesture owns the matching release");

        assertEquals(List.of("click 100.0,50.0 button 0"), events);
    }

    @Test
    void aPressOutsideAScrollRegionIsLeftToTheCaller() {
        PointerGesture gesture = gesture(null);

        assertFalse(gesture.press(0, 100, 50, true),
                "with no region the caller delivers the click itself, as it always did");
        assertFalse(gesture.dragged(140, 50), "the drag is the screen's, not the gesture's");
        assertFalse(gesture.released(), "and so is the release");
        assertEquals(List.of(), events);
    }

    @Test
    void aFewPixelsOfDriftIsStillATap() {
        PointerGesture gesture = gesture(VERTICAL);

        gesture.press(0, 100, 50, true);
        gesture.dragged(103, 52);
        gesture.released();

        assertEquals(List.of("click 100.0,50.0 button 0"), events,
                "a tap is delivered where the finger went down, not where it drifted to");
        assertEquals(List.of(), scrolls(), "and drifting a few pixels scrolls nothing");
    }

    @Test
    void aSwipeEmitsOneStepPerStepOfTravel() {
        PointerGesture gesture = gesture(VERTICAL);

        gesture.press(0, 100, 0, true);
        gesture.dragged(100, 20);
        assertEquals(List.of("scroll + at 100.0,20.0"), events, "one step of travel is one scroll event");

        gesture.dragged(100, 45);
        assertEquals(List.of("scroll + at 100.0,20.0", "scroll + at 100.0,45.0"), events,
                "and the second step follows at 40");
    }

    @Test
    void aSlowSwipeStillStepsOnceItAddsUp() {
        PointerGesture gesture = gesture(VERTICAL);

        gesture.press(0, 100, 0, true);
        // Two frames that each move less than a step: the travel has to accumulate, or a slow swipe
        // would scroll nothing at all.
        gesture.dragged(100, 15);
        assertEquals(List.of(), events);
        gesture.dragged(100, 30);

        assertEquals(List.of("scroll + at 100.0,30.0"), events);
    }

    @Test
    void aSwipeNeverClicks() {
        PointerGesture gesture = gesture(VERTICAL);

        gesture.press(0, 100, 0, true);
        gesture.dragged(100, 60);
        gesture.released();

        assertEquals(List.of(), clicks(),
                "a swipe must not press whatever it started on: in the shop that press buys, in the "
                        + "player picker it switches player, on the card bar it selects a card");
        assertEquals(List.of("scroll + at 100.0,60.0", "scroll + at 100.0,60.0",
                        "scroll + at 100.0,60.0"), events,
                "three steps of travel, each carrying the finger position the consumers hit-test");
    }

    @Test
    void aReverseSwipeUndoesItsOwnSteps() {
        PointerGesture gesture = gesture(VERTICAL);

        gesture.press(0, 100, 0, true);
        gesture.dragged(100, 40);
        gesture.dragged(100, 0);

        assertEquals(List.of("scroll + at 100.0,40.0", "scroll + at 100.0,40.0",
                        "scroll - at 100.0,0.0", "scroll - at 100.0,0.0"), events,
                "swiping back must emit the opposite steps, not a single accumulated amount");
    }

    @Test
    void movementAcrossTheAxisCancelsTheTapWithoutScrolling() {
        PointerGesture gesture = gesture(VERTICAL);

        gesture.press(0, 100, 100, true);
        gesture.dragged(160, 100);
        gesture.released();

        assertEquals(List.of(), events,
                "a finger dragged sideways across a list did not tap it, and a vertical region "
                        + "scrolled nothing");
    }

    @Test
    void theRegionDecidesWhichWayAFingerMovesTheContent() {
        // The same swipe - a finger travelling 20 pixels up - through three kinds of region.
        List<Double> mirror = new ArrayList<>();
        PointerGesture mirroring = new PointerGesture((x, y) -> VERTICAL,
                (x, y, button) -> {
                }, (x, y, amount) -> mirror.add(amount), true);
        mirroring.press(0, 100, 0, true);
        mirroring.dragged(100, 20);

        List<Double> dragging = new ArrayList<>();
        PointerGesture draggingContent = new PointerGesture((x, y) -> VERTICAL_DRAGGING,
                (x, y, button) -> {
                }, (x, y, amount) -> dragging.add(amount), true);
        draggingContent.press(0, 100, 0, true);
        draggingContent.dragged(100, 20);

        List<Double> horizontal = new ArrayList<>();
        PointerGesture sideways = new PointerGesture(
                (x, y) -> ScrollRegion.dragsContent(ScrollRegion.Axis.HORIZONTAL, 20),
                (x, y, button) -> {
                }, (x, y, amount) -> horizontal.add(amount), true);
        sideways.press(0, 0, 100, true);
        sideways.dragged(20, 100);

        assertEquals(List.of(1.0), mirror, "a bottom-up list mirrors its wheel: finger up = wheel up");
        assertEquals(List.of(-1.0), dragging,
                "content that runs downward follows the finger: finger up = wheel down");
        assertEquals(List.of(1.0), horizontal,
                "dragging a row to the right shows what is to its left, like the wheel");
    }

    @Test
    void aDisabledGestureLeavesEveryPressToTheCaller() {
        PointerGesture gesture = gesture(VERTICAL, false);

        assertFalse(gesture.press(0, 100, 0, true), "-Dpvzce.touch=false is a press-time click again");
        assertFalse(gesture.dragged(100, 60));
        assertFalse(gesture.released());
        assertEquals(List.of(), events, "and nothing scrolls, however far the finger travels");
    }

    @Test
    void aPressThatIsAlreadyUpIsStillATap() {
        PointerGesture gesture = gesture(VERTICAL);

        assertTrue(gesture.press(0, 100, 50, false),
                "a press and release inside one poll is still owned by the gesture");
        assertEquals(List.of("click 100.0,50.0 button 0"), events,
                "it has to be delivered here: the release branch never runs, because the polled "
                        + "button state already reads \"up\"");
        assertFalse(gesture.released(), "and there is nothing left to release");
    }

    @Test
    void aRightPressIsNeverAScrollCandidate() {
        PointerGesture gesture = gesture(VERTICAL);

        assertFalse(gesture.press(1, 100, 50, true),
                "the right button cancels selections; it is not a scroll gesture");
        assertEquals(List.of(), events);
    }
}

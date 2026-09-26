package com.pvzce.client.input;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The touch semantics that are not the gesture's business: which finger is followed, and when the
 * game is told to let go.
 *
 * <p>What a press <em>means</em> is decided downstream ({@link PointerGesture}); this class only has
 * to guarantee that one finger produces exactly one press-drag-release stream, that a second finger
 * cannot restart it, and that a compositor taking the touch away cannot leave the game holding
 * something.
 */
class TouchTranslatorTest {
    private final List<String> events = new ArrayList<>();

    private TouchTranslator translator() {
        return new TouchTranslator(new TouchTranslator.Pointer() {
            @Override
            public void press(double x, double y) {
                events.add("press " + x + "," + y);
            }

            @Override
            public void drag(double x, double y) {
                events.add("drag " + x + "," + y);
            }

            @Override
            public void release() {
                events.add("release");
            }
        });
    }

    @Test
    void aTapIsAPressAndARelease() {
        TouchTranslator touch = translator();

        touch.down(0, 120, 204);
        touch.up(0);

        assertEquals(List.of("press 120.0,204.0", "release"), events);
        assertFalse(touch.touching(), "the finger is gone");
    }

    @Test
    void aSwipeIsAPressThenDragsThenARelease() {
        TouchTranslator touch = translator();

        touch.down(0, 200, 200);
        touch.motion(0, 190, 200);
        touch.motion(0, 170, 200);
        touch.up(0);

        assertEquals(List.of("press 200.0,200.0", "drag 190.0,200.0", "drag 170.0,200.0", "release"),
                events);
    }

    @Test
    void aSecondFingerIsIgnoredWhileOneIsDown() {
        TouchTranslator touch = translator();

        touch.down(0, 10, 10);
        touch.down(1, 500, 300);
        touch.motion(1, 510, 300);
        touch.up(1);
        assertEquals(List.of("press 10.0,10.0"), events,
                "the second finger must not start a second gesture");
        assertTrue(touch.touching(), "the first finger is still down");

        touch.up(0);
        assertEquals(List.of("press 10.0,10.0", "release"), events);
    }

    @Test
    void theFingerIsFreeAgainAfterItLifts() {
        TouchTranslator touch = translator();

        touch.down(0, 10, 10);
        touch.up(0);
        touch.down(1, 20, 20);
        touch.up(1);

        assertEquals(List.of("press 10.0,10.0", "release", "press 20.0,20.0", "release"), events);
    }

    @Test
    void cancelLetsGo() {
        TouchTranslator touch = translator();

        touch.down(0, 30, 40);
        touch.motion(0, 60, 40);
        touch.cancel();

        assertEquals(List.of("press 30.0,40.0", "drag 60.0,40.0", "release"), events,
                "a compositor that takes the touch away must not leave a card in hand");
        assertFalse(touch.touching());
    }

    @Test
    void strayEventsDoNothing() {
        TouchTranslator touch = translator();

        touch.motion(0, 5, 5);
        touch.up(0);
        touch.cancel();
        assertEquals(List.of(), events, "nothing was ever pressed");

        touch.down(0, 1, 1);
        touch.up(7);
        assertEquals(List.of("press 1.0,1.0"), events, "another finger's up is not ours");
        touch.cancel();
    }
}

package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.ConsoleOverlay;
import com.pvzce.client.gui.Screen;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePackets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The navigation contract: what a screen stack entry means, what leaving one means, and why
 * the console is not one.
 *
 * <p>Three behaviours used to be emergent rather than stated, and each had already produced
 * a bug:
 *
 * <ul>
 *   <li>Popping the last screen silently did nothing, so the level list's 返回 button was
 *       dead after a level ended ({@code LevelSelectScreen.goBack} existed to work around
 *       it).</li>
 *   <li>A screen had to guess its destination from {@code screenDepth() > 1}, which cannot
 *       tell "the world list is underneath me" from "I am alone after a level".</li>
 *   <li>The console was pushed onto the stack, so the stack depth changed while it was open
 *       and the client had to special-case it to keep the screen underneath alive.</li>
 * </ul>
 */
class ScreenNavigationTest {
    @BeforeAll
    static void register() {
        PvzcePackets.register();
    }

    private static PvzceClient newClient() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-navigation");
        Connection.Pair pair = Connection.createMemoryPair();
        return new PvzceClient(pair.client(), gameDir,
                Thread.currentThread().getContextClassLoader());
    }

    /** Records whether it was told it is leaving. */
    private static class TrackingScreen extends Screen {
        private int removals;

        TrackingScreen(PvzceClient client) {
            super(client);
        }

        @Override
        protected void onRemoved() {
            removals++;
        }

        @Override
        public void render() {
        }
    }

    /** Claims every click, the way the level list's full-window card grid does. */
    private static final class GreedyScreen extends Screen {
        private int hits;

        GreedyScreen(PvzceClient client) {
            super(client);
        }

        @Override
        protected void onMouseClicked(double guiX, double guiY, int button) {
            hits++;
        }

        @Override
        public void keyPressed(int key) {
            hits++;
        }

        @Override
        public void render() {
        }
    }

    // ------------------------------------------------------------------
    // The console is an overlay
    // ------------------------------------------------------------------

    /**
     * Opening the console must not change the navigation depth.
     *
     * <p>The console is drawn over the current screen, not stacked on it. While it was a
     * stack entry, {@code screenDepth()} reported one more than the number of real screens,
     * and any screen reasoning about its own position got a different answer depending on
     * whether a command line happened to be open.
     */
    @Test
    void theConsoleFloatsOverTheScreenWithoutJoiningTheStack() throws Exception {
        PvzceClient client = newClient();
        Screen screen = new GreedyScreen(client);
        client.setScreenReplacing(screen);
        assertEquals(1, client.screenDepth(), "one screen installed");

        client.toggleConsole();

        assertInstanceOf(ConsoleOverlay.class, client.overlay(), "the console opens as an overlay");
        assertEquals(1, client.screenDepth(), "the overlay must not be counted as a screen");

        client.toggleConsole();

        assertNull(client.overlay(), "toggling again closes it");
        assertSame(screen, client.currentScreen(), "and the screen underneath is untouched");
        assertEquals(1, client.screenDepth(), "still one screen");
    }

    /** The console owns Escape while it is open, and closing it must not touch the stack. */
    @Test
    void escapeClosesTheConsoleRatherThanTryingToPopAScreen() throws Exception {
        PvzceClient client = newClient();
        GreedyScreen screen = new GreedyScreen(client);
        client.setScreenReplacing(screen);
        client.toggleConsole();
        ConsoleOverlay console = assertInstanceOf(ConsoleOverlay.class, client.overlay());

        // Driven through the client's own rule for "who gets this key": while an overlay is
        // open it is the overlay, whatever the screen underneath would have done with it.
        console.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);

        assertNull(client.overlay(), "ESC belongs to the console while it is open");
        assertSame(screen, client.currentScreen(), "and it must not have navigated");
        assertEquals(1, client.screenDepth(), "closing the console must not touch the stack");
        assertEquals(0, screen.hits, "the screen underneath never saw the key");
    }

    /** A click over an open console goes to the console, not to the screen behind it. */
    @Test
    void anOpenConsoleAbsorbsClicks() throws Exception {
        PvzceClient client = newClient();
        GreedyScreen screen = new GreedyScreen(client);
        client.setScreenReplacing(screen);
        client.toggleConsole();
        ConsoleOverlay console = assertInstanceOf(ConsoleOverlay.class, client.overlay());

        // Well below the console's own input strip, and on top of the screen's greedy
        // full-window hit region.
        console.mouseClicked(40, 40, 0);

        assertEquals(0, screen.hits, "the layer on top owns the mouse while it is open");
    }

    // ------------------------------------------------------------------
    // Back
    // ------------------------------------------------------------------

    /** A nested screen pops, and is told it is leaving. */
    @Test
    void backPopsANestedScreenAndTellsItItIsLeaving() throws Exception {
        PvzceClient client = newClient();
        TrackingScreen root = new TrackingScreen(client);
        TrackingScreen nested = new TrackingScreen(client);
        client.setScreenReplacing(root);
        client.openScreen(nested);

        client.navigateBack();

        assertSame(root, client.currentScreen(), "the screen underneath is revealed");
        assertEquals(1, nested.removals, "the screen that left is told exactly once");
        assertEquals(0, root.removals, "the screen that stayed is not");
    }

    /**
     * A screen alone on the stack is not popped into a client with nothing to render.
     *
     * <p>This is the case that used to be a silent no-op: the level list was installed as
     * the root after a level ended, its 返回 button called {@code closeScreen()}, and nothing
     * happened at all. A screen with nowhere to go must say where back leads instead - and
     * if it does not, the refusal is reported rather than faked.
     */
    @Test
    void aScreenAloneOnTheStackIsNotPopped() throws Exception {
        PvzceClient client = newClient();
        TrackingScreen only = new TrackingScreen(client);
        client.setScreenReplacing(only);

        client.navigateBack();

        assertSame(only, client.currentScreen(), "the last screen stays");
        assertEquals(0, only.removals, "a refused pop is not a removal");
        assertEquals(1, client.screenDepth(), "the stack still has the one screen");
    }

    /**
     * The level list states both of its destinations instead of guessing from the depth.
     *
     * <p>Reachable two ways: drilled into from the world list (pop reveals it) and installed
     * as the root after a level (nothing underneath, so the world list is the step back). The
     * old {@code goBack()} used the same depth test, but as an <em>action</em>
     * ({@code closeScreen()} or {@code showWorldSelect()}) rather than as a declared
     * destination - and a pop with nothing underneath is exactly what did not work.
     */
    @Test
    void theLevelListBackTargetFollowsHowItWasEntered() throws Exception {
        PvzceClient client = newClient();

        // Installed as the root, the way showLevelList() does it after a level ends.
        WorldSelectScreen worldList = new WorldSelectScreen(client);
        client.setScreenReplacing(worldList);
        LevelSelectScreen listAsRoot = new LevelSelectScreen(client);
        client.setScreenReplacing(listAsRoot);

        client.navigateBack();

        assertInstanceOf(WorldSelectScreen.class, client.currentScreen(),
                "a level list with nothing underneath goes back to the world list");

        // Nested under the world list, the way the 进入 button does it.
        client.setScreenReplacing(worldList);
        LevelSelectScreen nestedList = new LevelSelectScreen(client);
        client.openScreen(nestedList);

        client.navigateBack();

        assertInstanceOf(WorldSelectScreen.class, client.currentScreen(),
                "a nested level list pops back to the world list it was opened from");
        assertEquals(1, client.screenDepth(), "and the list is gone, not covered");
    }

    // ------------------------------------------------------------------
    // Replacement
    // ------------------------------------------------------------------

    /**
     * Installing a new root releases every screen it discards.
     *
     * <p>This is the path that leaked: a screen replaced by {@code setScreenReplacing} was
     * dropped without ever being told, so anything it had attached - the editor's canvas
     * animation playbacks, the seed chooser's preview entities - stayed alive for the rest
     * of the session. Each screen had to remember to clean up on its own exits instead.
     */
    @Test
    void replacingTheRootReleasesTheScreensItDiscards() throws Exception {
        PvzceClient client = newClient();
        TrackingScreen root = new TrackingScreen(client);
        TrackingScreen nested = new TrackingScreen(client);
        client.setScreenReplacing(root);
        client.openScreen(nested);

        client.setScreenReplacing(new TrackingScreen(client));

        assertEquals(1, nested.removals, "the top screen is released");
        assertEquals(1, root.removals, "so is the screen it was covering");
        assertEquals(1, client.screenDepth(), "and the new root is the only screen left");
    }

    /** A screen that survives a push is not told it is leaving. */
    @Test
    void pushingAScreenDoesNotReleaseTheOneUnderneath() throws Exception {
        PvzceClient client = newClient();
        TrackingScreen root = new TrackingScreen(client);
        client.setScreenReplacing(root);

        client.openScreen(new TrackingScreen(client));

        assertEquals(0, root.removals, "the screen underneath is still alive");
        assertEquals(2, client.screenDepth(), "and still on the stack");
    }

    /** The contract a screen inherits when it says nothing is "pop". */
    @Test
    void aScreenThatSaysNothingPops() throws Exception {
        PvzceClient client = newClient();
        Screen screen = new TrackingScreen(client);
        client.setScreenReplacing(screen);

        assertTrue(screen.backTarget() instanceof com.pvzce.client.gui.Navigation.Pop,
                "the default back target is a pop");
    }

    /** A screen that names a replacement is not popped: the whole stack is rebuilt. */
    @Test
    void aReplaceRootTargetRebuildsTheStack() throws Exception {
        PvzceClient client = newClient();
        TrackingScreen below = new TrackingScreen(client);
        TrackingScreen award = new TrackingScreen(client) {
            @Override
            public com.pvzce.client.gui.Navigation backTarget() {
                return com.pvzce.client.gui.Navigation.replaceRoot(TrackingScreen::new);
            }
        };
        client.setScreenReplacing(below);
        client.openScreen(award);

        client.navigateBack();

        assertFalse(client.currentScreen() == award, "the award page is gone");
        assertFalse(client.currentScreen() == below,
                "and so is the finished level it was covering, not merely revealed");
        assertEquals(1, client.screenDepth(), "the replacement is the only screen");
        assertEquals(1, award.removals, "the page that left was told");
        assertEquals(1, below.removals, "and so was the screen it discarded");
    }
}

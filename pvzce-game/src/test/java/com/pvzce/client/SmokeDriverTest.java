package com.pvzce.client;

import com.pvzce.client.gui.screens.AlmanacScreen;
import com.pvzce.client.gui.screens.LevelSelectScreen;
import com.pvzce.client.gui.screens.OnboardingScreen;
import com.pvzce.client.gui.screens.TitleScreen;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.testutil.ClientHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code pvzce.smokeScreen} switch, which decides what a screenshot run opens on.
 *
 * <p>This is the one part of the smoke harness that can be exercised without a window: the
 * properties are read when the driver is built, and picking a screen is a plain method call.
 * The rest of the harness (synthetic clicks, hover warping, capture) needs a real framebuffer
 * and is checked by running a smoke screenshot.
 *
 * <p>The mapping matters because a smoke run that opens the wrong screen fails silently: the
 * frames tick by, the PNG is written, and nothing in it says "you asked for the console".
 */
class SmokeDriverTest {
    /**
     * Properties are read in the constructor, so each case sets them, builds, and clears.
     *
     * <p>A run with no properties at all must be inert - that is the property that keeps the
     * harness from leaking into a normal game.
     */
    private static void withScreen(String screen, java.util.function.BiConsumer<PvzceClient, ClientHarness> assertions)
            throws Exception {
        // The registry bootstrap is global and idempotent, and the almanac reads it to decide
        // what the book holds - a harness that skipped it would render an empty book and the
        // assertion would look like a screen bug.
        com.pvzce.common.core.BuiltInRegistries.bootstrap();
        String previous = System.getProperty("pvzce.smokeScreen");
        String previousWorld = System.getProperty("pvzce.smokeWorld");
        try {
            if (screen == null) {
                System.clearProperty("pvzce.smokeScreen");
            } else {
                System.setProperty("pvzce.smokeScreen", screen);
            }
            System.setProperty("pvzce.smokeWorld", "smokeworld");
            try (ClientHarness harness = ClientHarness.create("pvzce-smoke-driver")) {
                SmokeDriver driver = new SmokeDriver(harness.client());
                driver.applyInitialScreen();
                assertions.accept(harness.client(), harness);
            }
        } finally {
            if (previous == null) {
                System.clearProperty("pvzce.smokeScreen");
            } else {
                System.setProperty("pvzce.smokeScreen", previous);
            }
            if (previousWorld == null) {
                System.clearProperty("pvzce.smokeWorld");
            } else {
                System.setProperty("pvzce.smokeWorld", previousWorld);
            }
        }
    }

    /**
     * No properties means "whatever this install opens on", which is not a constant.
     *
     * <p>A fresh harness has a fresh game directory and therefore no config, so the first screen is
     * the first-run page; a player who has answered it lands on the title screen. The switch itself
     * must not pick between them - that decision is {@code PvzceClient.openFirstScreen}'s, and this
     * is the test that keeps the smoke harness from having a second copy of it.
     */
    @Test
    void noPropertiesMeansWhateverThisInstallOpensOn() throws Exception {
        withScreen(null, (client, harness) -> {
            assertInstanceOf(OnboardingScreen.class, client.currentScreen(),
                    "a fresh game directory has not answered the first-run page");
            assertEquals(1, client.screenDepth(), "exactly the one screen the switch installed");
            assertTrue(harness.sentPackets().isEmpty(), "and nothing of its own to say");
        });
    }

    @Test
    void anAnsweredInstallOpensOnTheTitleScreen() throws Exception {
        withScreen(null, (client, harness) -> {
            client.config().setOnboarded(true);
            client.openFirstScreen();
            assertInstanceOf(TitleScreen.class, client.currentScreen());
        });
    }

    @Test
    void anUnknownScreenNameAlsoMeansTheInstallDefault() throws Exception {
        withScreen("smoe_screen", (client, harness) ->
                assertInstanceOf(OnboardingScreen.class, client.currentScreen(),
                        "an unknown name falls through to the same default"));
    }

    /**
     * {@code levels} has to name the world <em>and</em> ask the server for the list: the list
     * is per world and an unnamed one comes back empty, which is the trap the smoke guide
     * documents as "the hook looks like it did nothing".
     */
    @Test
    void theLevelListAsksForItsWorld() throws Exception {
        withScreen("levels", (client, harness) -> {
            assertInstanceOf(LevelSelectScreen.class, client.currentScreen());
            assertEquals("smokeworld", client.currentWorld());
            assertTrue(harness.sentPackets().stream()
                            .anyMatch(p -> p instanceof RequestLevelListC2S request
                                    && "smokeworld".equals(request.worldName())),
                    "the list only arrives for a named world, so the request carries it");
        });
    }

    @Test
    void theAlmanacScreenOpensOnItsIndex() throws Exception {
        withScreen("almanac", (client, harness) -> {
            assertInstanceOf(AlmanacScreen.class, client.currentScreen());
            AlmanacScreen almanac = (AlmanacScreen) client.currentScreen();
            assertEquals(-1, almanac.pageIndex(), "the book opens on its index");
        });
    }

    /**
     * The entry pages are three states deep (index -> plants -> entry) and no screenshot run can
     * click its way there, so the smoke driver walks in through the system properties.
     */
    @Test
    void theAlmanacEntryPageIsReachableByProperty() throws Exception {
        System.setProperty("pvzce.smokeAlmanacPage", "zombies");
        System.setProperty("pvzce.smokeAlmanacEntry", "0");
        try {
            withScreen("almanac", (client, harness) -> {
                AlmanacScreen almanac = (AlmanacScreen) client.currentScreen();
                // The screen initializes on its first rendered frame, so the driver's request is
                // remembered and applied there; a test has to walk the same path.
                almanac.initIfNeeded();
                assertEquals(1, almanac.pageIndex(), "page 1 is the zombies");

                assertEquals(com.pvzce.api.util.Identifier.withDefaultNamespace("basic_zombie"),
                        almanac.currentEntry(), "the first zombie in the original's book");
            });
        } finally {
            System.clearProperty("pvzce.smokeAlmanacPage");
            System.clearProperty("pvzce.smokeAlmanacEntry");
        }
    }

    @Test
    void theConsoleOpensAsAnOverlayOverAScreen() throws Exception {
        withScreen("console", (client, harness) -> {
            assertInstanceOf(TitleScreen.class, client.currentScreen());
            assertTrue(client.overlay() != null, "the console floats over the screen it was given");
        });
    }

    /**
     * With no smoke level requested, the per-frame hooks must not send anything: a normal run
     * must be indistinguishable from one with the harness present.
     */
    @Test
    void theFrameHooksAreInertWithoutProperties() throws Exception {
        withScreen(null, (client, harness) -> {
            int depthBefore = client.screenDepth();
            SmokeDriver driver = new SmokeDriver(client);
            driver.beforeFrame();
            assertFalse(driver.afterFrame(client.currentScreen()), "and the run keeps going");
            assertFalse(driver.openSeedChooserForSmoke(java.util.List.of()));
            assertEquals(depthBefore, client.screenDepth(), "no screen was pushed or replaced");
            assertTrue(harness.sentPackets().isEmpty(), "no packet was sent on the player's behalf");
        });
    }
}

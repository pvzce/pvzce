package com.pvzce.client.gui;

import com.pvzce.client.PvzceClient;

/**
 * What "back" means for a screen.
 *
 * <p>Before this existed, every screen guessed: {@code Screen.requestClose()} popped
 * unconditionally, and {@code closeScreen()} silently did nothing when the stack held only
 * one screen. {@code LevelSelectScreen} therefore had to decide from
 * {@code screenDepth() > 1} alone, which cannot distinguish "the world list is underneath
 * me" from "I am the only screen because the level just ended" - and the two cases want
 * different destinations even though the depth number happens to be the right hint today.
 *
 * <p>The contract is now declarative: a screen says where back goes, and
 * {@link PvzceClient#navigateBack()} carries it out. A screen that is only ever reached by
 * pushing leaves this at the default ({@link Pop}); a screen that the client installs as
 * the root of a flow (after a level, after a save reload) names its own destination.
 */
public sealed interface Navigation {
    /** Pop this screen, revealing the one underneath. The default. */
    Navigation POP = new Pop();

    /**
     * Replace the whole stack with a freshly built root screen.
     *
     * <p>For a screen that stands alone - the award page arrives by {@code openScreen} over
     * a finished level, but backing out of it must not reveal that dead level - so
     * repairing the stack here is the caller's declaration, not something
     * {@link PvzceClient} infers.
     */
    static Navigation replaceRoot(ScreenFactory factory) {
        return new ReplaceRoot(factory);
    }

    /** Builds a screen on demand; used by {@link #replaceRoot}. */
    @FunctionalInterface
    interface ScreenFactory {
        Screen create(PvzceClient client);
    }

    /** Pops the current screen. */
    record Pop() implements Navigation {
    }

    /** Replaces the entire screen stack with {@code factory}'s screen. */
    record ReplaceRoot(ScreenFactory factory) implements Navigation {
    }
}

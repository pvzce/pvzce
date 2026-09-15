package com.pvzce.client.gui.hud;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelHint;

import java.util.BitSet;
import java.util.List;

/**
 * Runs a level's {@code hints} block: which line goes up, and when.
 *
 * <p>The level definition is the whole input. Each hint names a moment the client can see
 * by itself ({@link LevelHint.Trigger}), so nothing here needs the server, and a hint
 * fires at most once per level instance: "the first time the player collects sun" is a
 * lesson, not a notification, and repeating it on every sun would turn the bottom of the
 * screen into a metronome.
 *
 * <p>A level that wrote no hints pays nothing for this class - the lists are empty and
 * every entry point returns immediately.
 */
public final class LevelHints {
    private final HintBox box;
    private final List<LevelHint> hints;
    /**
     * Which hints have already had their turn.
     *
     * <p>By index rather than by text: two identical lines in one level are a mistake, but
     * if they happen the second one should still get its own trigger rather than being
     * silently folded into the first.
     */
    private final BitSet fired = new BitSet();

    public LevelHints(HintBox box, LevelDef def) {
        this.box = box;
        this.hints = def == null ? List.of() : def.hints();
    }

    /** True when this level has anything to say at all. */
    public boolean isEmpty() {
        return hints.isEmpty();
    }

    /** The board came up: every {@code on_start} line goes up, in file order. */
    public void onLevelStart() {
        // Deliberately not "all of them at once": a level with two opening lines shows the
        // first now and the second when the first expires, which is what reading order
        // means. The queue is the box's single line plus this method's own order, so the
        // simplest honest rule is to show the first and let the rest wait for their own
        // trigger - a level that wants two lines in a row should say so with two triggers.
        fireFirst(LevelHint.Trigger.ON_START, null);
    }

    /** The player collected a drop of this resource id. */
    public void onResourceCollected(String resourceId) {
        if (resourceId == null || resourceId.isEmpty()) {
            return;
        }
        for (int i = 0; i < hints.size(); i++) {
            LevelHint hint = hints.get(i);
            if (hint.trigger() != LevelHint.Trigger.ON_RESOURCE || fired.get(i)) {
                continue;
            }
            String wanted = hint.resource().map(Object::toString).orElse("");
            if (wanted.equals(resourceId)) {
                fired.set(i);
                box.show(hint);
            }
        }
    }

    /**
     * A card was clicked while it could not be played.
     *
     * <p>The line is the game's own, not the level's - "still recharging" is true in every
     * level - but a level only gets it if it asked for one, so a sandbox that would rather
     * not be interrupted can leave {@code on_card_refused} out of its {@code hints}.
     *
     * <p>Not a one-shot: this is the answer to a click, and the player may ask again.
     */
    public void onCardRefused(String reason) {
        if (reason == null || reason.isEmpty() || !wantsRefusals()) {
            return;
        }
        box.refuse(reason);
    }

    /** True when this level opted into the built-in refusal lines. */
    private boolean wantsRefusals() {
        for (LevelHint hint : hints) {
            if (hint.trigger() == LevelHint.Trigger.ON_CARD_REFUSED) {
                return true;
            }
        }
        return false;
    }

    /** Fires the first un-fired hint of this trigger; later ones wait for their own call. */
    private void fireFirst(LevelHint.Trigger trigger, String resourceId) {
        for (int i = 0; i < hints.size(); i++) {
            LevelHint hint = hints.get(i);
            if (hint.trigger() != trigger || fired.get(i)) {
                continue;
            }
            if (hint.needsResource()) {
                String wanted = hint.resource().map(Object::toString).orElse("");
                if (!wanted.equals(resourceId)) {
                    continue;
                }
            }
            fired.set(i);
            box.show(hint);
            return;
        }
    }
}

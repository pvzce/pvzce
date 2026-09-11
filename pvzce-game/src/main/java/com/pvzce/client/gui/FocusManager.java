package com.pvzce.client.gui;

import com.pvzce.client.gui.components.EditBox;

/**
 * Screen-wide keyboard focus for text inputs.
 *
 * <p>{@link EditBox} used to own its own {@code focused} flag and set it to
 * "is the mouse over me?" on every click, but nothing ever cleared it. Focus was a
 * one-way latch: clicking a second field focused it too, and since dispatch stops
 * at the first widget that consumes the event, the *first* field kept swallowing
 * every keystroke and Backspace. Any screen with two text boxes therefore had one
 * permanently uneditable box - which is what the wave, music and level editors all
 * look like.
 *
 * <p>The screen owns one manager, and widgets are wired to it when they are added,
 * so "exactly one text field has the keyboard" holds across dialogs too.
 */
public final class FocusManager {
    private EditBox focused;

    /** Gives the keyboard to {@code box}, or clears it when {@code null}. */
    public void request(EditBox box) {
        if (focused == box) {
            return;
        }
        EditBox previous = focused;
        focused = box;
        if (previous != null) {
            previous.applyFocus(false);
        }
        if (box != null) {
            box.applyFocus(true);
        }
    }

    public void clear() {
        request(null);
    }

    public EditBox focused() {
        return focused;
    }

    public boolean isFocused(EditBox box) {
        return focused == box;
    }

    /**
     * True when the keyboard belongs to a widget. Screen-level shortcuts (the
     * {@code /} console key, for example) must not fire while typing.
     */
    public boolean hasTextFocus() {
        return focused != null && focused.isVisible() && focused.isActive();
    }
}

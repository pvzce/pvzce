package com.pvzce.client.gui.editor.form;

import com.pvzce.client.gui.editor.EditorContext;

/**
 * A declarative description of one field of a level file, and the widgets that edit it.
 *
 * <p>The editor's value is not in its pages but in its fields: every page is a list of
 * "edit this path in the level file" plus a widget. Writing that list by hand, twelve times,
 * is how the pages drifted apart - one page remembered to drop a key when its box went
 * empty, another wrote an empty string and made the file say two things at once.
 *
 * <p>Implementations are package-private: a page describes what it wants with
 * {@link FieldWidgets} and never names a widget class.
 */
public interface FormField {
    /** The label drawn to the left of the control. */
    String label();

    /** Creates this field's widgets; the row is where they may live. */
    void build(EditorContext context, FormLayout.Row row);

    /** Draws whatever the widgets do not (validity marks, hints, read-only values). */
    default void render(EditorContext context, FormLayout.Row row) {
    }

    /** Loads the value at {@code prefix + path} into the widgets. */
    void readFrom(EditorContext context, String prefix);

    /** Writes the widgets' value to {@code prefix + path}, following this field's own rules. */
    void writeTo(EditorContext context, String prefix);

    /** Path of this field inside the page's block, for diagnostics. */
    String path();
}

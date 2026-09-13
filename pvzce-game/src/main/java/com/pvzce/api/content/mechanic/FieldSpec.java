package com.pvzce.api.content.mechanic;

import java.util.List;

/**
 * A declarative description of one editable field of a mechanic's JSON block.
 *
 * <p>The editor builds a page from a list of these (see
 * {@code com.pvzce.client.gui.editor.FormPage}): a mechanic says <em>what</em> is
 * editable, the client decides <em>how</em> to draw it. That is what makes "register a
 * mechanic, get an editor page" true instead of aspirational - the alternative is every
 * mechanic hand-writing a screen, which is why the shipped conveyor belt and plantable
 * area have no UI at all today.
 *
 * <p>This type is plain data on purpose: it names no widget, no texture and no screen, so
 * it can live next to the mechanic and travel through the same layers a mechanic does.
 * {@code path} is a JSON path inside the mechanic's own block ({@code "capacity"},
 * {@code "cards[].weight"}), and {@code label} is a {@code GuiLang} key (see the
 * {@code <namespace>.<path>} convention).
 */
public sealed interface FieldSpec {

    /** JSON path of the field inside the mechanic's block. */
    String path();

    /** {@code GuiLang} key for the field's label. */
    String label();

    /**
     * True when an empty value means "delete the key" rather than "keep an empty one".
     *
     * <p>Mirrors the rule the hand-written editor already follows for {@code unlock} and
     * {@code dialogue}: "no conditions" and "a block that says nothing" must not both be
     * representable in the data.
     */
    default boolean removeWhenEmpty() {
        return false;
    }

    /** Free text, one line. */
    record Text(String path, String label, int maxLength) implements FieldSpec {
    }

    /** A number, drawn as a slider plus a typed input box. */
    record Number(String path, String label, float min, float max, boolean integer) implements FieldSpec {
    }

    /** A switch. */
    record Bool(String path, String label) implements FieldSpec {
    }

    /** One of a fixed set of strings. */
    record Choice(String path, String label, List<String> options) implements FieldSpec {
        public Choice {
            options = List.copyOf(options);
        }
    }

    /**
     * A reference to another registry's entry, drawn as a searchable picker.
     *
     * @param category a {@code PvzceRegistries.byCategory()} key ({@code "plant"}, {@code "slot"}, ...)
     * @param multiple true when the field is a list of ids rather than a single id
     */
    record Ref(String path, String label, String category, boolean multiple) implements FieldSpec {
    }

    /** A list of sub-fields, with add / remove / reorder. */
    record ListField(String path, String label, List<FieldSpec> item) implements FieldSpec {
        public ListField {
            item = List.copyOf(item);
        }
    }

    /**
     * Escape hatch: a block the editor does not model.
     *
     * <p>Kept as raw JSON and written back untouched, so a mechanic with a field this
     * version has no widget for is still editable in every other respect - and never
     * loses the part it could not draw.
     */
    record Json(String path, String label) implements FieldSpec {
    }

    static Text text(String path, String label) {
        return new Text(path, label, 256);
    }

    static Number integer(String path, String label, int min, int max) {
        return new Number(path, label, min, max, true);
    }

    static Number decimal(String path, String label, float min, float max) {
        return new Number(path, label, min, max, false);
    }

    static Bool bool(String path, String label) {
        return new Bool(path, label);
    }

    static ListField list(String path, String label, FieldSpec... item) {
        return new ListField(path, label, List.of(item));
    }
}

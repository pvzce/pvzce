package com.pvzce.client.gui.editor.form;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.GuiText;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.components.Slider;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.List;
import java.util.Optional;

/**
 * The widgets behind the field kinds: text, number, switch, choice, registry reference.
 *
 * <p>The one place that knows a number is a slider plus a box and a boolean is a toggle. A
 * page asks for a kind; adding a kind adds it here once instead of in every page that needs
 * it, and each kind owns its <em>writing rule</em> - the part that used to drift. Blank text
 * removes the key rather than writing an empty string, a value equal to the documented
 * default removes the key rather than freezing today's default into the file, and an
 * unparseable number leaves the file alone instead of writing zero.
 */
public final class FieldWidgets {

    /** A line of text; blank removes the key. */
    public static FormField text(String path, String label, int maxLength) {
        return new FormField() {
            private EditBox box;

            @Override
            public String label() {
                return label;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public void build(EditorContext context, FormLayout.Row row) {
                EditorContext.Rect control = row.control();
                box = context.own(new EditBox(control.x(), control.y(), control.width(),
                        control.height(), maxLength, null));
            }

            @Override
            public void readFrom(EditorContext context, String prefix) {
                if (box != null) {
                    box.setValue(context.draft().getString(prefix + path, ""), false);
                }
            }

            @Override
            public void writeTo(EditorContext context, String prefix) {
                if (box != null) {
                    context.draft().setStringOrRemove(prefix + path, box.value().trim());
                }
            }
        };
    }

    /**
     * A number: a slider for the coarse gesture and a box for the exact value.
     *
     * <p>Both write the same value, because a slider cannot express 0..12000 ticks and a box
     * alone cannot be dragged. When {@code knownDefault} is present a value equal to it
     * removes the key: "not set" and "set to the default" must not be two states in the file,
     * or a later change to the default would silently not apply.
     */
    public static FormField number(String path, String label, float min, float max, boolean integer,
                                   Optional<Float> knownDefault) {
        return new FormField() {
            private Slider slider;
            private EditBox box;

            @Override
            public String label() {
                return label;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public void build(EditorContext context, FormLayout.Row row) {
                EditorContext.Rect control = row.control();
                int boxWidth = Math.max(64, Math.min(110, control.width() / 4));
                box = context.own(new EditBox(control.x(), control.y(), boxWidth, control.height(),
                        this::applyBox));
                box.setValueChangedListener(this::applyBox);
                slider = context.own(new Slider(control.x() + boxWidth + 8, control.y(),
                        Math.max(80, control.width() - boxWidth - 8), control.height(),
                        min, max, min, value -> applySlider()));
            }

            @Override
            public void render(EditorContext context, FormLayout.Row row) {
                if (box != null && slider != null && !box.isFocused()) {
                    box.setValue(format(slider.value()), false);
                }
            }

            @Override
            public void readFrom(EditorContext context, String prefix) {
                float value = context.draft().getInt(prefix + path, (int) min);
                if (slider != null) {
                    slider.configure(min, max, Math.max(min, Math.min(max, value)), v -> {
                    });
                }
                if (box != null) {
                    box.setValue(format(Math.max(min, Math.min(max, value))), false);
                }
            }

            @Override
            public void writeTo(EditorContext context, String prefix) {
                if (slider == null) {
                    return;
                }
                float value = slider.value();
                String full = prefix + path;
                if (knownDefault.isPresent() && Math.abs(value - knownDefault.get()) < 0.0001F) {
                    context.draft().remove(full);
                } else if (integer) {
                    context.draft().setInt(full, Math.round(value));
                } else {
                    context.draft().setFloat(full, value);
                }
            }

            private void applySlider() {
                if (box != null && slider != null) {
                    box.setValue(format(slider.value()), false);
                }
            }

            private void applyBox() {
                if (box == null || slider == null) {
                    return;
                }
                float parsed = GuiText.parseFloat(box.value(), slider.value());
                slider.setValue(Math.max(min, Math.min(max, parsed)));
            }

            private String format(float value) {
                return integer ? String.valueOf(Math.round(value)) : GuiText.formatFloat(value);
            }
        };
    }

    /**
     * A switch.
     *
     * <p>Without a known default the key is written only while it is on, so "off" is the
     * absence of a key. With one - a game rule, whose default may be {@code true} - the key is
     * written whenever the value differs from that default and removed when it matches:
     * otherwise a rule that defaults to on could not be turned off at all, because removing
     * the key would turn it back on.
     */
    public static FormField bool(String path, String label, String onText, String offText,
                                 Optional<Boolean> knownDefault) {
        return new FormField() {
            private Button toggle;
            private boolean on;

            @Override
            public String label() {
                return label;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public void build(EditorContext context, FormLayout.Row row) {
                EditorContext.Rect control = row.control();
                // The row label already names the field; the button shows only its state, so
                // "单人时暂停  单人时暂停：关" does not happen.
                toggle = context.own(new Button(control.x(), control.y(),
                        Math.max(60, Math.min(160, control.width())), control.height(),
                        onText, this::flip));
                refresh();
            }

            @Override
            public void readFrom(EditorContext context, String prefix) {
                Optional<com.google.gson.JsonElement> json = context.draft().get(prefix + path);
                // An absent key means "whatever the game defaults to", which for a rule that
                // defaults to on is true - reading it as false would then write an override
                // the author never chose.
                on = json.isPresent()
                        ? json.get().isJsonPrimitive() && json.get().getAsJsonPrimitive().isBoolean()
                                && json.get().getAsBoolean()
                        : knownDefault.orElse(false);
                refresh();
            }

            @Override
            public void writeTo(EditorContext context, String prefix) {
                String full = prefix + path;
                if (knownDefault.isPresent() && on == knownDefault.get()) {
                    context.draft().remove(full);
                } else {
                    context.draft().setBool(full, on);
                }
            }

            private void flip() {
                on = !on;
                refresh();
            }

            private void refresh() {
                if (toggle != null) {
                    toggle.setLabel(on ? onText : offText);
                }
            }
        };
    }

    /** One of a fixed set of strings, cycled by clicking. */
    public static FormField choice(String path, String label, List<String> options, String fallback) {
        return new FormField() {
            private Button cycle;
            private String value;

            @Override
            public String label() {
                return label;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public void build(EditorContext context, FormLayout.Row row) {
                EditorContext.Rect control = row.control();
                cycle = context.own(new Button(control.x(), control.y(),
                        Math.max(100, Math.min(260, control.width())), control.height(), label, this::next));
                refresh();
            }

            @Override
            public void readFrom(EditorContext context, String prefix) {
                value = context.draft().getString(prefix + path, fallback);
                refresh();
            }

            @Override
            public void writeTo(EditorContext context, String prefix) {
                context.draft().setString(prefix + path, value == null ? fallback : value);
            }

            private void next() {
                if (options.isEmpty()) {
                    return;
                }
                value = options.get((options.indexOf(value) + 1) % options.size());
                refresh();
            }

            private void refresh() {
                if (cycle != null) {
                    cycle.setLabel(label + "：" + (value == null ? "—" : value));
                }
            }
        };
    }

    /**
     * A registry reference, edited as an id and drawn in red while it resolves to nothing.
     *
     * <p>Checking here rather than letting the level validator complain later keeps the
     * message next to the field that caused it.
     */
    public static FormField reference(String path, String label, String category) {
        return new FormField() {
            private EditBox box;

            @Override
            public String label() {
                return label;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public void build(EditorContext context, FormLayout.Row row) {
                EditorContext.Rect control = row.control();
                box = context.own(new EditBox(control.x(), control.y(), control.width(),
                        control.height(), 256, null));
            }

            @Override
            public void render(EditorContext context, FormLayout.Row row) {
                if (box == null || box.value().isBlank() || resolves()) {
                    return;
                }
                context.client().font().draw("未知 " + category + " id", box.x(),
                        box.y() + box.height() + 3F, 0.62F, 1F, 0.45F, 0.45F, 1F);
            }

            @Override
            public void readFrom(EditorContext context, String prefix) {
                if (box != null) {
                    box.setValue(context.draft().getString(prefix + path, ""), false);
                }
            }

            @Override
            public void writeTo(EditorContext context, String prefix) {
                if (box != null) {
                    context.draft().setStringOrRemove(prefix + path, box.value().trim());
                }
            }

            private boolean resolves() {
                Identifier id = Identifier.tryParse(box.value().trim());
                if (id == null) {
                    return false;
                }
                return switch (category) {
                    case "plant" -> BuiltInRegistries.PLANTS.containsKey(id);
                    case "zombie" -> BuiltInRegistries.ZOMBIES.containsKey(id);
                    case "slot" -> BuiltInRegistries.SLOT_TYPES.containsKey(id);
                    case "level" -> BuiltInRegistries.LEVELS.containsKey(id);
                    case "resource" -> BuiltInRegistries.RESOURCES.containsKey(id);
                    default -> true;
                };
            }
        };
    }

    /**
     * A list of registry ids, edited as one comma-separated line.
     *
     * <p>The same idiom the unlock page has always used for its prerequisites: a list of ids is
     * short, and a text line is editable with the keyboard, searchable by eye and diffable in
     * the file - a row-per-id list editor is a later problem, not a prerequisite for editing a
     * mechanic.
     */
    public static FormField idList(String path, String label, String category) {
        return new FormField() {
            private EditBox box;
            /**
             * The entries as the file has them, by id.
             *
             * <p>A pool entry may carry more than its id - a conveyor card's weight, for
             * instance. Writing the list back from ids alone would drop those keys, so the
             * original entry is kept as the template for its id and only the ids a user added
             * are written bare.
             */
            private final java.util.Map<String, JsonObject> templates = new java.util.LinkedHashMap<>();

            @Override
            public String label() {
                return label;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public void build(EditorContext context, FormLayout.Row row) {
                EditorContext.Rect control = row.control();
                box = context.own(new EditBox(control.x(), control.y(), control.width(),
                        control.height(), 256, null));
            }

            @Override
            public void render(EditorContext context, FormLayout.Row row) {
                if (box == null) {
                    return;
                }
                for (String id : split(box.value())) {
                    Identifier parsed = Identifier.tryParse(id);
                    if (parsed == null || !resolves(category, parsed)) {
                        context.client().font().draw("未知 " + category + " id：" + id, box.x(),
                                box.y() + box.height() + 3F, 0.62F, 1F, 0.45F, 0.45F, 1F);
                        return;
                    }
                }
            }

            @Override
            public void readFrom(EditorContext context, String prefix) {
                if (box == null) {
                    return;
                }
                templates.clear();
                java.util.List<String> ids = new java.util.ArrayList<>();
                for (JsonElement element : context.draft().getArray(prefix + path)) {
                    if (element.isJsonPrimitive()) {
                        ids.add(element.getAsString());
                    } else if (element.isJsonObject() && element.getAsJsonObject().has("id")) {
                        JsonObject entry = element.getAsJsonObject().deepCopy();
                        String id = entry.get("id").getAsString();
                        templates.put(id, entry);
                        ids.add(id);
                    }
                }
                box.setValue(String.join(", ", ids), false);
            }

            @Override
            public void writeTo(EditorContext context, String prefix) {
                if (box == null) {
                    return;
                }
                java.util.List<String> ids = split(box.value());
                if (ids.isEmpty()) {
                    context.draft().remove(prefix + path);
                    return;
                }
                com.google.gson.JsonArray array = new com.google.gson.JsonArray();
                for (String id : ids) {
                    JsonObject template = templates.get(id);
                    if (template != null) {
                        array.add(template);
                    } else {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("id", id);
                        array.add(entry);
                    }
                }
                context.draft().set(prefix + path, array);
            }
        };
    }

    /** One comma or whitespace separated id list, blanks dropped. */
    static java.util.List<String> split(String value) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String part : value.split("[,\\s]+")) {
            if (!part.isBlank()) {
                ids.add(part.trim());
            }
        }
        return ids;
    }

    private static boolean resolves(String category, Identifier id) {
        return switch (category) {
            case "plant" -> BuiltInRegistries.PLANTS.containsKey(id);
            case "zombie" -> BuiltInRegistries.ZOMBIES.containsKey(id);
            case "slot" -> BuiltInRegistries.SLOT_TYPES.containsKey(id);
            case "level" -> BuiltInRegistries.LEVELS.containsKey(id);
            case "resource" -> BuiltInRegistries.RESOURCES.containsKey(id);
            default -> true;
        };
    }

    /**
     * A field from a mechanic's own declaration.
     *
     * <p>This is what makes "register a mechanic, get an editor page" true: the mechanic
     * describes its fields as {@link FieldSpec} data and the editor turns each one into the
     * same widgets every other page uses.
     */
    public static FormField fromSpec(FieldSpec spec) {
        if (spec instanceof FieldSpec.Text text) {
            return text(text.path(), text.label(), text.maxLength());
        }
        if (spec instanceof FieldSpec.Number number) {
            return number(number.path(), number.label(), number.min(), number.max(), number.integer(),
                    Optional.empty());
        }
        if (spec instanceof FieldSpec.Bool bool) {
            return bool(bool.path(), bool.label(), "开", "关", Optional.empty());
        }
        if (spec instanceof FieldSpec.Choice choice) {
            return choice(choice.path(), choice.label(), choice.options(), "");
        }
        if (spec instanceof FieldSpec.Ref ref) {
            return ref.multiple()
                    ? idList(ref.path(), ref.label(), ref.category())
                    : reference(ref.path(), ref.label(), ref.category());
        }
        if (spec instanceof FieldSpec.ListField list) {
            // A list of ids (a weighted card pool, say) is edited as one line of ids until the
            // row-per-entry editor exists. Saying so beats a page that silently omits a field.
            return new UnsupportedField(list.path(), list.label(),
                    "列表字段：暂请在 JSON 里编辑这一项");
        }
        throw new UnsupportedOperationException(
                "No widget for this field kind: " + spec.path());
    }

    /** A field the editor cannot draw yet: named on the page, left untouched in the file. */
    private record UnsupportedField(String path, String label, String note) implements FormField {
        @Override
        public void build(EditorContext context, FormLayout.Row row) {
        }

        @Override
        public void render(EditorContext context, FormLayout.Row row) {
            context.client().font().draw(note, row.control().x(),
                    row.control().y() + row.control().height() / 2F - 4F, 0.66F, 1F, 0.8F, 0.4F, 1F);
        }

        @Override
        public void readFrom(EditorContext context, String prefix) {
        }

        @Override
        public void writeTo(EditorContext context, String prefix) {
        }
    }

    private FieldWidgets() {
    }
}

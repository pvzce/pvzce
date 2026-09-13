package com.pvzce.client.gui.editor.form;

import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.client.gui.editor.EditorPage;

import java.util.ArrayList;
import java.util.List;

/**
 * A page that is nothing but a list of fields.
 *
 * <p>"A new editor page in a few parameters" is this class plus a list of {@link FormField}s:
 * the page owns the geometry (one {@link FormLayout} row per field), the label drawing and
 * the read/write cycle, and the page author owns only the list. The rules page and a
 * mechanic's own page are both built this way.
 *
 * <p>A page may still be hand-written when its shape is not a form (the wave table, the
 * canvas, the card pool) - the point is not that every page is a form, it is that no page
 * invents its own plumbing.
 */
public final class FormPage implements EditorPage {
    private final String id;
    private final String label;
    private final int order;
    private final String heading;
    private final String pathPrefix;
    private final List<FormField> fields;
    private final java.util.function.Predicate<EditorContext> visibleWhen;

    private FormLayout layout;

    private FormPage(Builder builder) {
        this.id = builder.id;
        this.label = builder.label;
        this.order = builder.order;
        this.heading = builder.heading;
        this.pathPrefix = builder.pathPrefix;
        this.fields = List.copyOf(builder.fields);
        this.visibleWhen = builder.visibleWhen;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    /**
     * A page whose fields come from a mechanic's declaration, under its block path.
     *
     * <p>Field labels are language keys ({@code pvzce.mechanic.conveyor.field.capacity}); a key
     * with no translation falls back to its last path segment, so a mod's mechanic shows
     * {@code capacity} rather than the whole key or a blank row.
     */
    public static FormPage fromSpecs(String id, String label, String heading, String pathPrefix,
                                     List<FieldSpec> specs) {
        return builder(id).label(label).heading(heading).pathPrefix(pathPrefix)
                .fields(specs)
                .build();
    }

    private static String resolveLabel(String key) {
        int dot = key.lastIndexOf('.');
        String fallback = dot < 0 ? key : key.substring(dot + 1);
        return com.pvzce.client.gui.GuiLang.raw(key, fallback);
    }

    /** A field whose row label is resolved while the widget keeps the declaration's key. */
    private record Labelled(FormField delegate, String label) implements FormField {
        @Override
        public String path() {
            return delegate.path();
        }

        @Override
        public void build(EditorContext context, FormLayout.Row row) {
            delegate.build(context, row);
        }

        @Override
        public void render(EditorContext context, FormLayout.Row row) {
            delegate.render(context, row);
        }

        @Override
        public void readFrom(EditorContext context, String prefix) {
            delegate.readFrom(context, prefix);
        }

        @Override
        public void writeTo(EditorContext context, String prefix) {
            delegate.writeTo(context, prefix);
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public boolean visibleFor(EditorContext context) {
        return visibleWhen.test(context);
    }

    /**
     * Builds the widgets and loads them from the draft.
     *
     * <p>{@link #readFrom} also runs when the level is opened, but a widget cannot hold a value
     * before it exists - so the read is repeated here, where it can. The draft is the page's
     * state: a typed value reaches it through {@link #onClosed} and {@link #writeTo}.
     */
    @Override
    public void build(EditorContext context) {
        layout = new FormLayout(context.fullContent(), fields.size());
        for (int i = 0; i < fields.size(); i++) {
            fields.get(i).build(context, layout.row(i, fields.get(i).label()));
        }
        readFrom(context);
    }

    /** Flushes what is on screen into the draft, so switching pages does not drop an edit. */
    @Override
    public void onClosed(EditorContext context) {
        writeTo(context);
    }

    @Override
    public void render(EditorContext context) {
        if (layout == null) {
            return;
        }
        layout.drawHeading(context.client(), layout.heading(heading));
        for (int i = 0; i < fields.size(); i++) {
            FormLayout.Row row = layout.row(i, fields.get(i).label());
            layout.drawLabel(context.client(), row);
            fields.get(i).render(context, row);
        }
        if (!layout.fits(fields.size())) {
            context.client().font().draw(
                    "字段比面板多：把窗口调大，或减少这一关的字段",
                    context.fullContent().x() + 6, context.fullContent().y() + 2F, 0.68F,
                    1F, 0.8F, 0.4F, 1F);
        }
    }

    @Override
    public void readFrom(EditorContext context) {
        for (FormField field : fields) {
            field.readFrom(context, pathPrefix);
        }
    }

    @Override
    public void writeTo(EditorContext context) {
        for (FormField field : fields) {
            field.writeTo(context, pathPrefix);
        }
    }

    public static final class Builder {
        private final String id;
        private String label;
        private int order = 100;
        private String heading = "";
        private String pathPrefix = "";
        private final List<FormField> fields = new ArrayList<>();
        private java.util.function.Predicate<EditorContext> visibleWhen = context -> true;

        private Builder(String id) {
            this.id = id;
            this.label = id;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder heading(String heading) {
            this.heading = heading;
            return this;
        }

        /** Prefix every field path is resolved under, e.g. {@code "mechanics[0]."}. */
        public Builder pathPrefix(String prefix) {
            this.pathPrefix = prefix == null ? "" : prefix;
            return this;
        }

        public Builder field(FormField field) {
            this.fields.add(field);
            return this;
        }

        /**
         * Adds the fields a mechanic declares.
         *
         * <p>On the builder rather than in a second factory, so a mechanic's page sets its
         * order and label like any other page and still gets its fields from the declaration.
         */
        public Builder fields(List<FieldSpec> specs) {
            for (FieldSpec spec : specs) {
                field(new Labelled(FieldWidgets.fromSpec(spec), resolveLabel(spec.label())));
            }
            return this;
        }

        public Builder visibleWhen(java.util.function.Predicate<EditorContext> visibleWhen) {
            this.visibleWhen = visibleWhen;
            return this;
        }

        public FormPage build() {
            return new FormPage(this);
        }
    }
}

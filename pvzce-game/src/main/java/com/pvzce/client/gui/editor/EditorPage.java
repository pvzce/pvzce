package com.pvzce.client.gui.editor;

/**
 * One page of the level editor.
 *
 * <p>The editor used to be a private {@code enum Page} plus three {@code switch (page)}
 * blocks - one building widgets, one drawing labels, one routing clicks - and every page's
 * state was a field on the screen. Adding a page meant editing all four places, and a page
 * could not be added by anything outside this repository. A page is an object now: it
 * declares its own label, builds its own widgets, draws itself, and reads and writes its own
 * slice of the level JSON.
 *
 * <p>Pages come from {@link EditorPages}, and a page the editor does not show for a level
 * says so through {@link #visibleFor}: a conveyor level has no use for the card page, and
 * hiding the tab is clearer than opening a page whose edits are discarded.
 *
 * <p>The state of a page lives in the page, not on the screen, and {@link #readFrom} runs for
 * <em>every</em> page when a level is opened while {@link #writeTo} runs for every page on
 * save - not only for the page on screen. A page that was never opened still contributes its
 * fields, which is why the screen no longer has to keep a model of the whole level file.
 */
public interface EditorPage {
    /** Stable key: the smoke-test selector, the language key suffix, the registry key. */
    String id();

    /** Tab label; pages may override for a name that is not the key's title case. */
    default String label() {
        return com.pvzce.client.gui.GuiLang.raw("pvzce.editor.page." + id(), id());
    }

    /** Tab order; lower first. Ties fall back to registration order. */
    default int order() {
        return 100;
    }

    /** True when this page applies to the level being edited. */
    default boolean visibleFor(EditorContext context) {
        return true;
    }

    /**
     * True when the page needs a palette on the left.
     *
     * <p>Pages without one get the full width: the wave and music editors have two columns
     * of their own and read badly squeezed into the centre column.
     */
    default boolean hasPalette() {
        return false;
    }

    /** Loads this page's state from the level JSON. Runs once per level, before any build. */
    default void readFrom(EditorContext context) {
    }

    /** Writes this page's fields into the draft. Runs on save, for every page. */
    default void writeTo(EditorContext context) {
    }

    /** Creates this page's widgets. Runs on page switch and on resize. */
    default void build(EditorContext context) {
    }

    /** Draws anything the widgets do not cover: headings, hints, table labels. */
    default void render(EditorContext context) {
    }

    /** Per-tick work (models that follow a text box). */
    default void tick(EditorContext context) {
    }

    /**
     * Handles a click the widgets did not take.
     *
     * @return true when the page consumed it, so the board underneath does not also react
     */
    default boolean onMouseClicked(EditorContext context, double guiX, double guiY, int button) {
        return false;
    }

    /**
     * Handles a key the screen did not take.
     *
     * @return true when the page consumed it
     */
    default boolean keyPressed(EditorContext context, int key) {
        return false;
    }

    /** Called when another page takes over, so transient widgets can be dropped. */
    default void onClosed(EditorContext context) {
    }
}

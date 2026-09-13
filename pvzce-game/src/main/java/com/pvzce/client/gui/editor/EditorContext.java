package com.pvzce.client.gui.editor;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.AbstractWidget;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.gui.screens.EditorScreen;
import com.pvzce.common.core.JsonDraft;

import java.nio.file.Path;

/**
 * What a page is allowed to touch: the level being edited, the geometry it may draw in, and
 * the screen services it needs (owning widgets, showing a status line, opening a dialog).
 *
 * <p>A page never reaches into the screen's fields. The screen owns the file and the frame;
 * the pages own their own state, and this is the seam between them. It also keeps the
 * geometry in one place: pages that compute their own layout read {@link #content()} and
 * {@link #fullContent()} instead of repeating the margin arithmetic.
 */
public final class EditorContext {
    /** A GUI rectangle. */
    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }
    }

    private final EditorScreen screen;

    public EditorContext(EditorScreen screen) {
        this.screen = screen;
    }

    public EditorScreen screen() {
        return screen;
    }

    public PvzceClient client() {
        return screen.client();
    }

    /** The working copy of the level file; pages write their own fields into it. */
    public JsonDraft draft() {
        return screen.draft();
    }

    public Identifier levelId() {
        return screen.editorLevelId();
    }

    /** How the group fields and the smoke hooks see the level's id. */
    public void setLevelId(Identifier id) {
        screen.setEditorLevelId(id);
    }

    /** Where the level file lives; {@code null} until a new level is saved once. */
    public Path sourceFile() {
        return screen.editorSourceFile();
    }

    public void setSourceFile(Path file) {
        screen.setEditorSourceFile(file);
    }

    /** The name shown in the editor header, which the info page owns. */
    public String levelName() {
        return screen.editorLevelName();
    }

    public void setLevelName(String name) {
        screen.setEditorLevelName(name);
    }

    public Rect palette() {
        return screen.paletteArea();
    }

    /** Palette plus centre plus side panel: what a page with two columns of its own uses. */
    /** The centre column: the palette pages draw their board and side panel around it. */
    public Rect content() {
        return screen.contentArea();
    }

    public Rect fullContent() {
        return screen.fullContentRect();
    }

    public Rect side() {
        return screen.sideArea();
    }

    /** Registers a widget as belonging to the current page, so switching pages removes it. */
    public <T extends AbstractWidget> T own(T widget) {
        return screen.ownPageWidget(widget);
    }

    public void addWidget(AbstractWidget widget) {
        screen.addPageWidget(widget);
    }

    public void removeWidget(AbstractWidget widget) {
        screen.removePageWidget(widget);
    }

    public void setStatus(String text) {
        screen.setEditorStatus(text);
    }

    public void openDialog(Dialog dialog) {
        screen.showDialog(dialog);
    }

    /** Asks the screen to rebuild the current page; used when a page changes its own shape. */
    public void rebuildPage() {
        screen.rebuildCurrentPage();
    }

}

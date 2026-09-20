package com.pvzce.client.gui.editor.pages;

import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.editor.EditorPage;
import com.pvzce.client.gui.editor.form.FieldWidgets;
import com.pvzce.client.gui.editor.form.FormPage;

/**
 * The look page: which picture the level is played on, and which of its own scenery is drawn.
 *
 * <p>Two fields that only matter to a level that brings its own backdrop. The backdrop is a
 * texture id - the original's stages are all the same 1400x600 picture with the board in the
 * same place, so there is nothing else to configure with it - and the hidden list is what keeps
 * a level whose backdrop *is* the lawn from painting a second lawn over it. The terrain is
 * still painted in the file either way: that is what decides where a plant may go.
 *
 * <p>Its own page rather than two more rows on {@code InfoPage}: that page lays its fields out
 * by hand from the top of the panel down, and at 720p it already runs off the bottom - a form
 * page asks {@code FormLayout} for its rows and gets a second column instead.
 */
public final class LookPage {
    public static EditorPage create() {
        return FormPage.builder("look")
                .label(GuiLang.raw("pvzce.editor.page.look", "look"))
                .order(95)
                .heading(GuiLang.raw("pvzce.editor.tip.look", "背景与场景外观"))
                .field(FieldWidgets.reference("background",
                        GuiLang.raw("pvzce.editor.look.background", "Backdrop texture"), "texture"))
                .field(FieldWidgets.bool("disable_shaders",
                        GuiLang.raw("pvzce.editor.look.disable_shaders", "Disable shaders"),
                        GuiLang.raw("pvzce.editor.look.disable_shaders_on", "on"),
                        GuiLang.raw("pvzce.editor.look.disable_shaders_off", "off"),
                        java.util.Optional.of(Boolean.FALSE)))
                .field(FieldWidgets.stringList("hidden_scene_elements",
                        GuiLang.raw("pvzce.editor.look.hidden", "Hidden scene elements"), 256))
                .build();
    }

    private LookPage() {
    }
}

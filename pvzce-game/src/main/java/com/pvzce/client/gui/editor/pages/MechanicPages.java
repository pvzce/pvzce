package com.pvzce.client.gui.editor.pages;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.client.gui.editor.EditorPage;
import com.pvzce.client.gui.editor.form.FormPage;
import com.pvzce.common.level.mechanic.LevelMechanic;
import com.pvzce.common.level.mechanic.LevelMechanics;

import java.util.ArrayList;
import java.util.List;

/**
 * One editor page per level mechanic the level declares.
 *
 * <p>This is what the mechanic registry was for. A mechanic already says how its block decodes
 * ({@code codec}), what it does ({@code cardSource}, {@code canPlacePlant}, ...) and what may be
 * edited about it ({@code editorFields}); the editor reads that last answer and builds a page,
 * so a mod that adds a mechanic gets a working page without writing any UI - and the conveyor
 * belt, whose fields were hand-written JSON until now, gets one for free.
 *
 * <p>Pages edit a mechanic <em>in place</em>, under its own entry in the level's
 * {@code mechanics} array. Adding a mechanic to a level that has none is a level-shape
 * decision - it changes how the level plays - so it belongs where the level's shape is chosen,
 * not in a form field.
 */
public final class MechanicPages {
    /** After the rules page, before the pages that describe cards and waves. */
    private static final int ORDER = 35;

    public static List<EditorPage> create(EditorContext context) {
        List<EditorPage> pages = new ArrayList<>();
        List<JsonElement> mechanics = context.draft().getArray("mechanics").asList();
        for (int i = 0; i < mechanics.size(); i++) {
            JsonElement element = mechanics.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            JsonElement typeElement = block.get("type");
            Identifier type = typeElement == null ? null : Identifier.tryParse(typeElement.getAsString());
            LevelMechanic<?> mechanic = type == null ? null : LevelMechanics.get(type);
            if (mechanic == null || mechanic.editorFields().isEmpty()) {
                continue;
            }
            String label = GuiLang.raw("pvzce.mechanic." + type.path(), type.path());
            pages.add(FormPage.builder("mechanic." + type.path())
                    .label(label)
                    .order(ORDER)
                    .heading(label)
                    .pathPrefix("mechanics[" + i + "].")
                    .fields(mechanic.editorFields())
                    .build());
        }
        return pages;
    }

    /** A mechanic's editable fields, for tests and for a "this mechanic has no UI" message. */
    public static List<FieldSpec> fieldsOf(Identifier mechanicId) {
        LevelMechanic<?> mechanic = LevelMechanics.get(mechanicId);
        return mechanic == null ? List.of() : mechanic.editorFields();
    }

    private MechanicPages() {
    }
}

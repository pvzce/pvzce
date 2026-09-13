package com.pvzce.client.gui.editor;

import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.ConveyorMechanic;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a mechanic tells the editor about itself.
 *
 * <p>The editor builds one page per declared mechanic out of this list, so a mechanic with
 * fields is a mechanic with a page - that is the whole claim of the registry, and it is only
 * true if the declarations below stay non-empty and correctly typed.
 */
class MechanicPagesTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void theConveyorDescribesEveryFieldTheEditorNeeds() {
        List<FieldSpec> fields = LevelMechanics.get(PvzceIds.MECHANIC_CONVEYOR).editorFields();
        assertEquals(List.of("interval_ticks", "capacity", "initial_cards", "cards"),
                fields.stream().map(FieldSpec::path).toList());
        assertTrue(fields.get(0) instanceof FieldSpec.Number, "the interval is a number");
        // The pool is a list of card ids; weights stay a JSON-only detail until there is a
        // weighted-list widget, and must not be silently dropped by the editor.
        FieldSpec cards = fields.get(3);
        assertTrue(cards instanceof FieldSpec.Ref ref && ref.multiple(),
                "the card pool is a list of ids, so it edits as one");
    }

    @Test
    void thePlantableAreaDescribesItsFourBounds() {
        List<FieldSpec> fields = LevelMechanics.get(PvzceIds.MECHANIC_PLACEMENT_ZONE).editorFields();
        assertEquals(List.of("min_x", "max_x", "min_y", "max_y"),
                fields.stream().map(FieldSpec::path).toList());
        assertTrue(fields.stream().allMatch(spec -> spec instanceof FieldSpec.Number));
    }

    @Test
    void theDeckHasNoFieldsOfItsOwn() {
        // Its data is the level's top-level card fields, so it brings no page of its own.
        assertTrue(LevelMechanics.get(PvzceIds.MECHANIC_DECK).editorFields().isEmpty());
        assertFalse(LevelMechanics.get(PvzceIds.MECHANIC_CONVEYOR).editorFields().isEmpty());
    }

    @Test
    void everyMechanicFieldPathIsRelativeToItsOwnBlock() {
        for (var mechanic : List.of(LevelMechanics.get(PvzceIds.MECHANIC_CONVEYOR),
                LevelMechanics.get(PvzceIds.MECHANIC_PLACEMENT_ZONE))) {
            for (FieldSpec spec : mechanic.editorFields()) {
                assertFalse(spec.path().contains(".") || spec.path().contains("["),
                        "a field path is one key inside the block, not a path from the root: " + spec.path());
            }
        }
    }

    @Test
    void theConveyorIsTheOneSelfDealtCardSource() {
        assertTrue(LevelMechanics.get(PvzceIds.MECHANIC_CONVEYOR).dealsItsOwnCards());
        assertFalse(LevelMechanics.get(PvzceIds.MECHANIC_DECK).dealsItsOwnCards());
        assertFalse(LevelMechanics.get(PvzceIds.MECHANIC_PLACEMENT_ZONE).dealsItsOwnCards());
        assertTrue(ConveyorMechanic.BarState.CODEC != null, "the belt streams its bar");
    }
}

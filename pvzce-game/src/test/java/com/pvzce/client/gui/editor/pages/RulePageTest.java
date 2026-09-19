package com.pvzce.client.gui.editor.pages;

import com.pvzce.api.content.GameRuleType;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules page offers what the server accepts.
 *
 * <p>The page kept its own table of slider ranges, and the table had drifted: {@code sun_value}
 * was editable up to 500 while the registration allowed 10000, so an author could not express a
 * value the game runs with. The ranges now come from the registration
 * ({@link GameRuleType#bounds()}), and the only exceptions are the rules whose real bound is
 * "unbounded" and whose slider is narrowed on purpose - which this pins by name.
 */
class RulePageTest {
    /** Rules whose registered bound is effectively unbounded; the slider narrows them. */
    private static final List<String> DELIBERATELY_NARROWED = List.of(
            "pvzce:day_length", "pvzce:night_length", "pvzce:crater_recovery");

    @BeforeAll
    static void loadContent() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static List<Identifier> numericRules() {
        List<Identifier> rules = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.GAME_RULES.keySet()) {
            if (!(BuiltInRegistries.GAME_RULES.get(id) instanceof GameRuleType.BooleanRule)) {
                rules.add(id);
            }
        }
        return rules;
    }

    @Test
    void everyNumericRuleDeclaresItsBounds() {
        for (Identifier id : numericRules()) {
            GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(id);
            assertNotNull(type.bounds(), id + " is numeric, so the editor needs its range");
            assertEquals(2, type.bounds().length);
            assertTrue(type.bounds()[0] <= type.bounds()[1], id + " has inverted bounds");
        }
    }

    @Test
    void theEditorNeverOffersMoreThanTheServerAccepts() {
        for (Identifier id : numericRules()) {
            GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(id);
            float[] offered = RulePage.sliderRange(id, type);
            float[] accepted = type.bounds();
            assertTrue(offered[0] >= accepted[0] && offered[1] <= accepted[1],
                    id + " offers " + offered[0] + ".." + offered[1]
                            + " but the server accepts " + accepted[0] + ".." + accepted[1]);
        }
    }

    /**
     * The other direction: the editor must not hide values the game allows, except for the
     * three unbounded rules. This is the assertion that would have caught the {@code sun_value}
     * drift.
     */
    @Test
    void theEditorOffersEveryValueTheServerAccepts() {
        for (Identifier id : numericRules()) {
            if (DELIBERATELY_NARROWED.contains(id.toString())) {
                continue;
            }
            GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(id);
            float[] offered = RulePage.sliderRange(id, type);
            float[] accepted = type.bounds();
            assertEquals(accepted[0], offered[0], id + " hides the low end of its range");
            assertEquals(accepted[1], offered[1], id + " hides the high end of its range");
        }
    }

    /** The drift itself, written down: 1..10000 is what a level may now say. */
    @Test
    void sunValueIsEditableUpToTheRegisteredCeiling() {
        Identifier sun = Identifier.withDefaultNamespace("sun_value");
        GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(sun);
        assertNotNull(type, "the sun value rule must be registered");
        float[] offered = RulePage.sliderRange(sun, type);
        assertEquals(type.bounds()[1], offered[1]);
        assertTrue(offered[1] > 500F, "the old table stopped at 500, below the server's ceiling");
        assertTrue(type.integral(), "a sun value is a whole number");
    }
}

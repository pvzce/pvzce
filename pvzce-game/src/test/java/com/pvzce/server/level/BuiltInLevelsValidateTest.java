package com.pvzce.server.level;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.gamerule.GameRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shipped level passes the validator that runs when it is played.
 *
 * <p>{@code LevelServer.reportLevelProblems} collects every inconsistency in a level definition and
 * logs them - at <em>play</em> time, which means the shipped data had never been checked as a
 * whole. A level whose card pool names a plant that no longer exists, whose scene leaves cells
 * unpainted or whose dialogue points at a missing character would be found by whoever happened to
 * open it, in a log line, mid-game.
 *
 * <p>The checks are the same list the server runs, so a level that passes here cannot warn there.
 * The one thing not repeated is the constructor's own error path: this test asserts that the data
 * is clean, not that the server survives dirty data.
 */
class BuiltInLevelsValidateTest {
    @BeforeAll
    static void loadBuiltInContent() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void everyBuiltInLevelIsFreeOfValidatorProblems() {
        Set<Identifier> ids = BuiltInRegistries.LEVELS.keySet();
        assertTrue(ids.size() > 10, "expected the built-in levels to be loaded, found " + ids.size());

        List<String> problems = new ArrayList<>();
        for (Identifier id : ids) {
            LevelDef def = BuiltInRegistries.LEVELS.get(id);
            for (String problem : problemsOf(def)) {
                problems.add(id + ": " + problem);
            }
        }
        assertTrue(problems.isEmpty(), "shipped levels with validator problems:\n"
                + String.join("\n", problems));
    }

    /** The same checks {@code LevelServer.reportLevelProblems} runs, in the same order. */
    private static List<String> problemsOf(LevelDef def) {
        List<String> problems = new ArrayList<>();
        problems.addAll(LevelValidator.validatePlacementTags());
        problems.addAll(GameRules.validate(def.rules()));
        problems.addAll(LevelValidator.validateEnvVars(def.envVars()));
        problems.addAll(LevelMechanics.validate(def));
        problems.addAll(LevelValidator.validateRewards(def));
        problems.addAll(LevelValidator.validateUnlock(def));
        problems.addAll(LevelValidator.validateUnlockCycles());
        problems.addAll(LevelValidator.validateScene(def));
        problems.addAll(LevelValidator.validateInitialEntities(def));
        problems.addAll(LevelValidator.validateDialogue(def));
        problems.addAll(LevelValidator.validateHints(def));
        return problems;
    }
}

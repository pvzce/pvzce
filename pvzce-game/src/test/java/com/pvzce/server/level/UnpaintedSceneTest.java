package com.pvzce.server.level;

import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.LevelDef;
import com.pvzce.common.core.BuiltInRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A half-painted board used to be invisible until it was played: the level editor's
 * own {@code sceneAt} fell back to grass, so the canvas showed a complete lawn while
 * the saved level had cells with no element at all - which the game draws as bare
 * background and the server refuses to plant on.
 *
 * <p>This is the exact level reported as "the water in the middle is not drawn": 30
 * water cells around a cross of 15 unpainted ones. The renderer was correct all
 * along; what was missing was anything that told the author.
 */
class UnpaintedSceneTest {
    @BeforeAll
    static void bootstrap() {
        BuiltInRegistries.bootstrap();
    }

    /** The reported level, verbatim: 30 water cells, everything else unpainted. */
    private static LevelDef reportedLevel() {
        String json = """
                {
                  "id": "test:reported",
                  "width": 9, "height": 5,
                  "scene": { "pvzce:water": [
                    "2,4","3,4","4,4",
                    "0,3","1,3","2,3","3,3","4,3","5,3","6,3","7,3",
                    "0,2","1,2","2,2","3,2","4,2","5,2","6,2","7,2",
                    "0,1","1,1","2,1","3,1","4,1","5,1","6,1","7,1",
                    "2,0","3,0","4,0"
                  ] }
                }
                """;
        return LevelDef.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void theValidatorNamesTheHoles() {
        List<String> errors = LevelValidator.validateScene(reportedLevel());
        assertEquals(1, errors.size(), "one summary error, not one per hole: " + errors);
        String message = errors.get(0);
        assertTrue(message.contains("15 of 45"), message);
        assertTrue(message.contains("unpainted"), message);
        // The list is truncated after twelve entries so one bad level cannot flood the
        // log, so check cells that survive the cut.
        assertTrue(message.contains("0,0"), "a corner hole must be named: " + message);
        assertTrue(message.contains("1,4"), message);
        assertTrue(message.endsWith("..."), "long lists are truncated: " + message);
    }

    @Test
    void aFullyPaintedBoardReportsNothing() {
        String json = """
                {
                  "id": "test:full", "width": 3, "height": 2,
                  "scene": { "pvzce:grass": ["0,0","1,0","2,0","0,1","1,1","2,1"] }
                }
                """;
        LevelDef def = LevelDef.CODEC.parse(JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString(json)).getOrThrow();
        assertEquals(List.of(), LevelValidator.validateScene(def));
    }
}

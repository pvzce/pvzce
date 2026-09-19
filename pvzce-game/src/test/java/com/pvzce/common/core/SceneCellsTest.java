package com.pvzce.common.core;

import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A cell declared twice takes the later declaration.
 *
 * <p>This is not a detail: {@code combat_test} lists all forty-five cells as grass and then names
 * four of them water, four roof and two graves, which is a reasonable way to write "a grass lawn
 * with a pond in it". Playing the level gives the later declaration - the server sets each parsed
 * cell in map order - while the editor's canvas answered with the first, so the same file showed
 * grass where the game showed water. The rule is one method now, and this pins it.
 */
class SceneCellsTest {
    private static Map<String, List<String>> scene() {
        Map<String, List<String>> scene = new LinkedHashMap<>();
        scene.put("pvzce:grass", List.of("0,0", "1,0", "0,1", "1,1"));
        scene.put("pvzce:water", List.of("1,0"));
        scene.put("pvzce:grave", List.of("0,1", "1,1"));
        return scene;
    }

    @Test
    void theLaterDeclarationWins() {
        assertEquals("pvzce:grass", SceneCells.lookup(scene(), 0, 0));
        assertEquals("pvzce:water", SceneCells.lookup(scene(), 1, 0),
                "water is declared after the grass base, so it is what the cell is");
        assertEquals("pvzce:grave", SceneCells.lookup(scene(), 0, 1));
        assertEquals("pvzce:grave", SceneCells.lookup(scene(), 1, 1));
    }

    @Test
    void anUndeclaredCellIsBare() {
        assertNull(SceneCells.lookup(scene(), 5, 5));
        assertNull(SceneCells.lookup(null, 0, 0));
    }

    @Test
    void theSameRuleAnswersForParsedAndRawKeys() {
        // The editor holds the file's own spelling while the server holds parsed ids; both call
        // this method, so neither can drift into its own answer.
        Map<Identifier, List<String>> parsed = new LinkedHashMap<>();
        parsed.put(Identifier.withDefaultNamespace("grass"), List.of("0,0"));
        parsed.put(Identifier.withDefaultNamespace("water"), List.of("0,0"));
        assertEquals(Identifier.withDefaultNamespace("water"), SceneCells.lookup(parsed, 0, 0));
    }
}

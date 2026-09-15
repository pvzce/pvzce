package com.pvzce.server.level;

import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The save-directory key must be injective: equal keys only for equal level ids.
 *
 * <p>The rule it replaces replaced {@code /} with {@code _}, so {@code pvzce:a/b} and
 * {@code pvzce:a_b} shared one run save and one completion marker, and the level saved second
 * silently overwrote the first. Both characters are legal in an id and both are in use, so
 * nothing short of escaping can be injective - which is what this pins.
 */
class LevelKeyTest {
    @Test
    void theNamespaceIsHexAndThePathIsEscaped() {
        assertEquals("70767a6365__demo_level",
                LevelKey.of(Identifier.withDefaultNamespace("demo_level")));
        assertEquals("70767a6365__yard%2Fadventure%2F1_1",
                LevelKey.of(Identifier.withDefaultNamespace("yard/adventure/1_1")));
        assertEquals("6d796d6f64__yard%2F1_1", LevelKey.of(Identifier.of("mymod", "yard/1_1")));
        // The path half keeps the level's own name readable; only the namespace is opaque.
        assertTrue(LevelKey.of(Identifier.withDefaultNamespace("yard/adventure/1_1")).endsWith("__yard%2Fadventure%2F1_1"));
    }

    /**
     * No two distinct ids in a hostile set share a key.
     *
     * <p>Not a proof, but the shape of the encoding is: the namespace half is hex (an
     * alphabet the path half never uses) and the path half maps one output character per
     * input character, so no two inputs can produce one key. The set below is where that
     * would break if either half were sloppy - namespaces and paths built from the same
     * characters, underscores against slashes, empty-ish segments, deep paths.
     */
    @Test
    void distinctIdsNeverShareAKey() {
        // Namespaces and paths made of the same characters as each other on purpose: that is
        // where a plain separator between them stops being unambiguous.
        List<Identifier> ids = new ArrayList<>();
        for (String ns : List.of("pvzce", "c", "my_mod", "a_b", "pvzce_", "pvzce__", "a", "a__b")) {
            for (String path : List.of("a/b", "a_b", "_a_b", "__a_b", "a__b", "a/b_c", "a_b/c",
                    "yard/adventure/1_1", "yard/adventure/1_1/2", "a//b", "a/b/", "a.",
                    "a/b.c", "x", "_", "__", "a_/b")) {
                ids.add(Identifier.of(ns, path));
            }
        }
        Set<String> keys = new HashSet<>();
        for (Identifier id : ids) {
            String key = LevelKey.of(id);
            assertTrue(keys.add(key), "two ids share the save key " + key + " (at " + id + ")");
        }
        assertEquals(ids.size(), keys.size());
    }

}

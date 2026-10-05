package com.pvzce.client.renderer;

import com.pvzce.api.content.SceneElementArt;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which scene art is a tile sheet, and which is one cell-sized sprite.
 *
 * <p>The renderer draws a sheet by sampling the tile at {@code (x mod 6, y mod 6)} of the cell, and
 * anything else as a single sprite. That used to be a hardcoded list of two ids, which meant a
 * level could not bring a ground of its own - the terraced hillside's banks are the dirt sheet and
 * its terraces are the grass sheet, both under new names. The rule is now the element's own art
 * ({@code "tiled": true}), and this pins both halves of it: the declaration works, and the two
 * built-in atlases keep working without one.
 */
class SceneArtTilingTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static SceneElementArt art(String path) {
        var def = BuiltInRegistries.SCENE_ELEMENTS.get(Identifier.withDefaultNamespace(path));
        assertNotNull(def, "missing scene element " + path);
        return def.artOrDefault();
    }

    @Test
    void aDeclaredSheetIsTiledAndADecalIsNot() {
        assertTrue(art("hillside_mid").tiled(), "the terraced hillside's lawn borrows the grass sheet");
        assertTrue(art("hillside_bank_low").tiled(), "and its banks the dirt sheet");
        assertFalse(art("crater").tiled(), "a crater is one sprite in one cell");
        assertFalse(SceneElementArt.NONE.tiled(), "and declaring no art declares no sheet");
    }

    @Test
    void theBuiltInAtlasesKeepTilingWithoutTheField() {
        assertFalse(art("grass").tiled(), "the built-in lawn predates the field");
        assertTrue(EntityTextures.sceneTiled("pvzce:grass"), "and is still tiled by id");
        assertTrue(EntityTextures.sceneTiled("pvzce:ground"), "as is the bare ground");
        assertFalse(EntityTextures.sceneTiled("pvzce:crater"), "nothing else is");
        assertFalse(EntityTextures.sceneTiled("pvzce:not_a_real_element"), "and nothing unknown is");
    }
}

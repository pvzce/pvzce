package com.pvzce.client.gui;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Display-name resolution for the editor and the pickers.
 *
 * <p>The language files shipped from the first phase but nothing read them, so the
 * UI showed raw ids. These tests pin the key format ({@code <namespace>.<path>}, as
 * MC uses) and the fallback, because a silent key mismatch would put ids back on
 * every list row without failing anything else.
 */
class GuiLangTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        GuiLang.reload(TestContent.loadBuiltInContent());
    }

    @Test
    void builtInLanguageFileLoads() {
        assertTrue(GuiLang.isLoaded(), "assets/pvzce/lang/zh_cn.json must be readable");
        assertEquals("zh_cn", GuiLang.locale());
    }

    @Test
    void contentIdsResolveToTheirNames() {
        assertEquals("豌豆射手", GuiLang.name(Identifier.withDefaultNamespace("pea_shooter")));
        assertEquals("铁桶僵尸", GuiLang.name(Identifier.withDefaultNamespace("buckethead_zombie")));
        assertEquals("草地", GuiLang.name(Identifier.withDefaultNamespace("grass")));
        assertEquals("铲子", GuiLang.name(Identifier.withDefaultNamespace("shovel")));
    }

    @Test
    void everyRegistrationAndRuleHasAName() {
        // Every content id the editor can list, plus every rule: a missing entry shows
        // up as an id in a menu, which is exactly what the language file exists to avoid.
        assertAllNamed(com.pvzce.common.core.BuiltInRegistries.PLANTS.keySet(), "plant");
        assertAllNamed(com.pvzce.common.core.BuiltInRegistries.ZOMBIES.keySet(), "zombie");
        assertAllNamed(com.pvzce.common.core.BuiltInRegistries.SCENE_ELEMENTS.keySet(), "scene element");
        assertAllNamed(com.pvzce.common.core.BuiltInRegistries.TOOLS.keySet(), "tool");
        assertAllNamed(com.pvzce.common.core.BuiltInRegistries.RESOURCES.keySet(), "resource");
        assertAllNamed(com.pvzce.common.core.BuiltInRegistries.GAME_RULES.keySet(), "game rule");
    }

    private static void assertAllNamed(java.util.Set<Identifier> ids, String what) {
        for (Identifier id : ids) {
            assertFalse(GuiLang.lookup(id) == null, "no language entry for " + what + " " + id);
        }
    }

    @Test
    void missingEntriesFallBackToThePathNotAnId() {
        Identifier unknown = Identifier.withDefaultNamespace("definitely_not_translated");
        assertNull(GuiLang.lookup(unknown), "an untranslated id must report a miss");
        assertEquals("definitely_not_translated", GuiLang.name(unknown),
                "the fallback is the path, never namespace:path");
        assertEquals("pvzce:definitely_not_translated", GuiLang.idLabel(unknown));
    }

    @Test
    void missingIdsAreHandled() {
        assertEquals("", GuiLang.name((Identifier) null));
        assertNull(GuiLang.lookup((Identifier) null));
        assertEquals("", GuiLang.idLabel(null));
    }

    @Test
    void editorStringsAreTranslatedToo() {
        // The editor asks through raw() for its own labels; these are the ones whose
        // absence would leave a page titled "rule".
        assertEquals("地形", GuiLang.raw("pvzce.editor.page.terrain", "terrain"));
        assertEquals("规则", GuiLang.raw("pvzce.editor.page.rule", "rule"));
        assertEquals("新建关卡", GuiLang.raw("pvzce.editor.new_level", "New Level"));
    }
}

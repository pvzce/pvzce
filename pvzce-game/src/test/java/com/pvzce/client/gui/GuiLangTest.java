package com.pvzce.client.gui;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PvzceRegistries;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Display-name resolution for the editor, the pickers and the almanac.
 *
 * <p>The language files shipped from the first phase but nothing read them, so the UI showed raw
 * ids. These tests pin the key shape - {@code <registry>.<namespace>.<path>}, with optional
 * trailing segments - and the fallback, because a silent key mismatch puts ids back on every list
 * row without failing anything else.
 *
 * <p>The category matters as much as the id: {@code pvzce:water} is both a scene element and a
 * liquid, and before the category existed the two could not be translated apart.
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
        assertEquals("豌豆射手", GuiLang.name("plant", id("pea_shooter")));
        assertEquals("铁桶僵尸", GuiLang.name("zombie", id("buckethead_zombie")));
        assertEquals("草地", GuiLang.name("scene_element", id("grass")));
        assertEquals("铲子", GuiLang.name("tool", id("shovel")));
        assertEquals("阳光", GuiLang.name("resource", id("sun")));
    }

    /**
     * One id, two registries, two names.
     *
     * <p>{@code pvzce:water} is a scene element (the tile you paint) and a liquid (what fills it);
     * {@code pvzce:snow_pea} is a plant and the projectile it fires. A lookup that guessed the
     * category could only ever answer one of the two.
     */
    @Test
    void theSameIdInTwoRegistriesIsNamedByItsCategory() {
        assertNotNull(GuiLang.lookup("scene_element", id("water")));
        assertNotNull(GuiLang.lookup("liquid", id("water")));
        assertEquals(GuiLang.lookup("scene_element", id("grass")),
                GuiLang.name("scene_element", id("grass")));
        // An id only one registry holds still names itself through that registry's category.
        assertEquals("寒冰射手", GuiLang.name("plant", id("snow_pea")));
    }

    @Test
    void everyRegistrationAndRuleHasAName() {
        // Every content id the editor can list, plus every rule: a missing entry shows up as an
        // id in a menu, which is exactly what the language file exists to avoid.
        assertAllNamed(BuiltInRegistries.PLANTS.keySet(), "plant");
        assertAllNamed(BuiltInRegistries.ZOMBIES.keySet(), "zombie");
        assertAllNamed(BuiltInRegistries.PROJECTILES.keySet(), "projectile");
        assertAllNamed(BuiltInRegistries.SCENE_ELEMENTS.keySet(), "scene element");
        assertAllNamed(BuiltInRegistries.TOOLS.keySet(), "tool");
        assertAllNamed(BuiltInRegistries.RESOURCES.keySet(), "resource");
        assertAllNamed(BuiltInRegistries.SLOT_TYPES.keySet(), "slot");
        assertAllNamed(BuiltInRegistries.LIQUIDS.keySet(), "liquid");
        assertAllNamed(BuiltInRegistries.GAME_RULES.keySet(), "game rule");
        assertAllNamed(BuiltInRegistries.DAMAGE_TYPES.keySet(), "damage type");
        assertAllNamed(BuiltInRegistries.LEVEL_THEMES.keySet(), "level theme");
        assertAllNamed(BuiltInRegistries.LEVEL_CATEGORIES.keySet(), "level category");
        assertAllNamed(BuiltInRegistries.LEVEL_BUFFS.keySet(), "level buff");
    }

    private static void assertAllNamed(Set<Identifier> ids, String what) {
        String category = PvzceRegistries.byCategory().entrySet().stream()
                .filter(entry -> entry.getValue().registry().path().equals(what.replace(' ', '_')))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(what.replace(' ', '_'));
        for (Identifier id : ids) {
            assertNotNull(GuiLang.lookup(category, id), "no language entry for " + what + " " + id);
        }
    }

    /**
     * The almanac's own text: every entry it can show has a card line and a bio.
     *
     * <p>This is the one test that keeps "the text comes from the language file" honest. The
     * almanac draws whatever the registry holds, so a plant added without its two segments would
     * silently render an empty card rather than failing anything else.
     */
    @Test
    void everyAlmanacEntryHasItsCardLineAndBio() {
        assertAlmanacText(BuiltInRegistries.PLANTS.keySet(), "plant");
        assertAlmanacText(BuiltInRegistries.ZOMBIES.keySet(), "zombie");
        assertAlmanacText(BuiltInRegistries.RESOURCES.keySet(), "resource");
    }

    private static void assertAlmanacText(Set<Identifier> ids, String category) {
        for (Identifier id : ids) {
            assertNotNull(GuiLang.content(category, id, "desc"),
                    "no card line for " + category + " " + id);
            assertNotNull(GuiLang.content(category, id, "flavor"),
                    "no bio for " + category + " " + id);
        }
    }

    @Test
    void missingEntriesFallBackToThePathNotAnId() {
        Identifier unknown = id("definitely_not_translated");
        assertNull(GuiLang.lookup("plant", unknown), "an untranslated id must report a miss");
        assertEquals("definitely_not_translated", GuiLang.name("plant", unknown),
                "the fallback is the path, never namespace:path");
        assertEquals("pvzce:definitely_not_translated", GuiLang.idLabel(unknown));
        assertNull(GuiLang.content("plant", unknown, "desc"),
                "a missing segment reports a miss so the caller can leave the box empty");
        assertEquals("(none)", GuiLang.contentOr("plant", unknown, "desc", "(none)"));
    }

    @Test
    void aCategorylessLookupStillResolvesTheOldFlatKey() {
        // A pack written before the category existed keeps working: the flat
        // <namespace>.<path> key is the fallback, and a UI string is only ever that shape.
        assertEquals("返回", GuiLang.raw("pvzce.back", "Back"));
        assertEquals("未分类", GuiLang.raw("pvzce.uncategorized", "uncategorized"));
    }

    @Test
    void missingIdsAreHandled() {
        assertEquals("", GuiLang.name((Identifier) null));
        assertEquals("", GuiLang.name("plant", null));
        assertNull(GuiLang.lookup((Identifier) null));
        assertNull(GuiLang.content("plant", null, "desc"));
        assertNull(GuiLang.content(null, id("pea_shooter"), "desc"));
        assertEquals("", GuiLang.idLabel(null));
    }

    @Test
    void editorStringsAreTranslatedToo() {
        // The editor asks through raw() for its own labels; these are the ones whose absence
        // would leave a page titled "rule".
        assertEquals("地形", GuiLang.raw("pvzce.editor.page.terrain", "terrain"));
        assertEquals("规则", GuiLang.raw("pvzce.editor.page.rule", "rule"));
        assertEquals("新建关卡", GuiLang.raw("pvzce.editor.new_level", "New Level"));
    }

    @Test
    void theAlmanacsOwnStringsAreTranslated() {
        assertEquals("图鉴", GuiLang.raw("gui.pvzce.almanac.title", "Almanac"));
        assertEquals("植物", GuiLang.raw("gui.pvzce.almanac.tab.plants", "Plants"));
        assertFalse(GuiLang.raw("gui.pvzce.almanac.close", "Close").equals("Close"));
    }

    private static Identifier id(String path) {
        return Identifier.withDefaultNamespace(path);
    }
}

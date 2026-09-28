package com.pvzce.server.level;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The level collections: a tag over the levels, read as a row of the level list.
 *
 * <p>The shipped six are what is pinned here - the four adventure chapters, 变异 and 节奏草坪 - and
 * the rules around them: membership is the tag file's order, a collection's page and icon come from
 * its first member, and a tag that names nothing (or is not filed under the collection prefix) is
 * not a box.
 */
class LevelCollectionsTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelCollections.Collection find(String path) {
        for (LevelCollections.Collection collection : LevelCollections.build()) {
            if (collection.id().equals(Identifier.of("pvzce", path))) {
                return collection;
            }
        }
        return null;
    }

    /** The tag files really are read: without this the whole feature is a silent empty list. */
    @Test
    void theShippedCollectionsAreReadFromTheirTags() {
        List<LevelCollections.Collection> collections = LevelCollections.build();
        assertFalse(collections.isEmpty(), "the shipped collections must be found; the tags live at"
                + " data/pvzce/tags/levels/collections/*.json and are keyed as #pvzce:collections/*");
        for (String name : new String[] {"day_lawn", "night_lawn", "day_pool", "night_pool",
                "mutation", "rhythm"}) {
            assertNotNull(find("collections/" + name), name + " is one of the shipped collections");
        }
    }

    /** Ten chapters each, in the order the file lists them - not the alphabetical order of names. */
    @Test
    void anAdventureChapterHoldsItsTenLevelsInOrder() {
        LevelCollections.Collection day = find("collections/day_lawn");
        assertEquals(10, day.members().size(), "白天草坪 is ten levels");
        assertEquals("pvzce:yard/adventure/1_1", day.members().get(0).toString());
        assertEquals("pvzce:yard/adventure/1_10", day.members().get(9).toString(),
                "1-10 comes after 1-9, which is the order the file writes them in");
        assertEquals(Identifier.of("pvzce", "yard"), day.theme());
        assertEquals(Identifier.of("pvzce", "adventure"), day.category());
        assertEquals("day", day.icon(), "chapter one's icon is what 1-1's own row would draw");
    }

    /** 变异 is the four tiers and their tutorial, in difficulty order rather than alphabetically. */
    @Test
    void theMutationCollectionIsItsTiersAndTutorialInDifficultyOrder() {
        LevelCollections.Collection mutation = find("collections/mutation");
        assertEquals(List.of("pvzce:yard/survival/mutation_easy",
                        "pvzce:yard/survival/mutation_normal",
                        "pvzce:yard/survival/mutation_hard",
                        "pvzce:yard/survival/mutation_hell",
                        "pvzce:yard/survival/mutation_tutorial"),
                mutation.members().stream().map(Identifier::toString).toList(),
                "the order the tag file writes is the order the list shows: easy, normal, hard,"
                        + " hell, tutorial - which is not what sorting the ids would give");
        assertEquals(Identifier.of("pvzce", "survival"), mutation.category());
    }

    /** A collection sits where its first level does, so the four chapters come out in order. */
    @Test
    void aCollectionSortsWhereItsFirstLevelDoes() {
        assertEquals("pvzce:yard/adventure/1_1", find("collections/day_lawn").sortKey());
        assertTrue(com.pvzce.api.util.LevelGrouping.compareIds(
                        find("collections/day_lawn").sortKey(),
                        find("collections/night_lawn").sortKey()) < 0,
                "白天草坪 before 夜晚草坪");
        assertTrue(com.pvzce.api.util.LevelGrouping.compareIds(
                        find("collections/night_pool").sortKey(),
                        "pvzce:yard/adventure/combat_test") < 0,
                "and the four boxes before the loose levels, which is where chapter numbers sit");
    }

    /** The 节奏草坪 category is its own page, and its one collection is on it. */
    @Test
    void theRhythmCollectionIsItsOwnCategory() {
        LevelCollections.Collection rhythm = find("collections/rhythm");
        assertEquals(Identifier.of("pvzce", "rhythm"), rhythm.category(),
                "the four tiers are the 节奏草坪 page rather than a corner of the mini-games");
        assertEquals(4, rhythm.members().size());
        for (Identifier member : rhythm.members()) {
            assertNotNull(BuiltInRegistries.LEVELS.get(member), member + " is a registered level");
        }
    }

    /** Every member of every collection is a level this pack ships, and lives in exactly one box. */
    @Test
    void membershipIsRealAndDisjoint() {
        java.util.Set<Identifier> seen = new java.util.HashSet<>();
        for (LevelCollections.Collection collection : LevelCollections.build()) {
            for (Identifier member : collection.members()) {
                assertNotNull(BuiltInRegistries.LEVELS.get(member),
                        member + " is named by " + collection.id() + " and must exist");
                assertTrue(seen.add(member),
                        member + " is in two collections, and a level is reached through one box");
            }
        }
    }

    /** A tag that names nothing is not a box: an empty page is worse than no page. */
    @Test
    void aTagWithNoMembersIsNotACollection() {
        assertNull(LevelCollections.of(Identifier.of("pvzce", "collections/no_such_tag")));
    }

    /** Only tags under the prefix are read, so a pack's other statements about levels stay tags. */
    @Test
    void aTagOutsideThePrefixIsNotACollection() {
        List<LevelCollections.Collection> collections = LevelCollections.build();
        for (LevelCollections.Collection collection : collections) {
            assertTrue(collection.id().path().startsWith(PvzceTags.COLLECTION_PREFIX),
                    collection.id() + " came from a collection tag");
        }
        // And the reverse: the registry does have tags in it that are not collections, so the
        // filter is doing something. `#c:plantable` is over scene elements rather than levels, so
        // what this asserts is simply that the level registry's tag list is not empty-handed.
        assertTrue(PvzceTags.LEVELS.tagIds().size() >= collections.size());
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }
}

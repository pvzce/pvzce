package com.pvzce.client.gui.almanac;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The almanac's catalogue: what it can show, and in what order.
 *
 * <p>The order is the one thing about the almanac that is not derivable from a registry, so it is
 * the one thing that can silently go wrong: a plant added without an {@code order} lands after
 * every plant that has one, and a zombie added without an entry in the code table lands after
 * every zombie. Both are asserted here rather than discovered in a screenshot.
 */
class AlmanacEntriesTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void everyRegisteredPlantAppearsExactlyOnce() {
        List<Identifier> listed = AlmanacEntries.plants().ids();
        assertEquals(BuiltInRegistries.PLANTS.size(), listed.size());
        assertEquals(new HashSet<>(BuiltInRegistries.PLANTS.keySet()), new HashSet<>(listed));
    }

    @Test
    void everyRegisteredZombieAndResourceAppearsExactlyOnce() {
        assertEquals(new HashSet<>(BuiltInRegistries.ZOMBIES.keySet()),
                new HashSet<>(AlmanacEntries.zombies().ids()));
        assertEquals(new HashSet<>(BuiltInRegistries.RESOURCES.keySet()),
                new HashSet<>(AlmanacEntries.resources().ids()));
    }

    /**
     * Plants read in almanac order and no two of them claim the same place.
     *
     * <p>{@code order} is the original's almanac number, and the seed bar and the chooser sort by
     * it too - so a collision is not a cosmetic problem, it is "these two are in an arbitrary
     * order everywhere they are listed". One shipped: {@code threepeater} and {@code tangle_kelp}
     * were both 19.
     */
    @Test
    void plantOrderIsUniqueAndAscending() {
        List<Identifier> listed = AlmanacEntries.plants().ids();
        Set<Integer> seen = new HashSet<>();
        int previous = Integer.MIN_VALUE;
        for (Identifier id : listed) {
            var def = BuiltInRegistries.PLANTS.get(id);
            assertNotNull(def, "listed plant must be registered: " + id);
            int order = def.order();
            if (order != def.DEFAULT_ORDER) {
                assertTrue(seen.add(order), "two plants share almanac order " + order);
                assertTrue(order >= previous, "plants must read in ascending almanac order");
                previous = order;
            }
        }
        // The plants that do declare a number must still be the majority - a plant that forgot one
        // is a plant that reads at the end of the book.
        long numbered = listed.stream()
                .filter(id -> BuiltInRegistries.PLANTS.get(id).order() != 1000)
                .count();
        assertTrue(numbered >= 30, "only " + numbered + " plants declare an almanac order");
    }

    @Test
    void theOriginalOrderIsKept() {
        List<String> paths = AlmanacEntries.plants().ids().stream().map(Identifier::path).toList();
        // Spot checks against the original's almanac: the first five and the last numbered one.
        assertEquals("pea_shooter", paths.get(0));
        assertTrue(paths.indexOf("sunflower") < paths.indexOf("cherry_bomb"));
        assertTrue(paths.indexOf("threepeater") < paths.indexOf("tangle_kelp"),
                "Tangle Kelp is 20 and Threepeater is 19 in the original");
        assertTrue(paths.indexOf("tangle_kelp") < paths.indexOf("jalapeno"));
        // Bowling Wall-nut has no almanac number (it is a mini-game's ammunition) and sorts last.
        assertEquals("bowling_nut", paths.get(paths.size() - 1));
    }

    /**
     * Zombies read in the original's almanac order, with this project's own additions after them.
     *
     * <p>The order is a code table because nothing in {@code ZombieDef} carries one - see
     * {@link AlmanacEntries}. What this pins is that every shipped zombie is in the table: a new
     * zombie that forgets to say where it goes would read after every zombie that did, and that is
     * the kind of thing nobody notices until the book is three pages long.
     */
    @Test
    void zombieOrderCoversTheShippedZombies() {
        List<String> paths = AlmanacEntries.zombies().ids().stream().map(Identifier::path).toList();
        assertTrue(paths.indexOf("basic_zombie") < paths.indexOf("conehead_zombie"));
        assertTrue(paths.indexOf("conehead_zombie") < paths.indexOf("buckethead_zombie"));
        assertTrue(paths.indexOf("dancing_zombie") < paths.indexOf("backup_dancer"),
                "the Backup Dancer follows the Dancing Zombie, as in the original");
        assertTrue(paths.indexOf("gargantuar") < paths.indexOf("imp"));
        assertTrue(paths.indexOf("imp") < paths.indexOf("zombie_boss"));
        // The two dozen the original lists come first, in its order; ours follow.
        assertEquals("zombie_boss", paths.get(17),
                "Dr. Zomboss is 26th in the original and last of the zombies it lists");
        for (String path : paths) {
            assertTrue(AlmanacEntries.zombieOrderOf(Identifier.withDefaultNamespace(path)) >= 0,
                    path + " is not in the almanac order table");
        }
    }

    /** The six mini zombies of 3-5 are ours, so they read after every zombie the original lists. */
    @Test
    void theMiniZombiesReadLast() {
        List<String> paths = AlmanacEntries.zombies().ids().stream().map(Identifier::path).toList();
        List<String> minis = new ArrayList<>(paths.stream().filter(p -> p.startsWith("mini_")).toList());
        assertEquals(6, minis.size(), "3-5 ships six mini zombies");
        assertEquals(paths.size() - 6, paths.indexOf(minis.get(0)),
                "the mini zombies start right after everything else");
    }

    @Test
    void allThreePagesAreListedWithTheirCategories() {
        List<AlmanacEntries.Catalogue> all = AlmanacEntries.all();
        assertEquals(3, all.size());
        assertEquals("plant", all.get(0).page().category());
        assertEquals("zombie", all.get(1).page().category());
        assertEquals("resource", all.get(2).page().category());
        for (AlmanacEntries.Catalogue catalogue : all) {
            assertFalse(catalogue.ids().isEmpty(), catalogue.page() + " must not be empty");
            assertNotNull(catalogue.at(0));
            // Wrapping both ways, because the arrows go both ways.
            assertEquals(catalogue.at(0), catalogue.at(catalogue.size()));
            assertEquals(catalogue.at(catalogue.size() - 1), catalogue.at(-1));
        }
    }

    @Test
    void sunReadsFirstAmongResources() {
        assertEquals(com.pvzce.common.PvzceIds.SUN, AlmanacEntries.resources().at(0));
    }
}

package com.pvzce.client.gui.almanac;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The pages the almanac shows, and the order it shows them in.
 *
 * <p>Every entry is a <em>registry id</em>: the almanac has no catalogue of its own, so content a
 * data pack or a mod adds appears in it the moment it loads, and content removed stops appearing.
 * The alternative - a hand-written list of 58 ids - is a second place to forget to edit, and it
 * would go stale the first time someone shipped a plant.
 *
 * <p>What the list does decide is <em>order</em>, and that order is the original's:
 * <ul>
 *   <li>Plants read in almanac order, which is what {@link PlantDef#order()} already holds - the
 *       same number the seed bar and the chooser sort by, so all three agree on "the order the
 *       player met the plants". Plants that declare no order follow the ones that do, and are
 *       ordered by id among themselves.
 *   <li>Zombies read in the original's almanac order too, but nothing in the data carries it:
 *       {@link ZombieDef} has no {@code order}, because before this nothing ever listed zombies in
 *       a designed order - the wave editor sorted them alphabetically. {@link #ZOMBIE_ORDER} is
 *       therefore a code table, and a zombie a mod adds sorts after every zombie in it.
 * </ul>
 *
 * <p>The mini zombies (3-5's half-size bodies) and the content this project added are the only
 * entries not in the original's almanac; the test that pins these lists is what keeps a new plant
 * from silently landing at the bottom of the book.
 */
public final class AlmanacEntries {
    /**
     * The order the zombie page reads in, by path.
     *
     * <p>The first twenty are the original's own almanac numbers, with the numbers the original
     * gives the zombies this project does not have left as comments - so {@code gargantuar} is 24
     * here because it is 24 there, and the gap is visible rather than papered over.
     *
     * <p>The rest are this project's: the two other Ducky Tube varieties (the original folds all
     * four into one page, so they follow it) and the six mini zombies of 3-5, which are ours
     * outright and therefore read last. They are <em>in</em> the table rather than sorted after it
     * so the book's contents are a list someone wrote, not a list plus whatever the alphabet does
     * to the leftovers.
     */
    private static final List<String> ZOMBIE_ORDER = List.of(
            "basic_zombie",                 // 1  Zombie
            "flag_zombie",                  // 2  Flag Zombie
            "conehead_zombie",              // 3  Conehead Zombie
            "pole_vaulter_zombie",          // 4  Pole Vaulting Zombie
            "buckethead_zombie",            // 5  Buckethead Zombie
            "newspaper_zombie",             // 6  Newspaper Zombie
            "door_zombie",                  // 7  Screen Door Zombie
            "football_zombie",              // 8  Football Zombie
            "dancing_zombie",               // 9  Dancing Zombie
            "backup_dancer",                // 10 Backup Dancer
            "ducky_tube_zombie",            // 11 Ducky Tube Zombie
            "snorkel_zombie",               // 12 Snorkel Zombie
            // The original's own numbers are the reading order, and the zombies this project has
            // implemented sit under theirs rather than at the end of the list: a player who knows
            // the original's book expects the sled team to follow the Zomboni.
            "zamboni_zombie",               // 13 Zomboni
            "bobsled_zombie",               // 14 Zombie Bobsled Team
            // 20 Zombie Yeti is not implemented.
            "dolphin_rider_zombie",         // 15 Dolphin Rider Zombie
            "jack_in_the_box_zombie",       // 16 Jack-in-the-Box Zombie
            "balloon_zombie",               // 17 Balloon Zombie
            "miner_zombie",                 // 18 Digger Zombie
            "pogo_zombie",                  // 19 Pogo Zombie
            "bungee_zombie",                // 21 Bungee Zombie
            "ladder",                       // 22 Ladder Zombie
            "catapult",                     // 23 Catapult Zombie
            "gargantuar",                   // 24 Gargantuar
            "imp",                          // 25 Imp
            "zombie_boss",                  // 26 Dr. Zomboss
            // The other three Ducky Tubes, which the original lists inside page 11.
            "ducky_tube_conehead_zombie",
            "ducky_tube_buckethead_zombie",
            // The ZomBotany four, which the original has no page for either: they are a
            // minigame's cast rather than part of the adventure's bestiary.
            "zombotany_pea_zombie",
            "zombotany_wallnut_zombie",
            "zombotany_gatling_zombie",
            "zombotany_jalapeno_zombie"

    );

    /** Which registry a page reads and which language category names its entries. */
    public enum Page {
        PLANTS("plant"),
        ZOMBIES("zombie"),
        RESOURCES("resource");

        private final String category;

        Page(String category) {
            this.category = category;
        }

        /** The {@link com.pvzce.common.core.RegistryCategories} category these entries live in. */
        public String category() {
            return category;
        }

        /** The entity kind a preview of this page's entries is built as, or {@code null}. */
        public String entityKind() {
            return switch (this) {
                case PLANTS -> com.pvzce.api.entity.EntityKind.PLANT;
                case ZOMBIES -> com.pvzce.api.entity.EntityKind.ZOMBIE;
                case RESOURCES -> com.pvzce.api.entity.EntityKind.RESOURCE;
            };
        }

        public String labelKey() {
            return "gui.pvzce.almanac.tab." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One page of the book: what it reads, and every id in the order it is read. */
    public record Catalogue(Page page, List<Identifier> ids) {
        public int size() {
            return ids.size();
        }

        public Identifier at(int index) {
            if (ids.isEmpty()) {
                return null;
            }
            return ids.get(Math.floorMod(index, ids.size()));
        }
    }

    private AlmanacEntries() {
    }

    /** Every page, in the order the index lists them. */
    public static List<Catalogue> all() {
        return List.of(plants(), zombies(), resources());
    }

    public static Catalogue plants() {
        List<Identifier> ids = new ArrayList<>(BuiltInRegistries.PLANTS.keySet());
        ids.sort(Comparator.comparingInt(AlmanacEntries::plantOrder).thenComparing(Identifier::toString));
        return new Catalogue(Page.PLANTS, List.copyOf(ids));
    }

    private static int plantOrder(Identifier id) {
        PlantDef def = BuiltInRegistries.PLANTS.get(id);
        return def == null ? PlantDef.DEFAULT_ORDER : def.order();
    }

    public static Catalogue zombies() {
        List<Identifier> ids = new ArrayList<>(BuiltInRegistries.ZOMBIES.keySet());
        ids.sort(Comparator.comparingInt(AlmanacEntries::zombieOrder).thenComparing(Identifier::toString));
        return new Catalogue(Page.ZOMBIES, List.copyOf(ids));
    }

    private static int zombieOrder(Identifier id) {
        int index = ZOMBIE_ORDER.indexOf(id.path());
        return index < 0 ? ZOMBIE_ORDER.size() : index;
    }

    public static Catalogue resources() {
        List<Identifier> ids = new ArrayList<>(BuiltInRegistries.RESOURCES.keySet());
        ids.sort(Comparator.comparingInt(AlmanacEntries::resourceOrder).thenComparing(Identifier::toString));
        return new Catalogue(Page.RESOURCES, List.copyOf(ids));
    }

    /**
     * Resources read sun first, then the denominations by worth, then the rest by id.
     *
     * <p>Sun is the one every level pays with, so it is the one a reader looks for; the coins
     * follow in the order the wallet counts them.
     */
    private static int resourceOrder(Identifier id) {
        if (com.pvzce.common.PvzceIds.SUN.equals(id)) {
            return -1;
        }
        ResourceDef def = BuiltInRegistries.RESOURCES.get(id);
        if (def != null) {
            return 10_000 - def.defaultValue();
        }
        return 20_000;
    }

    /**
     * A zombie's position in {@link #ZOMBIE_ORDER}, or {@code -1} when it is not in the table.
     *
     * <p>Exposed for the test that pins the table against the shipped registries: a zombie that is
     * not in it still appears in the book (the page reads the registry), but it reads after every
     * zombie that is, which is a decision someone should have made on purpose.
     */
    public static int zombieOrderOf(Identifier id) {
        return id == null ? -1 : ZOMBIE_ORDER.indexOf(id.path());
    }
}

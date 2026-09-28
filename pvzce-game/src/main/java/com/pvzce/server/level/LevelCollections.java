package com.pvzce.server.level;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.tag.TagKey;
import com.pvzce.api.util.Identifier;
import com.pvzce.api.util.LevelGrouping;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PvzceRegistries;
import com.pvzce.common.tag.PvzceTags;

import java.util.ArrayList;
import java.util.List;

/**
 * The level collections: a tag over the level registry, read as one row of the level list.
 *
 * <p>A collection is what the original calls a world - 白天草坪, 夜晚草坪, 白天泳池, 夜晚泳池, 变异,
 * 节奏草坪 - and it is a <em>tag</em> ({@link PvzceTags#COLLECTION_PREFIX}) rather than a registry
 * of its own: "these ten levels are chapter one" is a statement about levels, which is exactly what
 * a tag is for, and a pack adds one by writing a file rather than by asking for a code change.
 *
 * <h2>Three facts come from the members</h2>
 *
 * <p>A collection has no page, no icon and no order of its own, and inventing them would be three
 * more things for an author to keep in step with the levels:
 *
 * <ul>
 *   <li><b>its page</b> is its first member's - {@code yard/adventure/1_1} puts 白天草坪 on the
 *       adventure tab, and a collection whose levels are on two different pages lands on the first
 *       one's rather than inventing a tab of its own;</li>
 *   <li><b>its icon</b> is its first member's, by the same rule the level list draws every level
 *       with ({@link LevelIcons}) - chapter one looks like a day lawn because 1-1 is one;</li>
 *   <li><b>its place in the list</b> is its first member's, so the four chapters come out in the
 *       order 1-1, 2-1, 3-1, 4-1 rather than in the alphabetical order of their own names.</li>
 * </ul>
 *
 * <p>Its display name is not here either: like a category's and a theme's it is a language key,
 * {@code level_collection.<namespace>.<path-without-the-prefix>}, which the client resolves
 * ({@code LevelPage.collectionLabel}).
 *
 * <h2>What the server does with it</h2>
 *
 * <p>Every level inside a collection is still sent to the client - the collection's own screen has
 * to draw its rows, with their locks, their trophies and their saves - but it is marked as living
 * inside one, and the level list's pages leave it out: the box is the way in, and a level that also
 * appeared beside its own box would be the same level twice on one page.
 */
public final class LevelCollections {
    /**
     * One collection, as the level list and the wire carry it.
     *
     * @param id       the tag's own id, which is also the row's id and the key to its name
     * @param theme    the page it is shown on, taken from its first member
     * @param category the page it is shown on, taken from its first member
     * @param icon     its first member's almanac icon
     * @param members  the levels it holds, in the order its file lists them
     */
    public record Collection(Identifier id, Identifier theme, Identifier category, String icon,
                             List<Identifier> members) {
        public Collection {
            members = List.copyOf(members);
        }

        /**
         * The id this collection's rows sort against: its first member's.
         *
         * <p>So that a page shows 白天草坪 before 夜晚草坪 - the chapters are ordered by the levels
         * in them and not by what they are called, which is the same rule the levels themselves are
         * ordered by ({@link LevelGrouping#compareIds}).
         */
        public String sortKey() {
            return members.isEmpty() ? id.toString() : members.get(0).toString();
        }
    }

    private LevelCollections() {
    }

    /**
     * Every collection the loaded packs declare, in the order the level list should walk them.
     *
     * <p>A tag that names no level this pack has is not a collection: an empty box that opens on
     * nothing is worse than no box, and it is what a data pack disabled halfway looks like. A tag
     * over the level registry that is not under {@link PvzceTags#COLLECTION_PREFIX} is not one
     * either - it is some other statement about levels, and reading it as a box would turn every
     * future tag into a page.
     */
    public static List<Collection> build() {
        List<Collection> collections = new ArrayList<>();
        for (Identifier tagId : PvzceTags.LEVELS.tagIds()) {
            if (tagId == null || !tagId.path().startsWith(PvzceTags.COLLECTION_PREFIX)) {
                continue;
            }
            Collection collection = of(tagId);
            if (collection != null) {
                collections.add(collection);
            }
        }
        return List.copyOf(collections);
    }

    /** One collection, or {@code null} when the tag names nothing this pack ships. */
    static Collection of(Identifier tagId) {
        List<Identifier> members = new ArrayList<>();
        for (Identifier member : PvzceTags.LEVELS.orderedIds(
                TagKey.create(PvzceRegistries.LEVELS, tagId))) {
            // A member the pack does not have is dropped rather than kept as a hole: a collection
            // is a list of levels a player can open, and an entry with nothing behind it would be
            // a row that cannot be entered.
            if (member != null && BuiltInRegistries.LEVELS.get(member) != null) {
                members.add(member);
            }
        }
        if (members.isEmpty()) {
            return null;
        }
        Identifier first = members.get(0);
        LevelGrouping.Group group = LevelGrouping.resolve(first,
                new ArrayList<>(BuiltInRegistries.LEVEL_THEMES.keySet()),
                new ArrayList<>(BuiltInRegistries.LEVEL_CATEGORIES.keySet()));
        if (!group.classified()) {
            // A collection of levels that have no page (a hand-written level, an unclassified one)
            // has nowhere to be shown, so it is not shown. The levels stay where they were.
            return null;
        }
        LevelDef firstDef = BuiltInRegistries.LEVELS.get(first);
        return new Collection(tagId, group.theme(), group.category(),
                firstDef == null ? "day" : LevelIcons.of(firstDef), members);
    }

    /**
     * Which collection each level belongs to, as the level list needs it.
     *
     * <p>The inverse of {@link Collection#members}, and it is derived rather than stored so that
     * "which box holds this level" and "what is in this box" cannot disagree - a level listed in
     * two collections belongs to two, and the page leaves it out either way.
     */
    public static java.util.Set<Identifier> collectedLevels(List<Collection> collections) {
        java.util.Set<Identifier> collected = new java.util.LinkedHashSet<>();
        for (Collection collection : collections) {
            collected.addAll(collection.members());
        }
        return collected;
    }
}

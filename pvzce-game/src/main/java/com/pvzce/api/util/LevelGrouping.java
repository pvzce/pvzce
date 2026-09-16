package com.pvzce.api.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The whole rule for which theme/category page a level belongs to.
 *
 * <p>A level's group is read from its <b>id path</b>, never from its file location:
 * {@code data/pvzce/levels/yard/adventure/1_1.json} loads as
 * {@code pvzce:yard/adventure/1_1} and that id is what the rule sees - the file's
 * explicit {@code "id"} wins over the path in the loader, so the two can disagree, and
 * the id is the one that is referenced by saves and other levels.
 *
 * <ul>
 *   <li>{@code theme} = first path segment, {@code category} = second
 *       ({@code yard/adventure/1_1}); any remaining segments are the level's own name
 *       and are not part of the group.
 *   <li>Shorter paths ({@code demo_level}, {@code yard/one_off}) are <b>unclassified</b>:
 *       they keep working, they are listed under the unclassified tab, and nothing
 *       invents a theme for them.
 * </ul>
 *
 * <p>A theme or category that is not registered, or a category the theme does not offer,
 * is treated as unclassified too. That keeps the tab list a single source of truth - a
 * typo cannot produce a phantom tab - and {@link #unknownGroupReason} says what the
 * problem was, for the validator and the editor.
 *
 * <p>Pure functions on purpose: the server, the level list and the editor all have to
 * agree, so there is exactly one implementation and no registry access hidden inside.
 */
public final class LevelGrouping {
    /** The bucket for levels whose id does not name a registered theme + category. */
    public static final Identifier UNCATEGORIZED = Identifier.withDefaultNamespace("uncategorized");

    private LevelGrouping() {
    }

    /**
     * A level's place: which tab, which category inside it, and its own remaining name.
     *
     * @param theme    a registered theme id, or {@link #UNCATEGORIZED}
     * @param category a category the theme offers, or {@link #UNCATEGORIZED}
     * @param name     the id segments after theme/category - what a rename keeps
     * @param classified false when the level fell into the unclassified bucket
     */
    public record Group(Identifier theme, Identifier category, String name, boolean classified) {
        public Group {
            name = name == null ? "" : name;
        }

        public boolean isUncategorized() {
            return !classified;
        }
    }

    /** One page of the select screen: a theme and a category, in tab order. */
    public record Tab(Identifier theme, Identifier category) {
    }

    /**
     * Groups levels into tab pages, in theme order and then category order.
     *
     * <p>Categories come from the ids that actually exist under the theme, ordered by the
     * category definition's {@code order} and then by id. An explicit
     * {@code themeIds} makes a theme with no levels yet show up as an empty page rather
     * than disappearing; pass an empty list to list only what exists.
     */
    public static List<Tab> tabs(List<Identifier> levelIds, List<Identifier> themeIds,
                                 List<Identifier> categoryIds,
                                 Map<Identifier, Integer> themeOrder,
                                 Map<Identifier, Integer> categoryOrder) {
        List<Identifier> themes = new ArrayList<>(themeIds == null ? List.of() : themeIds);
        Map<Identifier, LinkedHashSet<Identifier>> byTheme = new LinkedHashMap<>();
        boolean uncategorized = false;
        for (Identifier id : levelIds == null ? List.<Identifier>of() : levelIds) {
            if (id == null) {
                continue;
            }
            Group group = resolve(id, themeIds, categoryIds);
            if (!group.classified()) {
                uncategorized = true;
                continue;
            }
            if (!themes.contains(group.theme())) {
                themes.add(group.theme());
            }
            byTheme.computeIfAbsent(group.theme(), ignored -> new LinkedHashSet<>()).add(group.category());
        }
        sort(themes, themeOrder);

        List<Tab> tabs = new ArrayList<>();
        for (Identifier theme : themes) {
            List<Identifier> categories = new ArrayList<>(byTheme.getOrDefault(theme, new LinkedHashSet<>()));
            sort(categories, categoryOrder);
            for (Identifier category : categories) {
                tabs.add(new Tab(theme, category));
            }
        }
        if (uncategorized) {
            tabs.add(uncategorizedTab());
        }
        return List.copyOf(tabs);
    }

    /** The unclassified page; always last, and never merged with a real theme. */
    public static Tab uncategorizedTab() {
        return new Tab(UNCATEGORIZED, UNCATEGORIZED);
    }

    /** The theme/category/name a level id names, with validation applied. */
    public static Group resolve(Identifier id, List<Identifier> themeIds, List<Identifier> categoryIds) {
        if (id == null) {
            return unclassified("");
        }
        String[] parts = id.path().split("/");
        if (parts.length < 3) {
            // A bare name (demo_level) or a single segment before the name (yard/x):
            // there is no theme+category pair, so it is not a classified level.
            return unclassified(String.join("/", parts));
        }
        Identifier theme = Identifier.tryParse(id.namespace() + ":" + parts[0]);
        Identifier category = Identifier.tryParse(id.namespace() + ":" + parts[1]);
        if (theme == null || category == null || themeIds == null || !themeIds.contains(theme)
                || categoryIds == null || !categoryIds.contains(category)) {
            return unclassified(joinFrom(parts, 0));
        }
        String name = joinFrom(parts, 2);
        if (name.isEmpty()) {
            return unclassified("");
        }
        return new Group(theme, category, name, true);
    }

    /**
     * The id a level gets from its group and its own name.
     *
     * <p>Inverse of {@link #resolve} for classified levels: {@code (yard, adventure,
     * "1_1") -> pvzce:yard/adventure/1_1}. The unclassified bucket is the one group that
     * does not appear in the id, which is what lets a level be moved out of it again.
     */
    public static Identifier levelId(String namespace, Identifier theme, Identifier category, String name) {
        String ns = namespace == null || namespace.isBlank() ? "pvzce" : namespace;
        String leaf = name == null ? "" : name.trim();
        if (isUncategorized(theme) || isUncategorized(category)) {
            return Identifier.tryParse(ns + ":" + leaf);
        }
        return Identifier.tryParse(ns + ":" + theme.path() + "/" + category.path() + "/" + leaf);
    }

    /** True for the unclassified sentinel, or for a missing group. */
    public static boolean isUncategorized(Identifier group) {
        return group == null || UNCATEGORIZED.equals(group);
    }

    /**
     * Why a level is not in the theme its id names, or {@code null} when it is grouped.
     *
     * <p>For the validator: a level id that spells a theme and a category but lands in the
     * unclassified bucket is almost always a typo or a deleted definition, and the symptom
     * ("my level is not where I put it") points nowhere on its own.
     */
    public static String unknownGroupReason(Identifier id, List<Identifier> themeIds,
                                            List<Identifier> categoryIds) {
        if (id == null) {
            return null;
        }
        String[] parts = id.path().split("/");
        if (parts.length < 3) {
            return null;
        }
        Identifier theme = Identifier.tryParse(id.namespace() + ":" + parts[0]);
        Identifier category = Identifier.tryParse(id.namespace() + ":" + parts[1]);
        if (theme == null || category == null || UNCATEGORIZED.equals(theme) || UNCATEGORIZED.equals(category)) {
            return null;
        }
        if (themeIds == null || !themeIds.contains(theme)) {
            return "no level theme '" + theme + "' is registered";
        }
        if (categoryIds == null || !categoryIds.contains(category)) {
            return "no level category '" + category + "' is registered";
        }
        return null;
    }

    /** The last path segment, used as the suggested name when creating a level. */
    public static String leafName(Identifier id) {
        if (id == null) {
            return "";
        }
        String path = id.path();
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Level ids in the order a person reads them: {@code 1_2} before {@code 1_10}.
     *
     * <p>Plain string order puts {@code 1_10} between {@code 1_1} and {@code 1_2}, because
     * {@code '1'} sorts before {@code '2'} at the third character. Levels are numbered by
     * people, so the number is compared as a number: digit runs are compared by magnitude
     * (leading zeros ignored), everything else character by character.
     *
     * <p>Lives here rather than at each call site because the level list, the tab table
     * and anything else that walks the registry have to agree on the order - a list that
     * reads 1-1, 1-10, 1-2 while the tab next to it was derived in another order is worse
     * than either order alone.
     */
    public static int compareIds(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int endA = i;
                while (endA < a.length() && Character.isDigit(a.charAt(endA))) {
                    endA++;
                }
                int endB = j;
                while (endB < b.length() && Character.isDigit(b.charAt(endB))) {
                    endB++;
                }
                int byNumber = compareDigits(a, i, endA, b, j, endB);
                if (byNumber != 0) {
                    return byNumber;
                }
                i = endA;
                j = endB;
                continue;
            }
            if (ca != cb) {
                return Character.compare(ca, cb);
            }
            i++;
            j++;
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /** {@link #compareIds} as a comparator, for the registry walk and for tests. */
    public static Comparator<String> idOrder() {
        return LevelGrouping::compareIds;
    }

    /**
     * One pair of digit runs by magnitude, then by how they were written.
     *
     * <p>The length after dropping leading zeros is the magnitude (so no overflow on an
     * id with thirty digits), and equal magnitudes fall back to the raw run so
     * {@code "01"} and {@code "1"} still have a stable, total order.
     */
    private static int compareDigits(String a, int startA, int endA, String b, int startB, int endB) {
        int firstA = startA;
        while (firstA < endA && a.charAt(firstA) == '0') {
            firstA++;
        }
        int firstB = startB;
        while (firstB < endB && b.charAt(firstB) == '0') {
            firstB++;
        }
        int lengthA = endA - firstA;
        int lengthB = endB - firstB;
        if (lengthA != lengthB) {
            return Integer.compare(lengthA, lengthB);
        }
        for (int k = 0; k < lengthA; k++) {
            int byDigit = Character.compare(a.charAt(firstA + k), b.charAt(firstB + k));
            if (byDigit != 0) {
                return byDigit;
            }
        }
        return Integer.compare(endA - startA, endB - startB);
    }

    private static Group unclassified(String name) {
        return new Group(UNCATEGORIZED, UNCATEGORIZED, name, false);
    }

    private static String joinFrom(String[] parts, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < parts.length; i++) {
            if (i > start) {
                builder.append('/');
            }
            builder.append(parts[i]);
        }
        return builder.toString();
    }

    /** Stable order: declared order first, then id, so two levels cannot swap tabs. */
    private static void sort(List<Identifier> ids, Map<Identifier, Integer> order) {
        Map<Identifier, Integer> ranks = order == null ? Map.of() : order;
        ids.sort((a, b) -> {
            int byOrder = Integer.compare(ranks.getOrDefault(a, 0), ranks.getOrDefault(b, 0));
            return byOrder != 0 ? byOrder : a.toString().compareTo(b.toString());
        });
    }
}

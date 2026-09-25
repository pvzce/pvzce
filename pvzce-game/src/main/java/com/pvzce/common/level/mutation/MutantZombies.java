package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.tag.PvzceTags;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The zombies and grave designs the mutations that spawn things pick from.
 *
 * <p>Two questions with one answer each, asked by three mutations: "which zombies may a crisis
 * conjure" and "which stone does a grave wear". Both are content - the tag is data so a pack can
 * change the crisis pool without touching code, and the designs are the same four the shipped
 * night lawn uses - so neither belongs inside one mutation's body.
 */
final class MutantZombies {
    /**
     * The grave designs, in the order the night lawn cycles them.
     *
     * <p>A copy of {@code GraveScatter}'s list rather than a second source of truth: it is
     * package-private there, and a mutation that named one design would put a "which grave" answer
     * in two places. Read once, because the registry is frozen long before a level runs.
     */
    private static final List<Identifier> GRAVE_DESIGNS = List.of(
            PvzceIds.id("grave"),
            PvzceIds.id("grave_cross"),
            PvzceIds.id("grave_slab"),
            PvzceIds.id("grave_wide"));

    private MutantZombies() {
    }

    /**
     * The zombies a {@code zombie_crisis} or a {@code whack_a_zombie} may use.
     *
     * <p>Read from {@code #pvzce:mutation_crisis}, and filtered to what this build actually has:
     * a pack that names a zombie it does not ship would otherwise turn a crisis into a stream of
     * nothing, which reads as the mutation being broken rather than as a data mistake.
     */
    static List<Identifier> crisisPool() {
        List<Identifier> pool = new ArrayList<>();
        for (Identifier id : PvzceTags.ZOMBIES.ids(PvzceTags.ZOMBIE_MUTATION_CRISIS)) {
            if (com.pvzce.common.core.BuiltInRegistries.ZOMBIES.get(id) != null) {
                pool.add(id);
            }
        }
        pool.sort(java.util.Comparator.comparing(Identifier::toString));
        return List.copyOf(pool);
    }

    /** One of the four gravestone designs, at random. */
    static Identifier graveDesign(Random random) {
        return GRAVE_DESIGNS.get(random.nextInt(GRAVE_DESIGNS.size()));
    }
}

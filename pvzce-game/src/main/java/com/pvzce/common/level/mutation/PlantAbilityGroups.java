package com.pvzce.common.level.mutation;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups plant cards by what they can do, so a replacement can keep the mix the player chose.
 *
 * <p>The rule the mutation promises: "you picked three shooters, you still have three shooters -
 * they are just different plants now". A group is therefore the whole set of capabilities a plant
 * declares, not one of them: a plant that shoots <em>and</em> produces belongs to the
 * shooter-plus-producer group, and can only be replaced by another plant that does both. That
 * keeps every count the same at once, which is the only reading of "the same as the player
 * originally chose" that survives a plant having more than one job.
 *
 * <p>The shared recipe expands composite abilities into materials. Defence and carrier markers
 * keep passive plants in their own roles; a shell belongs to defence-plus-carrier, not either
 * single-material group.
 *
 * <p>Pure functions over definitions - no level, no player - so the whole rule is testable
 * without running a game.
 */
final class PlantAbilityGroups {
    private PlantAbilityGroups() {
    }

    /**
     * The group key of one plant: its complete material recipe, sorted.
     *
     * <p>A sorted list rather than a set, so the key is comparable and a group can be looked up
     * by value. Two plants that share their capabilities are one group whichever order their
     * files listed them in.
     */
    static List<Identifier> keyOf(PlantDef def) {
        return def == null ? List.of() : com.pvzce.common.core.PlantRecipes.recipe(def);
    }

    /**
     * Every plant card the player owns, grouped by {@link #keyOf}.
     *
     * <p>Plants only: a tool or a resource card is not a plant and has no abilities to keep, so
     * letting one into a group would let a mutation hand the player a shovel where their
     * Peashooter was - and the group would still "match", because neither has capabilities.
     */
    static Map<List<Identifier>, List<Identifier>> ownedPlantsByAbility(
            java.util.function.Predicate<Identifier> ownsCard) {
        Map<List<Identifier>, List<Identifier>> groups = new LinkedHashMap<>();
        for (Identifier cardId : BuiltInRegistries.PLANTS.keySet()) {
            if (!ownsCard.test(cardId)) {
                continue;
            }
            groups.computeIfAbsent(keyOf(BuiltInRegistries.PLANTS.get(cardId)), key -> new ArrayList<>())
                    .add(cardId);
        }
        return groups;
    }

    /**
     * The card ids one card may be replaced by: the owned plants in its own group.
     *
     * <p>Empty when the player owns nothing else with those abilities - which the caller reads as
     * "leave this card alone" rather than "pick something close", because a near miss is exactly
     * the thing the grouping exists to prevent.
     */
    static List<Identifier> replacementsFor(Identifier cardId,
                                            Map<List<Identifier>, List<Identifier>> groups) {
        SlotResolver.ResolvedCard resolved = SlotResolver.resolve(cardId).orElse(null);
        if (resolved == null || resolved.kind() != com.pvzce.common.core.Slot.Kind.PLANT) {
            return List.of();
        }
        List<Identifier> candidates = groups.get(keyOf(BuiltInRegistries.PLANTS.get(resolved.content())));
        return candidates == null ? List.of() : List.copyOf(candidates);
    }
}

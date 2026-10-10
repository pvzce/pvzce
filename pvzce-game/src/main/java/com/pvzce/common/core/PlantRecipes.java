package com.pvzce.common.core;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.PlantCapabilities;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Shared material recipes; behaviour types and parameters still own the actual plant mechanics. */
public final class PlantRecipes {
    private static final Map<Identifier, List<Identifier>> COMPOSITES = Map.of(
            PlantCapabilities.SHELL.id(), List.of(PlantCapabilities.CARRIER.id(), PlantCapabilities.DEFENSE.id()),
            PlantCapabilities.COB_CANNON.id(), List.of(PlantCapabilities.EXPLOSIVE.id(), PlantCapabilities.THROWER.id()));

    private PlantRecipes() { }

    /** Also expands legacy workshop tokens without changing their registered behaviour types. */
    public static List<Identifier> components(Identifier ability) {
        return COMPOSITES.getOrDefault(ability, List.of(ability));
    }

    public static List<Identifier> recipe(PlantDef plant) {
        return plant.resolvedCapabilities().stream().flatMap(entry -> components(entry.type()).stream())
                .sorted(Comparator.comparing(Identifier::toString)).toList();
    }

    public static List<Identifier> candidates(List<Identifier> abilities, Predicate<Identifier> owns) {
        if (abilities.isEmpty()) return List.of();
        List<Identifier> key = abilities.stream().sorted(Comparator.comparing(Identifier::toString)).toList();
        return BuiltInRegistries.PLANTS.keySet().stream().filter(owns)
                .filter(id -> recipe(BuiltInRegistries.PLANTS.get(id)).equals(key))
                .sorted(Comparator.comparing(Identifier::toString)).toList();
    }

    /** Unique complete recipes, so owning more variants does not make a combination more likely. */
    public static List<List<Identifier>> availableRecipes(Map<Identifier, Integer> materials, Predicate<Identifier> owns) {
        return BuiltInRegistries.PLANTS.keySet().stream().filter(owns)
                .sorted(Comparator.comparing(Identifier::toString))
                .map(id -> recipe(BuiltInRegistries.PLANTS.get(id)))
                .filter(recipe -> !recipe.isEmpty() && fits(recipe, materials)).distinct().toList();
    }

    private static boolean fits(List<Identifier> recipe, Map<Identifier, Integer> materials) {
        Map<Identifier, Integer> used = new HashMap<>();
        for (Identifier ability : recipe) {
            if (used.merge(ability, 1, Integer::sum) > materials.getOrDefault(ability, 0)) return false;
        }
        return true;
    }

    public static List<Identifier> abilities() {
        return BuiltInRegistries.PLANT_CAPABILITIES.keySet().stream()
                .flatMap(ability -> components(ability).stream()).distinct()
                .sorted(Comparator.comparing(Identifier::toString)).toList();
    }
}

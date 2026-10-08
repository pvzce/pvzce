package com.pvzce.common.core;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Capability types, including multiplicity; capability parameters are deliberately not a recipe. */
public final class PlantRecipes {
    private PlantRecipes() { }

    public static List<Identifier> recipe(PlantDef plant) {
        return plant.resolvedCapabilities().stream().map(entry -> entry.type())
                .sorted(Comparator.comparing(Identifier::toString)).toList();
    }

    public static List<Identifier> candidates(List<Identifier> abilities, Predicate<Identifier> owns) {
        if (abilities.isEmpty()) return List.of();
        List<Identifier> key = abilities.stream().sorted(Comparator.comparing(Identifier::toString)).toList();
        return BuiltInRegistries.PLANTS.keySet().stream().filter(owns)
                .filter(id -> recipe(BuiltInRegistries.PLANTS.get(id)).equals(key))
                .sorted(Comparator.comparing(Identifier::toString)).toList();
    }

    public static List<Identifier> abilities() {
        return BuiltInRegistries.PLANT_CAPABILITIES.keySet().stream()
                .sorted(Comparator.comparing(Identifier::toString)).toList();
    }
}

package com.pvzce.api.entity;

import com.pvzce.api.util.Identifier;

/**
 * The wire/behaviour kind of an entity. Kept as a small set of constants rather
 * than an enum because data packs and mods resolve the same strings from JSON.
 */
public final class EntityKind {
    public static final String PLANT = "plant";
    public static final String ZOMBIE = "zombie";
    public static final String PROJECTILE = "projectile";
    public static final String RESOURCE = "sun";

    /** Registry name of the definition backing each kind ({@code pvzce:<registry>}). */
    public static Identifier definitionRegistry(String kind) {
        return switch (kind) {
            case PLANT, ZOMBIE, PROJECTILE -> Identifier.withDefaultNamespace(kind);
            default -> Identifier.withDefaultNamespace("resource");
        };
    }

    private EntityKind() {
    }
}

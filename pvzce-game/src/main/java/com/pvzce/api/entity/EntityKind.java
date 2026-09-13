package com.pvzce.api.entity;

import com.pvzce.api.util.Identifier;

/**
 * The wire/behaviour kind of an entity. Kept as a small set of constants rather
 * than an enum because data packs and mods resolve the same strings from JSON.
 *
 * <p>A kind is a <em>category</em> - plant, zombie, projectile, resource - and never names a
 * piece of content. "Which resource is this" is the content id, and the two must not be
 * confused: see {@link #RESOURCE}.
 */
public final class EntityKind {
    public static final String PLANT = "plant";
    public static final String ZOMBIE = "zombie";
    public static final String PROJECTILE = "projectile";
    /**
     * Any resource drop: sun, coin, diamond, energy bean.
     *
     * <p>This used to be the string {@code "sun"} - it named the only drop that existed when
     * the constant was written, and then quietly became a lie. The kind could no longer tell a
     * coin from a sun, so every check that asked "is this the sun?" <em>by kind</em> answered
     * yes for all of them. {@code PvzceClient.applyEntityLights} is where that surfaced: it
     * turns each matching drop into a warm point light, so every coin the bowling nut paid out
     * spawned a pool of yellow light on the lawn as if it were a sun. The fix belongs in the
     * constant, not in the call site - and the call site now asks by content id anyway.
     */
    public static final String RESOURCE = "resource";

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

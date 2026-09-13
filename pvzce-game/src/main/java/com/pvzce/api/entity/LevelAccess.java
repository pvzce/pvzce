package com.pvzce.api.entity;

import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.util.Identifier;
import com.pvzce.server.Team;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.List;
import java.util.Random;

/**
 * The slice of a running level that server-side capabilities are allowed to
 * touch.
 *
 * <p>Capabilities, AI and commands are server-authoritative behaviour; this
 * interface is the single documented bridge between them and the level
 * implementation. {@code LevelServer} is the only production implementation, so
 * keeping the surface small means content cannot accidentally depend on level
 * internals.
 */
public interface LevelAccess {
    int width();

    int height();

    int tickCount();

    /** The level's own {@link Random}; capabilities must never allocate their own. */
    Random random();

    /** Read-only view of the level's game-rule table. */
    GameRuleAccess rules();

    List<ZombieEntity> zombiesInRow(int row);

    List<PlantEntity> plantsAt(int column, int row);

    /** The topmost plant in a cell (what zombies bite and the shovel removes first). */
    PlantEntity plantAt(int column, int row);

    /** Read-only view of the scene element in a cell; {@code null} when out of bounds. */
    SceneElementAccess sceneAt(int column, int row);

    void spawnProjectile(ProjectileRef ref, float x, float y, PlantEntity source);

    void spawnArcProjectile(ProjectileRef ref, float x, float y, PlantEntity source, ZombieEntity target);

    void spawnResource(Identifier resourceId, int amount, float x, float y, Team team);

    /**
     * Spawns a resource that came out of a plant.
     *
     * <p>Same as {@link #spawnResource} except for how the drop arrives: a harvested
     * sun pops out of the flower that made it and settles back, rather than falling in
     * from above the lawn. The distinction belongs here rather than in the resource
     * definition because the same sun is both - the sky drops one and a sunflower
     * makes one.
     */
    void spawnProducedResource(Identifier resourceId, int amount, float x, float y, Team team);

    void spawnZombie(Identifier zombieId, Team team, float x, int row);

    void damageArea(float centerX, float centerY, float radius, int damage, Team sourceTeam);

    void emitEffect(String particle, float x, float y, Identifier sound);

    /**
     * Disturbs a liquid surface at a world position.
     *
     * <p>Ripples are presentation only: the server decides that something happened
     * and the client animates it, exactly like {@link #emitEffect}. A capability or
     * an entity that enters water calls this rather than touching the renderer,
     * which keeps the simulated world free of any rendering dependency.
     *
     * @param liquid   content id of the liquid to disturb, e.g. {@code pvzce:water};
     *                 an unknown or empty id means "no ripple"
     * @param strength 0..1, how hard the surface is disturbed
     */
    void emitRipple(Identifier liquid, float x, float y, float strength);

    /** Disturbs whatever liquid is at a cell, if any. */
    void emitRippleAt(int cellX, int cellY, float strength);

    void addEntity(Entity entity);

    /** A zombie walked off the left edge and stayed there long enough to lose the level. */
    void zombieReachedLeft(ZombieEntity zombie);

    /**
     * A zombie's body reached zero health and it was removed.
     *
     * <p>The level, not the zombie, decides what a death is worth: the coin drop is
     * level data ({@code rewards.coin_drop_chance}), and an entity has no business
     * reading the level's reward block. Mirrors {@link #zombieReachedLeft}, the
     * other "the simulation did something the level may care about" callback.
     */
    void zombieDied(ZombieEntity zombie);

    /**
     * Drops {@code count} of the level's own coins at a world position.
     *
     * <p>Which coin that is comes from the level's reward block
     * ({@code rewards.coin_drop}), so a capability can pay out for something the level
     * considers worth paying for - a bowling ricochet, say - without knowing the
     * currency. Mirrors {@link #zombieDied}: the level decides what an event is worth,
     * the ability only reports that it happened.
     */
    void dropCoin(float x, float y, int count);

    /**
     * Read-only view of a scene element used by placement and movement checks.
     *
     * <p>There is no {@code accepts(feet)} here any more: what a plant may be
     * planted on is answered by tags ({@code #c:water}, {@code #c:plantable}, ...)
     * through {@code PlantPlacement}, not by asking the element about a stack
     * class string.
     */
    interface SceneElementAccess {
        Identifier id();

        String surfaceClass();

        float heightAt(float worldX, int levelWidth);
    }

    /** Read-only view of the level's rule table. */
    interface GameRuleAccess {
        int getInt(Identifier id);

        float getFloat(Identifier id);

        boolean getBoolean(Identifier id);
    }
}

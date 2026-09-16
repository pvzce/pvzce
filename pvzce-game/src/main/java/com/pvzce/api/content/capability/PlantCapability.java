package com.pvzce.api.content.capability;

import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;

/**
 * One composable plant behaviour (shooting, producing, exploding, ...).
 *
 * <p>A plant definition declares a list of capabilities; a plant entity
 * instantiates one copy of each so per-plant cooldowns live in the capability
 * instead of in a growing pile of fields on the entity. Capabilities that only
 * hold configuration can leave {@link #instantiate()} alone and are shared
 * between every plant of that type.
 */
public interface PlantCapability {
    /**
     * Creates the per-plant instance. Stateful capabilities must return a fresh
     * copy with reset timers; stateless ones keep the default (shared) instance.
     */
    default PlantCapability instantiate() {
        return this;
    }

    /** Called once after the plant is committed to the field. */
    default void onPlaced(PlantEntity plant, LevelAccess level) {
    }

    /** Called once per server tick while the plant is alive. */
    default void tick(PlantEntity plant, LevelAccess level) {
    }

    /** Called when the plant leaves the field for any reason. */
    default void onRemoved(PlantEntity plant, LevelAccess level) {
    }

    /**
     * Wakes this plant if it is asleep, and reports whether that did anything.
     *
     * <p>The coffee bean's whole effect. Capabilities that sleep answer here; every
     * other capability ignores it, so "put a coffee bean on a sunflower" is a wasted
     * item rather than a special case in the caller - which is what the original does.
     *
     * @return {@code true} when this capability was asleep and is now awake
     */
    default boolean wake(PlantEntity plant) {
        return false;
    }

    /**
     * Whether this plant is asleep right now and therefore not acting.
     *
     * <p>Asked by {@code PlantEntity} before it ticks anything: a sleeping plant skips
     * every capability that does not opt in through {@link #ticksWhileAsleep}, so the
     * shooter, producer and fuse of a nocturnal mushroom all stop together instead of
     * each remembering to check. Derived from the level's clock rather than stored, so
     * "night fell" and "the clock was rolled back" both work without bookkeeping.
     */
    default boolean asleep(PlantEntity plant, LevelAccess level) {
        return false;
    }

    /**
     * Whether this capability still wants ticks while the plant is asleep.
     *
     * <p>Only the capability that does the sleeping answers yes - it is the one that
     * publishes the {@code sleep} animation. Everything that would make the plant act
     * stays false, which is what "asleep" means.
     */
    default boolean ticksWhileAsleep(PlantEntity plant) {
        return false;
    }

    /**
     * Whether placement of this plant is immediately consumed (coffee bean).
     * Such plants are removed right after {@link #onPlaced}.
     */
    default boolean consumesOnPlace() {
        return false;
    }

    /**
     * Whether this plant is furniture of its cell.
     *
     * <p>Almost every plant is: a zombie eats the one it stands on and a shovel removes
     * it. A plant that leaves its cell - a bowling Wall-nut, which rolls off the moment it
     * is placed - is not, and answering {@code false} is how a capability says so without
     * a new entity type or a special case in the zombie's eat loop. The plant is still
     * simulated, drawn and damageable; it just stops being "the plant in this cell".
     */
    default boolean occupiesCell(PlantEntity plant) {
        return true;
    }

    /**
     * Whether this capability makes the plant immune to damage right now.
     *
     * <p>The ash line's answer: a cherry bomb has 100 health and a zombie's bite is 100,
     * so without this a single bite cancels the plant the player just paid 150 sun for -
     * and the original does not allow that. Zombies still <em>bite</em> it (the chomp
     * sound, the eat animation and the zombie standing still all still happen); nothing
     * comes of it. That distinction is why this is not {@link #occupiesCell}: the plant
     * is still furniture of its cell, it just cannot be worn down.
     *
     * <p>Any capability saying yes is enough, and it is asked on every hit rather than
     * cached, because it is a temporary state - an unexploded bomb is immune until its
     * fuse runs out and an ordinary plant is never immune at all.
     */
    default boolean invulnerable(PlantEntity plant) {
        return false;
    }

    /** Persists per-plant state; the caller stores it under this capability's type id. */
    default void save(CompoundTag tag) {
    }

    /** Restores state written by {@link #save}. */
    default void load(CompoundTag tag) {
    }
}

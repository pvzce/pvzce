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
     * Instant activation from an energy bean, a coffee bean or the glove tool.
     * Capabilities that have nothing to charge ignore it, so boosting a wall-nut
     * is a no-op instead of a special case in the caller.
     */
    default void boost(PlantEntity plant) {
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

    /** Persists per-plant state; the caller stores it under this capability's type id. */
    default void save(CompoundTag tag) {
    }

    /** Restores state written by {@link #save}. */
    default void load(CompoundTag tag) {
    }
}

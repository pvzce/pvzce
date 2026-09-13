package com.pvzce.api.content.mechanic;

import com.pvzce.api.util.Identifier;

/**
 * One mechanic in a level's {@code "mechanics"} list: the block plus the registry id it
 * was decoded from.
 *
 * <p>Shaped exactly like {@code TypedCapability}: the id is carried <em>beside</em> the
 * value rather than inside it, so the JSON stays flat
 * ({@code {"type": "pvzce:conveyor", "capacity": 6}}) and the registry remains the single
 * source of truth for what a type id means. Well-known ids live in
 * {@code com.pvzce.common.PvzceIds} ({@code MECHANIC_CONVEYOR} and friends).
 */
public record TypedMechanic(Identifier type, MechanicData value) {
    public TypedMechanic {
        if (type == null || value == null) {
            throw new IllegalArgumentException("A mechanic needs both a type and a value");
        }
    }

    public static TypedMechanic of(Identifier type, MechanicData value) {
        return new TypedMechanic(type, value);
    }

    /** True when this is the mechanic with that id. */
    public boolean is(Identifier id) {
        return type.equals(id);
    }
}

package com.pvzce.api.content.mechanic;

/**
 * Marker for a level mechanic's own data block.
 *
 * <p>A mechanic is an opt-in piece of behaviour a level declares in its
 * {@code "mechanics"} list (a conveyor belt, a restricted plantable area, and later
 * things like lawn mowers or a custom loss condition). Its JSON block decodes into a
 * record that implements this interface, so {@link TypedMechanic} can hold the block
 * and its type id together without the level definition knowing any mechanic by name.
 *
 * <p>The interface is deliberately empty: the behaviour lives in
 * {@code com.pvzce.common.level.mechanic.LevelMechanic}, which references server types
 * and therefore cannot live in {@code api}. Splitting the marker out keeps the level
 * definition's codec free of that dependency.
 */
public interface MechanicData {
    /** A mechanic that has no data of its own: it is a switch, not a configuration. */
    record Empty() implements MechanicData {
        public static final Empty INSTANCE = new Empty();
    }
}

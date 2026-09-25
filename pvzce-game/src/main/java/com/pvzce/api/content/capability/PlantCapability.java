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

    /**
     * The art variant this capability puts the plant in, or {@code ""} for the plain one.
     *
     * <p>A plant that grows swaps one set of clips for another - the sun-shroom's small
     * `idle`/`sleep` become `idle_big`/`sleep_big` - and every capability that publishes an
     * animation has to agree on which set is in play. Rather than each one asking the
     * growing capability (or, worse, keeping its own copy of "am I grown"), the state names
     * go through {@code PlantEntity#setState}, which appends the suffix its capabilities
     * report. `sleep` therefore becomes `sleep_big` without the nocturnal capability
     * knowing that growth exists, and a capability that grows only has to answer here.
     *
     * <p>The suffix is a clip-name suffix, not a separate field on the wire: the client is
     * sent the finished state name and looks up the clip it does not have a definition for.
     */
    default String variantSuffix(PlantEntity plant) {
        return "";
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
     * Waters this plant, and reports whether that did anything.
     *
     * <p>The watering can's whole effect, and deliberately shaped like {@link #wake}: a plant
     * whose behaviour is about growth answers here, every other plant ignores it, so pouring
     * water on a wall-nut is a wasted click rather than a special case in the caller.
     *
     * <p>"Did anything" is what the feedback keys on - a plant that ripened plays its growth
     * performance, one that was merely thirsty just gets wet - and it is why the answer is a
     * boolean rather than a side effect nobody can see.
     *
     * @return {@code true} when this capability used the water for something
     */
    default boolean water(PlantEntity plant, LevelAccess level) {
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
     *
     * <p>Removed, but not necessarily at once: a consumed plant that declares a
     * {@link #vanishAnimation} stays on the field for it first, which is how the coffee bean
     * gets to crumble instead of blinking out of existence on the tick it was planted.
     */
    default boolean consumesOnPlace() {
        return false;
    }

    /**
     * The animation this plant plays while it is being consumed, or empty for "no animation, it
     * is simply gone".
     *
     * <p>The coffee bean's {@code crumble}: a plant that a placement spends has one moment to
     * say what happened, and the original drew it as the bean shaking itself apart. A plant
     * that leaves no art behind - a mushroom a zombie finished eating, anything the shovel
     * takes - declares nothing and keeps the instant removal it always had.
     *
     * <p>While the clip plays the plant is <em>no longer in its cell</em>: {@code occupiesCell}
     * answers false, so nothing waits for the drawing. Another plant may be placed on the same
     * cell, a zombie walks over it, and the shovel cannot reach it. Gameplay is over the moment
     * the plant is consumed; this is the animation catching up with it.
     */
    default java.util.Optional<VanishAnimation> vanishAnimation() {
        return java.util.Optional.empty();
    }

    /**
     * What a consumed plant plays, and for how long it stays on the board.
     *
     * @param clip  the animation clip to play; a name the plant's own art has to define
     * @param ticks how long the plant stays before it is taken off the field.
     *              <strong>Ticks, authored, not derived from the clip:</strong> the server does
     *              not read animation files - that is the client's layer, and not depending on
     *              it is what keeps a level simulable without a renderer. An author who wants
     *              the whole clip reads its {@code animation_length} out of the model they just
     *              wrote. If the two ever disagree the simulation is right and the art is cut
     *              short, which is the safe direction to be wrong in.
     */
    record VanishAnimation(String clip, int ticks) {
        public VanishAnimation {
            ticks = Math.max(1, ticks);
        }
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

    /**
     * Called when a zombie bites this plant, before the damage lands.
     *
     * <p>The hypno-shroom's whole trigger. It is a hook rather than a scan of the lane because
     * the original's rule is "the zombie that <em>eats</em> it", and that is a fact only the
     * bite knows: which zombie, on which tick, and whether the plant is still there to be eaten.
     *
     * <p>Returning {@code false} means "nothing happened", and a plant whose capability says so
     * is not consumed by the bite - a resting mushroom is still a mushroom the zombie has to
     * chew through.
     *
     * @return true when the bite did something other than damage (it charmed the biter)
     */
    default boolean onBittenBy(PlantEntity plant, com.pvzce.server.entity.ZombieEntity zombie,
                              LevelAccess level) {
        return false;
    }

    /** Persists per-plant state; the caller stores it under this capability's type id. */
    default void save(CompoundTag tag) {
    }

    /** Restores state written by {@link #save}. */
    default void load(CompoundTag tag) {
    }
}

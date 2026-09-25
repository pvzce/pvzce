package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.server.entity.ZombieEntity;

/**
 * Swims with only its head above water, and surfaces to eat (the snorkel zombie).
 *
 * <p>What the original does with it is entirely a question of what the player can hit, so
 * that is what this capability answers:
 *
 * <ul>
 *   <li>while it is in water and nothing is in its way it is <b>submerged</b>: it publishes
 *       {@code swim}, and {@link #canBeHitByGround} is false - a pea flies over the place its
 *       body is not. That is the same hook the balloon uses in the air, which is why a straight
 *       shot misses one without any projectile-side special case;</li>
 *   <li>the moment it reaches a plant it is an ordinary biting zombie standing up to its knees
 *       in the pool, and every shot lands. That is the whole counter-play: kill it on the way in
 *       with something that does not need a straight line - a squash, a cherry bomb, a tangle
 *       kelp, or just let it come to the lily pad;</li>
 *   <li>on dry land it is a plain walker, so a level that puts one on the lawn does not have to
 *       know it was meant for water.</li>
 * </ul>
 *
 * <p>The art does the rest: the {@code swim} clip in {@code Zombie_snorkle.reanim} draws only
 * the head-at-the-waterline sprite and its wake, so "submerged" needs no height offset and no
 * second body - the head is authored where the surface is.
 *
 * <p>{@code can_swim} on the definition is still what keeps it from drowning; this capability
 * only decides whether it is worth shooting at.
 */
public final class SubmergeCapability implements ZombieCapability {
    /** The state a submerged snorkel publishes; its own clip in the reanim. */
    public static final String SWIM_STATE = "swim";

    /**
     * How much slower it moves while submerged, as a multiplier.
     *
     * <p>The original's snorkel zombie is the slow one of the pool: it swims at about seven
     * tenths of its walking pace and only moves at full speed once it has something to eat. The
     * number is a data field rather than a constant so a pack can have a fast fish.
     */
    public static final float DEFAULT_SUBMERGED_SPEED = 0.7F;

    private final float submergedSpeedMultiplier;

    /** Whether the zombie was under the surface on the last tick. */
    private boolean submerged;

    public SubmergeCapability(float submergedSpeedMultiplier) {
        this.submergedSpeedMultiplier = Math.max(0.05F, submergedSpeedMultiplier);
    }

    public static final MapCodec<SubmergeCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("submerged_speed", DEFAULT_SUBMERGED_SPEED)
                    .forGetter(SubmergeCapability::submergedSpeedMultiplier)
    ).apply(i, SubmergeCapability::new));

    public float submergedSpeedMultiplier() {
        return submergedSpeedMultiplier;
    }

    /** True while the last tick found it under water with nothing to bite. */
    public boolean isSubmerged() {
        return submerged;
    }

    @Override
    public ZombieCapability instantiate() {
        return new SubmergeCapability(submergedSpeedMultiplier);
    }

    @Override
    public void tick(ZombieEntity zombie, LevelAccess level) {
        // Asked before movement, and answered from the same two facts the walk loop is about to
        // use: the cell it stands in, and whether anything is there to eat. A plant in the cell
        // means it is biting - which is exactly when the original's snorkel stands up.
        submerged = zombie.isGrounded() && inWater(zombie, level) && !blocked(zombie, level);
    }

    @Override
    public String walkState(ZombieEntity zombie) {
        return submerged ? SWIM_STATE : null;
    }

    @Override
    public float speedMultiplier(ZombieEntity zombie) {
        return submerged ? submergedSpeedMultiplier : 1F;
    }

    @Override
    public boolean canBeHitByGround(ZombieEntity zombie) {
        return !submerged;
    }

    /**
     * True when the zombie's cell is water.
     *
     * <p>Asked of the tag rather than of the surface class string, so a pack's own water tile
     * (swamp, aquarium) submerges a snorkel without a code change - the same rule drowning uses.
     */
    private static boolean inWater(ZombieEntity zombie, LevelAccess level) {
        var scene = level.sceneAt(zombie.gridX(), zombie.gridY());
        return scene != null && scene.id() != null
                && PvzceTags.SCENE_ELEMENTS.contains(PvzceTags.SCENE_WATER, scene.id());
    }

    /** True when something is in the way, so the zombie is eating rather than swimming. */
    private static boolean blocked(ZombieEntity zombie, LevelAccess level) {
        var plant = level.plantAt(zombie.gridX(), zombie.gridY());
        return plant != null && !plant.isRemoved();
    }
}

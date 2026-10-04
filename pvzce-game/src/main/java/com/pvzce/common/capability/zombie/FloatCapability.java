package com.pvzce.common.capability.zombie;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.server.entity.ZombieEntity;

/**
 * Floats in a water cell, paddling instead of walking: the ducky-tube zombie's water gait.
 *
 * <p>Nothing about this changes what the player can do - the zombie is hit, moves and bites
 * exactly like the body it borrowed - so it is not a rule, it is a <em>pose</em>: the only
 * thing it decides is which clip the walk loop publishes. The original switches to
 * {@code anim_swim} in code when a floatie zombie is in a pool, and that is the whole
 * difference between a ducky-tube zombie crossing the water and one that looks like it is
 * walking on top of it:
 *
 * <ul>
 *   <li>{@code anim_swim} hides the legs (they are under the waterline in the original's own
 *       drawing, and the engine has no water-over-body pass to cover them),</li>
 *   <li>swaps in the tube's <em>in-water</em> drawing - the tube track changes image per frame,
 *       so the art already carries both, and only the frame range decides which one shows,</li>
 *   <li>and adds the whitewater wake at the waterline.</li>
 * </ul>
 *
 * <p>Eating is deliberately <em>not</em> covered: a ducky-tube zombie chewing a lily pad plays
 * its {@code eat} clip, which is the land gait with the legs drawn - exactly what the original
 * plays, because a zombie that has stopped to eat is standing, not paddling.
 *
 * <p>{@code can_swim} on the definition is still what keeps it from drowning; this capability
 * only picks the clip. A definition that carries it must therefore have a {@code swim} clip in
 * its animation file: an unknown state falls back to {@code idle}, which would stand a floating
 * zombie still in the middle of the pool - {@code PoolAreaLevelsTest} pins that pairing.
 */
public final class FloatCapability implements ZombieCapability {
    public static final FloatCapability INSTANCE = new FloatCapability();

    public static final MapCodec<FloatCapability> CODEC = MapCodec.unit(INSTANCE);

    /** Whether the last tick found the zombie floating on a water cell. */
    private boolean floating;

    public boolean isFloating() {
        return floating;
    }

    @Override
    public ZombieCapability instantiate() {
        return new FloatCapability();
    }

    @Override
    public void tick(ZombieEntity zombie, LevelAccess level) {
        // Asked before movement, from the same fact the walk loop is about to use: the cell it
        // stands in. A grounded zombie in a water cell floats; one on the lawn walks.
        floating = zombie.isGrounded() && inWater(zombie, level);
    }

    @Override
    public String walkState(ZombieEntity zombie) {
        return floating ? SubmergeCapability.SWIM_STATE : null;
    }

    /**
     * True when the zombie's cell is water.
     *
     * <p>Asked of the tag rather than of the surface class string, so a pack's own water tile
     * (swamp, aquarium) floats a floatie without a code change - the same rule drowning and
     * submerging use.
     */
    private static boolean inWater(ZombieEntity zombie, LevelAccess level) {
        var scene = level.sceneAt(zombie.gridX(), zombie.gridY(), zombie.surfaceId());
        return scene != null && scene.id() != null
                && PvzceTags.SCENE_ELEMENTS.contains(PvzceTags.SCENE_WATER, scene.id());
    }
}

package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.server.entity.PlantEntity;

/**
 * A shell around whatever is planted in the same cell (the pumpkin).
 *
 * <p>The lowest-effort capability in the game, and deliberately so: a pumpkin adds <em>nothing</em>
 * to the simulation. It is a plant with a lot of health, standing in the same cell as another
 * plant, drawn in front of it - and every rule that makes it work is a rule that already existed:
 *
 * <ul>
 *   <li><b>It takes the bites.</b> {@code LevelServer.plantAt} returns the topmost plant that
 *       occupies the cell, which is the shell, so a zombie eats the shell first and only reaches
 *       what is inside once the shell is gone. No branch anywhere.</li>
 *   <li><b>It coexists with what is inside.</b> {@code PlacementDef}'s {@code group} is the
 *       mutual-exclusion rule, and the pumpkin declares a group of its own - so "one ordinary
 *       plant per cell" and "one shell per cell" are two independent statements, and a pumpkin
 *       plus a peashooter is legal while a pumpkin plus a pumpkin is not.</li>
 *   <li><b>It is drawn around the plant.</b> It sits one layer above the ground, so the ordinary
 *       bottom-first sort puts it in front.</li>
 * </ul>
 *
 * <p>So this class exists for one reason: to be <em>named</em>. A plant whose behaviour is entirely
 * emergent from the placement rules still needs an entry in its definition that says which kind of
 * thing it is, and a capability with no hooks is the honest way to say "this plant's behaviour is
 * its placement". A tag would have worked too, but tags are for rules that code reads (the
 * {@code #c:walk_over} one is), and nothing reads this.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It does not protect against a blast, a mower or a Gargantuar's hammer: those are the same
 * "the whole cell is gone" events they are for every other plant, and a shell that survived a
 * hammer would make the hammer a different mechanic. {@code health} is the whole of its
 * protection.
 */
public final class ShellCapability implements PlantCapability {
    /**
     * How much of the damage the shell absorbs before anything reaches the plant inside.
     *
     * <p>Not a number this class uses - it is a note about the definition, kept here so the two
     * live together: a shell is nothing but health, so {@code PlantDef.health} is the whole of its
     * behaviour and there is no second place to look.
     */
    public static final int DEFAULT_HEALTH = 4000;

    public static final MapCodec<ShellCapability> CODEC = MapCodec.unit(new ShellCapability());

    public static final Codec<ShellCapability> UNIT = CODEC.codec();

    @Override
    public PlantCapability instantiate() {
        // Stateless: one shared instance is enough, and `instantiate` is what lets the level know
        // that no per-plant copy is needed.
        return this;
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        // Nothing to do. The pumpkin has no clock, no target and no state; see the class doc.
    }
}

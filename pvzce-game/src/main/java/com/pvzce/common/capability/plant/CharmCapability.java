package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.Optional;

/**
 * The hypno-shroom: the zombie that eats it changes sides.
 *
 * <p>One rule, and the rest of the game already agrees with it. Moving the zombie to the plants'
 * {@code Team} is the whole effect: every attack in the game decides what to hit by
 * {@code "is this entity an enemy"}, which is a question about teams
 * ({@code LevelServer.isEnemyOf}), so a charmed zombie is shot at by nothing, is hit by the
 * peas beside it as a friend, and - because a zombie's own walk loop asks whether it is charmed -
 * walks the lane biting the zombies it used to belong to. There is no "charmed" branch anywhere
 * in the simulation except the walk loop, which has to know, because walking toward the house
 * and eating plants is what a zombie <em>is</em>.
 *
 * <p><strong>What it does not do</strong>, and why each is deliberate:
 *
 * <ul>
 *   <li>It does not restore the zombie on death. A charmed zombie dies as a plant-side entity,
 *       so no coin drops from it - which is the price of the charm, exactly as in the
 *       original.</li>
 *   <li>It does not clear the zombie's armour. A charmed Buckethead keeps its bucket: the
 *       original's does too, and the bucket is what makes a charmed one worth having.</li>
 *   <li>It does not clear the zombie's statuses or its speed boost, except the one status that
 *       would make it useless: a slowed charmed zombie is still slowed, and the slow wears off
 *       on its own.</li>
 * </ul>
 *
 * <p>One zombie per plant, and then the mushroom is spent for {@link #DEFAULT_COOLDOWN_TICKS}:
 * a hypno-shroom that could charm a whole row for one card would not be the original's card.
 */
public final class CharmCapability implements PlantCapability {
    /**
     * How long the mushroom rests after charming one zombie, in ticks.
     *
     * <p>Eleven seconds, the original's own recharge for the plant: long enough that the card
     * is a decision about <em>which</em> zombie, short enough that it is not a one-shot.
     */
    public static final int DEFAULT_COOLDOWN_TICKS = 660;

    private final int cooldownTicks;
    private final Optional<Identifier> sound;

    /** Ticks left before it may charm again; zero when it is ready. */
    private int cooldown;

    public CharmCapability(int cooldownTicks, Optional<Identifier> sound) {
        this.cooldownTicks = Math.max(1, cooldownTicks);
        this.sound = sound;
    }

    public static final MapCodec<CharmCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("cooldown_ticks", DEFAULT_COOLDOWN_TICKS)
                    .forGetter(CharmCapability::cooldownTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(CharmCapability::sound)
    ).apply(i, CharmCapability::new));

    public int cooldownTicks() {
        return cooldownTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    /** True while it is resting and will not charm anything that bites it. */
    public boolean resting() {
        return cooldown > 0;
    }

    @Override
    public PlantCapability instantiate() {
        return new CharmCapability(cooldownTicks, sound);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (cooldown > 0) {
            cooldown--;
        }
    }

    /**
     * Turns the zombie that just bit this plant.
     *
     * <p>Driven by the zombie's own bite rather than by a scan of the lane: the original's
     * hypno-shroom charms the zombie that <em>eats</em> it, which is what makes placing it in
     * front of a Buckethead a plan and putting it in front of a cone a waste. The plant reports
     * the hit through the same {@code damageFrom} every plant uses, so this is one override
     * rather than a second collision path.
     */
    @Override
    public boolean onBittenBy(PlantEntity plant, ZombieEntity zombie, LevelAccess level) {
        if (cooldown > 0 || zombie == null || zombie.isRemoved() || !zombie.isAlive()) {
            return false;
        }
        if (!(level instanceof LevelServer server)) {
            return false;
        }
        // The zombies' own team, from the level rather than from a constant: a level that
        // renamed its sides still has exactly one "the side zombies spawn on".
        var plantTeam = plant.team();
        if (plantTeam == null || zombie.team() == null) {
            return false;
        }
        if (plantTeam.id().equals(zombie.team().id())) {
            // Already one of ours (a zombie a second mushroom charmed, or a mod's own). Nothing
            // happens, and - importantly - nothing is spent: the mushroom is still ready.
            return false;
        }
        cooldown = cooldownTicks;
        zombie.setTeam(plantTeam);
        // The mushroom on its head, and the original's sound. The tint the client draws comes
        // from the team change, which the entity update carries.
        level.emitEffect(PvzceParticles.LANTERN_SHINE.toString(), zombie.cellX(), zombie.cellY(),
                sound.orElseGet(() -> PvzceSounds.PLANT_HYPNO_FLOOP));
        // The state the client has to hear about this tick, not on whichever third tick comes
        // next: the tint is the only thing that says the bite worked.
        server.requestEntitySync();
        // The bite still happened: the zombie ate the mushroom, and the mushroom is gone. That
        // is the original's trade - the charm costs the plant.
        return true;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("cooldown", cooldown);
    }

    @Override
    public void load(CompoundTag tag) {
        cooldown = Math.max(0, tag.getInt("cooldown"));
    }
}

package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;

import java.util.List;
import java.util.Optional;

/**
 * The gold magnet: picks coins up off the lawn before the player has to click them.
 *
 * <p>The original's gold magnet is not a magnet at all - it does not pull armour off anything. It
 * is the magnet-shroom re-aimed at money: sun and coins within its reach are collected on their
 * own, which is the whole reason to buy one, because a lawn at the end of a wave is a field of
 * coins the player has to sweep the cursor over while zombies are still walking.
 *
 * <h2>Why it collects through the level rather than by hand</h2>
 *
 * <p>The alternative was a second copy of "credit this drop" here, and that copy would have missed
 * the level's own rules. {@code LevelServer.collectResource} already answers all of them - is the
 * resource collectible, is it unlocked in this level, does the player hold its card - and the
 * {@code auto} flag exists precisely for a collector that should stay quiet when it refuses (the
 * auto-pickup buff uses the same door). So this capability finds the drop and asks the level to
 * collect it, and the rules stay in one place.
 *
 * <p>Coins are {@code collectible_without_card}, so the card gate never fires for money; sun still
 * needs its card, exactly as it does when the player clicks it. A gold magnet is therefore "no
 * more clicking", not "free sun".
 */
public final class GoldMagnetCapability implements PlantCapability {
    /** How far it reaches, in cells. A little shorter than the magnet-shroom's armour pull. */
    public static final float DEFAULT_RANGE = 4.0F;
    /** How often it sweeps, in ticks. Often enough that a coin never expires under it. */
    public static final int DEFAULT_INTERVAL_TICKS = 30;

    private final float range;
    private final int intervalTicks;
    private final Optional<Identifier> sound;

    private int cooldown;
    private final com.pvzce.common.level.RateClock weatherClock = new com.pvzce.common.level.RateClock();
    private boolean pulling;

    public GoldMagnetCapability(float range, int intervalTicks, Optional<Identifier> sound) {
        this.range = Math.max(0.5F, range);
        this.intervalTicks = Math.max(1, intervalTicks);
        this.sound = sound;
    }

    public static final MapCodec<GoldMagnetCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("range", DEFAULT_RANGE)
                    .forGetter(GoldMagnetCapability::range),
            Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL_TICKS)
                    .forGetter(GoldMagnetCapability::intervalTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(GoldMagnetCapability::sound)
    ).apply(i, GoldMagnetCapability::new));

    public float range() {
        return range;
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public PlantCapability instantiate() {
        return new GoldMagnetCapability(range, intervalTicks, sound);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        pulling = false;
        if (cooldown > 0) {
            cooldown = Math.max(0, cooldown - weatherClock.step(level.weatherActionMultiplier(plant)));
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        if (!(level instanceof LevelServer server)) {
            // The one implementation there is; a second one would have to answer "collect this
            // drop" itself, which is the rule this capability deliberately does not own.
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        List<com.pvzce.server.entity.ResourceDropEntity> inReach = server.resourceDropsInReach(
                plant.cellX(), plant.cellY(), range * level.weatherRangeMultiplier(plant));
        if (inReach.isEmpty()) {
            // Nothing to pick up. The clock is *not* reset: a coin that lands the moment after a
            // sweep is collected on the next tick rather than a half second later.
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        boolean collected = false;
        for (com.pvzce.server.entity.ResourceDropEntity drop : inReach) {
            // One automatic collection per sweep, and only of *money*: the original's gold magnet
            // is the coin half of the magnet-shroom, and a plant that also swallowed every sun on
            // the lawn would make the sun economy somebody else's decision. Sun is still the
            // player's click (or the auto-pickup buff's business).
            if (!com.pvzce.common.PvzceIds.isCoin(drop.defId())) {
                continue;
            }
            if (server.autoCollectDrop(drop)) {
                collected = true;
                break;
            }
        }
        if (collected) {
            pulling = true;
            plant.setState(EntityAnimations.SHOOT);
            level.emitEffect("", plant.cellX(), plant.cellY(),
                    sound.orElseGet(() -> plant.def().sounds().shoot().orElse(null)));
            cooldown = intervalTicks;
        }
    }

    /** True on the tick this magnet pulled something in. */
    public boolean pulling() {
        return pulling;
    }

    @Override
    public void save(CompoundTag tag) {
        weatherClock.save(tag);
        tag.putInt("cooldown", cooldown);
    }

    @Override
    public void load(CompoundTag tag) {
        weatherClock.load(tag);
        cooldown = Math.max(0, tag.getInt("cooldown"));
    }
}

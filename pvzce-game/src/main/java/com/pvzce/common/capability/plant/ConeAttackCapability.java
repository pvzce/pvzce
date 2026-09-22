package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.DamageTypeDef;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.Optional;

/**
 * A short burst of area damage in front of the plant, with no projectile behind it.
 *
 * <p>This is the fume-shroom, and the thing it exists to say is that "attacks the space in
 * front of me" is not the same shape as "fires a bullet". The fume is a cloud: it appears
 * across the whole cone at once, hits everything standing in it, and is gone. Modelled as a
 * projectile it had to <em>travel</em> to each zombie, which made a plant that the original
 * draws breathing on its neighbours into a long-range sniper, and it needed
 * {@code pvzce:pierce} plus the {@code hitIds} book-keeping that "passes through and hits the
 * next one" implies - machinery for a shot that was never a shot.
 *
 * <p>So this capability is in the same family as {@code explosive}'s row trigger and
 * {@code freeze_all}: a shape, resolved once, against whatever is standing in it. It asks the
 * level for its row's enemies, keeps the ones in the cone, and hits each of them exactly once.
 * There is no per-zombie memory because there is nothing to remember: nothing overlaps
 * anything for more than the tick it is applied on.
 *
 * <p><strong>What is deliberately not here.</strong> No pierce flag, no projectile definition,
 * no per-tick re-hit; range is measured from the same muzzle the shooters fire from, so "worth
 * attacking" and "the reach" are one number. The cone is the plant's own row - the fume-shroom
 * is a one-lane plant, and {@code rows} is a shooter's problem, so a content author who wants a
 * wider gas gets a new field rather than a surprise.
 */
public final class ConeAttackCapability implements PlantCapability {
    /** Ticks between two bursts, matching the shooter's default cadence. */
    public static final int DEFAULT_INTERVAL = 90;
    /** One pea of damage per zombie per burst: the fume-shroom's own number. */
    public static final int DEFAULT_DAMAGE = 20;
    /** How far in front of the muzzle the cloud reaches, in cells. */
    public static final float DEFAULT_RANGE = 4F;
    /**
     * What the cloud is when the content does not name a type.
     *
     * <p>{@code pvzce:spray}, which is the whole point of the fume-shroom: gas goes through what
     * is held in <em>front</em> (a screen door, a newspaper) and is stopped by what is worn on
     * the <em>head</em>. It is not the ash line, so it leaves no charred body.
     */
    public static final Identifier DEFAULT_DAMAGE_TYPE = PvzceIds.DAMAGE_SPRAY;
    /**
     * The cloud to draw along the cone when the content does not name one.
     *
     * <p>The original's {@code FumeCloud} emitter: {@code PUFFSHROOM_PUFF1} puffs drifting
     * forward, which is the art the fume-shroom's gas has always been made of. It is emitted
     * several times down the cone rather than once, because a single emitter is a single point
     * and the thing being drawn is a line - see {@link #cloudCount}.
     */
    public static final Identifier DEFAULT_CLOUD_PARTICLE = PvzceIds.id("fume_cloud");
    /**
     * How many clouds are laid down along the cone, evenly spaced from the muzzle to the reach.
     *
     * <p>The whole reason this is a field and not a hardcoded 1. An emitter puts every particle
     * at one point, so one emit is a <em>blob</em> at the plant's face; the original drew the gas
     * as a flying sprite, which is what made it read as a cloud stretching down the lane.
     *
     * <p><strong>The count has to be large enough that the clouds touch, and it scales with how
     * big the cloud art is.</strong> The gap is {@code range / count}, and the cloud has to be
     * wider than that or the result is a dotted line of separate puffs rather than a cloud: four
     * cells at three stops was a 1.33-cell gap and read as three detached blobs. {@code
     * pvzce:fume_cloud} draws about {@code 0.5} cells across, so eight stops (a 0.5-cell gap)
     * is what overlaps into a continuous band - and because the size lives in the particle
     * definition, shrinking that art means raising this number to match. A longer cone should
     * likewise raise it rather than stretch the spacing.
     */
    public static final int DEFAULT_CLOUD_COUNT = 8;

    private final int intervalTicks;
    private final int damage;
    private final float range;
    private final Identifier damageType;
    private final Optional<Identifier> sound;
    private final int firstDelayTicks;
    private final Optional<Identifier> cloudParticle;
    private final int cloudCount;

    private int cooldown;

    public ConeAttackCapability(int intervalTicks, int damage, float range, Identifier damageType,
                                Optional<Identifier> sound, int firstDelayTicks,
                                Optional<Identifier> cloudParticle, int cloudCount) {
        this.intervalTicks = Math.max(1, intervalTicks);
        this.damage = Math.max(0, damage);
        this.range = Math.max(0F, range);
        this.damageType = damageType == null ? DEFAULT_DAMAGE_TYPE : damageType;
        this.sound = sound == null ? Optional.empty() : sound;
        this.firstDelayTicks = Math.max(0, firstDelayTicks);
        // Absent has to become the default, not stay empty. The codec reads a missing
        // `cloud_particle` as an empty Optional, and an empty one means "draw nothing" - so
        // taking it at face value made every plant that did not spell the field out silently
        // draw no gas at all, which is exactly the bug this default exists to prevent.
        this.cloudParticle = cloudParticle == null || cloudParticle.isEmpty()
                ? Optional.of(DEFAULT_CLOUD_PARTICLE)
                : cloudParticle;
        this.cloudCount = Math.max(0, cloudCount);
        this.cooldown = Math.min(this.intervalTicks, this.firstDelayTicks == 0 ? 1 : this.firstDelayTicks);
    }

    public static final MapCodec<ConeAttackCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("interval", DEFAULT_INTERVAL).forGetter(ConeAttackCapability::intervalTicks),
            Codec.INT.optionalFieldOf("damage", DEFAULT_DAMAGE).forGetter(ConeAttackCapability::damage),
            Codec.FLOAT.optionalFieldOf("range", DEFAULT_RANGE).forGetter(ConeAttackCapability::range),
            Identifier.CODEC.optionalFieldOf("damage_type", DEFAULT_DAMAGE_TYPE)
                    .forGetter(ConeAttackCapability::damageType),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(ConeAttackCapability::sound),
            Codec.INT.optionalFieldOf("first_delay", 0).forGetter(ConeAttackCapability::firstDelayTicks),
            Identifier.CODEC.optionalFieldOf("cloud_particle")
                    .forGetter(ConeAttackCapability::cloudParticle),
            // 0 draws nothing, for content that wants the damage without the visual.
            Codec.INT.optionalFieldOf("cloud_count", DEFAULT_CLOUD_COUNT)
                    .forGetter(ConeAttackCapability::cloudCount)
    ).apply(i, ConeAttackCapability::new));

    public int intervalTicks() {
        return intervalTicks;
    }

    public int damage() {
        return damage;
    }

    public float range() {
        return range;
    }

    /** The registered damage type this cloud lands as; {@code pvzce:spray} by default. */
    public Identifier damageType() {
        return damageType;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    /** The cloud drawn down the cone, or empty for one that draws nothing. */
    public Optional<Identifier> cloudParticle() {
        return cloudParticle;
    }

    /** How many clouds are spaced along the cone; 0 draws none. */
    public int cloudCount() {
        return cloudCount;
    }

    public int firstDelayTicks() {
        return firstDelayTicks;
    }

    @Override
    public PlantCapability instantiate() {
        return new ConeAttackCapability(intervalTicks, damage, range, damageType, sound, firstDelayTicks,
                cloudParticle, cloudCount);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (cooldown > 0) {
            cooldown--;
            if (cooldown == 0) {
                plant.setState(EntityAnimations.IDLE);
            }
            return;
        }
        // Nothing in the cone means nothing happens at all: the cooldown is not spent on an
        // empty lawn, so a fume-shroom that a zombie walks up to breathes on the tick it
        // arrives rather than finishing a pause it started at nothing. This is the shooter's
        // rule as well, and it is why the search runs before the cooldown is reset.
        if (!breath(plant, level)) {
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        plant.setState(EntityAnimations.SHOOT);
        cooldown = intervalTicks;
    }

    /**
     * Hits everything in the cone, and reports whether there was anything to hit.
     *
     * <p>The one place this differs from a projectile's impact is the ordering the armour sees:
     * every zombie in the cloud is hit by the same {@link DamageTypeDef}, so the spray's "past
     * the shield, into the hat" routing is the damage type's answer for all of them rather than
     * each shot's private layer comparison.
     */
    private boolean breath(PlantEntity plant, LevelAccess level) {
        float muzzleX = plant.cellX() + PlantShots.MUZZLE_OFFSET_X;
        DamageTypeDef type = BuiltInRegistries.DAMAGE_TYPES.get(damageType);
        if (type == null) {
            // Reported once per reload by LevelValidator.validateDamageTypes; here it only means
            // the fallback, so a pack that names a type this build does not have leaves the
            // fume-shroom a plant that still attacks rather than one that silently does nothing.
            type = BuiltInRegistries.DAMAGE_TYPES.get(DEFAULT_DAMAGE_TYPE);
        }
        boolean hitAny = false;
        for (ZombieEntity zombie : level.enemiesInRow(plant.gridY(), plant.team())) {
            if (zombie.isRemoved() || !zombie.canBeHitByGround()) {
                continue;
            }
            // Strictly in front of the muzzle, and no further than the reach - the same
            // `covers` arithmetic a shot uses, so the plant does not breathe on something it
            // could never touch (nor stand silent while one walks into the cloud).
            // "In front" is a question about the *cell*, not about the muzzle line: a zombie
            // standing on top of the plant has its centre at 1.5 and the muzzle sits at 1.8, so
            // a bare `cellX > muzzleX` test reads it as being in front and the mushroom breathes
            // on the very zombie that is eating it. The original's fume never hits its own cell.
            if (zombie.gridX() <= plant.gridX()) {
                continue;
            }
            if (zombie.cellX() - muzzleX > range) {
                continue;
            }
            zombie.damage(damage, type, level);
            hitAny = true;
        }
        if (hitAny) {
            drawCloud(plant, level);
        }
        return hitAny;
    }

    /**
     * Lays the gas down as a line of clouds from the muzzle to the reach.
     *
     * <p>One emit is one point - the particle engine puts every particle of a definition exactly
     * where the effect was spawned, and only {@code motion} moves it afterwards - so a single
     * emitter reads as a puff sitting on the mushroom's face rather than as a cloud reaching down
     * the lane. The original had no such problem: its {@code FumeCloud} was the <em>projectile
     * sprite</em>, a flying image, which is why the gas looked like it stretched. Now that the
     * damage is instant there is nothing to fly, so the shape is drawn instead: {@code cloudCount}
     * emitters spread evenly across the cone, each one's own forward drift carried by the
     * definition ({@code pvzce:fume_cloud}).
     *
     * <p>The sound rides on the nearest cloud only. Every emit carries the whole effect event, so
     * asking for the sound at each stop would play one breath three times over.
     */
    private void drawCloud(PlantEntity plant, LevelAccess level) {
        if (cloudCount <= 0 || cloudParticle.isEmpty()) {
            return;
        }
        String particle = cloudParticle.get().toString();
        Identifier held = sound.orElseGet(() -> plant.def().sounds().shoot().orElse(PvzceSounds.PLANT_SHOOT_PEA));
        float gap = range / cloudCount;
        for (int i = 0; i < cloudCount; i++) {
            level.emitEffect(particle, plant.cellX() + gap * (i + 0.5F), plant.cellY(), i == 0 ? held : null);
        }
    }

    /**
     * The registered damage type, falling back to the spray line.
     *
     * <p>The fallback matters for the same reason {@code ZombieEntity.damage} reads a missing
     * type as {@code pvzce:projectile}: a data pack that names a type this build does not have
     * must not turn the fume-shroom into a plant that does nothing.
     */
    @Override
    public void save(CompoundTag tag) {
        tag.putInt("cooldown", cooldown);
    }

    @Override
    public void load(CompoundTag tag) {
        cooldown = tag.getInt("cooldown");
    }
}

package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.DamageTypeDef;
import com.pvzce.api.content.StatusEffectDef;
import com.pvzce.api.content.ZombieStatus;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import java.util.Optional;

/**
 * Ice-shroom: one use, and the whole lawn stops.
 *
 * <p>The original's ice-shroom is not an explosion with a radius - it is a <em>screen</em>
 * effect: every zombie on the field freezes for three and a quarter seconds, takes a pea's
 * worth of damage, and is left chilled at half speed for sixteen. So this capability asks the
 * level for its rows instead of naming a radius, and a level wider than anyone's is covered
 * because "every zombie there is" is the shape, not a number that happens to reach.
 *
 * <p>Two things are deliberately data rather than code:
 *
 * <ul>
 *   <li>the damage type - the default is {@code pvzce:splash}, which ignores armour, because
 *       a screen-wide freeze is not a shot and stopping at a bucket would be an accident of
 *       the damage table rather than a design;</li>
 *   <li>{@code #pvzce:freeze_immune} - the zombies the cold does not hold. The original
 *       exempts the ones it would be nonsense to freeze (a balloon in the air, a digger under
 *       the lawn), and which those are is a list about content, which is what tags are for.
 *       They still take the damage and the chill: the tag is "not frozen", not "not hit".</li>
 * </ul>
 *
 * <p>Like the ash line it is invulnerable for the whole fuse and occupies its cell until the
 * freeze lands, and like them it then leaves: there is no art for this one to linger in, so
 * the plant is gone the moment the lawn freezes - which is exactly what the original draws
 * (a flash, and no mushroom).
 */
public final class FreezeAllCapability implements PlantCapability {
    /** Ticks between planting and the freeze: the original's charge-up. */
    public static final int DEFAULT_FUSE_TICKS = 60;
    /** The original's one pea of damage. */
    public static final int DEFAULT_DAMAGE = 20;
    /** 3.25 seconds of not moving. */
    public static final int DEFAULT_FREEZE_TICKS = 195;
    /**
     * The chill left behind: sixteen seconds at half speed.
     *
     * <p>Half speed is both halves of the original's chill - movement <em>and</em> biting -
     * because that is what {@code slow} means here (see {@code ZombieEntity.moveSpeed} and
     * its bite clock); there is no second status for "slow jaws".
     */
    public static final StatusEffectDef DEFAULT_CHILL =
            new StatusEffectDef(ZombieStatus.SLOW, 960, 0.5F);
    /**
     * What a screen-wide freeze lands as when the data does not name a type.
     *
     * <p>{@code splash} ignores armour and does not burn: cold is neither a shot nor a fire.
     */
    public static final Identifier DEFAULT_DAMAGE_TYPE = PvzceIds.DAMAGE_SPLASH;

    private final int fuseTicks;
    private final int damage;
    private final int freezeTicks;
    private final StatusEffectDef chill;
    private final Identifier damageType;
    private final Optional<Identifier> sound;
    private final Optional<Identifier> particle;

    /** Ticks left before the freeze; 0 once it has happened. */
    private int fuse;
    private final com.pvzce.common.level.RateClock weatherClock = new com.pvzce.common.level.RateClock();

    public FreezeAllCapability(int fuseTicks, int damage, int freezeTicks, StatusEffectDef chill,
                               Identifier damageType, Optional<Identifier> sound,
                               Optional<Identifier> particle) {
        this.fuseTicks = Math.max(1, fuseTicks);
        this.damage = Math.max(0, damage);
        this.freezeTicks = Math.max(0, freezeTicks);
        this.chill = chill == null ? DEFAULT_CHILL : chill;
        this.damageType = damageType == null ? DEFAULT_DAMAGE_TYPE : damageType;
        this.sound = sound == null ? Optional.empty() : sound;
        this.particle = particle == null ? Optional.empty() : particle;
        this.fuse = this.fuseTicks;
    }

    public static final MapCodec<FreezeAllCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("fuse_ticks", DEFAULT_FUSE_TICKS)
                    .forGetter(FreezeAllCapability::fuseTicks),
            Codec.INT.optionalFieldOf("damage", DEFAULT_DAMAGE).forGetter(FreezeAllCapability::damage),
            Codec.INT.optionalFieldOf("freeze_ticks", DEFAULT_FREEZE_TICKS)
                    .forGetter(FreezeAllCapability::freezeTicks),
            StatusEffectDef.CODEC.optionalFieldOf("chill", DEFAULT_CHILL)
                    .forGetter(FreezeAllCapability::chill),
            Identifier.CODEC.optionalFieldOf("damage_type", DEFAULT_DAMAGE_TYPE)
                    .forGetter(FreezeAllCapability::damageType),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(FreezeAllCapability::sound),
            Identifier.CODEC.optionalFieldOf("particle").forGetter(FreezeAllCapability::particle)
    ).apply(i, FreezeAllCapability::new));

    public int fuseTicks() {
        return fuseTicks;
    }

    public int damage() {
        return damage;
    }

    public int freezeTicks() {
        return freezeTicks;
    }

    public StatusEffectDef chill() {
        return chill;
    }

    public Identifier damageType() {
        return damageType;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    public Optional<Identifier> particle() {
        return particle;
    }

    /** True once the lawn has been frozen; the plant is on its way out. */
    public boolean spent() {
        return fuse <= 0;
    }

    @Override
    public PlantCapability instantiate() {
        return new FreezeAllCapability(fuseTicks, damage, freezeTicks, chill, damageType, sound, particle);
    }

    @Override
    public boolean invulnerable(PlantEntity plant) {
        return fuse > 0;
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (fuse <= 0) {
            // Nothing to wait for any more; the removal below is the plant's last act.
            return;
        }
        fuse = Math.max(0, fuse - weatherClock.step(level.weatherActionMultiplier(plant)));
        if (fuse > 0) {
            // The art has no charging pose, and the shiver it does have is its idle: asking
            // for a clip the file does not define is what makes the animation manager log a
            // fallback per request, so the idle is asked for by name.
            plant.setState(EntityAnimations.IDLE);
            return;
        }
        freeze(plant, level);
        plant.remove();
    }

    /**
     * Freezes every zombie on the lawn.
     *
     * <p>Rows rather than a radius, and every row: the only shape this effect has is "the
     * field". Zombies the cold does not hold take the damage and the chill and keep walking.
     */
    private void freeze(PlantEntity plant, LevelAccess level) {
        DamageTypeDef type = BuiltInRegistries.DAMAGE_TYPES.get(damageType);
        if (type == null) {
            // Reported once per reload by LevelValidator.validateDamageTypes; here it only
            // means the fallback, which is the same "armour applies" default every other
            // unknown type gets.
            type = BuiltInRegistries.DAMAGE_TYPES.get(PvzceIds.DAMAGE_PROJECTILE);
        }
        Identifier held = sound.orElseGet(() -> plant.def().sounds().explode()
                .orElse(PvzceSounds.EFFECT_FROZEN));
        boolean first = true;
        for (int row = 0; row < level.height(); row++) {
            for (ZombieEntity zombie : level.zombiesInRow(row)) {
                if (zombie.isRemoved()) {
                    continue;
                }
                if (damage > 0 && type != null) {
                    zombie.damage(damage, type, level);
                }
                if (!zombie.isRemoved() && !frozenImmune(zombie)) {
                    zombie.applyStatus(ZombieStatus.IMMOBILIZED,
                            com.pvzce.common.level.StatusDurations.coldFreeze(level, freezeTicks), 1F);
                }
                if (!zombie.isRemoved() && chill != null && chill.ticks() > 0) {
                    zombie.applyStatus(chill.status(),
                            com.pvzce.common.level.StatusDurations.cold(level, chill.ticks()),
                            chill.magnitude());
                }
                // The flash is drawn on the zombies, not on the mushroom: they are what the
                // player is looking at, and a screen-wide effect anchored to one cell reads
                // as that cell having done something.
                String effect = particle.map(Identifier::toString).orElse("");
                // The sound rides along with the first effect so a lawn of twenty zombies
                // does not play twenty freezes on the same tick.
                level.emitEffect(effect, zombie.position(), zombie.surfaceId(), first ? held : null);
                first = false;
            }
        }
        if (first) {
            // Nothing on the lawn: the freeze still happened, and it should still be heard.
            level.emitEffect(particle.map(Identifier::toString).orElse(""), plant.position(), plant.surfaceId(), held);
        }
    }

    /** True when this zombie is on the list the cold does not freeze. */
    private static boolean frozenImmune(ZombieEntity zombie) {
        return PvzceTags.ZOMBIES.contains(PvzceTags.ZOMBIE_FREEZE_IMMUNE, zombie.defId());
    }

    @Override
    public void save(CompoundTag tag) {
        weatherClock.save(tag);
        tag.putInt("fuse", fuse);
    }

    @Override
    public void load(CompoundTag tag) {
        weatherClock.load(tag);
        // A save taken mid-fuse resumes it. A save with no block (one written before this
        // capability existed) keeps the value it was built with, which is a full fuse - the
        // plant has been waiting for at most a second, so nothing is lost by it waiting one
        // more.
        if (tag.contains("fuse")) {
            fuse = Math.max(0, tag.getInt("fuse"));
        }
    }
}

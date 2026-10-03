package com.pvzce.common.capability.projectile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.common.PvzceParticles;

import java.util.List;
import java.util.Optional;

/**
 * Area-of-effect impact (melon, cherry-like pults): the touched target is
 * replaced by a blast that hits every zombie within {@code radius} cells.
 */
public final class SplashImpactCapability implements ProjectileCapability {
    public static final float DEFAULT_RADIUS = 1.5F;
    /**
     * What a thrown plant's blast is when the content does not say.
     *
     * <p>{@code pvzce:splash} ignores armour: the melon's area damage is a blast in the
     * original too, so a cone does not turn 80 points of splash into 80 points of cone.
     */
    public static final Identifier DEFAULT_DAMAGE_TYPE = PvzceIds.DAMAGE_SPLASH;
    /** The melon's splash: what a thrown plant's blast has looked like since it was written. */
    public static final Identifier DEFAULT_PARTICLE = PvzceParticles.POOL_SPLASH;

    private final float radius;
    private final Optional<Identifier> sound;
    private final Identifier damageType;
    /**
     * Whether the blast is a block of cells rather than a distance.
     *
     * <p>The melon's blast is round - it is a splash, and "the melon caught the neighbours" reads
     * better as a radius. The cob cannon's is a 3x3 square, exactly like the cherry bomb's, and the
     * difference is not cosmetic: with {@code radius 1.0} a round blast reaches 1.0 cells (five
     * cells across the middle row and three in the ones beside it) while a square one reaches 1.5,
     * which is the nine cells the original covers. See {@code LevelAccess.damageArea}'s
     * {@code square} parameter.
     */
    private final boolean square;
    /**
     * What the blast looks like where it lands.
     *
     * <p>The melon's splash of water is the default because the melon is what this capability was
     * written for; the cob cannon's blast is fire and smoke and says so. One field rather than two
     * capabilities, because everything else about the two impacts is the same code.
     */
    private final Identifier particle;

    public SplashImpactCapability(float radius, Optional<Identifier> sound, Identifier damageType,
                                  boolean square, Identifier particle) {
        this.radius = Math.max(0F, radius);
        this.sound = sound;
        this.damageType = damageType == null ? DEFAULT_DAMAGE_TYPE : damageType;
        this.square = square;
        this.particle = particle == null ? DEFAULT_PARTICLE : particle;
    }

    public static final MapCodec<SplashImpactCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(SplashImpactCapability::radius),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(SplashImpactCapability::sound),
            Identifier.CODEC.optionalFieldOf("damage_type", DEFAULT_DAMAGE_TYPE)
                    .forGetter(SplashImpactCapability::damageType),
            Codec.BOOL.optionalFieldOf("square", false).forGetter(SplashImpactCapability::square),
            Identifier.CODEC.optionalFieldOf("particle", DEFAULT_PARTICLE)
                    .forGetter(SplashImpactCapability::particle)
    ).apply(i, SplashImpactCapability::new));

    public float radius() {
        return radius;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    /** The registered damage type this blast lands as. */
    public Identifier damageType() {
        return damageType;
    }

    /** True when the blast covers a square block of cells rather than a radius. */
    public boolean square() {
        return square;
    }

    /** The effect drawn where the blast lands. */
    public Identifier particle() {
        return particle;
    }

    /** Runtime footprint; a cold status and splash capability together identify an icy splash. */
    public float effectiveRadius(ProjectileEntity projectile, LevelAccess level) {
        boolean cold = projectile.def().capability(StatusOnHitCapability.class)
                .map(StatusOnHitCapability::isCold).orElse(false);
        return radius * (cold ? level.weatherIcySplashMultiplier() : 1F);
    }

    public List<ZombieEntity> targets(ProjectileEntity projectile, ZombieEntity hit, LevelAccess level) {
        return level.enemiesInArea(projectile.team(), hit == null ? projectile.cellX() : hit.cellX(),
                hit == null ? projectile.cellY() : hit.cellY(), effectiveRadius(projectile, level), square);
    }

    @Override
    public ProjectileCapability instantiate() {
        return this;
    }

    @Override
    public boolean replacesDirectHit() {
        return true;
    }

    @Override
    public void onHit(ProjectileEntity projectile, ZombieEntity zombie, LevelAccess level) {
        float x = zombie != null ? zombie.cellX() : projectile.cellX();
        float y = zombie != null ? zombie.cellY() : projectile.cellY();
        level.damageArea(ZombieEntity.damageType(damageType), x, y, effectiveRadius(projectile, level), projectile.damage(),
                projectile.team(), square);
        level.emitEffect(particle.toString(), x, y,
                sound.orElseGet(() -> projectile.def().sounds().impact().orElse(PvzceSounds.PROJECTILE_HIT)));
    }
}

package com.pvzce.common.capability.projectile;

import com.pvzce.common.level.WorldPosition;
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
    /** Ordinary area projectiles retain their air-layer armour routing. */
    public static final Identifier DEFAULT_DAMAGE_TYPE = PvzceIds.DAMAGE_PROJECTILE;
    /** The melon's splash: what a thrown plant's blast has looked like since it was written. */
    public static final Identifier DEFAULT_PARTICLE = PvzceParticles.POOL_SPLASH;
    /**
     * What a blast that does not name {@code splash_damage} deals to everything but its target.
     *
     * <p>Half, which is the original's melon (80 direct, 40 splash). A number rather than a
     * formula because the two are not related by anything but the original's tuning: a level is
     * free to write a {@code splash_damage} that is larger than the shot.
     */
    public static final float DEFAULT_SPLASH_SHARE = 0.5F;

    private final float radius;
    private final Optional<Identifier> sound;
    private final Identifier damageType;
    /**
     * What the blast deals to everything except the zombie it hit; {@code 0} means "half the
     * projectile's damage", which is {@link #DEFAULT_SPLASH_SHARE}.
     *
     * <p>A field rather than a constant because the two numbers are a balance decision per
     * projectile: the winter melon is the same 80/40 as the melon, and a future one is not
     * obliged to be. Zero is the "use the default" sentinel rather than "no damage", so a level
     * cannot accidentally write a blast that does nothing to its own neighbours - one that
     * wants that writes a radius of zero.
     */
    private final int splashDamage;
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
        this(radius, sound, damageType, square, particle, 0);
    }

    public SplashImpactCapability(float radius, Optional<Identifier> sound, Identifier damageType,
                                  boolean square, Identifier particle, int splashDamage) {
        this.radius = Math.max(0F, radius);
        this.sound = sound;
        this.damageType = damageType == null ? DEFAULT_DAMAGE_TYPE : damageType;
        this.square = square;
        this.particle = particle == null ? DEFAULT_PARTICLE : particle;
        this.splashDamage = Math.max(0, splashDamage);
    }

    public static final MapCodec<SplashImpactCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(SplashImpactCapability::radius),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(SplashImpactCapability::sound),
            Identifier.CODEC.optionalFieldOf("damage_type", DEFAULT_DAMAGE_TYPE)
                    .forGetter(SplashImpactCapability::damageType),
            Codec.BOOL.optionalFieldOf("square", false).forGetter(SplashImpactCapability::square),
            Identifier.CODEC.optionalFieldOf("particle", DEFAULT_PARTICLE)
                    .forGetter(SplashImpactCapability::particle),
            Codec.INT.optionalFieldOf("splash_damage", 0)
                    .forGetter(SplashImpactCapability::authoredSplashDamage)
    ).apply(i, SplashImpactCapability::new));

    /** The written {@code splash_damage}, or zero for "half the projectile's damage". */
    public int authoredSplashDamage() {
        return splashDamage;
    }

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
        return level.enemiesInArea(projectile.team(), hit == null ? projectile.position() : hit.position(),
                effectiveRadius(projectile, level), square);
    }

    /**
     * What this blast deals to everything except the zombie it hit: half the shot, or the
     * authored {@code splash_damage}.
     *
     * <p>Half, because that is the original's own melon: 80 on the target it lands on and 40 on
     * the neighbours the splash reaches. Paying every target the full projectile damage - which is
     * what this did - made one melon as good as a lane-wide bomb, and it is why the melon measured
     * as the strongest card in the game (see {@code docs/03-第三阶段-精修完善.md}: "疑似过强 →
     * 校准溅射半径/伤害").
     *
     * <p>A level that wants its own numbers writes them: {@code "splash_damage": 30} is a blast
     * that is weaker than the shot, and {@code 0} is one that only hits what it lands on.
     */
    public int splashDamage(ProjectileEntity projectile) {
        return splashDamage > 0 ? splashDamage
                : Math.max(1, Math.round(projectile.damage() * DEFAULT_SPLASH_SHARE));
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
        int splash = splashDamage(projectile);
        for (ZombieEntity target : targets(projectile, zombie, level)) {
            // The zombie the melon actually hit takes the full shot; everything the blast merely
            // reaches takes the splash. Paying full damage to all of them is what made the melon
            // delete a whole lane's worth of a wave with one throw - see `splashDamage`.
            boolean direct = target == zombie && target.isAlive();
            target.damage(projectile.def(), direct ? projectile.damage() : splash, level, damageType, false);
        }
        String impact = projectile.def().impactParticle().map(Identifier::toString).orElse(particle.toString());
        level.emitEffect(impact, new WorldPosition(x, y,
                zombie == null ? projectile.height() : zombie.height()), projectile.surfaceId(),
                sound.orElseGet(() -> projectile.def().sounds().impact().orElse(PvzceSounds.PROJECTILE_HIT)));
    }
}

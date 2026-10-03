package com.pvzce.common.capability.projectile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.DamageTypeDef;
import com.pvzce.api.content.ZombieStatus;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.StatusDurations;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ZombieEntity;


/**
 * The ice-boom shroom's shot: chill on every hit, ice on the last one, and a burst on a
 * target that is already held.
 *
 * <p>Two rules, and the second one is why the plant exists:
 *
 * <ol>
 *   <li><b>Cold stacks into ice.</b> Each hit slows the zombie (the same {@code slow} status a
 *       snow pea lands, so a lawn of both shares one definition) and counts up. The
 *       {@code hits_to_freeze}-th hit freezes it instead - {@link ZombieStatus#IMMOBILIZED}, the
 *       status the ice-shroom uses and the one thing the client draws a block of ice for.</li>
 *   <li><b>A frozen target shatters.</b> A shot that lands on a zombie already held does not
 *       deal a pea's worth of damage: it deals {@code shatter_multiplier} times that, under its
 *       own damage type. That is the whole decision the card asks of the player - freeze first,
 *       then hit the one you froze - and it is why the third hit of a cycle is worth more than
 *       the first two put together.</li>
 * </ol>
 *
 * <p><b>The count lives on the zombie</b> ({@code ZombieEntity#addBuildup}), not on the shot.
 * That is not a detail of where a field sits: a projectile is destroyed by the hit that lands
 * it, so a stack it owned could never reach two, and the freeze would never happen at all -
 * which is exactly the bug this plant was written with. It is also why a snow pea's slow does
 * not advance it: the tally is keyed, and only this plant's bolts write that key.
 *
 * <p>Freeze durations and the slow's strength are data ({@code chill} / {@code freeze_ticks}),
 * scaled by the level's slow-duration rules through {@link StatusDurations} like every other
 * status in the game.
 */
public final class ShatterCapability implements ProjectileCapability {
    /**
     * Hits before the target is held solid, this project's own number rather than the
     * original's (the original has no stacking cold). Three is chosen so that one plant
     * alone takes about four and a half seconds of uninterrupted fire to freeze a zombie
     * and then pay it off on the next shot.
     */
    public static final int DEFAULT_HITS_TO_FREEZE = 3;

    /** What a hit on an already-frozen zombie is worth, as a multiple of the shot's damage. */
    public static final float DEFAULT_SHATTER_MULTIPLIER = 3.0F;

    /** How long the frozen state lasts; the ice-shroom's own number. */
    public static final int DEFAULT_FREEZE_TICKS = 195;

    /** The slow every hit lands before the freeze; a snow pea's slow. */
    public static final com.pvzce.api.content.StatusEffectDef DEFAULT_CHILL =
            new com.pvzce.api.content.StatusEffectDef(ZombieStatus.SLOW, 240, 0.5F);

    private final int hitsToFreeze;
    private final float shatterMultiplier;
    private final int freezeTicks;
    private final com.pvzce.api.content.StatusEffectDef chill;
    private final Identifier shatterDamageType;

    public ShatterCapability(int hitsToFreeze, float shatterMultiplier, int freezeTicks,
                             com.pvzce.api.content.StatusEffectDef chill,
                             Identifier shatterDamageType) {
        this.hitsToFreeze = Math.max(1, hitsToFreeze);
        this.shatterMultiplier = Math.max(1F, shatterMultiplier);
        this.freezeTicks = Math.max(0, freezeTicks);
        this.chill = chill;
        this.shatterDamageType = shatterDamageType;
    }

    public static final MapCodec<ShatterCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("hits_to_freeze", DEFAULT_HITS_TO_FREEZE)
                    .forGetter(ShatterCapability::hitsToFreeze),
            Codec.FLOAT.optionalFieldOf("shatter_multiplier", DEFAULT_SHATTER_MULTIPLIER)
                    .forGetter(ShatterCapability::shatterMultiplier),
            Codec.INT.optionalFieldOf("freeze_ticks", DEFAULT_FREEZE_TICKS)
                    .forGetter(ShatterCapability::freezeTicks),
            com.pvzce.api.content.StatusEffectDef.CODEC
                    .optionalFieldOf("chill", DEFAULT_CHILL).forGetter(ShatterCapability::chill),
            Identifier.CODEC.optionalFieldOf("shatter_damage_type", PvzceIds.DAMAGE_SHATTER)
                    .forGetter(ShatterCapability::shatterDamageType)
    ).apply(i, ShatterCapability::new));

    public int hitsToFreeze() {
        return hitsToFreeze;
    }

    public float shatterMultiplier() {
        return shatterMultiplier;
    }

    public int freezeTicks() {
        return freezeTicks;
    }

    public com.pvzce.api.content.StatusEffectDef chill() {
        return chill;
    }

    public Identifier shatterDamageType() {
        return shatterDamageType;
    }

    @Override
    public ProjectileCapability instantiate() {
        // A fresh counter per shot: the stack is the plant's fire, not the bullet's.
        return new ShatterCapability(hitsToFreeze, shatterMultiplier, freezeTicks, chill, shatterDamageType);
    }

    @Override
    public boolean replacesDirectHit() {
        // The shatter replaces the plain hit rather than adding to it: "the shot is worth
        // three times as much" is one number, and adding the two would make a frozen target
        // take four times a pea while the data says three.
        return true;
    }

    @Override
    public void onHit(ProjectileEntity projectile, ZombieEntity target, LevelAccess level) {
        if (target == null || target.isRemoved()) {
            return;
        }
        // Counted on the zombie, not on this shot: a projectile is destroyed by the hit that
        // lands it, so a counter it owned would never leave one (see ZombieEntity#addBuildup).
        if (target.frozen()) {
            // Already held: this is the payoff shot, and the ice breaks under it. The tally
            // starts over, because the state it was counting towards is over.
            target.addBuildup(buildupKey(), -target.buildup(buildupKey()));
            applyShatter(projectile, target, level);
            return;
        }
        // The ordinary hit has to be dealt here. This capability answers
        // `replacesDirectHit` - it must, or a shatter would be a bolt *plus* a bonus - and
        // that flag is the capability's promise that it lands the damage itself. Leaving this
        // out is how the first version of the plant shot zombies forever without hurting them.
        target.damage(projectile.def(), projectile.damage(), level);
        if (target.isRemoved()) {
            return;
        }
        int landed = target.addBuildup(buildupKey(), 1);
        if (landed >= hitsToFreeze) {
            target.addBuildup(buildupKey(), -landed);
            if (freezeTicks > 0) {
                target.applyStatus(ZombieStatus.IMMOBILIZED,
                        StatusDurations.cold(level, freezeTicks), 1F);
            }
            // A local puff, not `ICE_SPARKLE`: that one is the ice-shroom's *whole-lawn*
            // white-out (one 1600x white pixel at half opacity), and freezing a single zombie
            // with it flashed the entire board - the user's "很晃眼". The plant's own colours
            // are what should say "that one is ice".
            level.emitEffect(PvzceParticles.SNOW_PEA_SPLAT.toString(),
                    target.cellX(), target.cellY() + target.height(), null);
            return;
        }
        if (chill != null && chill.ticks() > 0) {
            target.applyStatus(chill.status(), StatusDurations.cold(level, chill.ticks()),
                    chill.magnitude());
        }
    }

    /** The tally this shot counts towards: the cold one, shared with the rest of the ice line. */
    private Identifier buildupKey() {
        return PvzceIds.ICEBOOM_CHILL;
    }

    /**
     * The third hit of a cycle: the shot's damage under its own damage type.
     *
     * <p>Damage goes through {@link ZombieEntity#damage(int, DamageTypeDef, LevelAccess)} like
     * everything else, so the type - not this method - decides what armour does about it.
     */
    private void applyShatter(ProjectileEntity projectile, ZombieEntity target, LevelAccess level) {
        DamageTypeDef type = BuiltInRegistries.DAMAGE_TYPES.get(shatterDamageType);
        int amount = Math.max(1, Math.round(projectile.damage() * shatterMultiplier));
        if (type == null) {
            // The same silent fallback every unknown damage type gets; LevelValidator is what
            // reports the typo, once per load. `damageImpact` is the armour-respecting hit a
            // plain projectile would have landed.
            target.damageImpact(amount, level);
        } else {
            target.damage(amount, type, level);
        }
        level.emitEffect(PvzceParticles.ICEBOOM_SHATTER.toString(),
                target.cellX(), target.cellY() + target.height(), null);
    }
}

package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

/**
 * A plant that is bowled: placed, it immediately rolls down the lawn instead of
 * standing where it was put (Wall-nut Bowling).
 *
 * <p>The nut leaves its cell and rolls <em>forward</em> - it never turns back toward the
 * house. Hitting a zombie knocks it into the neighbouring lane, and that lane change is the
 * whole mechanic: a nut carves a zigzag down the lawn, taking a hit out of everything it
 * passes, and only the top and bottom edges turn it around. Because it never stops, it is
 * not part of the board's furniture: {@link #occupiesCell} is false, so zombies do not eat
 * it and the shovel does not remove it - a zombie that could stop a bowling ball by chewing
 * on it would make the whole level unplayable.
 *
 * <p>Hits go through {@link ZombieEntity#damageImpact}, which lets armor absorb them, so
 * the original's hit counts fall out of the numbers rather than being special-cased: 650
 * damage kills a 200-health Zombie in one hit, breaks a Conehead's 370 cone on the first
 * and reaches the body on the second, and needs three against a 1100 Buckethead.
 */
public final class BowlCapability implements PlantCapability {
    /** Cells per second. */
    public static final float DEFAULT_SPEED = 6F;
    /**
     * Damage per hit.
     *
     * <p>Chosen so the armor ladder lands on the original's counts <em>with the armor model
     * this project already has</em> - a piece absorbs until it breaks and the breaking hit
     * does not carry into the body, so the body always dies on the hit after the armor is
     * gone. One hit must therefore be enough for 200 health and not enough for a 370 cone,
     * while two must be enough for a 1100 bucket: that is the band [550, 1100], and any
     * value in it gives 1 / 2 / 3.
     */
    public static final int DEFAULT_DAMAGE = 650;
    /** How close a zombie's centre must come, in cells. */
    public static final float DEFAULT_HIT_RADIUS = 0.45F;
    /**
     * Which hit starts paying coins.
     *
     * <p>2 means the second zombie a nut touches drops one coin, the third two, and so on -
     * the original's "every ricochet is worth more" ladder, with the first hit free because
     * that one is just the nut doing its job.
     */
    public static final int DEFAULT_COIN_FROM_HIT = 2;

    private final float speed;
    private final int damage;
    private final float hitRadius;
    private final int coinFromHit;
    private final boolean ricochet;

    /** Lane drift: {@code -1}, {@code 0} or {@code +1} rows per cell travelled. */
    private float directionY;
    /** Ticks left before this nut may damage another zombie. */
    private int hitCooldown;
    /** Zombies hit so far, which is also what the coin ladder is read from. */
    private int hits;

    public BowlCapability(float speed, int damage, float hitRadius, int coinFromHit, boolean ricochet) {
        this.speed = Math.max(0.1F, speed);
        this.damage = Math.max(1, damage);
        this.hitRadius = Math.max(0.1F, hitRadius);
        this.coinFromHit = Math.max(1, coinFromHit);
        this.ricochet = ricochet;
        this.directionY = 0F;
    }

    public static final MapCodec<BowlCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("speed", DEFAULT_SPEED).forGetter(BowlCapability::speed),
            Codec.INT.optionalFieldOf("damage", DEFAULT_DAMAGE).forGetter(BowlCapability::damage),
            Codec.FLOAT.optionalFieldOf("hit_radius", DEFAULT_HIT_RADIUS).forGetter(BowlCapability::hitRadius),
            Codec.INT.optionalFieldOf("coin_from_hit", DEFAULT_COIN_FROM_HIT).forGetter(BowlCapability::coinFromHit),
            Codec.BOOL.optionalFieldOf("ricochet", true).forGetter(BowlCapability::ricochet)
    ).apply(i, BowlCapability::new));

    public float speed() {
        return speed;
    }

    public int damage() {
        return damage;
    }

    public float hitRadius() {
        return hitRadius;
    }

    public int coinFromHit() {
        return coinFromHit;
    }

    public boolean ricochet() {
        return ricochet;
    }

    public int hits() {
        return hits;
    }

    /** {@code -1}, {@code 0} or {@code +1}: which way the last ricochet sent it. */
    public float laneDrift() {
        return directionY;
    }

    @Override
    public PlantCapability instantiate() {
        return new BowlCapability(speed, damage, hitRadius, coinFromHit, ricochet);
    }

    @Override
    public boolean occupiesCell(PlantEntity plant) {
        return false;
    }

    @Override
    public void onPlaced(PlantEntity plant, LevelAccess level) {
        plant.setState(EntityAnimations.ROLL);
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (hitCooldown > 0) {
            hitCooldown--;
        }
        // Always forward. A nut that turned around after a hit spent the rest of its life
        // rolling back toward the house, which is the opposite of what a bowling ball does:
        // the hit is what sends it into the next lane, not what sends it home.
        // Lane drift shortens the forward step so the nut keeps one speed whether it is
        // rolling straight or across: a diagonal nut that moved at full speed on both axes
        // would visibly outrun its straight counterpart.
        float step = speed / PvzceConstants.TICKS_PER_SECOND;
        float norm = (float) (1D / Math.sqrt(1D + directionY * directionY));
        float nextX = plant.cellX() + step * norm;
        float nextY = plant.cellY() + directionY * step * norm;

        // The lawn's top and bottom are walls, not exits: the nut bounces off them and keeps
        // going. Only the right edge retires it.
        float minY = 0.5F;
        float maxY = Math.max(minY, level.height() - 0.5F);
        if (nextY < minY) {
            nextY = minY + (minY - nextY);
            directionY = Math.abs(directionY);
        } else if (nextY > maxY) {
            nextY = maxY - (nextY - maxY);
            directionY = -Math.abs(directionY);
        }
        plant.setCellX(nextX);
        plant.setCellY(Math.max(minY, Math.min(maxY, nextY)));
        plant.setState(EntityAnimations.ROLL);

        ZombieEntity target = findTarget(plant, level);
        if (target != null) {
            strike(plant, target, level);
        }

        if (plant.cellX() > level.width() + 1F) {
            plant.remove();
        }
    }

    /** The zombie in the nut's lane that it is currently touching, or {@code null}. */
    private ZombieEntity findTarget(PlantEntity plant, LevelAccess level) {
        if (hitCooldown > 0) {
            return null;
        }
        ZombieEntity best = null;
        float bestDistance = Float.MAX_VALUE;
        for (ZombieEntity zombie : level.zombiesInRow(plant.gridY())) {
            if (zombie.isRemoved() || !zombie.canBeHitByGround()) {
                continue;
            }
            // Only what is ahead of it: a nut rolls forward, so a zombie it has already
            // passed (or is standing behind it) is not in the way.
            float ahead = zombie.cellX() - plant.cellX();
            if (ahead < -hitRadius || ahead > hitRadius) {
                continue;
            }
            float distance = Math.abs(ahead);
            if (distance < bestDistance) {
                best = zombie;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void strike(PlantEntity plant, ZombieEntity target, LevelAccess level) {
        hits++;
        target.damageImpact(damage, level);
        level.emitEffect(PvzceParticles.HIT_SPARK.toString(), target.cellX(), target.cellY(),
                PvzceSounds.EFFECT_BONK);
        if (hits >= coinFromHit) {
            // The ladder from the original: one coin on the second zombie, two on the
            // third, three on the fourth, and so on. The coin itself is the level's.
            level.dropCoin(target.cellX(), target.cellY(), hits - coinFromHit + 1);
            level.emitEffect("", plant.cellX(), plant.cellY(), PvzceSounds.UI_POINTS);
        }
        hitCooldown = 6;
        if (ricochet) {
            // Knocked into the next lane, still going forward. `ricochet: false` keeps it
            // rolling straight; there is no "bounce back" mode, because a ball that turns
            // around on contact is not what this mini-game is.
            directionY = pickLane(plant, level);
        } else {
            directionY = 0F;
        }
    }

    /**
     * Chooses the lane a hit knocks the nut into.
     *
     * <p>The lane change is what turns one Wall-nut into a combo, and the original biases it
     * toward the middle of the lawn - always inward from the top and bottom rows, and a coin
     * flip in the centre - so a nut does not simply hug an edge.
     */
    private float pickLane(PlantEntity plant, LevelAccess level) {
        int height = Math.max(2, level.height());
        float middle = (height - 1) / 2F;
        float bias = middle <= 0F ? 0F : (plant.gridY() - middle) / middle;
        float inwardChance = 0.5F + 0.5F * Math.abs(bias);
        boolean inward = level.random().nextFloat() < inwardChance;
        int inwardSign = bias > 0F ? -1 : bias < 0F ? 1 : (level.random().nextBoolean() ? 1 : -1);
        return inward ? inwardSign : -inwardSign;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putFloat("directionY", directionY);
        tag.putInt("hitCooldown", hitCooldown);
        tag.putInt("hits", hits);
    }

    @Override
    public void load(CompoundTag tag) {
        directionY = tag.getFloat("directionY");
        hitCooldown = tag.getInt("hitCooldown");
        hits = tag.getInt("hits");
    }
}

package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.common.PvzceParticles;

import java.util.Optional;

/**
 * Single-use explosive plant, covering both classic shapes:
 *
 * <ul>
 *   <li>{@link Trigger#TIMED} - arms for {@code fuse_ticks} and then detonates
 *       (cherry bomb);</li>
 *   <li>{@link Trigger#PROXIMITY} - arms for {@code fuse_ticks}, then waits for a
 *       ground zombie to step within {@code trigger_range} cells (potato mine).</li>
 * </ul>
 *
 * <p>Previously these were two separate string branches ({@code pvzce:ash},
 * {@code pvzce:mine}) with the fuse smuggled through {@code attack_interval} and
 * the mine's proximity rule hard-coded; the two also disagreed on which position
 * they used as the blast centre.
 *
 * <p>Three things about this capability are worth knowing before changing it:
 *
 * <ul>
 *   <li><b>The blast is a registered damage type.</b> {@code damage_type} defaults to
 *       {@code pvzce:ash}, whose whole meaning is "armour does not absorb it" - that
 *       is why a cherry bomb kills a Buckethead.</li>
 *   <li><b>The plant cannot be destroyed while it is arming.</b> {@link #invulnerable}
 *       answers true from the moment it is placed until the blast, so zombies bite it
 *       (and a Gargantuar swings at it) for nothing, as in the original. Once the blast
 *       has happened the plant is gone and answers false again.</li>
 *   <li><b>It stays on the field for {@link #LINGER_TICKS} after detonating.</b> The
 *       blast is instantaneous on the server, but the client draws an {@code explode}
 *       clip that is longer than one tick, and entity state is only published every
 *       third tick - removing the plant in the same tick it went off meant the state
 *       was usually never sent at all and the client just saw it disappear.</li>
 * </ul>
 */
public final class ExplosiveCapability implements PlantCapability {
    public enum Trigger {
        TIMED,
        PROXIMITY,
        /**
         * Armed for the fuse, then takes its whole row.
         *
         * <p>The jalapeno. A row is not a radius: a square wide enough to reach the far end of
         * a lawn also reaches the rows beside it, so the shape has to be stated as a row
         * rather than approximated with a number - and a number that happens to fit a 9-wide
         * board stops short of a 12-wide one.
         */
        ROW
    }

    public static final int DEFAULT_FUSE = 60;
    /** Minimum blast radius so a proximity mine still covers its own cell. */
    public static final float MIN_RADIUS = 0.55F;
    /**
     * The damage type a blast lands as when the content does not name one.
     *
     * <p>The ash line is the original meaning of "explosion" in this game, and the one
     * property the type carries is exactly the one an explosion needs.
     */
    public static final Identifier DEFAULT_DAMAGE_TYPE = PvzceIds.DAMAGE_ASH;
    /**
     * How long the plant lingers on the field after its blast, in ticks.
     *
     * <p>Half a second: longer than every built-in {@code explode} clip and long enough
     * that the state is published (the entity sync runs every third tick, and the state
     * is published on the blast tick either way). Without it the plant was removed in
     * the tick it detonated, so the client's only news of the explosion was a despawn.
     *
     * <p>The plant is <em>not</em> a plant while it lingers: {@link #occupiesCell} is
     * false, so zombies walk past it and the shovel cannot reach it. This is the server
     * holding a drawing on screen, not a second life.
     */
    public static final int LINGER_TICKS = 30;

    private final Trigger trigger;
    private final int fuseTicks;
    private final float radius;
    private final int damage;
    private final float triggerRange;
    private final boolean square;
    private final Optional<Identifier> sound;
    private final Identifier damageType;

    private int fuse;
    /** Ticks left of the explosion drawing; {@link #LINGER_NONE} before the blast. */
    private int linger = LINGER_NONE;

    /** Sentinel for "this mine has not gone off yet". */
    private static final int LINGER_NONE = -1;

    public ExplosiveCapability(Trigger trigger, int fuseTicks, float radius, int damage, float triggerRange,
                               boolean square, Optional<Identifier> sound, Identifier damageType) {
        this.trigger = trigger;
        this.fuseTicks = Math.max(0, fuseTicks);
        this.radius = Math.max(0F, radius);
        this.damage = Math.max(0, damage);
        this.triggerRange = Math.max(0F, triggerRange);
        this.square = square;
        this.sound = sound;
        this.damageType = damageType == null ? DEFAULT_DAMAGE_TYPE : damageType;
        this.fuse = this.fuseTicks;
    }

    public static final MapCodec<ExplosiveCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.optionalFieldOf("trigger", "timed")
                    .xmap(ExplosiveCapability::parseTrigger, Trigger::name)
                    .forGetter(ExplosiveCapability::trigger),
            Codec.INT.optionalFieldOf("fuse_ticks", DEFAULT_FUSE).forGetter(ExplosiveCapability::fuseTicks),
            Codec.FLOAT.optionalFieldOf("radius", 1F).forGetter(ExplosiveCapability::radius),
            Codec.INT.optionalFieldOf("damage", 1800).forGetter(ExplosiveCapability::damage),
            Codec.FLOAT.optionalFieldOf("trigger_range", 0.6F).forGetter(ExplosiveCapability::triggerRange),
            // The original's ash line is authored in *cells*: a cherry bomb covers the nine
            // around it, a potato mine only the one it is standing in. A square footprint is
            // that statement; the radial alternative is for the hits that are really a
            // distance and is what this capability did before the flag existed, so the
            // default stays radial for any content that does not say.
            Codec.BOOL.optionalFieldOf("square", false).forGetter(ExplosiveCapability::square),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(ExplosiveCapability::sound),
            Identifier.CODEC.optionalFieldOf("damage_type", DEFAULT_DAMAGE_TYPE)
                    .forGetter(ExplosiveCapability::damageType)
    ).apply(i, ExplosiveCapability::new));

    private static Trigger parseTrigger(String name) {
        if (name == null) {
            return Trigger.TIMED;
        }
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "proximity" -> Trigger.PROXIMITY;
            case "row" -> Trigger.ROW;
            default -> Trigger.TIMED;
        };
    }

    public Trigger trigger() {
        return trigger;
    }

    public int fuseTicks() {
        return fuseTicks;
    }

    public float radius() {
        return radius;
    }

    public int damage() {
        return damage;
    }

    public float triggerRange() {
        return triggerRange;
    }

    /** True when the blast covers a square block of cells rather than a radius. */
    public boolean square() {
        return square;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    /** The registered damage type this blast lands as; {@code pvzce:ash} by default. */
    public Identifier damageType() {
        return damageType;
    }

    /** Remaining fuse ticks; zero means "armed and ready to detonate". */
    public int fuseLeft() {
        return fuse;
    }

    @Override
    public PlantCapability instantiate() {
        return new ExplosiveCapability(trigger, fuseTicks, radius, damage, triggerRange, square, sound, damageType);
    }

    /**
     * Nothing a zombie can do to an unexploded bomb.
     *
     * <p>True for the whole fuse, and false again once the blast has happened - at which
     * point the plant is a drawing on its way out and has no business absorbing hits
     * either. {@code PlantEntity.damageFrom} asks every capability and drops the hit if
     * any of them says yes, which is the ash line's "a zombie can chew on a cherry bomb
     * for the whole fuse and it still goes off".
     */
    @Override
    public boolean invulnerable(PlantEntity plant) {
        return fuse > 0;
    }

    /**
     * A detonated bomb is no longer the plant in its cell.
     *
     * <p>{@code plantAt} skips a plant that answers false, so the zombie that was
     * biting it stops (its {@code EAT} state goes back to {@code walk}) and walks on
     * top of the explosion instead of being held by a plant that is already gone - the
     * same door the rolling bowling Wall-nut uses.
     */
    @Override
    public boolean occupiesCell(PlantEntity plant) {
        return linger == LINGER_NONE;
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (linger != LINGER_NONE) {
            // The blast has already happened: everything left is the drawing. The plant
            // removes itself when the count runs out, which is what makes this a state on
            // the plant rather than a special case in the level's removal pass.
            plant.setState(EntityAnimations.EXPLODE);
            linger--;
            if (linger <= 0) {
                plant.remove();
            }
            return;
        }
        if (fuse > 0) {
            fuse--;
            plant.setState(trigger == Trigger.PROXIMITY
                    ? (fuse == 0 ? EntityAnimations.ARMED : EntityAnimations.GROW)
                    // A timed explosive has nothing to grow into. The ash line's own art
                    // says so: only the two mines have a `grow` clip, and asking a cherry
                    // bomb for one made the animation manager fall back to `idle` on every
                    // request for the whole fuse (see AnimationManager.play).
                    : EntityAnimations.IDLE);
            if (trigger != Trigger.PROXIMITY && fuse == 0) {
                detonate(plant, level);
            }
            return;
        }
        if (trigger == Trigger.ROW) {
            detonate(plant, level);
            return;
        }
        if (trigger == Trigger.TIMED) {
            detonate(plant, level);
            return;
        }
        ZombieEntity target = level.enemiesInRow(plant.gridY(), plant.team()).stream()
                .filter(z -> !z.isRemoved() && z.canBeHitByGround()
                        && Math.abs(z.cellX() - plant.cellX()) < triggerRange)
                .findFirst()
                .orElse(null);
        if (target == null) {
            // The loop, not the emergence: `armed` is the one-shot that ends on this, and
            // re-requesting it every tick restarted the mine's rise for as long as it waited.
            plant.setState(EntityAnimations.ARMED_LOOP);
            return;
        }
        detonate(plant, level);
    }

    private void detonate(PlantEntity plant, LevelAccess level) {
        plant.setState(EntityAnimations.EXPLODE);
        // The blast has to cover whatever set it off. A proximity mine triggers on
        // "a zombie is within trigger_range", so a mine whose blast falls short of that
        // range detonates while the zombie is still outside the damage - which is what the
        // shipped potato mine did (radius 0.55, trigger_range 0.6) and made every detonation
        // a guaranteed miss: a zombie walks 0.003 cells per tick, so the first tick inside
        // the trigger zone left it at ~0.599, just past the 0.55 blast.
        // The two values are still authored separately (a mine may want a wider blast), but
        // the blast can never be narrower than the zone that armed it.
        float blastRadius = Math.max(MIN_RADIUS, radius);
        if (trigger == Trigger.PROXIMITY) {
            // How far from the centre the blast actually reaches: a radial blast reaches
            // `radius`, a square one reaches the far edge of the cell `radius` cells away
            // (see LevelServer.damageArea). The zone that armed the mine has to fit inside
            // that, or the zombie that set it off is standing outside the damage.
            float reach = square ? blastRadius + 0.5F : blastRadius;
            if (triggerRange > reach) {
                blastRadius = square ? triggerRange - 0.5F : triggerRange;
            }
        }
        if (trigger == Trigger.ROW) {
            // The whole row, which is a shape and not a distance - see LevelAccess.damageRow.
            level.damageRow(ZombieEntity.damageType(damageType), plant.gridY(), damage, plant.team());
        } else {
            level.damageArea(ZombieEntity.damageType(damageType), plant.cellX(), plant.cellY(),
                    blastRadius, damage, plant.team(), square);
        }
        level.emitEffect(PvzceParticles.EXPLOSION_POW.toString(), plant.cellX(), plant.cellY(),
                sound.orElseGet(() -> plant.def().sounds().explode().orElse(PvzceSounds.EFFECT_EXPLOSION)));
        // The blast is over as far as the simulation is concerned, but the plant stays
        // for LINGER_TICKS so the client can actually draw what just happened. The state
        // goes out on this tick rather than on whichever third tick comes next, so the
        // clip starts when the blast does.
        if (level instanceof LevelServer server) {
            server.requestEntitySync();
        }
        linger = LINGER_TICKS;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("fuse", fuse);
        tag.putInt("linger", linger);
    }

    @Override
    public void load(CompoundTag tag) {
        fuse = tag.getInt("fuse");
        // A save written before the linger existed has no such key; NBT's getInt would
        // hand back 0, which reads as "the drawing is finished" and would remove a mine
        // that has not gone off. LINGER_NONE is what "not detonated" is spelled as.
        linger = tag.contains("linger") ? tag.getInt("linger") : LINGER_NONE;
    }
}

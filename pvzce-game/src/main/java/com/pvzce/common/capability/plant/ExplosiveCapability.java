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
 *   <li><b>It stays on the field after detonating</b>, for {@link #DEFAULT_LINGER_TICKS} ticks or
 *       whatever {@code linger_ticks} the content asks for. The blast is instantaneous on the
 *       server, but the client draws an {@code explode} clip that is longer than one tick, and
 *       entity state is only published every third tick - removing the plant in the same tick it
 *       went off meant the state was usually never sent at all and the client just saw it
 *       disappear. The number is per plant because the clips are not the same length: a cherry
 *       bomb's burst is about a second, the doom-shroom's growing cloud nearly three, and one
 *       constant for both either cuts the long one off or holds a spent bomb on the lawn.</li>
 *
 *   <li><b>What the blast looks like is a list.</b> {@code particles} names the pieces of the
 *       effect in the order they are emitted, at the plant's own cell; each piece places itself
 *       with its own {@code offset_x}/{@code offset_y} (see {@code ParticleDef.ParticleMotion}).
 *       One id is the common case - a flash - and several is a composition: the doom-shroom's
 *       mushroom cloud is nine.</li>
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
     * What a blast draws when the content does not say.
     *
     * <p>The ash line's own flash, which is what every explosive in the game had before this
     * list existed. A plant with a shape of its own - the doom-shroom's cloud - names its
     * pieces instead.
     */
    public static final java.util.List<Identifier> DEFAULT_PARTICLES =
            java.util.List.of(PvzceParticles.EXPLOSION_POW);

    /**
     * How long the plant lingers on the field after its blast, in ticks, when the content
     * does not say.
     *
     * <p>Half a second: long enough that the state is published (the entity sync runs every
     * third tick, and the state is published on the blast tick either way) and that a short
     * burst is visible. A plant whose {@code explode} clip is longer than this has to name a
     * bigger {@code linger_ticks} - see the class comment; the potato mine's five-tick burst is
     * what this default was sized for.
     *
     * <p>The plant is <em>not</em> a plant while it lingers: {@link #occupiesCell} is
     * false, so zombies walk past it and the shovel cannot reach it. This is the server
     * holding a drawing on screen, not a second life.
     */
    public static final int DEFAULT_LINGER_TICKS = 30;
    /**
     * How wide the hole an explosion leaves is, in cells: just the plant's own cell.
     *
     * <p>Half a cell, which is the same "radius 0.5 = this one cell" the potato mine's blast
     * uses. The crater is <em>where the plant was</em>, not where its blast reached - the
     * original's Doom-shroom leaves one sunken tile, the one it stood on, and reusing the blast
     * radius turned a 7x7 explosion into a 7x7 hole: the lawn around the mushroom became
     * unplantable for the whole recovery, which is neither what the original does nor a cost the
     * player can play around (the point of the crater is that it is <em>one</em> cell you chose
     * to give up).
     */
    public static final float CRATER_RADIUS = 0.5F;

    private final Trigger trigger;
    private final int fuseTicks;
    private final float radius;
    private final int damage;
    private final float triggerRange;
    private final boolean square;
    private final boolean leavesCrater;
    /** True when this blast is fire, and therefore takes the zamboni's ice off the lawn. */
    private final boolean meltsIce;
    private final Optional<Identifier> sound;
    private final Identifier damageType;
    /** The pieces of this blast's effect, emitted in order at the plant's cell. */
    private final java.util.List<Identifier> particles;
    /** How long the plant stays drawn after the blast, in ticks. */
    private final int lingerTicks;

    private int fuse;
    /** Ticks left of the explosion drawing; {@link #LINGER_NONE} before the blast. */
    private int linger = LINGER_NONE;

    /** Sentinel for "this mine has not gone off yet". */
    private static final int LINGER_NONE = -1;

    public ExplosiveCapability(Trigger trigger, int fuseTicks, float radius, int damage, float triggerRange,
                               boolean square, boolean leavesCrater, Optional<Identifier> sound,
                               Identifier damageType) {
        this(trigger, fuseTicks, radius, damage, triggerRange, square, leavesCrater, sound,
                damageType, DEFAULT_PARTICLES, DEFAULT_LINGER_TICKS);
    }

    public ExplosiveCapability(Trigger trigger, int fuseTicks, float radius, int damage, float triggerRange,
                               boolean square, boolean leavesCrater, Optional<Identifier> sound,
                               Identifier damageType, java.util.List<Identifier> particles,
                               int lingerTicks) {
        this(trigger, fuseTicks, radius, damage, triggerRange, square, leavesCrater, sound,
                damageType, particles, lingerTicks, false);
    }

    /**
     * @param meltsIce true when this blast is fire: the blast then also melts the ice a zamboni
     *                 left, on top of whatever it damaged. Opt-in content rather than a property
     *                 of "explosion", because a potato mine and a jack-in-the-box are explosions
     *                 too and neither of them is fire - the original only lets the cherry bomb
     *                 and the jalapeno clear a frozen lane.
     */
    public ExplosiveCapability(Trigger trigger, int fuseTicks, float radius, int damage, float triggerRange,
                               boolean square, boolean leavesCrater, Optional<Identifier> sound,
                               Identifier damageType, java.util.List<Identifier> particles,
                               int lingerTicks, boolean meltsIce) {
        this.trigger = trigger;
        this.fuseTicks = Math.max(0, fuseTicks);
        this.radius = Math.max(0F, radius);
        this.damage = Math.max(0, damage);
        this.triggerRange = Math.max(0F, triggerRange);
        this.square = square;
        this.leavesCrater = leavesCrater;
        this.meltsIce = meltsIce;
        this.sound = sound;
        this.damageType = damageType == null ? DEFAULT_DAMAGE_TYPE : damageType;
        // An empty list has to mean the default rather than "draw nothing": the codec reads a
        // missing field as empty, and a blast that silently draws nothing is indistinguishable
        // from a broken definition. Content that really wants no effect names a particle with
        // no texture, which is a different statement.
        this.particles = particles == null || particles.isEmpty()
                ? DEFAULT_PARTICLES
                : java.util.List.copyOf(particles);
        this.lingerTicks = Math.max(1, lingerTicks);
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
            // Only the doom shroom leaves one; a cherry bomb's ash and a jalapeno's are not
            // holes in the lawn, so this is opt-in content rather than a property of blasts.
            Codec.BOOL.optionalFieldOf("leaves_crater", false)
                    .forGetter(ExplosiveCapability::leavesCrater),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(ExplosiveCapability::sound),
            Identifier.CODEC.optionalFieldOf("damage_type", DEFAULT_DAMAGE_TYPE)
                    .forGetter(ExplosiveCapability::damageType),
            // What the blast draws, in emission order. One id is a flash; several are a
            // composition that places itself (see the class comment).
            Identifier.CODEC.listOf().optionalFieldOf("particles", DEFAULT_PARTICLES)
                    .forGetter(ExplosiveCapability::particles),
            // How long the plant stays on the field after the blast. Has to cover its own
            // `explode` clip or the animation is cut off mid-gesture.
            Codec.INT.optionalFieldOf("linger_ticks", DEFAULT_LINGER_TICKS)
                    .forGetter(ExplosiveCapability::lingerTicks),
            // The fire half of the crater idea, and last because that is where the constructor
            // takes it: only a blast that *is* fire takes the zamboni's ice back off the lawn,
            // and "is this fire" cannot be derived from "is this a blast".
            Codec.BOOL.optionalFieldOf("melts_ice", false).forGetter(ExplosiveCapability::meltsIce)
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

    /** True when this plant's blast leaves its footprint as a crater. */
    public boolean leavesCrater() {
        return leavesCrater;
    }

    /** True when this plant's blast is fire, and so melts the ice a zamboni left. */
    public boolean meltsIce() {
        return meltsIce;
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

    /**
     * Sets the fuse to nothing, so the next tick is the blast.
     *
     * <p>For a caller that has just <em>placed</em> this bomb rather than watched it arm: the
     * apocalypse mutation summons a whole lawn of Doom-shrooms at once, and making the player wait
     * out ninety ticks of arming while the lawn is covered in them is ninety ticks of a board that
     * cannot be played. The plant's own fuse is untouched - a Doom-shroom bought from the card bar
     * still arms on its own.
     *
     * <p>{@code linger} is deliberately not set here: the blast happens through the ordinary
     * {@link #tick}, which is also what draws it.
     */
    public void detonateNow() {
        if (fuse > 0) {
            fuse = 0;
        }
    }

    /** The effect this blast draws, in emission order. Never empty. */
    public java.util.List<Identifier> particles() {
        return particles;
    }

    /** Ticks the plant stays drawn after the blast, which has to cover its {@code explode} clip. */
    public int lingerTicks() {
        return lingerTicks;
    }

    @Override
    public PlantCapability instantiate() {
        return new ExplosiveCapability(trigger, fuseTicks, radius, damage, triggerRange, square,
                leavesCrater, sound, damageType, particles, lingerTicks, meltsIce);
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
        if (meltsIce) {
            // Fire takes the zamboni's lane back. The shape follows the blast the same way the
            // damage did: a jalapeno burns one whole row and a cherry bomb burns the nine cells
            // around itself, so the ice that goes is the ice that was in the fire.
            if (trigger == Trigger.ROW) {
                level.meltIceRow(plant.gridY());
            } else {
                level.meltIce(plant.cellX(), plant.cellY(), blastRadius, square);
            }
        }
        if (leavesCrater) {
            // The hole the plant made, in its own cell - not the footprint of the blast. See
            // CRATER_RADIUS: the doom shroom is the one explosive that does not leave the lawn
            // as it found it, and it leaves *one* tile that way.
            level.leaveCraters(plant.cellX(), plant.cellY(), CRATER_RADIUS, false);
        }
        // The effect, piece by piece, from the plant's own cell: a composition is a list, and
        // each piece is placed by its own birth offset (see ParticleMotion#offsetX). The sound
        // rides on the first piece only - every emit carries the whole effect event, so asking
        // for it at each stop would play one blast as many times as the cloud has parts.
        Identifier blastSound = sound.orElseGet(
                () -> plant.def().sounds().explode().orElse(PvzceSounds.EFFECT_EXPLOSION));
        for (int i = 0; i < particles.size(); i++) {
            level.emitEffect(particles.get(i).toString(), plant.cellX(), plant.cellY(),
                    i == 0 ? blastSound : null);
        }
        if (trigger == Trigger.ROW) {
            // A row's worth of fire, one tongue per cell along it. The original draws the
            // jalapeno's blast as one long flame animation spanning the lane, spawned cell by cell;
            // here it is the same particle emitted down the row, which is what makes it a *row*
            // rather than the round cloud the cherry bomb draws - the reported "火爆辣椒的特效错误
            // ……应该是当前整行的火焰".
            //
            // The whole board and not the cells to the right: a jalapeno is planted in the middle
            // of a lawn and burns both ways, and `damageRow` above already reaches every cell.
            // Derived from the trigger rather than declared, because "a row" *is* this shape - the
            // cherry bomb's square gets the cloud and no square blast is a wall of flame.
            float rowY = plant.gridY() + 0.5F;
            for (int cell = 0; cell < level.width(); cell++) {
                level.emitEffect(PvzceParticles.JALAPENO_FIRE.toString(),
                        cell + 0.5F, rowY, null);
            }
        }
        // The blast is over as far as the simulation is concerned, but the plant stays for its
        // own linger so the client can actually draw what just happened - which for the
        // doom-shroom is a two-and-three-quarter-second cloud, not the half second a flash
        // needs. The state goes out on this tick rather than on whichever third tick comes
        // next, so the clip starts when the blast does.
        if (level instanceof LevelServer server) {
            server.requestEntitySync();
        }
        linger = lingerTicks;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("fuse", fuse);
        tag.putInt("linger", linger);
    }

    @Override
    public void load(CompoundTag tag) {
        fuse = tag.getInt("fuse");
        linger = tag.getInt("linger");
    }
}

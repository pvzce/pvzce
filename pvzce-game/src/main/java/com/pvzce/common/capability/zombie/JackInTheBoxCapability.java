package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.DamageTypeDef;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.Optional;

/**
 * Jack-in-the-box: limps in, winds up, and goes off (the Jack-in-the-Box Zombie).
 *
 * <p>The one zombie whose threat is a <em>place</em> rather than a lane. It walks slowly and
 * does nothing at all until its fuse runs out, and then everything standing near it - plants
 * and zombies alike - is gone. That is what makes it a decision: the answer is to shoot it
 * early, while it is still far from anything worth keeping.
 *
 * <h2>The fuse is a distance, not a clock</h2>
 *
 * <p>It arms the box when it has <em>walked</em> {@code fuse_cells} cells, which is the
 * original's rule ({@code 450 + Rand(300)} px) and not a timer with the same average: a
 * jack-in-the-box that spends twenty seconds chewing through a wall-nut has not advanced its
 * fuse by a single cell, and one that is chilled (or sped up by a mutation) blows up after the
 * same distance at a different time. The count is the ground it covered, so it is also what a
 * player reads: "it goes off about halfway down the lane".
 *
 * <h2>What the blast is worth</h2>
 *
 * <p>Everything inside {@code plant_radius} dies - not takes damage, dies - and every zombie
 * inside {@code zombie_radius} takes the original's 1800. Both go through the one damage path
 * the rest of the game uses: plants are hit through {@code PlantEntity.damage} the way the
 * ZomBotany line's own blast does, and zombies through
 * {@link LevelAccess#damageArea} - with <b>no source team</b>, because the box is not fighting
 * for a side. It is a bomb on the lawn: a charmed zombie standing beside it is as dead as the
 * peashooter, and a zombie walking past its own team's bomb is not spared. That is what
 * {@code null} as the source means (see {@code LevelServer.isEnemyOf}), and it is the only
 * caller in the game that passes it.
 *
 * <p>The zombie itself dies of it, and dies <b>unpaid</b>: the player did not kill this one, so
 * the sun and the coin roll that a kill normally pays would be a reward for standing next to a
 * bomb.
 */
public final class JackInTheBoxCapability implements ZombieCapability {
    /**
     * How far it walks before the box opens, in cells.
     *
     * <p>The original rolls {@code 450 + Rand(300)} px, i.e. 5.6 to 9.4 cells. This is the
     * middle of that band and it is <em>not</em> rolled: "when does it go off" has to be a fact
     * the level author and the player can both reason about (and a test can assert), and a
     * rolled fuse makes the same wave play out differently for no gain the player can see. A
     * definition that wants the original's spread writes {@code fuse_roll_cells}.
     */
    public static final float DEFAULT_FUSE_CELLS = 6.5F;
    /**
     * How much farther the fuse may run, rolled once when it arms, in cells.
     *
     * <p>The original's {@code Rand(300)}. Off by default - see {@link #DEFAULT_FUSE_CELLS}.
     */
    public static final float DEFAULT_FUSE_ROLL_CELLS = 0F;
    /**
     * How long the box stays open before it goes off, in ticks.
     *
     * <p>The original's {@code PHASE_JACK_IN_THE_BOX_POPPING}. The exported {@code pop} clip is
     * played at the rate that fills exactly this long, so the lid is all the way up on the tick
     * the blast lands.
     */
    public static final int DEFAULT_POP_TICKS = 110;
    /** How far the blast reaches for plants, in cells: the original's 90 px. */
    public static final float DEFAULT_PLANT_RADIUS = 1.1F;
    /** And for other zombies: the original's 115 px. */
    public static final float DEFAULT_ZOMBIE_RADIUS = 1.4F;
    /**
     * What the blast does to the zombies it covers.
     *
     * <p>The original's own number for {@code KillAllZombiesInRadius}. It is damage rather than
     * a flag because {@link LevelAccess#damageArea} is the one radius path and damage is what
     * it takes: an ordinary body dies to a fraction of it, a buckethead's armour is spent and
     * then some, and a gargantuar survives - which is exactly the original's arithmetic.
     */
    public static final int BLAST_DAMAGE = 1800;

    private final float fuseCells;
    private final float fuseRollCells;
    private final int popTicks;
    private final float plantRadius;
    private final float zombieRadius;
    private final Optional<Identifier> sound;

    /** Whether the fuse has been rolled; before that the walk has not started. */
    private boolean armed;
    /** Where the walk started, so the fuse is the distance covered rather than a sum of ticks. */
    private float fuseStartX;
    /** How far this box's fuse runs, rolled once when it arms. */
    private float fuse;
    /** Ticks left with the box open; 0 while it is still walking. */
    private int popTicksLeft;
    /** True once it has gone off, so the blast can only ever happen once. */
    private boolean exploded;

    public JackInTheBoxCapability(float fuseCells, float fuseRollCells, int popTicks,
                                  float plantRadius, float zombieRadius,
                                  Optional<Identifier> sound) {
        this.fuseCells = Math.max(0.1F, fuseCells);
        this.fuseRollCells = Math.max(0F, fuseRollCells);
        this.popTicks = Math.max(1, popTicks);
        this.plantRadius = Math.max(0.1F, plantRadius);
        this.zombieRadius = Math.max(0.1F, zombieRadius);
        this.sound = sound == null ? Optional.empty() : sound;
    }

    public static final MapCodec<JackInTheBoxCapability> CODEC =
            RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.FLOAT.optionalFieldOf("fuse_cells", DEFAULT_FUSE_CELLS)
                            .forGetter(JackInTheBoxCapability::fuseCells),
                    Codec.FLOAT.optionalFieldOf("fuse_roll_cells", DEFAULT_FUSE_ROLL_CELLS)
                            .forGetter(JackInTheBoxCapability::fuseRollCells),
                    Codec.INT.optionalFieldOf("pop_ticks", DEFAULT_POP_TICKS)
                            .forGetter(JackInTheBoxCapability::popTicks),
                    Codec.FLOAT.optionalFieldOf("plant_radius", DEFAULT_PLANT_RADIUS)
                            .forGetter(JackInTheBoxCapability::plantRadius),
                    Codec.FLOAT.optionalFieldOf("zombie_radius", DEFAULT_ZOMBIE_RADIUS)
                            .forGetter(JackInTheBoxCapability::zombieRadius),
                    Identifier.CODEC.optionalFieldOf("sound").forGetter(JackInTheBoxCapability::sound)
            ).apply(i, JackInTheBoxCapability::new));

    public float fuseCells() {
        return fuseCells;
    }

    public float fuseRollCells() {
        return fuseRollCells;
    }

    public int popTicks() {
        return popTicks;
    }

    public float plantRadius() {
        return plantRadius;
    }

    public float zombieRadius() {
        return zombieRadius;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    /** True once the box is open; the zombie stands still from then on. */
    public boolean isPopping() {
        return popTicksLeft > 0;
    }

    /** True once it has gone off; only ever read by a test or a debug command. */
    public boolean hasExploded() {
        return exploded;
    }

    @Override
    public ZombieCapability instantiate() {
        return new JackInTheBoxCapability(fuseCells, fuseRollCells, popTicks, plantRadius,
                zombieRadius, sound);
    }

    @Override
    public void tick(ZombieEntity zombie, LevelAccess level) {
        if (exploded) {
            return;
        }
        if (popTicksLeft > 0) {
            // Published from here rather than through `walkState`: the box opening is what this
            // zombie is doing, and `tickMovement` answers true for the whole of it - which is
            // exactly the tick the walk loop (the only caller of `walkState`) does not run.
            zombie.setAnimation(EntityAnimations.POP);
            if (--popTicksLeft <= 0) {
                explode(zombie, level);
            }
            return;
        }
        if (!armed) {
            armed = true;
            fuseStartX = zombie.cellX();
            fuse = fuseCells + (fuseRollCells <= 0F ? 0F : level.random().nextFloat() * fuseRollCells);
        }
        if (fuseStartX - zombie.cellX() >= fuse) {
            openTheBox(zombie, level);
        }
    }

    /**
     * A jack-in-the-box with the lid up stands still.
     *
     * <p>It is not eating, not walking and not shootable-in-passing: the 110 ticks are the last
     * chance to kill it, and a body still advancing down the lane during them would move the
     * blast's centre after the player had already read where it was going to land.
     */
    @Override
    public boolean tickMovement(ZombieEntity zombie, LevelAccess level) {
        return popTicksLeft > 0;
    }

    private void openTheBox(ZombieEntity zombie, LevelAccess level) {
        popTicksLeft = popTicks;
        zombie.setAnimation(EntityAnimations.POP);
        level.emitEffect("", zombie.cellX(), zombie.cellY(),
                sound.orElseGet(() -> zombie.def().sounds().special()
                        .orElse(PvzceSounds.ZOMBIE_JACK_IN_THE_BOX)));
    }

    /**
     * The blast: the plants inside the radius, then the zombies inside theirs, then itself.
     *
     * <p>Itself first would be tidier and is wrong: {@code damageArea} skips bodies that are no
     * longer alive, and the box's own corpse must not be paid for - so it is killed through
     * {@link ZombieEntity#selfDestruct} <em>before</em> the area hit, which both keeps the blast
     * from landing on it twice and keeps the neighbouring zombies (which are still alive) in the
     * blast.
     */
    private void explode(ZombieEntity zombie, LevelAccess level) {
        exploded = true;
        float x = zombie.cellX();
        float y = zombie.cellY();
        killPlantsAround(zombie, level);
        zombie.selfDestruct(level);
        DamageTypeDef type = ZombieEntity.damageType(PvzceIds.DAMAGE_IMPACT);
        // No source team: a bomb on the lawn does not have a side. See the class doc.
        level.damageArea(type, x, y, zombieRadius, BLAST_DAMAGE, null);
        // The original's own two effects, converted with the rest of its particle set: the
        // cloud, and the spring that came out of the box.
        level.emitEffect(PvzceParticles.JACK_EXPLODE_BIG_CLOUD.toString(), x, y,
                PvzceSounds.EFFECT_EXPLOSION);
        level.emitEffect(PvzceParticles.JACK_EXPLODE_SPROING.toString(), x, y,
                PvzceSounds.ZOMBIE_JACK_SURPRISE);
    }

    /**
     * Kills every plant within {@code plant_radius} of the zombie's own centre.
     *
     * <p>A radius rather than a row: the original measures a distance, and 90 px is more than
     * the 80 px between two rows - so a jack-in-the-box that goes off next to a plant takes the
     * plant <em>above</em> it with it. Rows are walked explicitly because that is where plants
     * live ({@code plantsAt} takes a cell), and the distance test is on the centres, which is
     * the same measurement the original makes.
     */
    private void killPlantsAround(ZombieEntity zombie, LevelAccess level) {
        for (int row = zombie.gridY() - 1; row <= zombie.gridY() + 1; row++) {
            if (row < 0 || row >= level.height()) {
                continue;
            }
            for (int column = 0; column < level.width(); column++) {
                for (PlantEntity plant : level.plantsAt(column, row)) {
                    if (plant.isRemoved()) {
                        continue;
                    }
                    float dx = plant.cellX() - zombie.cellX();
                    float dy = plant.cellY() - zombie.cellY();
                    if (Math.hypot(dx, dy) > plantRadius) {
                        continue;
                    }
                    // `damage` and not `damageFrom`: a bomb that goes off beside a plant reaches
                    // the one sitting on its own fuse, which is the same reading the ash line and
                    // the ZomBotany bomber use. Its whole health, because the rule is "dies",
                    // not "takes a hit".
                    plant.damage(plant.health());
                }
            }
        }
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("armed", armed ? 1 : 0);
        tag.putFloat("fuseStartX", fuseStartX);
        tag.putFloat("fuse", fuse);
        tag.putInt("popTicksLeft", popTicksLeft);
        tag.putInt("exploded", exploded ? 1 : 0);
    }

    @Override
    public void load(CompoundTag tag) {
        // A save with no block - one written before this zombie existed - is read as a fresh
        // box: not armed, no fuse rolled, nothing exploded. That is also the only reading that
        // cannot skip a blast the player has not seen.
        armed = tag.getInt("armed") != 0;
        fuseStartX = tag.getFloat("fuseStartX");
        fuse = tag.contains("fuse") ? tag.getFloat("fuse") : fuseCells;
        popTicksLeft = Math.max(0, tag.getInt("popTicksLeft"));
        exploded = tag.getInt("exploded") != 0;
    }
}

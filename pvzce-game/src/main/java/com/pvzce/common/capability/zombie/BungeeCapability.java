package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.List;
import java.util.Optional;

/**
 * Drops out of the sky, takes a plant, and leaves with it (the bungee zombie).
 *
 * <p>A raid rather than an assault: it appears above the lawn, picks one plant - losing it, not
 * just damaging it - and is gone. The player cannot shoot it on the way down; the only answers are
 * to have nothing worth taking, or to be somewhere else when it lands.
 *
 * <h2>Why it is invulnerable while it works</h2>
 *
 * <p>Because the alternative is a zombie that arrives, is shot once and dies, and the plant is
 * never in danger - which makes the whole raid a free kill rather than a threat. The original gives
 * it the same protection for the same reason. It stops being invulnerable the moment it leaves, by
 * which time it is off the board anyway.
 *
 * <h2>The four beats</h2>
 *
 * <p>{@code bungee_drop} → {@code bungee_grab} → {@code bungee_hold} → {@code bungee_rise}, each
 * one the clip the reanim draws for it. Every one is published as a state, so the art and the
 * simulation agree about which beat is running without either inferring it from the other.
 */
public final class BungeeCapability implements ZombieCapability {
    public static final int DEFAULT_DROP_TICKS = 80;
    public static final int DEFAULT_GRAB_TICKS = 50;
    /** How long it hangs with the plant before climbing. */
    public static final int DEFAULT_HOLD_TICKS = 40;
    public static final int DEFAULT_RISE_TICKS = 70;
    /** How many columns it will try before giving up on finding anything to take. */
    public static final int DEFAULT_RETRIES = 3;
    /**
     * How far above its cell the bungee starts, in world cells.
     *
     * <p>The original's bungee hangs above the board and is lowered on its cord; the reanim is
     * only poses and does not move the body, so the descent is the entity's own {@code height} -
     * the same vertical offset a falling sun uses. Eight cells is above the top of even a
     * six-row pool board's stage, so the player sees it come down rather than blink into place.
     */
    public static final float DEFAULT_DROP_HEIGHT = 8.0F;

    private final int dropTicks;
    private final int grabTicks;
    private final int holdTicks;
    private final int riseTicks;
    private final float dropHeight;
    private final int retries;
    private final Optional<Identifier> sound;

    private int phaseTicks;
    private Stage stage = Stage.ARRIVING;
    private int targetColumn = -1;
    private int targetPlantId = -1;
    private int attemptsLeft;

    private enum Stage {
        ARRIVING, DROPPING, GRABBING, HOLDING, RISING, DONE
    }

    public BungeeCapability(int dropTicks, int grabTicks, int holdTicks, int riseTicks,
                            float dropHeight, int retries, Optional<Identifier> sound) {
        this.dropTicks = Math.max(1, dropTicks);
        this.grabTicks = Math.max(1, grabTicks);
        this.holdTicks = Math.max(0, holdTicks);
        this.riseTicks = Math.max(1, riseTicks);
        this.dropHeight = Math.max(0F, dropHeight);
        this.retries = Math.max(1, retries);
        this.sound = sound;
    }

    public static final MapCodec<BungeeCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("drop_ticks", DEFAULT_DROP_TICKS)
                    .forGetter(BungeeCapability::dropTicks),
            Codec.INT.optionalFieldOf("grab_ticks", DEFAULT_GRAB_TICKS)
                    .forGetter(BungeeCapability::grabTicks),
            Codec.INT.optionalFieldOf("hold_ticks", DEFAULT_HOLD_TICKS)
                    .forGetter(BungeeCapability::holdTicks),
            Codec.INT.optionalFieldOf("rise_ticks", DEFAULT_RISE_TICKS)
                    .forGetter(BungeeCapability::riseTicks),
            Codec.FLOAT.optionalFieldOf("drop_height", DEFAULT_DROP_HEIGHT)
                    .forGetter(BungeeCapability::dropHeight),
            Codec.INT.optionalFieldOf("retries", DEFAULT_RETRIES)
                    .forGetter(BungeeCapability::retries),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(BungeeCapability::sound)
    ).apply(i, BungeeCapability::new));

    public int dropTicks() {
        return dropTicks;
    }

    public float dropHeight() {
        return dropHeight;
    }

    public int grabTicks() {
        return grabTicks;
    }

    public int holdTicks() {
        return holdTicks;
    }

    public int riseTicks() {
        return riseTicks;
    }

    public int retries() {
        return retries;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public ZombieCapability instantiate() {
        return new BungeeCapability(dropTicks, grabTicks, holdTicks, riseTicks, dropHeight,
                retries, sound);
    }

    /** It flies: nothing on the lawn can reach it, and it never drowns. */
    @Override
    public int layerOverride(ZombieEntity zombie) {
        return EntityLayers.AIR;
    }

    /**
     * Unreachable by ground fire while it works; see the class doc.
     *
     * <p>{@code canBeHitByGround} rather than an invulnerability flag: the answer is about
     * <em>what can reach it</em>, and the balloon zombie's own capability answers the same
     * question the same way. "Invulnerable" would also have stopped a mower, which is a
     * different statement.
     */
    @Override
    public boolean canBeHitByGround(ZombieEntity zombie) {
        return stage == Stage.DONE;
    }

    /**
     * The raid is the zombie's whole movement, so the ordinary walk-and-eat step is skipped.
     *
     * <p>{@code tickMovement} rather than {@code tick}: the default step is what makes a zombie
     * walk left and bite, and a bungee zombie does neither - it is placed above a column and
     * descends. Answering true here is how a capability takes the body over completely.
     */
    @Override
    public boolean tickMovement(ZombieEntity zombie, LevelAccess level) {
        phaseTicks++;
        return switch (stage) {
            case ARRIVING -> arrive(zombie, level);
            case DROPPING -> {
                // The cord is paying out: the body comes down over the clip's whole length.
                zombie.setHeight(dropHeight * (1F - progress(dropTicks)));
                yield advance(zombie, level, dropTicks, Stage.GRABBING,
                        EntityAnimations.BUNGEE_DROP);
            }
            case GRABBING -> {
                zombie.setHeight(0F);
                yield grab(zombie, level);
            }
            case HOLDING -> {
                zombie.setHeight(0F);
                yield advance(zombie, level, holdTicks, Stage.RISING,
                        EntityAnimations.BUNGEE_HOLD);
            }
            case RISING -> {
                // Hauled back up with whatever it took; `rise` removes it at the top.
                zombie.setHeight(dropHeight * progress(riseTicks));
                yield rise(zombie, level);
            }
            case DONE -> true;
        };
    }

    /** How far through a phase this tick is, 0 at its first tick and 1 at its last. */
    private float progress(int duration) {
        return Math.min(1F, phaseTicks / (float) Math.max(1, duration));
    }

    /** Picks a column with something in it, or leaves if there is nothing worth taking. */
    private boolean arrive(ZombieEntity zombie, LevelAccess level) {
        attemptsLeft = retries;
        targetColumn = pickColumn(zombie, level);
        zombie.setAnimation(EntityAnimations.BUNGEE_HOLD);
        if (targetColumn < 0) {
            // Nothing on the lawn to steal. It leaves rather than hovering forever: a raid that
            // never lands is a zombie the player cannot kill standing in the sky.
            zombie.setHeight(dropHeight);
            stage = Stage.RISING;
            phaseTicks = 0;
            return true;
        }
        zombie.setAnimation(EntityAnimations.BUNGEE_DROP);
        zombie.setCellX(targetColumn + 0.5F);
        zombie.setHeight(dropHeight);
        stage = Stage.DROPPING;
        phaseTicks = 0;
        return true;
    }

    /** One column that has a plant in it, or -1. Chosen with the level's dice, so a replay of the
     * same seed steals from the same column. */
    private int pickColumn(ZombieEntity zombie, LevelAccess level) {
        List<Integer> candidates = new java.util.ArrayList<>();
        for (int x = 0; x < 9; x++) {
            if (level.plantAt(x, zombie.gridY()) != null) {
                candidates.add(x);
            }
        }
        if (candidates.isEmpty()) {
            return -1;
        }
        return candidates.get(level.random().nextInt(candidates.size()));
    }

    private boolean advance(ZombieEntity zombie, LevelAccess level, int duration, Stage next,
                            String animation) {
        zombie.setAnimation(animation);
        if (phaseTicks < duration) {
            return true;
        }
        stage = next;
        phaseTicks = 0;
        return true;
    }

    /** Takes the plant. It is gone from the lawn, not damaged. */
    private boolean grab(ZombieEntity zombie, LevelAccess level) {
        zombie.setAnimation(EntityAnimations.BUNGEE_GRAB);
        if (phaseTicks == 1) {
            PlantEntity plant = level.plantAt(targetColumn, zombie.gridY());
            if (plant == null) {
                // Eaten by something else between the drop and the grab. Try again with what is
                // left, or leave.
                if (--attemptsLeft > 0) {
                    stage = Stage.ARRIVING;
                    phaseTicks = 0;
                } else {
                    stage = Stage.RISING;
                    phaseTicks = 0;
                }
                return true;
            }
            targetPlantId = plant.id();
            level.emitEffect("", plant.cellX(), plant.cellY(),
                    sound.orElse(PvzceSounds.ZOMBIE_GROAN));
        }
        if (phaseTicks < grabTicks) {
            return true;
        }
        // Looked up again rather than held: the plant is re-read at the moment it is taken, so a
        // plant that was dug up or eaten during the grab is simply not there to steal.
        PlantEntity plant = level.plantAt(targetColumn, zombie.gridY());
        if (plant != null && plant.id() == targetPlantId) {
            plant.remove();
        }
        stage = Stage.HOLDING;
        phaseTicks = 0;
        return true;
    }

    private boolean rise(ZombieEntity zombie, LevelAccess level) {
        zombie.setAnimation(EntityAnimations.BUNGEE_RISE);
        if (phaseTicks < riseTicks) {
            return true;
        }
        stage = Stage.DONE;
        zombie.remove();
        return true;
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("phase", phaseTicks);
        tag.putInt("target", targetColumn);
        tag.putInt("plant", targetPlantId);
        tag.putInt("stage", stage.ordinal());
    }

    @Override
    public void load(CompoundTag tag) {
        phaseTicks = Math.max(0, tag.getInt("phase"));
        targetColumn = tag.getInt("target");
        targetPlantId = tag.getInt("plant");
        int ordinal = tag.getInt("stage");
        stage = ordinal >= 0 && ordinal < Stage.values().length
                ? Stage.values()[ordinal] : Stage.ARRIVING;
    }
}

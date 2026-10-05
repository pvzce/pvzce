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

/** A roof raid: descends, steals a plant or delivers a wave zombie, then rises away.
 * Only the time spent at the bottom is reachable by ordinary plant attacks.
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
    private Identifier cargo;
    private float cargoHealthScale = 1F;
    private com.pvzce.api.entity.attribute.AttributeOverrides cargoAttributes =
            com.pvzce.api.entity.attribute.AttributeOverrides.EMPTY;

    /** Wave-only delivery; the cargo appears at touchdown and is saved independently thereafter. */
    public void deliver(Identifier id, int column, float healthScale) {
        deliver(id, column, healthScale, com.pvzce.api.entity.attribute.AttributeOverrides.EMPTY);
    }

    public void deliver(Identifier id, int column, float healthScale,
                        com.pvzce.api.entity.attribute.AttributeOverrides attributes) {
        cargo = id;
        targetColumn = column;
        cargoHealthScale = healthScale;
        cargoAttributes = attributes;
    }

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

    @Override
    public int layerOverride(ZombieEntity zombie) {
        return stage == Stage.GRABBING || stage == Stage.HOLDING ? EntityLayers.GROUND : EntityLayers.AIR;
    }

    @Override
    public boolean canBeHitByGround(ZombieEntity zombie) {
        return stage == Stage.GRABBING || stage == Stage.HOLDING || stage == Stage.DONE;
    }

    @Override
    public boolean canBeHitByArc(ZombieEntity zombie) {
        return canBeHitByGround(zombie);
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
        var terrain = level.sceneAt(targetColumn, zombie.gridY(), zombie.surfaceId());
        float ground = level.surfaceHeight(zombie.surfaceId(), targetColumn + 0.5F, zombie.cellY());
        return switch (stage) {
            case ARRIVING -> arrive(zombie, level);
            case DROPPING -> {
                // The cord is paying out: the body comes down over the clip's whole length.
                zombie.setHeight(ground + dropHeight * (1F - progress(dropTicks)));
                yield advance(zombie, level, dropTicks, Stage.GRABBING,
                        EntityAnimations.BUNGEE_DROP);
            }
            case GRABBING -> {
                zombie.setHeight(ground);
                yield grab(zombie, level);
            }
            case HOLDING -> {
                zombie.setHeight(ground);
                yield advance(zombie, level, holdTicks, Stage.RISING,
                        EntityAnimations.BUNGEE_HOLD);
            }
            case RISING -> {
                // Hauled back up with whatever it took; `rise` removes it at the top.
                zombie.setHeight(ground + dropHeight * progress(riseTicks));
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
        if (cargo == null) targetColumn = pickColumn(zombie, level);
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
        var terrain = level.sceneAt(targetColumn, zombie.gridY(), zombie.surfaceId());
        zombie.setHeight(dropHeight + level.surfaceHeight(zombie.surfaceId(), zombie.cellX(), zombie.cellY()));
        stage = Stage.DROPPING;
        phaseTicks = 0;
        return true;
    }

    /** One column that has a plant in it, or -1. Chosen with the level's dice, so a replay of the
     * same seed steals from the same column. */
    private int pickColumn(ZombieEntity zombie, LevelAccess level) {
        List<Integer> candidates = new java.util.ArrayList<>();
        for (int x = 0; x < level.width(); x++) {
            if (level.plantAt(x, zombie.gridY(), zombie.surfaceId()) != null) {
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
        if (com.pvzce.common.capability.plant.UmbrellaLeafCapability.block(level, targetColumn,
                zombie.gridY(), null, zombie.surfaceId())) {
            cargo = null;
            stage = Stage.RISING;
            phaseTicks = 0;
            return true;
        }
        if (cargo != null) {
            level.spawnZombie(cargo, zombie.team(), targetColumn + 0.5F, zombie.gridY(), cargoHealthScale,
                    zombie.surfaceId(), cargoAttributes);
            cargo = null;
            stage = Stage.RISING;
            phaseTicks = 0;
            return true;
        }
        if (phaseTicks == 1) {
            PlantEntity plant = level.plantAt(targetColumn, zombie.gridY(), zombie.surfaceId());
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
            level.emitEffect("", plant.position(), plant.surfaceId(), sound.orElse(PvzceSounds.ZOMBIE_GROAN));
        }
        if (phaseTicks < grabTicks) {
            return true;
        }
        // Looked up again rather than held: the plant is re-read at the moment it is taken, so a
        // plant that was dug up or eaten during the grab is simply not there to steal.
        PlantEntity plant = level.plantAt(targetColumn, zombie.gridY(), zombie.surfaceId());
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
        tag.putString("cargo", cargo == null ? "" : cargo.toString());
        tag.putFloat("cargo_scale", cargoHealthScale);
        tag.put("cargo_attributes", cargoAttributes.save());
    }

    @Override
    public void load(CompoundTag tag) {
        phaseTicks = Math.max(0, tag.getInt("phase"));
        targetColumn = tag.getInt("target");
        targetPlantId = tag.getInt("plant");
        cargo = Identifier.tryParse(tag.getString("cargo"));
        cargoHealthScale = tag.contains("cargo_scale") ? tag.getFloat("cargo_scale") : 1F;
        cargoAttributes = com.pvzce.api.entity.attribute.AttributeOverrides.restore(tag.getCompound("cargo_attributes"));
        int ordinal = tag.getInt("stage");
        stage = ordinal >= 0 && ordinal < Stage.values().length
                ? Stage.values()[ordinal] : Stage.ARRIVING;
    }
}

package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.util.MathUtil;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;
import java.util.Optional;

/**
 * The grave buster: a plant that eats the gravestone it was planted on.
 *
 * <p>Two halves, and both of them already existed before this class did. <em>Where it may go</em>
 * is the {@code #c:grave_only} tag and the rule in
 * {@link com.pvzce.common.core.PlantPlacement} - a placement rule, so the card is refused at the
 * cell rather than being spent and then doing nothing. <em>What it does when it is there</em> is
 * this: chew for a while, then take the grave away.
 *
 * <p>The plant is <strong>vulnerable while it eats</strong>, unlike an arming mine: the original
 * lets a zombie walk up and bite it, and a grave buster eaten before it finishes is a card spent
 * for nothing. That is what {@link #invulnerable} not being overridden means here, which is why
 * it is worth saying out loud.
 *
 * <p>The gravestone is cleared through {@link LevelServer#clearGrave}, which also tells the
 * client: the board's terrain is server state, and a grave that vanished only on the server
 * would still block planting in the player's picture of the lawn.
 */
public final class GraveBusterCapability implements PlantCapability {
    /**
     * How long the chewing takes, in ticks.
     *
     * <p>Four seconds: long enough that the plant is worth defending, short enough that clearing
     * a lawn full of graves is a rhythm rather than a chore, and the same order of magnitude as
     * the melee line's own chew.
     */
    public static final int DEFAULT_CHEW_TICKS = 240;

    /**
     * How far the plant sinks into the stone it is eating, in cells.
     *
     * <p>The original eats a tombstone from the top down: the stone gets shorter from above
     * while the plant works its way down it. Both halves of that are one number here - the
     * plant's own height - because the height already travels every sync, and the client reads
     * it back as "how far through the meal this is" to crop the tombstone (see
     * {@code SceneShifts}). A second field would only be a second answer to the same question.
     */
    public static final float SINK_DEPTH_CELLS = 0.3F;

    private final int chewTicks;
    private final Optional<Identifier> sound;

    /**
     * Ticks left before the grave falls; zero once it has.
     *
     * <p>Starts at {@code -1}, which is "still falling onto the stone": the landing clip is a
     * one-shot that hands over to the chew by itself ({@code on_end: chew} in the model), so the
     * server publishes it once and then publishes the loop. Publishing the loop from the first
     * tick would skip the landing, and publishing the landing every tick would restart it.
     */
    private int remaining = LANDING;

    /** Sentinel for "the drop onto the gravestone is still playing". */
    private static final int LANDING = -1;

    public GraveBusterCapability(int chewTicks, Optional<Identifier> sound) {
        this.chewTicks = Math.max(1, chewTicks);
        this.sound = sound;
    }

    public static final MapCodec<GraveBusterCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("chew_ticks", DEFAULT_CHEW_TICKS).forGetter(GraveBusterCapability::chewTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(GraveBusterCapability::sound)
    ).apply(i, GraveBusterCapability::new));

    public int chewTicks() {
        return chewTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public PlantCapability instantiate() {
        return new GraveBusterCapability(chewTicks, sound);
    }

    /**
     * True until the grave is gone.
     *
     * <p>{@code plantAt} skips a plant that answers false, so the cell is free to plant on the
     * moment the grave falls rather than one entity-removal pass later. The plant is removed in
     * the same tick either way; this is what that same tick looks like from the outside.
     */
    @Override
    public boolean occupiesCell(PlantEntity plant) {
        return remaining != 0;
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (remaining == 0) {
            return;
        }
        if (remaining == LANDING) {
            plant.setState(EntityAnimations.IDLE);
            remaining = chewTicks;
            return;
        }
        plant.setState(EntityAnimations.CHEW);
        // Down the stone as it goes: the plant's height is the meal's progress bar, published
        // with everything else the entity syncs, and the client crops the tombstone from it.
        plant.setHeight(level.surfaceHeight(plant.surfaceId(), plant.cellX(), plant.cellY())
                - SINK_DEPTH_CELLS * sinkProgress());
        if (--remaining > 0) {
            return;
        }
        if (level instanceof LevelServer server && server.clearGrave(plant.gridX(), plant.gridY())) {
            var base = level.sceneAt(plant.gridX(), plant.gridY(), plant.surfaceId());
            boolean water = base != null && com.pvzce.common.tag.PvzceTags.SCENE_ELEMENTS.contains(
                    com.pvzce.common.tag.PvzceTags.SCENE_WATER, base.id());
            level.emitEffect((water ? PvzceParticles.POOL_SPLASH : PvzceParticles.DIRT_BIG).toString(),
                    plant.position(), plant.surfaceId(), water ? PvzceSounds.PLANT_PLANT_WATER
                            : sound.orElseGet(() -> PvzceSounds.EFFECT_SHOVEL));
        }
        plant.remove();
    }

    /** How far through the stone this plant is, 0 (just started) to 1 (through to the lawn). */
    private float sinkProgress() {
        return MathUtil.clamp01(1F - remaining / (float) chewTicks);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("remaining", remaining);
    }

    @Override
    public void load(CompoundTag tag) {
        // A save written before this capability existed has no key, and getInt would answer 0 -
        // "already finished" - which would leave the grave standing with the plant just gone.
        // A grave buster in a save is always still eating, so that is the fallback.
        remaining = tag.contains("remaining") ? tag.getInt("remaining") : LANDING;
    }
}

package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.server.entity.PlantEntity;

import java.util.List;
import java.util.Optional;

/**
 * Instant-use support plant: wakes the plant it was stacked on and is consumed by the
 * placement (coffee bean).
 *
 * <p>The original's coffee bean does exactly one thing - it wakes a sleeping mushroom -
 * and does nothing at all to a plant that was already awake. That second half is the
 * part worth being explicit about: the bean is still spent, so using one on a sunflower
 * is a mistake the player can make, not an "instant sun" button. This used to fan out
 * into every capability's {@code boost()} (an instant shot, an instant sun), which made
 * the coffee bean the strongest card in the game and left nothing for the energy bean's
 * own design.
 *
 * <p>Stacking is the placement rule ({@code #c:plant_only}), not a check here.
 */
public final class WakeBelowCapability implements PlantCapability {
    /**
     * How long the bean stays on the board being drawn while it crumbles, in ticks.
     *
     * <p>Matches {@code vanish} in its model ({@code coffee_bean.json},
     * {@code animation_length} 1.25s at 60 tps). Written out rather than read from the file for
     * the reason {@link PlantCapability.VanishAnimation} gives: the simulation does not read
     * animation resources.
     */
    public static final int CRUMBLE_TICKS = 75;

    private final Optional<Identifier> sound;
    /** Played instead of {@code sound} when the plant below was really asleep and woke up. */
    private final Optional<Identifier> wakeSound;
    /** The clip the bean plays as it is spent; empty = it simply disappears. */
    private final Optional<String> vanishClip;

    public WakeBelowCapability(Optional<Identifier> sound, Optional<Identifier> wakeSound,
                               Optional<String> vanishClip) {
        this.sound = sound;
        this.wakeSound = wakeSound;
        this.vanishClip = vanishClip == null ? Optional.empty() : vanishClip;
    }

    /** The common case: the bean's own {@code vanish} clip, as its model ships it. */
    public WakeBelowCapability(Optional<Identifier> sound, Optional<Identifier> wakeSound) {
        this(sound, wakeSound, Optional.of("vanish"));
    }

    public static final MapCodec<WakeBelowCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Identifier.CODEC.optionalFieldOf("sound").forGetter(WakeBelowCapability::sound),
            Identifier.CODEC.optionalFieldOf("wake_sound").forGetter(WakeBelowCapability::wakeSound),
            // Empty string = "it just disappears", which is what a mod's own instant plant
            // wants; an absent field keeps the built-in clip.
            Codec.STRING.optionalFieldOf("vanish_clip", "vanish")
                    .forGetter(capability -> capability.vanishClip.orElse(""))
    ).apply(i, (sound, wakeSound, clip) ->
            new WakeBelowCapability(sound, wakeSound,
                    clip == null || clip.isBlank() ? Optional.empty() : Optional.of(clip))));

    public Optional<Identifier> sound() {
        return sound;
    }

    public Optional<Identifier> wakeSound() {
        return wakeSound;
    }

    /** The clip the bean plays while it is spent; empty means it disappears outright. */
    public Optional<String> vanishClip() {
        return vanishClip;
    }

    @Override
    public PlantCapability instantiate() {
        return this;
    }

    @Override
    public boolean consumesOnPlace() {
        return true;
    }

    @Override
    public java.util.Optional<VanishAnimation> vanishAnimation() {
        return vanishClip.map(clip -> new VanishAnimation(clip, CRUMBLE_TICKS));
    }

    @Override
    public void onPlaced(PlantEntity plant, LevelAccess level) {
        List<PlantEntity> stacked = level.plantsAt(plant.gridX(), plant.gridY());
        PlantEntity target = null;
        for (int i = stacked.size() - 1; i >= 0; i--) {
            if (stacked.get(i) != plant) {
                target = stacked.get(i);
                break;
            }
        }
        if (target == null) {
            // Placement rules say a coffee bean needs a plant under it, so this is the
            // editor or a command placing one in an empty cell: still spend it, and say so.
            level.emitEffect(PvzceParticles.LANTERN_SHINE.toString(), plant.cellX(), plant.cellY(),
                    sound.orElse(PvzceSounds.UI_TAP));
            plant.remove();
            return;
        }
        boolean woke = target.wake();
        level.emitEffect(PvzceParticles.LANTERN_SHINE.toString(), plant.cellX(), plant.cellY(),
                woke
                        ? wakeSound.orElse(PvzceSounds.PLANT_WAKEUP)
                        : sound.orElse(PvzceSounds.UI_TAP));
        plant.remove();
    }
}

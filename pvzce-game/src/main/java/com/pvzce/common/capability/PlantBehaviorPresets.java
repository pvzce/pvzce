package com.pvzce.common.capability;

import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.plant.WakeBelowCapability;
import com.pvzce.common.capability.plant.ExplosiveCapability;
import com.pvzce.common.capability.plant.MeleeCapability;
import com.pvzce.common.capability.plant.ProducerCapability;
import com.pvzce.common.capability.plant.ShooterCapability;
import com.pvzce.common.capability.plant.ThrowerCapability;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional {@code behavior} presets: a named, canned capability bundle so a short
 * data file can say {@code "behavior": "pvzce:shooter"} instead of spelling the
 * capability list out.
 *
 * <p>Presets are pure sugar - they expand to the same
 * {@link TypedCapability} list that an explicit {@code capabilities} array would
 * produce, and an explicit list always wins. Mods may add presets with
 * {@link #register}.
 */
public final class PlantBehaviorPresets {
    private static final Map<Identifier, List<TypedCapability<PlantCapability>>> PRESETS = new ConcurrentHashMap<>();

    public static void register(Identifier id, List<TypedCapability<PlantCapability>> capabilities) {
        PRESETS.put(id, List.copyOf(capabilities));
    }

    /** Expands a preset id, or returns an empty list for an unknown/passive behavior. */
    public static List<TypedCapability<PlantCapability>> expand(Identifier behavior) {
        if (behavior == null) {
            return List.of();
        }
        List<TypedCapability<PlantCapability>> preset = PRESETS.get(behavior);
        return preset == null ? List.of() : preset;
    }

    public static boolean isKnown(Identifier behavior) {
        return behavior != null && PRESETS.containsKey(behavior);
    }

    private static void bootstrapDefaults() {
        register("shooter", List.of(cap(PlantCapabilities.SHOOTER,
                new ShooterCapability(ShooterCapability.DEFAULT_INTERVAL, List.of(), Optional.empty(), 0))));
        register("thrower", List.of(cap(PlantCapabilities.THROWER,
                new ThrowerCapability(ThrowerCapability.DEFAULT_INTERVAL, List.of(), 0F,
                        Identifier.withDefaultNamespace("butter"), Optional.empty(), 0))));
        register("producer", List.of(cap(PlantCapabilities.PRODUCER,
                new ProducerCapability(Identifier.withDefaultNamespace("sun"), ProducerCapability.DEFAULT_AMOUNT,
                        1440, -1, Optional.empty()))));
        register("explosive", List.of(cap(PlantCapabilities.EXPLOSIVE,
                new ExplosiveCapability(ExplosiveCapability.Trigger.TIMED, ExplosiveCapability.DEFAULT_FUSE,
                        1F, 1800, 0.6F, false, Optional.empty(), ExplosiveCapability.DEFAULT_DAMAGE_TYPE))));
        register("mine", List.of(cap(PlantCapabilities.EXPLOSIVE,
                new ExplosiveCapability(ExplosiveCapability.Trigger.PROXIMITY, 900, 0.5F, 1800, 0.5F,
                        true, Optional.empty(), ExplosiveCapability.DEFAULT_DAMAGE_TYPE))));
        register("melee", List.of(cap(PlantCapabilities.MELEE,
                new MeleeCapability(MeleeCapability.DEFAULT_RANGE, 0, MeleeCapability.DEFAULT_CHEW_TICKS,
                        Optional.empty()))));
        register("support", List.of(cap(PlantCapabilities.WAKE_BELOW,
                new WakeBelowCapability(Optional.empty(), Optional.empty()))));
        // Passive behaviors intentionally expand to nothing.
        register("passive", List.of());
        register("defense", List.of());
        register("environment", List.of());
    }

    private static TypedCapability<PlantCapability> cap(
            com.pvzce.api.content.capability.CapabilityType<? extends PlantCapability> type,
            PlantCapability value) {
        return new TypedCapability<>(type.id(), value);
    }

    private static void register(String path, List<TypedCapability<PlantCapability>> capabilities) {
        register(Identifier.withDefaultNamespace(path), capabilities);
    }

    /** Called from {@code BuiltInRegistries.bootstrap()}. */
    public static void bootstrap() {
        bootstrapDefaults();
    }

    private PlantBehaviorPresets() {
    }
}

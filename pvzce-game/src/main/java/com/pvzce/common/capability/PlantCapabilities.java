package com.pvzce.common.capability;

import com.pvzce.api.content.capability.CapabilityType;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.capability.plant.BoostBelowCapability;
import com.pvzce.common.capability.plant.ExplosiveCapability;
import com.pvzce.common.capability.plant.MeleeCapability;
import com.pvzce.common.capability.plant.ProducerCapability;
import com.pvzce.common.capability.plant.ShooterCapability;
import com.pvzce.common.capability.plant.ThrowerCapability;
import com.mojang.serialization.Codec;

import java.util.List;

/**
 * Registration + JSON codec for plant capabilities.
 *
 * <p>{@link #CODEC} is the single polymorphic codec used by {@code PlantDef}; a
 * mod adds a new plant behaviour by registering a
 * {@link CapabilityType} against {@link BuiltInRegistries#PLANT_CAPABILITIES}
 * and can then use it from data JSON immediately.
 */
public final class PlantCapabilities {
    public static final CapabilityType<ShooterCapability> SHOOTER = type("shooter", ShooterCapability.CODEC);
    public static final CapabilityType<ThrowerCapability> THROWER = type("thrower", ThrowerCapability.CODEC);
    public static final CapabilityType<ProducerCapability> PRODUCER = type("producer", ProducerCapability.CODEC);
    public static final CapabilityType<ExplosiveCapability> EXPLOSIVE = type("explosive", ExplosiveCapability.CODEC);
    public static final CapabilityType<MeleeCapability> MELEE = type("melee", MeleeCapability.CODEC);
    public static final CapabilityType<BoostBelowCapability> BOOST_BELOW = type("boost_below", BoostBelowCapability.CODEC);

    public static final Codec<TypedCapability<PlantCapability>> CODEC =
            TypedCapability.codec(BuiltInRegistries.PLANT_CAPABILITIES, "plant");
    public static final Codec<List<TypedCapability<PlantCapability>>> LIST_CODEC = CODEC.listOf();

    private static <T extends PlantCapability> CapabilityType<T> type(String path, com.mojang.serialization.MapCodec<T> codec) {
        return new CapabilityType<>(Identifier.withDefaultNamespace(path), codec);
    }

    /** Registers every built-in plant capability; called from {@code BuiltInRegistries}. */
    public static void bootstrap() {
        register(SHOOTER, "shooter");
        register(THROWER, "thrower");
        register(PRODUCER, "producer");
        register(EXPLOSIVE, "explosive");
        register(MELEE, "melee");
        register(BOOST_BELOW, "boost_below");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(CapabilityType<?> type, String path) {
        BuiltInRegistries.registerStatic((com.pvzce.api.registry.Registry) BuiltInRegistries.PLANT_CAPABILITIES,
                "pvzce:" + path, type);
    }

    private PlantCapabilities() {
    }
}

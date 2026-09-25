package com.pvzce.common.capability;

import com.pvzce.api.content.capability.CapabilityType;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.capability.plant.BowlCapability;
import com.pvzce.common.capability.plant.CharmCapability;
import com.pvzce.common.capability.plant.ConeAttackCapability;
import com.pvzce.common.capability.plant.DragUnderCapability;
import com.pvzce.common.capability.plant.ExplosiveCapability;
import com.pvzce.common.capability.plant.FreezeAllCapability;
import com.pvzce.common.capability.plant.GraveBusterCapability;
import com.pvzce.common.capability.plant.MeleeCapability;
import com.pvzce.common.capability.plant.NocturnalCapability;
import com.pvzce.common.capability.plant.ProducerCapability;
import com.pvzce.common.capability.plant.ShooterCapability;
import com.pvzce.common.capability.plant.ThrowerCapability;
import com.pvzce.common.capability.plant.WakeBelowCapability;
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
    public static final CapabilityType<WakeBelowCapability> WAKE_BELOW = type("wake_below", WakeBelowCapability.CODEC);
    /**
     * Eats the gravestone it was planted on.
     *
     * <p>Where it may be planted is not this capability's business: {@code #c:grave_only} in the
     * placement rules refuses the card at any other cell, so the two halves of "clears graves"
     * stay in the places that already own them.
     */
    public static final CapabilityType<GraveBusterCapability> GRAVE_BUSTER =
            type("grave_buster", GraveBusterCapability.CODEC);
    /**
     * Turns the zombie that eats the plant onto the plant's own side.
     *
     * <p>The hypno-shroom. See {@link CharmCapability}: the effect is one team change, and every
     * attack rule in the game already asks "is this an enemy".
     */
    public static final CapabilityType<CharmCapability> CHARM =
            type("charm", CharmCapability.CODEC);
    public static final CapabilityType<BowlCapability> BOWL = type("bowl", BowlCapability.CODEC);
    public static final CapabilityType<NocturnalCapability> NOCTURNAL = type("nocturnal", NocturnalCapability.CODEC);
    /**
     * Freezes every zombie on the lawn once, then the plant is spent.
     *
     * <p>The ice-shroom. Not an {@code explosive} with a big radius: "the whole field" is a
     * shape rather than a distance, and the effect is a status rather than a blast - the
     * twenty damage is the least of what it does.
     */
    public static final CapabilityType<FreezeAllCapability> FREEZE_ALL =
            type("freeze_all", FreezeAllCapability.CODEC);
    /**
     * A burst of area damage in front of the plant, with no projectile behind it.
     *
     * <p>The fume-shroom. Not a {@code shooter} with a short range: a cloud is a shape that
     * appears across the whole cone at once, while a shot is a thing that travels to each
     * zombie in turn. See {@link ConeAttackCapability} for what modelling it as a projectile
     * cost, and why that needed {@code pvzce:pierce} plus per-shot hit book-keeping.
     */
    public static final CapabilityType<ConeAttackCapability> CONE =
            type("cone", ConeAttackCapability.CODEC);
    /**
     * Pulls the first zombie that walks onto the plant under the water.
     *
     * <p>The tangle kelp. Where it may be planted is the {@code #c:water_plant} tag's business,
     * exactly like the lily pad's; what it does once something is standing over it is here.
     */
    public static final CapabilityType<DragUnderCapability> DRAG_UNDER =
            type("drag_under", DragUnderCapability.CODEC);

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
        register(WAKE_BELOW, "wake_below");
        register(GRAVE_BUSTER, "grave_buster");
        register(CHARM, "charm");
        register(BOWL, "bowl");
        register(NOCTURNAL, "nocturnal");
        register(FREEZE_ALL, "freeze_all");
        register(CONE, "cone");
        register(DRAG_UNDER, "drag_under");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(CapabilityType<?> type, String path) {
        BuiltInRegistries.registerStatic((com.pvzce.api.registry.Registry) BuiltInRegistries.PLANT_CAPABILITIES,
                "pvzce:" + path, type);
    }

    private PlantCapabilities() {
    }
}

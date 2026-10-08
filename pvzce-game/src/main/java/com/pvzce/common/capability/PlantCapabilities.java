package com.pvzce.common.capability;

import com.pvzce.api.content.capability.CapabilityType;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.capability.plant.BowlCapability;
import com.pvzce.common.capability.plant.CharmCapability;
import com.pvzce.common.capability.plant.CobCannonCapability;
import com.pvzce.common.capability.plant.ConeAttackCapability;
import com.pvzce.common.capability.plant.DragUnderCapability;
import com.pvzce.common.capability.plant.ExplosiveCapability;
import com.pvzce.common.capability.plant.EchoRelayCapability;
import com.pvzce.common.capability.plant.EchoConduitCapability;
import com.pvzce.common.capability.plant.FreezeAllCapability;
import com.pvzce.common.capability.plant.GoldMagnetCapability;
import com.pvzce.common.capability.plant.GraveBusterCapability;
import com.pvzce.common.capability.plant.MeleeCapability;
import com.pvzce.common.capability.plant.NocturnalCapability;
import com.pvzce.common.capability.plant.ProducerCapability;
import com.pvzce.common.capability.plant.ShooterCapability;
import com.pvzce.common.capability.plant.BlowAwayCapability;
import com.pvzce.common.capability.plant.MagnetCapability;
import com.pvzce.common.capability.plant.RevealCapability;
import com.pvzce.common.capability.plant.ShellCapability;
import com.pvzce.common.capability.plant.SpikeCapability;
import com.pvzce.common.capability.plant.SquashCapability;
import com.pvzce.common.capability.plant.TorchwoodCapability;
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
    public static final CapabilityType<com.pvzce.common.capability.plant.MarkerCapability> DEFENSE =
            type("defense", com.pvzce.common.capability.plant.MarkerCapability.CODEC);
    public static final CapabilityType<com.pvzce.common.capability.plant.MarkerCapability> CARRIER =
            type("carrier", com.pvzce.common.capability.plant.MarkerCapability.CODEC);
    public static final CapabilityType<com.pvzce.common.capability.plant.GarlicCapability> GARLIC =
            type("garlic", com.pvzce.common.capability.plant.GarlicCapability.CODEC);
    public static final CapabilityType<com.pvzce.common.capability.plant.UmbrellaLeafCapability> UMBRELLA =
            type("umbrella", com.pvzce.common.capability.plant.UmbrellaLeafCapability.CODEC);
    public static final CapabilityType<com.pvzce.common.capability.plant.CactusCapability> CACTUS =
            type("cactus", com.pvzce.common.capability.plant.CactusCapability.CODEC);
    public static final CapabilityType<ShooterCapability> SHOOTER = type("shooter", ShooterCapability.CODEC);
    public static final CapabilityType<EchoConduitCapability> ECHO_CONDUIT = type("echo_conduit", EchoConduitCapability.CODEC);
    public static final CapabilityType<EchoRelayCapability> ECHO_RELAY = type("echo_relay", EchoRelayCapability.CODEC);
    public static final CapabilityType<ThrowerCapability> THROWER = type("thrower", ThrowerCapability.CODEC);
    public static final CapabilityType<ProducerCapability> PRODUCER = type("producer", ProducerCapability.CODEC);
    public static final CapabilityType<ExplosiveCapability> EXPLOSIVE = type("explosive", ExplosiveCapability.CODEC);
    public static final CapabilityType<MeleeCapability> MELEE = type("melee", MeleeCapability.CODEC);
    /**
     * Leaps onto the one zombie that walks into range and flattens it (the squash).
     *
     * <p>Deliberately not {@link #EXPLOSIVE}: the squash used to be a proximity blast, which
     * killed the neighbours and scorched the lawn as well. See {@link SquashCapability}.
     */
    public static final CapabilityType<SquashCapability> SQUASH =
            type("squash", SquashCapability.CODEC);
    /** Stands on the ground and stabs whatever walks over it (the spikeweed). */
    public static final CapabilityType<SpikeCapability> SPIKE =
            type("spike", SpikeCapability.CODEC);
    /**
     * Loads a cob on a clock and waits for the player to aim it (the cob cannon).
     *
     * <p>The only plant in the game whose trigger is a click rather than a zombie: see
     * {@link CobCannonCapability} for why that made it a capability of its own instead of an
     * {@code explosive} with a longer fuse.
     */
    public static final CapabilityType<CobCannonCapability> COB_CANNON =
            type("cob_cannon", CobCannonCapability.CODEC);
    /** Picks coins up off the lawn before the player has to click them (the gold magnet). */
    public static final CapabilityType<GoldMagnetCapability> GOLD_MAGNET =
            type("gold_magnet", GoldMagnetCapability.CODEC);
    /** Sets alight the shots that fly through it (the torchwood). */
    public static final CapabilityType<TorchwoodCapability> TORCHWOOD =
            type("torchwood", TorchwoodCapability.CODEC);
    /** A shell around whatever is in the same cell (the pumpkin). */
    public static final CapabilityType<ShellCapability> SHELL =
            type("shell", ShellCapability.CODEC);
    /** Lights up the fog around itself (the plantern). */
    public static final CapabilityType<RevealCapability> REVEAL =
            type("reveal", RevealCapability.CODEC);
    /** Blows everything in the air off the lawn, once (the blover). */
    public static final CapabilityType<BlowAwayCapability> BLOW_AWAY =
            type("blow_away", BlowAwayCapability.CODEC);
    /** Pulls the armour off a nearby zombie (the magnet-shroom). */
    public static final CapabilityType<MagnetCapability> MAGNET =
            type("magnet", MagnetCapability.CODEC);
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
        register(DEFENSE, "defense");
        register(CARRIER, "carrier");
        register(GARLIC, "garlic");
        register(UMBRELLA, "umbrella");
        register(CACTUS, "cactus");
        register(SHOOTER, "shooter");
        register(ECHO_RELAY, "echo_relay");
        register(ECHO_CONDUIT, "echo_conduit");
        register(COB_CANNON, "cob_cannon");
        register(GOLD_MAGNET, "gold_magnet");
        register(THROWER, "thrower");
        register(PRODUCER, "producer");
        register(EXPLOSIVE, "explosive");
        register(MELEE, "melee");
        register(SQUASH, "squash");
        register(SPIKE, "spike");
        register(TORCHWOOD, "torchwood");
        register(SHELL, "shell");
        register(REVEAL, "reveal");
        register(BLOW_AWAY, "blow_away");
        register(MAGNET, "magnet");
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

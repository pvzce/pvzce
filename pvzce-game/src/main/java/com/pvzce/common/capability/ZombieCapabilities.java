package com.pvzce.common.capability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.capability.CapabilityType;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.registry.Registry;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.zombie.ArmorCapability;
import com.pvzce.common.capability.zombie.BossPhasesCapability;
import com.pvzce.common.capability.zombie.DigCapability;
import com.pvzce.common.capability.zombie.FlyCapability;
import com.pvzce.common.capability.zombie.HammerCapability;
import com.pvzce.common.capability.zombie.SummonDancersCapability;
import com.pvzce.common.capability.zombie.VaultCapability;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.List;

/** Registration + JSON codec for zombie capabilities. */
public final class ZombieCapabilities {
    public static final CapabilityType<ArmorCapability> ARMOR = type("armor", ArmorCapability.CODEC);
    public static final CapabilityType<VaultCapability> VAULT = type("vault", VaultCapability.CODEC);
    public static final CapabilityType<FlyCapability> FLY = type("fly", FlyCapability.CODEC);
    public static final CapabilityType<DigCapability> DIG = type("dig", DigCapability.CODEC);
    public static final CapabilityType<HammerCapability> HAMMER = type("hammer", HammerCapability.CODEC);
    public static final CapabilityType<BossPhasesCapability> BOSS_PHASES = type("boss_phases", BossPhasesCapability.CODEC);
    /**
     * Calls a crew of backup dancers and keeps the formation full.
     *
     * <p>The dancing zombie. It is a capability rather than a level mechanic because what it
     * does is about the zombie: it walks, it stops, it calls, and from then on it is an
     * ordinary zombie with four more bodies in its lanes.
     */
    public static final CapabilityType<SummonDancersCapability> SUMMON_DANCERS =
            type("summon_dancers", SummonDancersCapability.CODEC);

    public static final Codec<TypedCapability<ZombieCapability>> CODEC =
            TypedCapability.codec(BuiltInRegistries.ZOMBIE_CAPABILITIES, "zombie");
    public static final Codec<List<TypedCapability<ZombieCapability>>> LIST_CODEC = CODEC.listOf();

    private static <T extends ZombieCapability> CapabilityType<T> type(String path, MapCodec<T> codec) {
        return new CapabilityType<>(Identifier.withDefaultNamespace(path), codec);
    }

    public static void bootstrap() {
        register(ARMOR, "armor");
        register(VAULT, "vault");
        register(FLY, "fly");
        register(DIG, "dig");
        register(HAMMER, "hammer");
        register(BOSS_PHASES, "boss_phases");
        register(SUMMON_DANCERS, "summon_dancers");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(CapabilityType<?> type, String path) {
        BuiltInRegistries.registerStatic((Registry) BuiltInRegistries.ZOMBIE_CAPABILITIES, "pvzce:" + path, type);
    }

    private ZombieCapabilities() {
    }
}

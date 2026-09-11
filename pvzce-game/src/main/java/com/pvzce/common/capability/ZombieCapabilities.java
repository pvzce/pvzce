package com.pvzce.common.capability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.capability.CapabilityType;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.registry.Registry;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.projectile.ArcMotionCapability;
import com.pvzce.common.capability.projectile.LinearMotionCapability;
import com.pvzce.common.capability.projectile.PierceCapability;
import com.pvzce.common.capability.projectile.SplashImpactCapability;
import com.pvzce.common.capability.projectile.StatusOnHitCapability;
import com.pvzce.common.capability.zombie.ArmorCapability;
import com.pvzce.common.capability.zombie.BossPhasesCapability;
import com.pvzce.common.capability.zombie.DigCapability;
import com.pvzce.common.capability.zombie.FlyCapability;
import com.pvzce.common.capability.zombie.HammerCapability;
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
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(CapabilityType<?> type, String path) {
        BuiltInRegistries.registerStatic((Registry) BuiltInRegistries.ZOMBIE_CAPABILITIES, "pvzce:" + path, type);
    }

    private ZombieCapabilities() {
    }
}

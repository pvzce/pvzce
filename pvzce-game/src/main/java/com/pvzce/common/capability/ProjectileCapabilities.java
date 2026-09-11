package com.pvzce.common.capability;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.capability.CapabilityType;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.registry.Registry;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.projectile.ArcMotionCapability;
import com.pvzce.common.capability.projectile.LinearMotionCapability;
import com.pvzce.common.capability.projectile.PierceCapability;
import com.pvzce.common.capability.projectile.SplashImpactCapability;
import com.pvzce.common.capability.projectile.StatusOnHitCapability;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.List;

/** Registration + JSON codec for projectile capabilities. */
public final class ProjectileCapabilities {
    public static final CapabilityType<LinearMotionCapability> LINEAR =
            type("linear", LinearMotionCapability.CODEC);
    public static final CapabilityType<ArcMotionCapability> ARC = type("arc", ArcMotionCapability.CODEC);
    public static final CapabilityType<SplashImpactCapability> SPLASH =
            type("splash", SplashImpactCapability.CODEC);
    public static final CapabilityType<StatusOnHitCapability> STATUS =
            type("status", StatusOnHitCapability.CODEC);
    public static final CapabilityType<PierceCapability> PIERCE = type("pierce", PierceCapability.CODEC);

    public static final Codec<TypedCapability<ProjectileCapability>> CODEC =
            TypedCapability.codec(BuiltInRegistries.PROJECTILE_CAPABILITIES, "projectile");
    public static final Codec<List<TypedCapability<ProjectileCapability>>> LIST_CODEC = CODEC.listOf();

    private static <T extends ProjectileCapability> CapabilityType<T> type(String path, MapCodec<T> codec) {
        return new CapabilityType<>(Identifier.withDefaultNamespace(path), codec);
    }

    public static void bootstrap() {
        register(LINEAR, "linear");
        register(ARC, "arc");
        register(SPLASH, "splash");
        register(STATUS, "status");
        register(PIERCE, "pierce");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(CapabilityType<?> type, String path) {
        BuiltInRegistries.registerStatic((Registry) BuiltInRegistries.PROJECTILE_CAPABILITIES, "pvzce:" + path, type);
    }

    private ProjectileCapabilities() {
    }
}

package com.pvzce.api.content;

import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.ProjectileCapabilities;
import com.pvzce.common.capability.projectile.ArcMotionCapability;
import com.pvzce.common.capability.projectile.LinearMotionCapability;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Named, canned projectile capability bundles for the optional {@code behavior} shorthand. */
public final class ProjectileBehaviorPresets {
    private static final Map<Identifier, List<TypedCapability<ProjectileCapability>>> PRESETS =
            new ConcurrentHashMap<>();

    public static void register(Identifier id, List<TypedCapability<ProjectileCapability>> capabilities) {
        PRESETS.put(id, List.copyOf(capabilities));
    }

    public static List<TypedCapability<ProjectileCapability>> expand(Identifier behavior) {
        if (behavior == null) {
            return List.of();
        }
        List<TypedCapability<ProjectileCapability>> preset = PRESETS.get(behavior);
        return preset == null ? List.of() : preset;
    }

    /** Called from {@code BuiltInRegistries.bootstrap()}. */
    public static void bootstrap() {
        register(Identifier.withDefaultNamespace("linear"), List.of(cap(ProjectileCapabilities.LINEAR,
                new LinearMotionCapability(LinearMotionCapability.DEFAULT_SPEED))));
        register(Identifier.withDefaultNamespace("arc"), List.of(cap(ProjectileCapabilities.ARC,
                new ArcMotionCapability(ArcMotionCapability.DEFAULT_SPEED, ArcMotionCapability.DEFAULT_GRAVITY))));
    }

    private static TypedCapability<ProjectileCapability> cap(
            com.pvzce.api.content.capability.CapabilityType<? extends ProjectileCapability> type,
            ProjectileCapability value) {
        return new TypedCapability<>(type.id(), value);
    }

    private ProjectileBehaviorPresets() {
    }
}

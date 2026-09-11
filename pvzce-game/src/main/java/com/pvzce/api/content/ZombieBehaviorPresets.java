package com.pvzce.api.content;

import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.ZombieCapabilities;
import com.pvzce.common.capability.zombie.ArmorCapability;
import com.pvzce.common.capability.zombie.HammerCapability;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Named, canned zombie capability bundles for the optional {@code behavior} shorthand. */
public final class ZombieBehaviorPresets {
    private static final Map<Identifier, List<TypedCapability<ZombieCapability>>> PRESETS =
            new ConcurrentHashMap<>();

    public static void register(Identifier id, List<TypedCapability<ZombieCapability>> capabilities) {
        PRESETS.put(id, List.copyOf(capabilities));
    }

    public static List<TypedCapability<ZombieCapability>> expand(Identifier behavior) {
        if (behavior == null) {
            return List.of();
        }
        List<TypedCapability<ZombieCapability>> preset = PRESETS.get(behavior);
        return preset == null ? List.of() : preset;
    }

    /** Called from {@code BuiltInRegistries.bootstrap()}. */
    public static void bootstrap() {
        register(Identifier.withDefaultNamespace("basic"), List.of());
        register(Identifier.withDefaultNamespace("armored"), List.of(cap(ZombieCapabilities.ARMOR,
                new ArmorCapability(List.of(), -1F))));
        register(Identifier.withDefaultNamespace("newspaper"), List.of(cap(ZombieCapabilities.ARMOR,
                new ArmorCapability(List.of(), -1F))));
        register(Identifier.withDefaultNamespace("flying"), List.of(cap(ZombieCapabilities.FLY,
                new com.pvzce.common.capability.zombie.FlyCapability(1F))));
        register(Identifier.withDefaultNamespace("miner"), List.of(cap(ZombieCapabilities.DIG,
                new com.pvzce.common.capability.zombie.DigCapability(
                        com.pvzce.common.capability.zombie.DigCapability.DEFAULT_SPEED,
                        com.pvzce.common.capability.zombie.DigCapability.DEFAULT_EMERGE_TICKS,
                        Optional.empty()))));
        register(Identifier.withDefaultNamespace("pole_vault"), List.of(cap(ZombieCapabilities.VAULT,
                new com.pvzce.common.capability.zombie.VaultCapability(
                        com.pvzce.common.capability.zombie.VaultCapability.DEFAULT_JUMP_DISTANCE,
                        Optional.empty()))));
        register(Identifier.withDefaultNamespace("giant"), List.of(cap(ZombieCapabilities.HAMMER,
                new HammerCapability(HammerCapability.DEFAULT_INTERVAL, false,
                        Identifier.withDefaultNamespace("imp"), HammerCapability.DEFAULT_IMP_INTERVAL, 3,
                        Optional.empty()))));
        register(Identifier.withDefaultNamespace("boss"), List.of(cap(ZombieCapabilities.BOSS_PHASES,
                new com.pvzce.common.capability.zombie.BossPhasesCapability(List.of(), 0.6F))));
    }

    private static TypedCapability<ZombieCapability> cap(
            com.pvzce.api.content.capability.CapabilityType<? extends ZombieCapability> type,
            ZombieCapability value) {
        return new TypedCapability<>(type.id(), value);
    }

    private ZombieBehaviorPresets() {
    }
}

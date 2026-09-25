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
import com.pvzce.common.capability.zombie.BungeeCapability;
import com.pvzce.common.capability.zombie.FloatCapability;
import com.pvzce.common.capability.zombie.ZamboniCapability;
import com.pvzce.common.capability.zombie.FlyCapability;
import com.pvzce.common.capability.zombie.HammerCapability;
import com.pvzce.common.capability.zombie.SubmergeCapability;
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
    /**
     * Swims submerged and surfaces to eat.
     *
     * <p>The snorkel zombie. It is a capability rather than a definition flag because what it
     * changes is who can hit the zombie, and that question is already asked of capabilities
     * (see {@code ZombieCapability.canBeHitByGround}): a definition flag would have meant a
     * second, parallel answer to it.
     */
    public static final CapabilityType<SubmergeCapability> SUBMERGE =
            type("submerge", SubmergeCapability.CODEC);
    /**
     * Paddles while it is in water, instead of walking.
     *
     * <p>The ducky-tube zombie's water gait, and the smallest capability there is: it changes
     * no rule, only which clip the walk loop publishes. It is a capability rather than a
     * definition flag for the same reason {@code submerge} is - "what does this zombie look
     * like while it walks" is already a capability hook, and a flag would be a second answer
     * to it.
     */
    public static final CapabilityType<FloatCapability> FLOAT =
            type("float", FloatCapability.CODEC);

    /** The bungee zombie: drops in, takes a plant, leaves with it. */
    public static final CapabilityType<BungeeCapability> BUNGEE =
            type("bungee", BungeeCapability.CODEC);

    /** The zamboni: crushes what it drives over and leaves ice behind. */
    public static final CapabilityType<ZamboniCapability> ZAMBONI =
            type("zamboni", ZamboniCapability.CODEC);

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
        register(SUBMERGE, "submerge");
        register(FLOAT, "float");
        register(BUNGEE, "bungee");
        register(ZAMBONI, "zamboni");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(CapabilityType<?> type, String path) {
        BuiltInRegistries.registerStatic((Registry) BuiltInRegistries.ZOMBIE_CAPABILITIES, "pvzce:" + path, type);
    }

    private ZombieCapabilities() {
    }
}

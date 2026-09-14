package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.ZombieCapabilities;

import java.util.List;
import java.util.Optional;

/**
 * Data-driven zombie definition.
 *
 * <p>Every zombie walks and eats (that loop lives in {@code ZombieEntity});
 * everything that differs between types is a {@link ZombieCapability}: armor,
 * vaulting, flying, digging, hammering, boss phases. A zombie can carry several
 * at once, and mods add new capability types without touching this record.
 *
 * <p>Non-flying zombies drown when they enter water unless {@code can_swim} is
 * true (reserved for snorkel-type zombies).
 */
public record ZombieDef(
        Identifier id,
        int health,
        float moveSpeed,
        int biteDamage,
        int biteIntervalTicks,
        boolean canSwim,
        List<TypedCapability<ZombieCapability>> capabilities,
        Optional<Identifier> behavior,
        ZombieSounds sounds,
        AnimationBindings animations,
        Optional<Identifier> texture,
        /**
         * Presentation-only size multiplier; see {@link ContentDefs#RENDER_SCALE_CODEC}.
         *
         * <p>The client draws this content that many times bigger than its art declares,
         * in both axes so the shape is kept. Nothing the server simulates changes.
         */
        float renderScale,
        /**
         * What this zombie carries that visibly wears out; see {@link EquipmentDef}.
         *
         * <p>Empty for a zombie with no equipment art. The entries are presentation except
         * for {@code drop_particle}, which the server emits when a piece is destroyed - so
         * both sides read this one list instead of each keeping its own idea of which
         * sprite belongs to which hat.
         */
        List<EquipmentDef> equipment,
        /**
         * Whether half health costs this zombie its outer arm, as it does for the
         * original's ordinary zombies.
         *
         * <p>False for the giant and the boss, whose arms are part of what they are. The
         * arm is hidden client-side from the synced health, and the server plays the pop
         * right before it happens.
         */
        boolean dropsArm
) {
    public static final int DEFAULT_HEALTH = 200;

    /** A definition that does not care about presentation size: {@code render_scale} 1. */
    public ZombieDef(Identifier id, int health, float moveSpeed, int biteDamage, int biteIntervalTicks,
                     boolean canSwim, List<TypedCapability<ZombieCapability>> capabilities,
                     Optional<Identifier> behavior, ZombieSounds sounds, AnimationBindings animations,
                     Optional<Identifier> texture) {
        this(id, health, moveSpeed, biteDamage, biteIntervalTicks, canSwim, capabilities, behavior,
                sounds, animations, texture, ContentDefs.DEFAULT_RENDER_SCALE, List.of(), true);
    }

    /** A definition with no equipment and the default arm rule. */
    public ZombieDef(Identifier id, int health, float moveSpeed, int biteDamage, int biteIntervalTicks,
                     boolean canSwim, List<TypedCapability<ZombieCapability>> capabilities,
                     Optional<Identifier> behavior, ZombieSounds sounds, AnimationBindings animations,
                     Optional<Identifier> texture, float renderScale) {
        this(id, health, moveSpeed, biteDamage, biteIntervalTicks, canSwim, capabilities, behavior,
                sounds, animations, texture, renderScale, List.of(), true);
    }
    /** Cells per second at the 60tps baseline. */
    public static final float DEFAULT_MOVE_SPEED = 0.47F;
    public static final int DEFAULT_BITE_DAMAGE = 100;
    public static final int DEFAULT_BITE_INTERVAL = 60;

    public static final Codec<ZombieDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(ZombieDef::id),
            Codec.INT.optionalFieldOf("health", DEFAULT_HEALTH).forGetter(ZombieDef::health),
            Codec.FLOAT.optionalFieldOf("move_speed", DEFAULT_MOVE_SPEED).forGetter(ZombieDef::moveSpeed),
            Codec.INT.optionalFieldOf("bite_damage", DEFAULT_BITE_DAMAGE).forGetter(ZombieDef::biteDamage),
            Codec.INT.optionalFieldOf("bite_interval", DEFAULT_BITE_INTERVAL).forGetter(ZombieDef::biteIntervalTicks),
            Codec.BOOL.optionalFieldOf("can_swim", false).forGetter(ZombieDef::canSwim),
            ZombieCapabilities.LIST_CODEC.optionalFieldOf("capabilities", List.of())
                    .forGetter(ZombieDef::capabilities),
            Identifier.CODEC.optionalFieldOf("behavior").forGetter(ZombieDef::behavior),
            ZombieSounds.CODEC.optionalFieldOf("sounds", ZombieSounds.EMPTY).forGetter(ZombieDef::sounds),
            AnimationBindings.MAP_CODEC.forGetter(ZombieDef::animations),
            Identifier.CODEC.optionalFieldOf("texture").forGetter(ZombieDef::texture),
            ContentDefs.RENDER_SCALE_CODEC.forGetter(ZombieDef::renderScale),
            EquipmentDef.CODEC.listOf().optionalFieldOf("equipment", List.of()).forGetter(ZombieDef::equipment),
            Codec.BOOL.optionalFieldOf("drops_arm", true).forGetter(ZombieDef::dropsArm)
    ).apply(i, ZombieDef::new));

    public ZombieDef {
        capabilities = List.copyOf(capabilities);
        equipment = List.copyOf(equipment);
    }

    /** The equipment entry driven by this armor piece id, if any. */
    public java.util.Optional<EquipmentDef> equipmentForPiece(Identifier pieceId) {
        if (pieceId == null) {
            return java.util.Optional.empty();
        }
        for (EquipmentDef entry : equipment) {
            if (entry.piece().isPresent() && pieceId.equals(entry.piece().get())) {
                return java.util.Optional.of(entry);
            }
        }
        return java.util.Optional.empty();
    }

    public boolean canSwim() {
        return canSwim;
    }

    /** Explicit capabilities when present, otherwise the {@code behavior} preset. */
    public List<TypedCapability<ZombieCapability>> resolvedCapabilities() {
        return capabilities.isEmpty() ? ZombieBehaviorPresets.expand(behavior.orElse(null)) : capabilities;
    }

    /** First capability of the given implementation type, if present. */
    public <T extends ZombieCapability> Optional<T> capability(Class<T> type) {
        for (TypedCapability<ZombieCapability> entry : resolvedCapabilities()) {
            if (type.isInstance(entry.value())) {
                return Optional.of(type.cast(entry.value()));
            }
        }
        return Optional.empty();
    }

    /** Cells advanced per tick at the 60tps baseline. */
    public float moveSpeedPerTick() {
        return moveSpeed / com.pvzce.common.PvzceConstants.TICKS_PER_SECOND;
    }

    /** Optional per-zombie sound event overrides; empty fields fall back to capability defaults. */
    public record ZombieSounds(
            Optional<Identifier> spawn,
            Optional<Identifier> hit,
            Optional<Identifier> armorHit,
            Optional<Identifier> bite,
            Optional<Identifier> death,
            Optional<Identifier> special
    ) {
        public static final ZombieSounds EMPTY = new ZombieSounds(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());

        public static final MapCodec<ZombieSounds> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.optionalFieldOf("spawn").forGetter(ZombieSounds::spawn),
                Identifier.CODEC.optionalFieldOf("hit").forGetter(ZombieSounds::hit),
                Identifier.CODEC.optionalFieldOf("armor_hit").forGetter(ZombieSounds::armorHit),
                Identifier.CODEC.optionalFieldOf("bite").forGetter(ZombieSounds::bite),
                Identifier.CODEC.optionalFieldOf("death").forGetter(ZombieSounds::death),
                Identifier.CODEC.optionalFieldOf("special").forGetter(ZombieSounds::special)
        ).apply(i, ZombieSounds::new));

        public static final Codec<ZombieSounds> CODEC = MAP_CODEC.codec();
    }
}

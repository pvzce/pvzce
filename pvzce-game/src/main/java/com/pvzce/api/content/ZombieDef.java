package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.ZombieCapabilities;
import com.pvzce.common.capability.zombie.ArmorCapability;

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
         * What it looks like and what falls off it; see {@link Presentation}.
         *
         * <p>One component rather than four because DFU's record codec stops at sixteen fields and
         * the budget cost below is the seventeenth - and because the four belong together anyway.
         */
        Presentation presentation,
        /**
         * What this zombie costs a wave that budgets its composition; see {@code WavePacingData}.
         *
         * <p>Unwritten ({@code 0}) means "work it out from what the zombie is" - see
         * {@link #effectiveBudgetCost()}. The field exists for the content a formula cannot know
         * about: a zombie whose threat is an ability rather than its health bar.
         */
        int budgetCost,
        com.pvzce.api.entity.attribute.AttributeOverrides attributes
) {
    /** Source compatibility for definitions created before per-entity attributes. */
    public ZombieDef(Identifier id, int health, float moveSpeed, int biteDamage, int biteIntervalTicks,
                     boolean canSwim, List<TypedCapability<ZombieCapability>> capabilities,
                     Optional<Identifier> behavior, ZombieSounds sounds, AnimationBindings animations,
                     Optional<Identifier> texture, float renderScale, Presentation presentation, int budgetCost) {
        this(id, health, moveSpeed, biteDamage, biteIntervalTicks, canSwim, capabilities, behavior,
                sounds, animations, texture, renderScale, presentation, budgetCost,
                com.pvzce.api.entity.attribute.AttributeOverrides.EMPTY);
    }
    public static final int DEFAULT_HEALTH = 200;

    /** A definition that does not care about presentation size: {@code render_scale} 1. */
    public ZombieDef(Identifier id, int health, float moveSpeed, int biteDamage, int biteIntervalTicks,
                     boolean canSwim, List<TypedCapability<ZombieCapability>> capabilities,
                     Optional<Identifier> behavior, ZombieSounds sounds, AnimationBindings animations,
                     Optional<Identifier> texture) {
        this(id, health, moveSpeed, biteDamage, biteIntervalTicks, canSwim, capabilities, behavior,
                sounds, animations, texture, ContentDefs.DEFAULT_RENDER_SCALE, Presentation.DEFAULT, 0);
    }

    /** A definition with no equipment and the default arm rule. */
    public ZombieDef(Identifier id, int health, float moveSpeed, int biteDamage, int biteIntervalTicks,
                     boolean canSwim, List<TypedCapability<ZombieCapability>> capabilities,
                     Optional<Identifier> behavior, ZombieSounds sounds, AnimationBindings animations,
                     Optional<Identifier> texture, float renderScale) {
        this(id, health, moveSpeed, biteDamage, biteIntervalTicks, canSwim, capabilities, behavior,
                sounds, animations, texture, renderScale, Presentation.DEFAULT, 0);
    }
    /** Cells per second at the 60tps baseline. */
    public static final float DEFAULT_MOVE_SPEED = 0.47F;
    public static final int DEFAULT_BITE_DAMAGE = 100;
    public static final int DEFAULT_BITE_INTERVAL = 60;
    /** What a zombie with nothing written and nothing special about it costs. */
    public static final int BASE_BUDGET_COST = 1;
    /**
     * How much one health bar is worth, as a fraction of {@link #DEFAULT_HEALTH}.
     *
     * <p>Five ordinary zombies' worth of health is one point more than an ordinary zombie, so the
     * point is roughly "what the original would call one more zombie" and a level's budget reads
     * in the number of zombies it is about to send.
     */
    public static final float HEALTH_POINTS_PER_DEFAULT_HEALTH = 1F / 5F;
    /** How much each point of total armor durability is worth, as a fraction of a health bar. */
    public static final float ARMOR_POINTS_PER_HEALTH_BAR = 1F / 3F;
    /** How much being faster than {@link #DEFAULT_MOVE_SPEED} is worth, per multiple of it. */
    public static final float SPEED_POINTS_PER_DEFAULT_SPEED = 1F / 3F;
    /** What no zombie may cost, however cheap its numbers are. */
    public static final int MIN_BUDGET_COST = 1;
    /** What no zombie may cost, however expensive: a boss is one wave, not one zombie. */
    public static final int MAX_BUDGET_COST = 40;

    /**
     * The zombie definition's JSON, split into two groups.
     *
     * <p>The four presentation fields ride in one nested {@link Presentation} because DFU's
     * {@code RecordCodecBuilder} stops at sixteen members and the budget system added the
     * seventeenth. Grouping what the client draws is also the honest split: equipment, the arm and
     * head rules and the hidden bones are all "how this zombie looks", and a pack that adds a
     * fifth such field extends that record instead of hitting the same wall.
     */
    public static final MapCodec<ZombieDef> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
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
            Presentation.MAP_CODEC.forGetter(ZombieDef::presentation),
            Codec.INT.optionalFieldOf("budget_cost", 0).forGetter(ZombieDef::budgetCost),
            com.pvzce.api.entity.attribute.AttributeOverrides.CODEC.optionalFieldOf("attributes",
                    com.pvzce.api.entity.attribute.AttributeOverrides.EMPTY).forGetter(ZombieDef::attributes)
    ).apply(i, ZombieDef::new));

    public static final Codec<ZombieDef> CODEC = MAP_CODEC.codec();

    /**
     * What this zombie looks like: its equipment, whether it loses an arm or its head on the way
     * down, and which model bones are never drawn.
     *
     * <p>Presentation except for {@code drop_particle}, which the server emits when a piece of
     * equipment is destroyed - so both sides read this one block instead of each keeping its own
     * idea of which sprite belongs to which hat.
     *
     * @param equipment   what this zombie carries that visibly wears out; see {@link EquipmentDef}
     * @param dropsArm    whether half health costs it its outer arm
     * @param dropsHead    whether dying throws its head off as a particle
     * @param hiddenBones model bones this zombie never draws, whatever the clip says
     */
    public record Presentation(List<EquipmentDef> equipment, boolean dropsArm, boolean dropsHead,
                               List<String> hiddenBones) {
        public static final Presentation DEFAULT = new Presentation(List.of(), true, true, List.of());

        public static final MapCodec<Presentation> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                EquipmentDef.CODEC.listOf().optionalFieldOf("equipment", List.of())
                        .forGetter(Presentation::equipment),
                Codec.BOOL.optionalFieldOf("drops_arm", true).forGetter(Presentation::dropsArm),
                Codec.BOOL.optionalFieldOf("drops_head", true).forGetter(Presentation::dropsHead),
                Codec.STRING.listOf().optionalFieldOf("hidden_bones", List.of())
                        .forGetter(Presentation::hiddenBones)
        ).apply(i, Presentation::new));

        public Presentation {
            equipment = equipment == null ? List.of() : List.copyOf(equipment);
            hiddenBones = hiddenBones == null ? List.of() : List.copyOf(hiddenBones);
        }
    }

    public ZombieDef {
        capabilities = List.copyOf(capabilities);
        presentation = presentation == null ? Presentation.DEFAULT : presentation;
        attributes = attributes == null ? com.pvzce.api.entity.attribute.AttributeOverrides.EMPTY : attributes;
    }

    /**
     * The forwarding accessors for the presentation block.
     *
     * <p>Kept so the two dozen call sites that ask a zombie whether it drops its head or what
     * equipment it carries do not have to know the four fields moved into {@link #presentation}.
     * A record's components are its shape; what it <em>means</em> is allowed to be a method.
     */
    public List<EquipmentDef> equipment() {
        return presentation.equipment();
    }

    public boolean dropsArm() {
        return presentation.dropsArm();
    }

    public boolean dropsHead() {
        return presentation.dropsHead();
    }

    public List<String> hiddenBones() {
        return presentation.hiddenBones();
    }

    /** The equipment entry driven by this armor piece id, if any. */
    public java.util.Optional<EquipmentDef> equipmentForPiece(Identifier pieceId) {
        if (pieceId == null) {
            return java.util.Optional.empty();
        }
        for (EquipmentDef entry : equipment()) {
            if (entry.piece().isPresent() && pieceId.equals(entry.piece().get())) {
                return java.util.Optional.of(entry);
            }
        }
        return java.util.Optional.empty();
    }

    public boolean canSwim() {
        return canSwim;
    }

    /**
     * What one of these costs a budget wave: the written number, or what the zombie is.
     *
     * <p>The derived number is deliberately crude and deliberately in the open, because a level
     * author has to be able to predict it: one point, plus a point per five health bars, plus a
     * point per three health bars of armor, plus a point per third of the default walking speed
     * it is faster than. That reads 1 for an ordinary zombie, 2 for a conehead or a pole vaulter,
     * 3 for a buckethead, 4 for a football zombie and 13 for a gargantuar - which is the shape of
     * the original's own zombie values.
     *
     * <p>Health is compared against {@link #DEFAULT_HEALTH} rather than against a table so that a
     * pack's own zombie is priced by what it is, and a zombie whose threat is an ability rather
     * than a stat writes {@code budget_cost} instead.
     */
    public int effectiveBudgetCost() {
        if (budgetCost > 0) {
            return Math.min(MAX_BUDGET_COST, Math.max(MIN_BUDGET_COST, budgetCost));
        }
        float points = BASE_BUDGET_COST;
        points += HEALTH_POINTS_PER_DEFAULT_HEALTH * ((health - DEFAULT_HEALTH) / (float) DEFAULT_HEALTH);
        int armorDurability = 0;
        for (TypedCapability<ZombieCapability> entry : resolvedCapabilities()) {
            if (entry.value() instanceof ArmorCapability armor) {
                for (ArmorDef piece : armor.armor()) {
                    armorDurability += Math.max(0, piece.durability());
                }
            }
        }
        points += ARMOR_POINTS_PER_HEALTH_BAR * (armorDurability / (float) DEFAULT_HEALTH);
        points += SPEED_POINTS_PER_DEFAULT_SPEED
                * Math.max(0F, moveSpeed / DEFAULT_MOVE_SPEED - 1F);
        return Math.min(MAX_BUDGET_COST, Math.max(MIN_BUDGET_COST, Math.round(points)));
    }

    /** Explicit capabilities when present, otherwise the {@code behavior} preset. */
    public List<TypedCapability<ZombieCapability>> resolvedCapabilities() {
        return capabilities.isEmpty() ? ZombieBehaviorPresets.expand(behavior.orElse(null)) : capabilities;
    }

    /** Airborne spawns can cross water without being swimmers. */
    public boolean spawnsAirborne() {
        return resolvedCapabilities().stream().anyMatch(c -> c.value().spawnsAirborne());
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
            Optional<Identifier> special,
            Optional<Identifier> walk
    ) {
        public static final ZombieSounds EMPTY = new ZombieSounds(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        public static final MapCodec<ZombieSounds> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.optionalFieldOf("spawn").forGetter(ZombieSounds::spawn),
                Identifier.CODEC.optionalFieldOf("hit").forGetter(ZombieSounds::hit),
                Identifier.CODEC.optionalFieldOf("armor_hit").forGetter(ZombieSounds::armorHit),
                Identifier.CODEC.optionalFieldOf("bite").forGetter(ZombieSounds::bite),
                Identifier.CODEC.optionalFieldOf("death").forGetter(ZombieSounds::death),
                Identifier.CODEC.optionalFieldOf("special").forGetter(ZombieSounds::special),
                Identifier.CODEC.optionalFieldOf("walk").forGetter(ZombieSounds::walk)
        ).apply(i, ZombieSounds::new));

        public static final Codec<ZombieSounds> CODEC = MAP_CODEC.codec();

        /**
         * A sound that plays <em>while</em> the zombie walks, on a loop, rather than once.
         *
         * <p>The jack-in-the-box's music box, and the original's mechanism for it: its
         * {@code StartZombieSound} starts {@code FOLEY_JACKINTHEBOX} when the zombie reaches the
         * board and {@code StopZombieSound} takes it away when the box opens or the body dies, so
         * the tune runs under the whole walk - twenty-odd seconds of it - and stops with the thing
         * making it. A one-shot fired at the wrong end of that walk is what this build had: the
         * music box only started as the box opened, 110 ticks before the blast, so the player heard
         * a second and a half of a six-second tune and nothing at all while the clown approached.
         *
         * <p>Client-side: the sound belongs to an entity the client is already drawing, and only the
         * client can stop it the moment that entity stops walking. See {@code EntityLoops}.
         */
        public Optional<Identifier> walkSound() {
            return walk;
        }

        /** The same block with a walking sound, for tests and for content built in code. */
        public ZombieSounds withWalkSound(Identifier sound) {
            return new ZombieSounds(spawn, hit, armorHit, bite, death, special,
                    Optional.ofNullable(sound));
        }
    }
}

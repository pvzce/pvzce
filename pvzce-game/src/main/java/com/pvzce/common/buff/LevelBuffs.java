package com.pvzce.common.buff;

import com.pvzce.api.content.LevelBuff;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.plant.ShooterCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PvzceRegistries;

import java.util.List;

/**
 * Registration and lookup for level buffs.
 *
 * <p>Mirrors {@code LevelMechanics} one system over: the built-ins are registered against
 * {@link PvzceRegistries#LEVEL_BUFFS} from {@code BuiltInRegistries.bootstrap()}, and every
 * other class asks this one question - "what is this id" - instead of reaching into the
 * registry. There is no codec here because a buff has no data: the id <em>is</em> the whole
 * description, which is the same reason the capability registries are code-only.
 */
public final class LevelBuffs {
    private LevelBuffs() {
    }

    /** Registers every built-in buff; called from {@code BuiltInRegistries.bootstrap()}. */
    public static void bootstrap() {
        for (BuiltInBuffs buff : BuiltInBuffs.values()) {
            register(buff.id(), buff);
        }
    }

    /** Registers one buff. A mod calls this before level data is loaded. */
    public static void register(Identifier id, LevelBuff buff) {
        BuiltInRegistries.registerStatic(BuiltInRegistries.LEVEL_BUFFS, id.toString(), buff);
    }

    public static LevelBuff get(Identifier id) {
        return id == null ? null : BuiltInRegistries.LEVEL_BUFFS.get(id);
    }

    /** True when this build has that buff: the check a reward entry needs before it is paid. */
    public static boolean isRegistered(Identifier id) {
        return get(id) != null;
    }

    /** The id of a registered buff, or {@code null} for one this build does not have. */
    public static Identifier idOf(LevelBuff buff) {
        if (buff == null) {
            return null;
        }
        if (buff.id() != null) {
            return buff.id();
        }
        return BuiltInRegistries.LEVEL_BUFFS.getKey(buff);
    }

    /**
     * The product of every active buff's mushroom-range multiplier; 1 means "unchanged".
     *
     * <p>A product rather than a maximum so two mods' multipliers compose instead of one
     * silently winning, and folded from 1 so the no-buff answer is exactly 1 - which is what
     * the callers compare against to decide whether to touch the range at all.
     */
    public static float sporeRangeMultiplier(List<LevelBuff> active) {
        float multiplier = 1F;
        for (LevelBuff buff : active == null ? List.<LevelBuff>of() : active) {
            multiplier *= buff.mushroomRangeMultiplier();
        }
        return multiplier;
    }

    /**
     * How many columns right the fog's boundary moves for this run.
     *
     * <p>Summed rather than maximised: two copies of a "shorter fog" buff - a level that pins one
     * and a player who brought one - are two reasons to see further, and a maximum would silently
     * discard one of them.
     */
    public static float fogRetreat(List<LevelBuff> active) {
        float columns = 0F;
        for (LevelBuff buff : active == null ? List.<LevelBuff>of() : active) {
            columns += Math.max(0F, buff.fogRetreat());
        }
        return columns;
    }

    /** True when any active buff picks resources up on its own. */
    public static boolean autoCollects(List<LevelBuff> active) {
        for (LevelBuff buff : active == null ? List.<LevelBuff>of() : active) {
            if (buff.autoCollectsResources()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A spore shooter: a plant that fires projectiles <em>and</em> is a mushroom.
     *
     * <p>Both halves matter. "Mushroom" is the {@code pvzce:nocturnal} capability rather than a
     * list of ids, so a mod's mushroom joins as soon as it says it sleeps in daylight;
     * "fires projectiles" is {@link ShooterCapability}, which is what separates the spore
     * shooters from the mushrooms that do something else entirely - the fume-shroom's cloud is
     * {@code ConeAttackCapability} and is not a shot with a range, and the hypno-shroom,
     * sun-shroom, ice-shroom and doom-shroom never fire at all.
     */
    public static boolean isSporeShooter(PlantDef def) {
        return def != null && def.capability(ShooterCapability.class).isPresent() && isNocturnal(def);
    }

    private static boolean isNocturnal(PlantDef def) {
        return def.capability(com.pvzce.common.capability.plant.NocturnalCapability.class).isPresent();
    }
}

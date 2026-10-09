package com.pvzce.common.level;

import com.pvzce.api.content.ZombieDef;
import com.pvzce.common.PvzceConstants;

import java.util.List;

/** One draw chooses an exact quantity; an unfilled probability interval means no drop. */
public final class FusionDrops {
    private FusionDrops() { }

    public static List<Float> chances(ZombieDef zombie) {
        int tier = zombie.effectiveBudgetCost();
        // Budget pricing discounts durability. Keep heavy armour and giants above buckets
        // even when they round to the same cost; never read the damaged health at death.
        long durability = (long) zombie.health() + zombie.armorDurability();
        if (durability > PvzceConstants.FUSION_STRONG_DURABILITY) tier = Math.max(tier, 4);
        if (durability > PvzceConstants.FUSION_ELITE_DURABILITY) tier = Math.max(tier, 5);
        var tables = PvzceConstants.FUSION_DROP_CHANCES;
        return tables.get(Math.max(0, Math.min(tables.size() - 1, tier - 1)));
    }

    public static int count(ZombieDef zombie, float multiplier, float roll) {
        if (multiplier <= 0F) return 0;
        List<Float> chances = chances(zombie);
        double total = chances.stream().mapToDouble(Float::doubleValue).sum();
        double scale = Math.min(multiplier, 1D / total);
        double cumulative = 0D;
        for (int i = 0; i < chances.size(); i++) {
            cumulative += chances.get(i) * scale;
            if (roll < cumulative) return i + 1;
        }
        return 0;
    }
}

package com.pvzce.common.level.mechanic;

import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.WavePacingData;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Spends a wave's point budget on a random selection from its pool.
 *
 * <p>The {@code budget} half of {@link WavePacingData}: a wave says how many points it may spend
 * and which zombies it may spend them on, and this picks. What it buys is variety without a
 * table - the same twenty points are a buckethead and a crowd, or two bucketheads and a
 * conehead, and the level author wrote neither.
 *
 * <p>Greedy and random rather than optimal: the picks are shuffled and taken while they fit,
 * which leaves the remainder unspent instead of hunting for a combination that adds up exactly.
 * Spending the last point on a sixth ordinary zombie is not worth searching for, and a search
 * would also make the wave's size depend on the algorithm rather than on the budget.
 *
 * <p>This lives in {@code common} beside the mechanic rather than in {@code api} because it needs
 * the zombie registry: a budget is written in ids, and whether an id is a zombie is not a
 * question the level definition answers by itself.
 */
public final class BudgetPlanner {
    private BudgetPlanner() {
    }

    /**
     * The zombies one wave's budget buys, in the order they should be released.
     *
     * @param pace     the wave's resolved pacing (its budget, pool and count limits)
     * @param fallback what to send when the budget cannot buy anything at all - the wave's own
     *                 {@code entries}, which is what a level that wrote a budget and a pool of
     *                 unknown ids would otherwise silently turn into an empty wave
     * @param random   the level's own random source, so a save/restore does not re-roll the wave
     * @return the composition, already in release order; empty only when nothing is eligible
     */
    public static List<Identifier> plan(WavePacingData.Pace pace, List<WaveDef.Entry> fallback,
                                        Random random) {
        List<Identifier> pool = pool(pace);
        if (pool.isEmpty()) {
            return fallback == null ? List.of() : expand(fallback);
        }
        List<Identifier> candidates = new ArrayList<>();
        for (Identifier id : pool) {
            ZombieDef def = BuiltInRegistries.ZOMBIES.get(id);
            if (def != null) {
                candidates.add(id);
            }
        }
        if (candidates.isEmpty()) {
            return fallback == null ? List.of() : expand(fallback);
        }

        int budget = pace.budget();
        int minCount = Math.max(0, pace.minCount());
        int maxCount = Math.max(0, pace.maxCount());
        Collections.shuffle(candidates, random);

        List<Identifier> chosen = new ArrayList<>();
        int spent = 0;
        for (int round = 0; (maxCount == 0 || chosen.size() < maxCount) && round < 512; round++) {
            boolean bought = false;
            for (Identifier id : candidates) {
                if (maxCount > 0 && chosen.size() >= maxCount) {
                    break;
                }
                int cost = costOf(id);
                if (spent + cost > budget) {
                    continue;
                }
                chosen.add(id);
                spent += cost;
                bought = true;
            }
            if (!bought) {
                break;
            }
        }
        // The floor wins over the ceiling: a wave that says "at least four zombies" gets four
        // even if the pool's cheapest zombie costs more than the budget can afford four of, and
        // a level whose numbers were meant to be read as "a threat" is not silently halved.
        while (chosen.size() < minCount) {
            chosen.add(candidates.get(chosen.size() % candidates.size()));
        }
        if (chosen.isEmpty()) {
            chosen.add(candidates.get(0));
        }
        Collections.shuffle(chosen, random);
        return List.copyOf(chosen);
    }

    /** What one zombie costs, as the data says or as its own numbers imply. */
    public static int costOf(Identifier id) {
        ZombieDef def = BuiltInRegistries.ZOMBIES.get(id);
        return def == null ? ZombieDef.BASE_BUDGET_COST : def.effectiveBudgetCost();
    }

    /**
     * The wave's pool: what it named, or every registered zombie when it named nothing.
     *
     * <p>"Every zombie" is the useful default for a level that wants the engine to surprise it;
     * a pool that names ids the pack did not ship is a validation error, not a silent narrowing.
     */
    private static List<Identifier> pool(WavePacingData.Pace pace) {
        if (!pace.pool().isEmpty()) {
            return pace.pool();
        }
        return List.copyOf(BuiltInRegistries.ZOMBIES.keySet());
    }

    /**
     * The wave's own {@code entries}, expanded, for a budget that can buy nothing.
     *
     * <p>A pool of ids the pack does not ship, or a budget below the cheapest zombie, would
     * otherwise turn a wave the level wrote into an empty one - and "nothing arrives" is a much
     * worse failure than "the written composition arrives".
     */
    private static List<Identifier> expand(List<WaveDef.Entry> entries) {
        List<Identifier> zombies = new ArrayList<>();
        for (WaveDef.Entry entry : entries) {
            for (int i = 0; i < Math.max(0, entry.count()); i++) {
                zombies.add(entry.id());
            }
        }
        // A wave with no entries either is a grave level's progress-bar-only wave; one ordinary
        // zombie is the smallest thing that still lets the level end.
        if (zombies.isEmpty()) {
            zombies.add(Identifier.withDefaultNamespace("basic_zombie"));
        }
        return zombies;
    }
}

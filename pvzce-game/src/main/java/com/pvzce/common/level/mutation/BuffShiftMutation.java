package com.pvzce.common.level.mutation;

import com.pvzce.api.content.LevelBuff;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.buff.LevelBuffs;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * One more buff, or one fewer: the run's buff list moves by itself.
 *
 * <p>A coin flip between taking and giving, because both are interesting: losing the auto-pickup
 * the player was relying on is a real cost, and gaining a buff they did not choose is the kind of
 * gift a mutation should be able to hand out. The tier's multiplier is the number of <em>buffs</em>
 * moved in one go - at HELL a roll of 3 adds three - which is what makes a high-tier buff shift
 * worth the panel slot.
 *
 * <p>The level's own pinned buffs are never taken away: a level that says "this run has
 * auto-pickup" is making a statement about the level, not lending the player something to lose.
 */
final class BuffShiftMutation implements Mutation {
    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_BUFF_SHIFT;
    }

    @Override
    public boolean canRun(LevelServer level) {
        return !registeredBuffs().isEmpty();
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        List<LevelBuff> before = level.activeBuffs();
        List<LevelBuff> after = shift(level, before, roll);
        level.setActiveBuffs(after);
        return new Applied(before);
    }

    @Override
    public void revert(LevelServer level, Mutation.Roll roll, Object state) {
        if (state instanceof Applied applied) {
            level.setActiveBuffs(applied.before());
        }
    }

    /**
     * Says which buffs moved, because the generic banner cannot.
     *
     * <p>"增益变动 ×1.40" tells the player that something happened to their buffs and nothing
     * about what; the only place it showed was the icon row in the corner, which nobody was
     * looking at. Naming the gains and losses is the whole of "let the player know".
     */
    @Override
    public java.util.Optional<String> announcement(LevelServer level, Mutation.Roll roll,
                                                  Object state) {
        if (!(state instanceof Applied applied)) {
            return java.util.Optional.empty();
        }
        List<LevelBuff> after = level.activeBuffs();
        List<String> gained = namesOf(after, applied.before());
        List<String> lost = namesOf(applied.before(), after);
        if (gained.isEmpty() && lost.isEmpty()) {
            return java.util.Optional.empty();
        }
        StringBuilder line = new StringBuilder("增益变动");
        if (!gained.isEmpty()) {
            line.append("：获得 ").append(String.join("、", gained));
        }
        if (!lost.isEmpty()) {
            line.append(gained.isEmpty() ? "：" : "；").append("失去 ").append(String.join("、", lost));
        }
        return java.util.Optional.of(line.toString());
    }

    /** The display names of everything in {@code from} that {@code without} does not hold. */
    private static List<String> namesOf(List<LevelBuff> from, List<LevelBuff> without) {
        List<String> names = new ArrayList<>();
        for (LevelBuff buff : from) {
            if (!without.contains(buff)) {
                names.add(MutationText.subjectName(LevelBuffs.idOf(buff)));
            }
        }
        return names;
    }

    /**
     * The new list.
     *
     * <p>The roll decides which way it goes: the chance of adding is {@code roll / (1 + roll)}, so
     * a roll as written is an even coin flip and a tier's multiplier tilts it towards giving - at
     * HELL a roll of 3 adds three times as often as it takes. How much moves at once is that same
     * number of buffs. "Nothing to add" and "nothing to take" both fall back to the other
     * direction rather than doing nothing at all: a mutation that silently does nothing is
     * indistinguishable from a broken one.
     */
    private static List<LevelBuff> shift(LevelServer level, List<LevelBuff> before,
                                         Mutation.Roll roll) {
        float weight = Math.max(0.05F, roll.multiplier());
        int count = Math.max(1, Math.round(weight));
        boolean adding = level.random().nextFloat() < weight / (1F + weight);
        List<LevelBuff> after = adding ? grow(level, before, count) : shrink(level, before, count);
        if (after.equals(before)) {
            // The direction the dice picked had nothing to work with, so the other one gets its
            // turn. The fallback used to be written as "if the direction is add and there is
            // nothing missing, shrink instead", which covers a full list but not an empty one - so
            // a shift that rolled "take" against no buffs at all did nothing whatsoever. A silent
            // no-op is exactly what this class says a mutation must never be, and it made
            // MutationManagerTest.aBuffShiftReachesTheClientAndSaysWhichBuffMoved fail about once
            // in a hundred runs on a clean tree.
            after = adding ? shrink(level, before, count) : grow(level, before, count);
        }
        return after;
    }

    /** Up to {@code count} buffs the run does not have yet, picked at random. */
    private static List<LevelBuff> grow(LevelServer level, List<LevelBuff> before, int count) {
        List<LevelBuff> missing = missing(before);
        if (missing.isEmpty()) {
            return List.copyOf(before);
        }
        List<LevelBuff> after = new ArrayList<>(before);
        for (int i = 0; i < count && !missing.isEmpty(); i++) {
            LevelBuff picked = missing.remove(level.random().nextInt(missing.size()));
            after.add(picked);
        }
        return List.copyOf(after);
    }

    /** Drops up to {@code count} buffs the level did not pin. */
    private static List<LevelBuff> shrink(LevelServer level, List<LevelBuff> before, int count) {
        List<LevelBuff> after = new ArrayList<>(before);
        for (int i = 0; i < count; i++) {
            LevelBuff victim = removable(level, after);
            if (victim == null) {
                break;
            }
            after.remove(victim);
        }
        return List.copyOf(after);
    }

    /** One buff that may be taken away: not one of the level's own pinned ones. */
    private static LevelBuff removable(LevelServer level, List<LevelBuff> active) {
        List<Identifier> pinned = level.def().buffPlan().fixedBuffs();
        for (LevelBuff buff : active) {
            Identifier id = LevelBuffs.idOf(buff);
            if (id != null && !pinned.contains(id)) {
                return buff;
            }
        }
        return null;
    }

    /** Every registered buff the run does not have yet. */
    private static List<LevelBuff> missing(List<LevelBuff> active) {
        List<LevelBuff> missing = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.LEVEL_BUFFS.keySet()) {
            LevelBuff buff = LevelBuffs.get(id);
            if (buff != null && !active.contains(buff)) {
                missing.add(buff);
            }
        }
        return missing;
    }

    /** Every registered buff, for the "may it run" answer. */
    private static List<LevelBuff> registeredBuffs() {
        List<LevelBuff> buffs = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.LEVEL_BUFFS.keySet()) {
            LevelBuff buff = LevelBuffs.get(id);
            if (buff != null) {
                buffs.add(buff);
            }
        }
        return buffs;
    }

    /** The buff list as it was. */
    private record Applied(List<LevelBuff> before) {
    }
}

package com.pvzce.common.core;

import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The one rule for "may this level be played yet?".
 *
 * <p>Pure functions, no registry and no file access: the server evaluates them to
 * decide what to send and what to accept, and the client receives the verdict rather
 * than a rule it would have to re-implement. Keeping it here - rather than inside
 * {@code LevelServer} - is what lets the server, the editor and the tests share one
 * implementation, exactly like {@link LevelGrouping} for level pages.
 *
 * <p>A level with no {@code unlock} block is always playable, so nothing that existed
 * before unlock conditions has to be touched.
 */
public final class LevelUnlocks {
    /**
     * Everything the evaluation needs to look at.
     *
     * @param clearedLevels   levels the world has finished at least once
     * @param purchasedLevels levels bought with coins in this world
     * @param ownsCard        the backpack rule, so the server's {@code PlayerProfile}
     *                        and the client's {@code ClientProfile} answer identically
     * @param coins           the wallet balance
     * @param sandbox         a world that unlocks everything
     */
    public record Context(Set<Identifier> clearedLevels, Set<Identifier> purchasedLevels,
                          Predicate<Identifier> ownsCard, int coins, boolean sandbox) {
        public Context {
            clearedLevels = Set.copyOf(clearedLevels);
            purchasedLevels = Set.copyOf(purchasedLevels);
            ownsCard = ownsCard == null ? card -> false : ownsCard;
        }

        /** A world with nothing done in it yet, for tests and for a fresh profile. */
        public static Context empty() {
            return new Context(Set.of(), Set.of(), card -> false, 0, false);
        }
    }

    /**
     * The verdict for one level, as it travels to the client.
     *
     * @param unlocked     whether the level can be entered
     * @param buyable      whether buying it would help right now (locked, priced, affordable)
     * @param cost         the price when there is one, for the button label
     * @param unmet        the conditions still outstanding, for the client to draw
     * @param reason       a ready-to-show sentence; empty when unlocked
     * @param hidden       not to be listed at all until unlocked
     */
    public record State(boolean unlocked, boolean buyable, int cost,
                        List<LevelUnlock.Requirement> unmet, String reason, boolean hidden) {
        public static final State OPEN = new State(true, false, 0, List.of(), "", false);

        public State {
            unmet = List.copyOf(unmet);
        }

        /** True when the level should not appear in the list yet. */
        public boolean isHidden() {
            return hidden && !unlocked;
        }
    }

    /**
     * Evaluates one level's unlock block against a world's progress.
     *
     * @param levelId the level being asked about, so an earlier purchase of it counts
     */
    public static State evaluate(Identifier levelId, LevelUnlock unlock, Context context) {
        LevelUnlock rule = unlock == null ? LevelUnlock.NONE : unlock;
        if (context.sandbox() || rule.isOpen()
                || (levelId != null && context.purchasedLevels().contains(levelId))) {
            return State.OPEN;
        }
        List<LevelUnlock.Requirement> unmet = new ArrayList<>();
        for (LevelUnlock.Requirement requirement : rule.requires()) {
            if (!satisfied(requirement, context)) {
                unmet.add(requirement);
            }
        }
        if (unmet.isEmpty()) {
            return State.OPEN;
        }
        int cost = rule.cost().orElse(0);
        boolean buyable = cost > 0 && context.coins() >= cost;
        return new State(false, buyable, cost, unmet, describe(unmet), rule.hidden());
    }

    /** A single requirement against a world's progress. */
    public static boolean satisfied(LevelUnlock.Requirement requirement, Context context) {
        if (requirement == null || requirement.type() == null) {
            return false;
        }
        if (requirement.isLevel()) {
            return requirement.id().map(context.clearedLevels()::contains).orElse(false);
        }
        if (requirement.isCard()) {
            return requirement.id().map(context.ownsCard()::test).orElse(false);
        }
        if (requirement.isCoins()) {
            return context.coins() >= Math.max(0, requirement.amount());
        }
        // An unknown type is reported by LevelValidator; until then it blocks, because
        // silently dropping a condition the author wrote is worse than a stuck level.
        return false;
    }

    /**
     * The player-facing sentence for a set of unmet requirements.
     *
     * <p>Built here rather than in the screen so the level list, the tooltip and the
     * refusal message all say the same thing.
     */
    public static String describe(List<LevelUnlock.Requirement> unmet) {
        List<String> parts = new ArrayList<>();
        for (LevelUnlock.Requirement requirement : unmet) {
            String name = requirement.id().map(LevelUnlocks::shortName).orElse("");
            if (requirement.isLevel()) {
                parts.add("通关 " + name);
            } else if (requirement.isCard()) {
                parts.add("解锁 " + name);
            } else if (requirement.isCoins()) {
                parts.add("金币达到 " + requirement.amount());
            } else {
                parts.add("未知条件 " + requirement.type());
            }
        }
        return String.join("，", parts);
    }

    /** The bit after the namespace; what the UI shows when it has no display name. */
    private static String shortName(Identifier id) {
        String path = id.path();
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Levels whose unlock chain is circular, so nothing in the cycle can ever open.
     *
     * <p>Only loops through <em>levels</em> are reported: a level that requires a card a
     * later level grants is an unusual but legitimate design, and this check deliberately
     * does not try to reason about that.
     *
     * @return one message per level that sits on a cycle
     */
    public static List<String> findCycles(Map<Identifier, LevelUnlock> unlocks) {
        List<String> problems = new ArrayList<>();
        for (Identifier start : unlocks.keySet()) {
            Set<Identifier> seen = new LinkedHashSet<>();
            Identifier at = start;
            while (at != null) {
                if (!seen.add(at)) {
                    problems.add(start + " takes part in a circular unlock chain: " + seen);
                    break;
                }
                LevelUnlock rule = unlocks.get(at);
                at = rule == null ? null : prerequisiteOf(rule).orElse(null);            }
        }
        return problems;
    }

    /** The first prerequisite level named by a rule, if any. */
    private static Optional<Identifier> prerequisiteOf(LevelUnlock rule) {
        for (LevelUnlock.Requirement requirement : rule.requires()) {
            if (requirement.isLevel() && requirement.id().isPresent()) {
                return requirement.id();
            }
        }
        return Optional.empty();
    }

    /**
     * Why an entry is not a usable requirement, or {@code null} when it is fine.
     *
     * <p>Used by {@link com.pvzce.server.level.LevelValidator}; lives next to the
     * evaluation so the two cannot disagree about what "valid" means.
     */
    public static String problemWith(LevelUnlock.Requirement requirement) {
        if (requirement == null) {
            return "null requirement";
        }
        if (requirement.type() == null || requirement.type().isBlank()) {
            return "requirement has no type";
        }
        if (!LevelUnlock.Requirement.types().contains(requirement.type())) {
            return "unknown requirement type '" + requirement.type() + "' (known: "
                    + String.join(", ", LevelUnlock.Requirement.types()) + ")";
        }
        if ((requirement.isLevel() || requirement.isCard()) && requirement.id().isEmpty()) {
            return requirement.type() + " requirement names no id";
        }
        if (requirement.isCoins() && requirement.amount() <= 0) {
            return "coins requirement must ask for a positive amount";
        }
        return null;
    }

    private LevelUnlocks() {
    }
}

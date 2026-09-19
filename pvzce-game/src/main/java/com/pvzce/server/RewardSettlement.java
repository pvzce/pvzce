package com.pvzce.server;

import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.server.level.LevelServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a finished run pays out, and what it does to the profile.
 *
 * <p>Split out of {@code PvzceServer} because these are the rules that change when the
 * win/loss or reward rules change, and because they were the part nobody could test: the
 * server's own payout path is only reachable through a played-out level, so a refactor once
 * dropped the "rewards are for a win" condition and paid every <em>loss</em> the full
 * completion stipend. The rules are now reachable with a profile and a {@code LevelRewards} -
 * see {@link #applyLevelRewards} - while the wiring (which world, saving, telling the client)
 * stays in the server.
 */
final class RewardSettlement {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Rewards");

    private RewardSettlement() {
    }

    /**
     * Everything a finished level pays, and what happens to the profile while it is worked out.
     *
     * @param collected   coins the run picked up, summed over every denomination; paid either way
     * @param bonus       the level's own rewards plus the mower bonus; a win only
     * @param mowers      lawn mowers that survived, which the award page draws as coins
     * @param mowerCoins  what those mowers are worth, sent beside {@code bonus} so the page can
     *                    show coins flying without recomputing a price
     */
    record Payout(int collected, int bonus, int mowers, int mowerCoins,
                  Identifier unlocked, Identifier item, int itemAmount) {
        /** Coins the wallet is given: what the run picked up, plus the bonus. */
        int totalCoins() {
            return collected + bonus;
        }
    }

    /**
     * Settles a finished run against a profile, granting the coins and unlocks it earned.
     *
     * <p>The level's own rewards are for a win, first clear or repeat; a loss banks only what the
     * run picked up. Mowers that were never needed are worth a gold coin each and are a <em>win</em>
     * bonus: a row that still has its mower is a row the zombies never got through, and a defeat
     * has no such rows to speak of.
     */
    static Payout settle(LevelServer current, PlayerProfile profile, boolean plantWin,
                         boolean firstClear) {
        Identifier id = current.def().id();
        int collected = collectedCoins(current);
        Outcome outcome = plantWin
                ? applyLevelRewards(id, current.def().rewards(), firstClear, profile)
                : new Outcome(0, null, null, 0);
        int mowers = plantWin ? current.readyMowerCount() : 0;
        int mowerCoins = mowers * mowerCoinValue();
        profile.grantCoins(collected + outcome.bonus() + mowerCoins);
        return new Payout(collected, outcome.bonus() + mowerCoins, mowers, mowerCoins,
                outcome.unlocked(), outcome.item(), outcome.itemAmount());
    }

    /**
     * What one payout granted: the coin bonus, the first card it unlocked, and the first resource
     * it paid out (with how many), which the award page draws instead of cash.
     */
    record Outcome(int bonus, Identifier unlocked, Identifier item, int itemAmount) {
    }

    /**
     * Applies a level's rewards to a profile.
     *
     * <p>The coins follow the first-clear/repeat split. The unlocks do <em>not</em>: a
     * {@code first_clear} unlock is paid out on any clear whose profile does not have that card
     * yet. "First clear" is a file's existence, not "the reward was handed over", and the two
     * disagree in both directions that matter:
     *
     * <ul>
     *   <li>a world cleared before the level declared the unlock can never receive it - every
     *       later clear takes the repeat branch, so the reward page shows the money bag forever
     *       (this is what happened to 1-4's glove);</li>
     *   <li>a pack that adds an unlock to an already-cleared level would never pay it.</li>
     * </ul>
     *
     * <p>Granting an already-owned card is a no-op, so this is idempotent: the card is what is
     * idempotent, not the payout. Repeat coins are unaffected, so a replay still pays its stipend.
     *
     * <p>A {@code resource} entry is worth what the resource definition says it is worth,
     * multiplied by how many are paid - the wallet holds one number, so a diamond <em>is</em> 1000
     * coins, and the worth is read from the same {@code default_value} the whole denomination
     * ladder lives in rather than written again in the level file. The entry is also reported back
     * so the award page can draw the object rather than count the money. Like coins, resources
     * follow the first-clear/repeat split.
     */
    static Outcome applyLevelRewards(Identifier id, LevelRewards rewards, boolean firstClear,
                                     PlayerProfile profile) {
        int bonus = 0;
        Identifier unlocked = null;
        Identifier rewardItem = null;
        int rewardItemAmount = 0;
        for (LevelRewards.Reward reward : rewards.firstClear()) {
            if (!reward.isUnlock() || reward.id().isEmpty()) {
                continue;
            }
            Identifier card = reward.id().get();
            if (profile.owns(card)) {
                // Already in the backpack - an ordinary replay of a level whose unlock was
                // paid long ago, or a sandbox world where everything is open. Nothing to do;
                // ``unlock`` is a set add and would report a grant the player cannot see.
                continue;
            }
            if (!SlotResolver.requiresUnlock(card)) {
                LOGGER.warn("Level {} rewards '{}' as an unlock, but that card needs no unlocking."
                        + " The reward does nothing.", id, card);
                continue;
            }
            if (profile.unlock(card) && unlocked == null) {
                unlocked = card;
            }
        }
        for (LevelRewards.Reward reward : firstClear ? rewards.firstClear() : rewards.repeat()) {
            if (reward.isCoins()) {
                bonus += reward.amount();
            } else if (reward.isResource() && reward.id().isPresent()) {
                Identifier resource = reward.id().get();
                ResourceDef def = BuiltInRegistries.RESOURCES.get(resource);
                int worth = def == null ? 0 : Math.max(0, def.defaultValue());
                bonus += worth * Math.max(0, reward.amount());
                if (rewardItem == null) {
                    // The page's frame holds one object, so the first entry is the one drawn -
                    // the same convention ``unlock`` already follows with its cards. A second
                    // entry is still paid; it just has no picture of its own.
                    rewardItem = resource;
                    rewardItemAmount = reward.amount();
                }
            }
        }
        return new Outcome(bonus, unlocked, rewardItem, rewardItemAmount);
    }

    /**
     * Coins the run picked up, summed over every denomination.
     *
     * <p>Each denomination's amount is already its worth in coins, so this is a sum and not a
     * conversion - the ladder lives in the resource definitions, once.
     */
    static int collectedCoins(LevelServer current) {
        Team plantTeam = current.team(PvzceIds.PLANT_TEAM);
        if (plantTeam == null) {
            return 0;
        }
        int total = 0;
        for (Identifier denomination : PvzceIds.COIN_DENOMINATIONS) {
            total += plantTeam.resourcesOf(denomination);
        }
        return total;
    }

    /**
     * What one surviving lawn mower is worth, read from the gold coin it is paid in.
     *
     * <p>Not a literal: the denominations and their values live in the resource definitions, and a
     * pack that revalues the gold coin revalues the mowers with it. A pack that deletes the coin
     * entirely gets zero rather than a number invented here.
     */
    static int mowerCoinValue() {
        ResourceDef coin = BuiltInRegistries.RESOURCES.get(PvzceIds.COIN_GOLD);
        return coin == null ? 0 : Math.max(0, coin.defaultValue());
    }

    /** Whether the run ended in a win for the level's own winning team. */
    public static boolean isPlantWin(LevelServer current) {
        return GameStateS2C.WON.equals(current.gameState())
                && current.winner() != null
                && current.winner().equals(current.def().winTeam());
    }
}

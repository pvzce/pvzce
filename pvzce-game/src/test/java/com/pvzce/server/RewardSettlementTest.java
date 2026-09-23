package com.pvzce.server;

import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payout rules, called directly rather than through a played-out level.
 *
 * <p>These rules have been wrong twice, both times in ways a full playthrough would have taken
 * minutes to notice: a refactor dropped "the level's rewards are for a win" and paid every defeat
 * the completion stipend, and an unlock reward gated on {@code owns} paid nothing at all (the
 * point of an unlock is the card you do <em>not</em> have). Neither has a test that can see it
 * from here except this one - the server's own path needs a run to finish, and the only hook that
 * could fake that was never called by anything, so it is gone.
 */
class RewardSettlementTest {
    private static final Identifier LEVEL = Identifier.withDefaultNamespace("test_rewards");
    /** A tool card a fresh profile does not own; what a level unlock really names. */
    private static final Identifier GLOVE = Identifier.withDefaultNamespace("glove");
    /** The starter plant: every real profile owns it, which is its own case below. */
    private static final Identifier STARTER = PvzceIds.STARTER_PLANT;
    private static final Identifier DIAMOND = Identifier.withDefaultNamespace("diamond");

    @BeforeAll
    static void loadContent() throws Exception {
        // The reward math reads resource definitions (what a diamond is worth) out of the pack.
        TestContent.loadBuiltInContentAndTags();
    }

    private static PlayerProfile emptyProfile() {
        return PlayerProfile.load(new CompoundTag());
    }

    /**
     * A {@code buff} entry hands over a level buff, once, on any clear.
     *
     * <p>Idempotent and paid on a repeat for the same reason a card unlock is: "first clear" is a
     * file's existence, not "the reward was handed over", and a world that cleared 1-9 before the
     * buff existed must still receive it.
     */
    @Test
    void aBuffRewardIsPaidOnceOnAnyClear() {
        Identifier range = PvzceIds.BUFF_MUSHROOM_RANGE;
        LevelRewards rewards = new LevelRewards(List.of(LevelRewards.Reward.buff(range)),
                List.of(LevelRewards.Reward.coins(100)), 0F, PvzceIds.COIN_SILVER, 1);
        PlayerProfile profile = emptyProfile();

        RewardSettlement.Outcome first = RewardSettlement.applyLevelRewards(LEVEL, rewards, true, profile);
        assertEquals(range, first.unlockedBuff(), "the award page is told what was handed over");
        assertTrue(profile.ownsBuff(range));

        RewardSettlement.Outcome again = RewardSettlement.applyLevelRewards(LEVEL, rewards, false, profile);
        assertNull(again.unlockedBuff(), "a second clear hands over nothing new");
        assertEquals(100, again.bonus(), "and still pays the repeat stipend");
    }

    /** A buff entry naming something no build knows is skipped rather than granted. */
    @Test
    void anUnknownBuffRewardDoesNothing() {
        Identifier missing = Identifier.withDefaultNamespace("not_a_buff");
        LevelRewards rewards = new LevelRewards(List.of(LevelRewards.Reward.buff(missing)),
                List.of(), 0F, PvzceIds.COIN_SILVER, 1);
        PlayerProfile profile = emptyProfile();
        assertNull(RewardSettlement.applyLevelRewards(LEVEL, rewards, true, profile).unlockedBuff());
        assertFalse(profile.ownsBuff(missing));
    }

    @Test
    void aFirstClearPaysTheLevelsCoinsAndARepeatPaysTheStipend() {
        LevelRewards rewards = new LevelRewards(List.of(LevelRewards.Reward.coins(500)),
                List.of(LevelRewards.Reward.coins(100)), 0F, PvzceIds.COIN_SILVER, 1);
        PlayerProfile profile = emptyProfile();

        assertEquals(500, RewardSettlement.applyLevelRewards(LEVEL, rewards, true, profile).bonus());
        assertEquals(100, RewardSettlement.applyLevelRewards(LEVEL, rewards, false, profile).bonus(),
                "a replay pays the repeat stipend, not the first-clear one");
    }

    @Test
    void anUnlockIsPaidOnARepeatTooWhileTheCardIsMissing() {
        // The card is not owned, so the unlock is worth paying even though the level was
        // cleared before: "first clear" is a file's existence, not "the reward was handed over".
        LevelRewards rewards = new LevelRewards(List.of(LevelRewards.Reward.unlock(GLOVE)),
                List.of(), 0F, PvzceIds.COIN_SILVER, 1);
        PlayerProfile profile = emptyProfile();

        RewardSettlement.Outcome paid = RewardSettlement.applyLevelRewards(LEVEL, rewards, false, profile);
        assertEquals(GLOVE, paid.unlocked(), "a missing card is granted on any clear");
        assertTrue(profile.ownsCard(GLOVE));

        RewardSettlement.Outcome again = RewardSettlement.applyLevelRewards(LEVEL, rewards, false, profile);
        assertNull(again.unlocked(), "an owned card reports no grant the player cannot see");
        assertEquals(0, again.bonus());
    }

    @Test
    void aResourceRewardIsWorthItsDefinitionsValueTimesTheAmount() {
        LevelRewards rewards = new LevelRewards(
                List.of(LevelRewards.Reward.resource(DIAMOND, 2)), List.of(), 0F,
                PvzceIds.COIN_SILVER, 1);
        PlayerProfile profile = emptyProfile();

        RewardSettlement.Outcome paid = RewardSettlement.applyLevelRewards(LEVEL, rewards, true, profile);
        assertEquals(2000, paid.bonus(), "a diamond is worth 1000 and two were paid");
        assertEquals(DIAMOND, paid.item(), "the award page draws the object rather than the money");
        assertEquals(2, paid.itemAmount());

        assertEquals(0, RewardSettlement.applyLevelRewards(LEVEL, rewards, false, profile).bonus(),
                "resources follow the first-clear/repeat split like coins do");
    }

    @Test
    void aMowerIsWorthOneGoldCoin() {
        // 50 is the shipped gold coin's default_value. Asserting the number rather than
        // re-reading the definition here is the point: a copy of the lookup would agree with
        // itself no matter what the settlement did.
        assertEquals(50, RewardSettlement.mowerCoinValue());
    }

    @Test
    void anUnlockForTheCardEveryProfileStartsWithReportsNothing() {
        // The starter plant is owned by construction, so a level that "rewards" it grants
        // nothing and must not claim otherwise - the same rule as a replay of a level whose
        // unlock was paid long ago, reached by a different road.
        LevelRewards rewards = new LevelRewards(List.of(LevelRewards.Reward.unlock(STARTER)),
                List.of(), 0F, PvzceIds.COIN_SILVER, 1);
        PlayerProfile profile = emptyProfile();

        assertTrue(profile.ownsCard(STARTER), "the fixture must own the starter card to prove anything");
        RewardSettlement.Outcome paid = RewardSettlement.applyLevelRewards(LEVEL, rewards, true, profile);
        assertNull(paid.unlocked());
    }

    @Test
    void anUnlockNamingSomethingThatIsNotACardReportsNothing() {
        // Sun is a resource, not a card: there is nothing to unlock, and the payout must not
        // claim it granted something just because the file said "unlock".
        LevelRewards rewards = new LevelRewards(
                List.of(LevelRewards.Reward.unlock(PvzceIds.SUN)), List.of(), 0F,
                PvzceIds.COIN_SILVER, 1);
        PlayerProfile profile = emptyProfile();

        RewardSettlement.Outcome paid = RewardSettlement.applyLevelRewards(LEVEL, rewards, true, profile);
        assertNull(paid.unlocked());
        assertFalse(profile.unlocked().contains(PvzceIds.SUN));
    }

    @Test
    void anUnlockNamingAnIdNoSlotKnowsReportsNothing() {
        // Data errors are the validator's job to report; the payout's job is not to invent a
        // card. A name nothing resolves is owned by nobody and unlockable by nobody.
        Identifier mystery = Identifier.withDefaultNamespace("not_a_card");
        LevelRewards rewards = new LevelRewards(List.of(LevelRewards.Reward.unlock(mystery)),
                List.of(), 0F, PvzceIds.COIN_SILVER, 1);
        PlayerProfile profile = emptyProfile();

        RewardSettlement.Outcome paid = RewardSettlement.applyLevelRewards(LEVEL, rewards, true, profile);
        assertNull(paid.unlocked());
        assertFalse(profile.unlocked().contains(mystery));
    }
}

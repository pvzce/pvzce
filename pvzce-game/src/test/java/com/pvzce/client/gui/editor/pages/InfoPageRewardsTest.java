package com.pvzce.client.gui.editor.pages;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the info page does to a {@code rewards} block it did not write.
 *
 * <p>Every entry the page has no box for is carried through a save verbatim. The failure this
 * pins is silent in both directions: a {@code resource} bounty read as "the unlock box's card"
 * would be rewritten as an unlock of a resource id the moment an author renamed the level, and
 * a bounty dropped outright would simply not be paid any more - neither leaves a trace in the
 * editor, which is where the author would look.
 */
class InfoPageRewardsTest {
    private static JsonObject rewards(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void aResourceBountyIsNotReadAsAnUnlockAndIsKept() {
        InfoPage.RewardLists read = InfoPage.readRewardLists(rewards("""
                {
                  "first_clear": [ { "type": "resource", "id": "pvzce:diamond", "amount": 1 } ],
                  "repeat": [ { "type": "coins", "amount": 200 } ]
                }
                """));

        assertEquals("", read.unlock(), "a diamond is not the card the unlock box edits");
        assertEquals(200, read.repeatCoins(), "the stipend is still summed from the coins entry");
        assertEquals(List.of(LevelRewards.Reward.resource(Identifier.of("pvzce", "diamond"), 1)),
                read.keptFirstClear(), "and the bounty is kept exactly as it was read");
        assertEquals(List.of(), read.keptRepeat());
    }

    @Test
    void anEntryWithNoBoxAnywhereIsKeptInTheListItCameFrom() {
        InfoPage.RewardLists read = InfoPage.readRewardLists(rewards("""
                {
                  "first_clear": [ { "type": "unlock", "id": "pvzce:sunflower" } ],
                  "repeat": [
                    { "type": "coins", "amount": 100 },
                    { "type": "resource", "id": "pvzce:diamond", "amount": 2 }
                  ]
                }
                """));

        assertEquals("pvzce:sunflower", read.unlock());
        assertEquals(100, read.repeatCoins(), "only the coins entry is folded into the stipend");
        assertEquals(List.of(LevelRewards.Reward.resource(Identifier.of("pvzce", "diamond"), 2)),
                read.keptRepeat(), "the replay bounty rides along untouched");
    }

    @Test
    void aSecondUnlockIsStillFoldedIntoTheFirst() {
        InfoPage.RewardLists read = InfoPage.readRewardLists(rewards("""
                {
                  "first_clear": [
                    { "type": "unlock", "id": "pvzce:sunflower" },
                    { "type": "unlock", "id": "pvzce:wall_nut" }
                  ]
                }
                """));

        assertEquals("pvzce:sunflower", read.unlock(), "the box edits the first one");
        assertEquals(List.of(), read.keptFirstClear(),
                "and the second is normalised away rather than kept: the level only draws one");
    }

    @Test
    void anEmptyBlockReadsAsTheDefaults() {
        InfoPage.RewardLists read = InfoPage.readRewardLists(new JsonObject());
        assertEquals("", read.unlock());
        assertEquals(LevelRewards.DEFAULT_REPEAT_COINS, read.repeatCoins(),
                "a block that never mentions repeat shows the default stipend");
        assertEquals(List.of(), read.keptFirstClear());
        assertEquals(List.of(), read.keptRepeat());
    }

    @Test
    void anExplicitEmptyRepeatListStaysEmpty() {
        // `"repeat": []` is an author saying "this level pays no stipend". Reading it as the
        // default would put 100 coins back into the box, and the next save would pay them.
        InfoPage.RewardLists read = InfoPage.readRewardLists(rewards("{ \"repeat\": [] }"));
        assertEquals(0, read.repeatCoins());
    }
}

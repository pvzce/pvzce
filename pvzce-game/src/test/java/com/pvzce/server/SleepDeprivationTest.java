package com.pvzce.server;

import com.pvzce.api.content.LevelCategoryDef;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.Slot;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.common.util.LevelKey;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.LevelValidator;
import com.pvzce.testutil.ServerHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sleep Deprivation, the first mini-game: a day lawn where every mushroom is asleep, five
 * cards that fill the bar, and a card bar that recharges three times as fast.
 *
 * <p>What is pinned here is what the level was asked to be, and the two mechanisms it is the
 * first user of - the level's {@code pvzce:seed_cooldown_multiplier} and a {@code resource}
 * reward. The wave table is balance data and belongs to the same tuning pass as every other
 * level's.
 */
class SleepDeprivationTest {

    /** A fresh directory per test; JUnit deletes it, and prints it when a test fails. */
    @TempDir
    Path gameDir;
    private static final Identifier LEVEL = PvzceIds.id("yard/minigame/sleep_deprivation");
    private static final String WORLD = "minigameworld";
    private static com.pvzce.common.resource.PvzceResourceManager resources;

    @BeforeAll
    static void load() throws Exception {
        resources = TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef def() {
        LevelDef def = BuiltInRegistries.LEVELS.get(LEVEL);
        assertNotNull(def, "the shipped mini-game must load");
        return def;
    }

    /**
     * The level as specified: a five-lane day lawn, the five cards the mini-game is played
     * with, nothing left to choose, and no mushroom that could work without a coffee bean.
     */
    @Test
    void theDeckIsFixedAndTheLawnIsAnOrdinaryDaytimeOne() {
        LevelDef def = def();
        assertEquals(9, def.width());
        assertEquals(5, def.height());
        assertEquals(List.of("pvzce:sun", "pvzce:sun_shroom", "pvzce:puff_shroom",
                        "pvzce:coffee_bean", "pvzce:shovel"),
                def.slots().stream().map(Identifier::toString).toList(),
                "the fixed cards, in the order the level deals them");

        // The five cards fill all five slots, so there is nothing to pick: the level opens
        // through the preview cutscene (which is where its dialogue plays) and deals its own
        // deck, exactly like 1-1.
        assertEquals(5, def.effectiveMaxSeedSlots(PvzceConstants.DEFAULT_SEED_SLOTS));
        assertTrue(SeedOptions.hasNothingToChoose(
                        SeedOptions.forLevel(def), def.effectiveMaxSeedSlots(PvzceConstants.DEFAULT_SEED_SLOTS),
                        SeedOptions.lockedSlotIds(def)),
                "a fixed deck that fills the bar must not open the seed chooser");
        assertTrue(def.unlockResources().getOrDefault(PvzceIds.SUN, false),
                "the sun card is in the bar, so the level has to unlock the resource it collects");

        // Daylight, and no clock that could ever leave it: the whole point of the level is
        // that the mushrooms sleep.
        LevelServer level = new LevelServer(def);
        assertFalse(level.isNight(), "the lawn is in daylight on the first tick");
        level.setDayTicks(24 * 60 * 60);
        assertFalse(level.isNight(), "and stays there for the rest of any run it can have");
        assertEquals(level.height(), level.readyMowerCount(),
                "an ordinary lawn: one mower per row, so a leak is not an instant loss");

        // The sun-shroom is the only income, and it is a mushroom: the level would not work
        // at all if the two mushrooms were not nocturnal.
        assertNotNull(BuiltInRegistries.PLANTS.get(PvzceIds.id("sun_shroom")));
        assertNotNull(BuiltInRegistries.PLANTS.get(PvzceIds.id("puff_shroom")));
    }

    /** The page it lives on, the medal it earns, and the level that opens it. */
    @Test
    void itIsAMiniGameOnItsOwnPageAndOpensAfterTheNightArea() {
        LevelDef def = def();
        assertEquals(List.of("pvzce:yard/adventure/2_1"),
                def.unlock().requires().stream()
                        .filter(LevelUnlock.Requirement::isLevel)
                        .map(requirement -> requirement.id().orElseThrow().toString())
                        .toList(),
                "the mushrooms it is built around are handed out by the night area");

        LevelCategoryDef category = BuiltInRegistries.LEVEL_CATEGORIES.get(PvzceIds.id("minigame"));
        assertNotNull(category, "the mini-game page has to exist, or the level falls into 未分类");
        assertTrue(category.trophy(), "beating a mini-game is worth the trophy on the row");
        LevelCategoryDef adventure = BuiltInRegistries.LEVEL_CATEGORIES.get(PvzceIds.id("adventure"));
        assertNotNull(adventure);
        assertFalse(adventure.trophy(), "an adventure level is progress, not a medal");

        assertEquals("pvzce:music/loon_boon",
                def.music().cues().get(0).event().orElseThrow().toString(),
                "the mini-game theme, the one Wall-nut Bowling already plays");

        // The payout the level was specified with: a medal on the row, and a diamond for the
        // first clear. The type is a string discriminant, so a typo would decode and pay
        // nothing - which is why the entry itself is pinned here and not only its effect.
        assertEquals(1, def.rewards().firstClear().size(), "one first-clear reward");
        com.pvzce.api.content.LevelRewards.Reward reward = def.rewards().firstClear().get(0);
        assertTrue(reward.isResource(), "a diamond is a resource, not a sum of coins");
        assertEquals("pvzce:diamond", reward.id().orElseThrow().toString());
        assertEquals(1, reward.amount());
    }

    /**
     * The opening conversation: the purple-white one, on the left, eight lines, with the
     * startled beat that shakes on nothing but "！！".
     *
     * <p>Pinned because it is content the level was specified with, and because two of its
     * failure modes are silent: a portrait that is not on disk draws nothing over an empty
     * bubble, and a misspelt {@code side} decodes into {@code UNKNOWN} (drawn as the left
     * side) rather than failing the level. The asset check runs against the same resource
     * manager the game loads, so a portrait that never got copied over is caught here.
     */
    @Test
    void theOpeningConversationIsTheScriptTheLevelWasWrittenWith() {
        assertEquals(List.of("sleep", "sleepy", "sleepy", "startled", "scared", "confused",
                        "coffee", "smile"),
                def().dialogue().lines().stream()
                        .map(com.pvzce.api.content.DialogueLine::portrait)
                        .toList());
        for (com.pvzce.api.content.DialogueLine line : def().dialogue().lines()) {
            assertEquals("pvzce:purwhite", line.character().toString());
            assertEquals(com.pvzce.api.content.DialogueLine.Side.LEFT, line.side(),
                    "the whole conversation stands on the left: " + line.portrait());
        }
        com.pvzce.api.content.DialogueLine startled = def().dialogue().lines().get(3);
        assertEquals("！！", startled.text());
        assertEquals(com.pvzce.api.content.DialogueAnimation.TYPE_SHAKE,
                startled.animation().type(), "the shout is what the shake is for");

        assertTrue(LevelValidator.validateDialogue(def()).isEmpty(),
                "no unknown character, side or animation in this script");
        assertTrue(LevelValidator.validateAllDialogues(resources).stream()
                        .noneMatch(problem -> problem.contains(LEVEL.toString())),
                "every portrait it names has to be on disk, coffee bean included: "
                        + LevelValidator.validateAllDialogues(resources));
    }

    /**
     * The level's own rule: every card recharges at a third of its own cooldown.
     *
     * <p>Three numbers have to agree - what the card is worth, what the level charges, and
     * what the bar is told to draw the recharge against - and the card bar reads the third as
     * the divisor of the first, so a level that scaled one and not the other would show a
     * sweep that finishes before or after the card actually comes back.
     */
    @Test
    void everyCardRechargesThreeTimesAsFast() {
        LevelServer level = new LevelServer(def());
        Slot puff = slot(level, "pvzce:puff_shroom");
        assertNotNull(puff);
        assertEquals(300, puff.cooldownTicks(), "the card's own cooldown is the plant's");
        assertEquals(100, level.effectiveCooldownTicks(puff), "a third of it in this level");
        assertEquals(100, level.toSlotInfo(puff).cooldownTotal(),
                "and the bar is told the same divisor it is charged");

        // Spent through the real placement path, so what is pinned is the charge and not the
        // arithmetic: this is the number the player waits out.
        CapturingBridge bridge = new CapturingBridge();
        assertTrue(level.placePlant(bridge, puff.index(), 4, 2), "a free mushroom on open grass");
        assertEquals(100, puff.cooldownLeft(), "the recharge the level charges for it");

        // An ordinary level is untouched: the rule is the level's, not the card's.
        LevelServer ordinary = new LevelServer(
                BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1")));
        Slot pea = slot(ordinary, "pvzce:pea_shooter");
        assertNotNull(pea);
        assertEquals(300, ordinary.effectiveCooldownTicks(pea),
                "a level that says nothing about cooldowns charges the card's own number");
    }

    /**
     * A first clear that pays a {@code resource} hands over the object and banks what it is
     * worth: one diamond, a thousand coins, plus whatever the untouched mowers were worth.
     *
     * <p>Played end to end on a one-zombie level of its own, because the three halves that
     * have to agree are in three different places - the reward entry decodes, the wallet is
     * credited the resource's own {@code default_value}, and the packet says which object to
     * draw. A repeat clear pays the stipend instead, because a resource entry follows the
     * same first-clear/repeat split coins do.
     */
    @Test
    void aResourceRewardBanksItsWorthAndTravelsAsAnItem() throws Exception {
        try (ServerHarness harness = ServerHarness.create(gameDir)) {
            writeLevel(gameDir, "diamond_reward_test", """
                    {
                      "id": "pvzce:diamond_reward_test",
                      "name": "Diamond Reward Test",
                      "width": 3,
                      "height": 1,
                      "scene": { "pvzce:grass": [ "0,0","1,0","2,0" ] },
                      "rules": { "pvzce:day_length": 0, "pvzce:night_length": -1 },
                      "waves": [ { "type": "final", "delay": 0, "warning_ticks": 0,
                                   "entries": [ { "id": "pvzce:basic_zombie", "count": 1 } ] } ],
                      "slots": [ "pvzce:pea_shooter", "pvzce:sun" ],
                      "initial_sun": 50,
                      "rewards": {
                        "first_clear": [ { "type": "resource", "id": "pvzce:diamond", "amount": 1 } ],
                        "repeat": [ { "type": "coins", "amount": 100 } ],
                        "coin_drop_chance": 0.0,
                        "coin_drop": "pvzce:coin_silver"
                      }
                    }
                    """);
            reloadAndAwaitLevelList(harness);

            harness.send(new PlayLevelC2S("pvzce:diamond_reward_test", WORLD, true, List.of()));
            harness.awaitPacket(LevelInitS2C.class, 5_000);
            harness.winByClearingTheField();

            LevelRewardS2C first = harness.awaitPacket(LevelRewardS2C.class, 8_000);
            assertEquals("pvzce:diamond", first.rewardItem(),
                    "the packet has to name the object, or the award page can only draw money");
            assertEquals(1, first.rewardItemAmount());
            assertFalse(first.hasUnlock(), "a resource reward is not a card");
            // One row, so one mower, and the run never needed it: 1000 for the diamond plus
            // the mower's gold coin.
            assertEquals(1050, first.bonusCoins(),
                    "the diamond is worth its denomination, and the mower is paid on top");
            assertEquals(1050, first.totalCoins());

            Path profileFile = gameDir.resolve("saves/" + WORLD + "/profile.dat");
            harness.waitForFile(profileFile, 5_000);
            assertEquals(1050, PlayerProfile.load(NbtIo.readCompressed(profileFile)).coins(),
                    "the wallet is written before the packet is sent");

            // Replay: the stipend, and nothing to draw in the frame.
            harness.send(new LeaveLevelC2S());
            harness.clear();
            harness.send(new PlayLevelC2S("pvzce:diamond_reward_test", WORLD, true, List.of()));
            harness.awaitPacket(LevelInitS2C.class, 5_000);
            harness.clear();
            harness.winByClearingTheField();

            LevelRewardS2C repeat = harness.awaitPacket(LevelRewardS2C.class, 8_000);
            assertEquals("", repeat.rewardItem(), "a replay pays coins, not a second diamond");
            assertEquals(150, repeat.bonusCoins(), "the repeat stipend plus the leftover mower");
            assertEquals(1200, repeat.totalCoins());
        }
    }

    /**
     * The row's trophy reads the new {@code cleared} field, which is its own fact rather than
     * the status label: a level that was beaten and then started over reads 进行中 and keeps
     * its medal, exactly like the entry decision keeps loading its save.
     */
    @Test
    void theLevelListCarriesWhetherTheLevelWasEverBeaten() throws Exception {
        try (ServerHarness harness = ServerHarness.create(gameDir)) {
            assertFalse(row(harness, WORLD).cleared(), "nothing has been beaten in a fresh world");

            writeCompletionMarker(gameDir, WORLD);
            // The marker is read from disk on the next list, which is also how a reopen after
            // a win sees it. The recorder is cleared first: the first response is still in it,
            // and `awaitPacket` would hand back the list that was asked for before the marker.
            harness.clear();
            LevelListS2C.LevelInfo row = row(harness, WORLD);
            assertTrue(row.cleared(), "a completion marker is what the trophy reads");
            assertEquals("pvzce:minigame", row.category(), "and it is the mini-game page");
        }
    }

    private static LevelListS2C.LevelInfo row(ServerHarness harness, String world) throws Exception {
        return find(harness.levelList(world), LEVEL);
    }

    /** Writes a data pack level; the server only sees it after a reload. */
    private static void writeLevel(Path gameDir, String name, String json) throws Exception {
        Path pack = gameDir.resolve("datapacks/" + name);
        Path levelFile = pack.resolve("data/pvzce/levels/" + name + ".json");
        Files.createDirectories(levelFile.getParent());
        Files.writeString(pack.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":1,\"description\":\"" + name + "\"}}");
        Files.writeString(levelFile, json);
    }

    /** Reloads content and waits for the pushed level list, so the new level exists. */
    private static void reloadAndAwaitLevelList(ServerHarness harness) throws Exception {
        harness.clear();
        harness.send(new CommandC2S("/reload"));
        harness.awaitPacket(LevelListS2C.class, 8_000);
        harness.clear();
    }

    private static LevelListS2C.LevelInfo find(LevelListS2C list, Identifier id) {
        return list.levels().stream()
                .filter(level -> level.id().equals(id.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(id + " is not in the level list"));
    }

    /** The marker file a finished run writes, under the key the server derives from the id. */
    private static void writeCompletionMarker(Path gameDir, String world) throws Exception {
        Path dir = gameDir.resolve("saves").resolve(world).resolve("level_status");
        Files.createDirectories(dir);
        CompoundTag status = new CompoundTag();
        status.putString("GameState", LevelListS2C.LevelInfo.COMPLETED);
        status.putString("LevelId", LEVEL.toString());
        NbtIo.writeCompressed(status, dir.resolve(LevelKey.of(LEVEL) + ".dat"));
    }

    private static Slot slot(LevelServer level, String contentId) {
        for (com.pvzce.common.network.packet.SlotInfo info : level.slotInfos()) {
            if (info.defId().equals(contentId)) {
                return level.plantPlayer().slot(info.index());
            }
        }
        return null;
    }

    /** A bridge that only records; the placement path needs somewhere to send its messages. */
    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }
}

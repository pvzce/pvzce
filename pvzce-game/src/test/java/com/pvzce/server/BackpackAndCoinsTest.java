package com.pvzce.server;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.network.packet.ProfileS2C;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.ServerHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backpack and the coin wallet, from the wire down to the file.
 *
 * <p>Three things have to hold together for the feature to mean anything, and each
 * gets its own group here: what a fresh world owns, what that does to the cards a
 * level offers, and what happens to coins when a run ends.
 */
class BackpackAndCoinsTest {

    /** A fresh directory per test; JUnit deletes it, and prints it when a test fails. */
    @TempDir
    Path gameDir;
    private static final String FIRST_LEVEL = "pvzce:yard/adventure/1_1";
    private static final String WORLD = "backpackworld";

    // ------------------------------------------------------------------
    // The profile itself: defaults, round trip, gating rules
    // ------------------------------------------------------------------

    @Test
    void aFreshProfileOwnsTheStarterPlantAndToolAndNothingElse() {
        PlayerProfile profile = PlayerProfile.starter();
        assertTrue(profile.ownsCard(PvzceIds.STARTER_PLANT), "the first level's only plant must be owned");
        assertTrue(profile.ownsCard(PvzceIds.STARTER_TOOL), "the shovel exists so a mistake can be undone");
        assertFalse(profile.ownsCard(id("sunflower")), "sunflower is earned by finishing 1-1");
        assertFalse(profile.ownsCard(id("wall_nut")));
        assertEquals(0, profile.coins());
    }

    @Test
    void resourceCardsAreNeverGated() {
        PlayerProfile profile = PlayerProfile.starter();
        // A level without its sun card cannot be played at all, so the backpack has
        // no say over resources - and an unknown id is not "locked" either.
        assertTrue(profile.ownsCard(PvzceIds.SUN));
        assertFalse(SlotResolver.requiresUnlock(PvzceIds.SUN));
        assertTrue(SlotResolver.requiresUnlock(id("wall_nut")));
    }

    @Test
    void aProfileSurvivesTheNbtRoundTrip() {
        PlayerProfile profile = PlayerProfile.starter();
        profile.unlock(id("sunflower"));
        profile.grantCoins(275);

        PlayerProfile loaded = PlayerProfile.load(profile.save());

        assertEquals(275, loaded.coins());
        assertTrue(loaded.ownsCard(id("sunflower")));
        assertTrue(loaded.ownsCard(PvzceIds.STARTER_PLANT));
        assertFalse(loaded.ownsCard(id("chomper")));
        assertFalse(loaded.unlocksEverything());
    }

    @Test
    void boughtLevelsSurviveTheNbtRoundTripWithoutBecomingCards() {
        PlayerProfile profile = PlayerProfile.starter();
        Identifier bought = id("yard/adventure/1_2");
        profile.unlockLevel(bought);
        profile.grantCoins(90);

        PlayerProfile loaded = PlayerProfile.load(profile.save());

        assertTrue(loaded.ownsLevel(bought));
        assertEquals(List.of(bought.toString()), loaded.unlockedLevelIds());
        // The sets are separate: buying a level names nothing in the backpack, and the
        // only card the profile still has is the starter plant it began with.
        assertEquals(Set.of(PvzceIds.STARTER_PLANT, PvzceIds.STARTER_TOOL), loaded.unlocked());
        assertFalse(loaded.ownsLevel(id("yard/adventure/1_3")), "only the bought level is owned");
        assertEquals(90, loaded.coins());
    }

    @Test
    void aSandboxProfileOwnsCardsItHasNeverHeardOf() {
        PlayerProfile profile = PlayerProfile.unlockEverything();
        // The flag is the point: content added later is unlocked too, instead of the
        // world silently missing it because the list was frozen when it was created.
        assertTrue(profile.ownsCard(id("some_future_plant")));
        assertTrue(profile.ownsCard(id("wall_nut")));
        assertTrue(PlayerProfile.load(profile.save()).unlocksEverything());
    }

    @Test
    void cardSlotsStartAtTheDefaultAndUpgradeToACeiling() {
        PlayerProfile profile = PlayerProfile.starter();
        assertEquals(PvzceConstants.DEFAULT_SEED_SLOTS, profile.seedSlots(),
                "a fresh backpack holds eight cards");

        assertEquals(2, profile.addSeedSlots(2), "the shop hook reports what it granted");
        assertEquals(PvzceConstants.DEFAULT_SEED_SLOTS + 2, profile.seedSlots());
        // The ceiling is what stops a price list from selling a bar the editor could not
        // express; the grant reports the shortfall rather than silently exceeding it.
        assertEquals(PvzceConstants.MAX_SEED_SLOTS - PvzceConstants.DEFAULT_SEED_SLOTS - 2,
                profile.addSeedSlots(99));
        assertEquals(PvzceConstants.MAX_SEED_SLOTS, profile.seedSlots());
        assertEquals(0, profile.addSeedSlots(1), "and there is nothing left to grant");

        profile.setSeedSlots(0);
        assertEquals(1, profile.seedSlots(), "a bar always holds at least one card");
    }

    @Test
    void cardSlotsSurviveTheNbtRoundTrip() {
        PlayerProfile profile = PlayerProfile.starter();
        profile.addSeedSlots(3);
        assertEquals(PvzceConstants.DEFAULT_SEED_SLOTS + 3, PlayerProfile.load(profile.save()).seedSlots());

        // A record written before card slots existed has no key, and that has to mean the
        // default - the same thing an unwritten max_seed_slots in a level means.
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("Coins", 10);
        legacy.put("Unlocked", new ListTag());
        assertEquals(PvzceConstants.DEFAULT_SEED_SLOTS, PlayerProfile.load(legacy).seedSlots());
    }

    @Test
    void coinsHaveNoCeiling() {
        PlayerProfile profile = PlayerProfile.starter();
        assertEquals(40, profile.grantCoins(40));
        // The wallet used to stop at 9990, which meant a long-running world silently ate
        // everything past it. What the player collected is what the player keeps.
        assertEquals(50_000, profile.grantCoins(50_000));
        assertEquals(50_040, profile.coins());
        assertEquals(1_000_000, profile.grantCoins(1_000_000));
        assertEquals(1_050_040, profile.coins());
        assertEquals(1_050_040, PlayerProfile.load(profile.save()).coins(), "and it round-trips");
    }

    @Test
    void aMissingOrEmptyRecordFallsBackToTheStarterProfile() {
        assertTrue(PlayerProfile.load(new CompoundTag()).ownsCard(PvzceIds.STARTER_PLANT),
                "a world that predates profiles must still be playable");
        assertTrue(PlayerProfile.load(null).ownsCard(PvzceIds.STARTER_TOOL));
    }

    @Test
    void theFourDenominationsAreWorthTheOriginalsNumbers() {
        // The worth lives in the resource definition, once; the HUD and the wallet only
        // sum, so a drift here would silently change what a coin is worth.
        assertEquals(10, BuiltInRegistries.RESOURCES.get(PvzceIds.COIN_SILVER).defaultValue());
        assertEquals(50, BuiltInRegistries.RESOURCES.get(PvzceIds.COIN_GOLD).defaultValue());
        assertEquals(1000, BuiltInRegistries.RESOURCES.get(PvzceIds.DIAMOND).defaultValue());
        assertEquals(250, BuiltInRegistries.RESOURCES.get(PvzceIds.MONEY_BAG).defaultValue());
        for (Identifier denomination : PvzceIds.COIN_DENOMINATIONS) {
            assertTrue(BuiltInRegistries.RESOURCES.get(denomination).collectibleWithoutCard(),
                    denomination + " is currency: picking it up must not need a card slot");
            assertTrue(PvzceIds.isCoin(denomination));
        }
        assertTrue(PvzceIds.isCoin("pvzce:diamond"));
        assertFalse(PvzceIds.isCoin(PvzceIds.SUN));
    }

    /**
     * Currency rings; the sun chimes.
     *
     * <p>Collecting a coin used to play the sun's collect sound, so a bowling combo - one coin
     * per ricochet - sounded like a shower of sun. It is the same per-resource idea as the
     * sparkle, one field over.
     */
    @Test
    void currencyRingsAndTheSunChimes() {
        assertEquals(PvzceSounds.UI_COLLECT,
                BuiltInRegistries.RESOURCES.get(PvzceIds.SUN).pickupSound(),
                "the sun keeps the collect sound it always had");
        assertEquals(PvzceSounds.UI_COIN,
                BuiltInRegistries.RESOURCES.get(PvzceIds.COIN_SILVER).pickupSound());
        assertEquals(PvzceSounds.UI_COIN,
                BuiltInRegistries.RESOURCES.get(PvzceIds.COIN_GOLD).pickupSound());
        assertNotEquals(PvzceSounds.UI_COLLECT,
                BuiltInRegistries.RESOURCES.get(PvzceIds.COIN_SILVER).pickupSound(),
                "a coin must not sound like a sun");
    }

    // ------------------------------------------------------------------
    // What the backpack does to a level's cards
    // ------------------------------------------------------------------

    @Test
    void theSeedPoolHidesUnownedCardsButKeepsTheLevelsOwn() throws Exception {
        try (ServerHarness harness = ServerHarness.create(gameDir)) {
            harness.send(new RequestLevelListC2S(WORLD));
            LevelListS2C list = harness.awaitPacket(LevelListS2C.class, 5_000);
            var info = list.levels().stream().filter(l -> l.id().equals(FIRST_LEVEL))
                    .findFirst().orElseThrow();

            List<String> pool = info.seedPool().stream().map(SeedOption::slotId).toList();
            assertTrue(pool.contains("pvzce:pea_shooter"), "the level's own card is always offered");
            assertTrue(pool.contains("pvzce:sun"), "the level pins the sun card, and it collects sun");
            assertFalse(pool.contains("pvzce:sunflower"),
                    "a card the player has not unlocked must not be in the chooser pool");
            assertFalse(pool.contains("pvzce:wall_nut"));

            harness.clear();
            harness.send(new PlayLevelC2S(FIRST_LEVEL, WORLD, true, List.of()));
            LevelInitS2C init = harness.awaitPacket(LevelInitS2C.class, 5_000);
            List<String> running = init.payload().seedPool().stream().map(SeedOption::slotId).toList();
            assertEquals(pool, running, "the level list and the running level must describe one pool");
        }
    }

    @Test
    void theFirstLevelFixesPeashooterAndSunAndNothingElse() throws Exception {
        try (ServerHarness harness = ServerHarness.create(gameDir)) {
            // The client asks for cards it does not own; the level's own two win out.
            harness.send(new PlayLevelC2S(FIRST_LEVEL, WORLD, true,
                    List.of("pvzce:wall_nut", "pvzce:sunflower")));
            LevelInitS2C init = harness.awaitPacket(LevelInitS2C.class, 5_000);

            assertEquals(List.of("pvzce:pea_shooter", "pvzce:sun"),
                    init.slots().stream().map(SlotInfo::defId).toList(),
                    "1-1 is the original's level: one plant card, the sun card, no choice");
            assertEquals(2, init.maxSeedSlots());
            assertEquals(1, init.payload().height(), "1-1 has a single lane");
        }
    }

    @Test
    void aSandboxWorldOffersEveryCard() throws Exception {
        try (ServerHarness harness = ServerHarness.create(gameDir)) {
            harness.send(new CreateWorldC2S(WORLD, true));
            harness.send(new RequestLevelListC2S(WORLD));
            LevelListS2C list = harness.awaitPacket(LevelListS2C.class, 5_000);
            var info = list.levels().stream().filter(l -> l.id().equals(FIRST_LEVEL))
                    .findFirst().orElseThrow();
            assertTrue(info.seedPool().stream().anyMatch(option -> option.slotId().equals("pvzce:sunflower")),
                    "a sandbox world owns every card");
        }
    }

    // ------------------------------------------------------------------
    // Coins: earned in a run, banked when it ends
    // ------------------------------------------------------------------

    @Test
    void aFirstClearUnlocksTheRewardAndAReplayPaysTheStipend() throws Exception {
        try (ServerHarness harness = ServerHarness.create(gameDir)) {
            // One zombie, no delay: the level can be won inside a test. Its rewards
            // block is 1-1's, which ContentFoundationTest pins separately.
            writeLevel(harness.gameDir(), "reward_test", """
                    {
                      "id": "pvzce:reward_test",
                      "name": "Reward Test",
                      "width": 3,
                      "height": 1,
                      "scene": { "pvzce:grass": [ "0,0","1,0","2,0" ] },
                      "rules": { "pvzce:day_length": 0, "pvzce:night_length": -1 },
                      "waves": [ { "type": "final", "delay": 0, "warning_ticks": 0,
                                   "entries": [ { "id": "pvzce:basic_zombie", "count": 1 } ] } ],
                      "slots": [ "pvzce:pea_shooter", "pvzce:sun" ],
                      "initial_sun": 50,
                      "rewards": {
                        "first_clear": [ { "type": "unlock", "id": "pvzce:sunflower" } ],
                        "repeat": [ { "type": "coins", "amount": 100 } ],
                        "coin_drop_chance": 0.0,
                        "coin_drop": "pvzce:coin_silver"
                      }
                    }
                    """);
            reloadAndAwaitLevelList(harness);

            // First clear: the level's first_clear reward is the sunflower.
            harness.send(new PlayLevelC2S("pvzce:reward_test", WORLD, true, List.of()));
            harness.awaitPacket(LevelInitS2C.class, 5_000);
            harness.winByClearingTheField();

            LevelRewardS2C first = harness.awaitPacket(LevelRewardS2C.class, 8_000);
            assertEquals("pvzce:sunflower", first.unlockedCard(),
                    "a first clear is the only thing that grants a card");
            // The reward lands where the fight ended, not in the middle of the lawn: the
            // client drops the seed packet on the last zombie's cell.
            assertTrue(Float.isFinite(first.dropX()) && Float.isFinite(first.dropY()),
                    "the payout must carry the spot the last zombie died on");
            assertTrue(first.dropX() >= 0F && first.dropX() <= 9F
                            && first.dropY() >= 0F && first.dropY() <= 3F,
                    "the spot must be on the board, got " + first.dropX() + "," + first.dropY());
            assertEquals(0, first.collectedCoins(), "nothing dropped a coin in this run");
            // The level is one row, so it has one mower, and the run never needed it:
            // an unused mower pays a gold coin (see PvzceServer.mowerCoinValue), which is
            // part of the bonus. What a *first* clear must not pay is the repeat stipend.
            assertEquals(1, first.mowers(), "one row, one mower");
            assertEquals(50, first.mowerCoins(), "an untouched mower is worth a gold coin");
            assertEquals(first.mowerCoins(), first.bonusCoins(),
                    "a first clear pays the unlock and the leftover mowers, not the stipend");
            assertTrue(harness.awaitPacket(ProfileS2C.class, 5_000).unlocked().contains("pvzce:sunflower"));

            Path profileFile = gameDir.resolve("saves/" + WORLD + "/profile.dat");
            harness.waitForFile(profileFile, 5_000);
            assertTrue(PlayerProfile.load(NbtIo.readCompressed(profileFile)).ownsCard(id("sunflower")),
                    "the unlock must be on disk, not only in memory");

            // Replay: the card is already owned, so the level pays its coin stipend.
            harness.send(new LeaveLevelC2S());
            harness.clear();
            harness.send(new PlayLevelC2S("pvzce:reward_test", WORLD, true, List.of()));
            harness.awaitPacket(LevelInitS2C.class, 5_000);
            harness.clear();
            harness.winByClearingTheField();

            LevelRewardS2C repeat = harness.awaitPacket(LevelRewardS2C.class, 8_000);
            assertEquals("", repeat.unlockedCard(), "there is nothing left to unlock");
            assertEquals(150, repeat.bonusCoins(),
                    "a replay pays the level's repeat stipend plus the leftover mower");
            assertEquals(200, repeat.totalCoins(), "the wallet after both runs");
            // 50 from the first clear's mower plus 150 from the replay.
            assertEquals(200, PlayerProfile.load(NbtIo.readCompressed(profileFile)).coins());
        }
    }

    @Test
    void coinsCollectedInARunAreBankedEvenWhenTheRunIsLost() throws Exception {
        try (ServerHarness harness = ServerHarness.create(gameDir)) {
            harness.send(new PlayLevelC2S(FIRST_LEVEL, WORLD, true, List.of()));
            harness.awaitPacket(LevelInitS2C.class, 5_000);
            // Stands in for coins the run picked up: the wallet rule is what is under
            // test here, and the drop roll has its own test below.
            // A gold coin and a silver one: the wallet has to add what each is worth,
            // and the bank's total is a sum over the denominations.
            harness.send(new CommandC2S("/resource give pvzce:plant_team pvzce:coin_gold 50"));
            harness.waitForCondition(() -> team(harness).resourcesOf(PvzceIds.COIN_GOLD) == 50, 5_000);
            harness.send(new CommandC2S("/resource give pvzce:plant_team pvzce:coin_silver 10"));
            harness.waitForCondition(() -> team(harness).resourcesOf(PvzceIds.COIN_SILVER) == 10, 5_000);
            harness.clear();

            endLevel(harness);
            LevelRewardS2C reward = harness.awaitPacket(LevelRewardS2C.class, 8_000);
            assertEquals(60, reward.collectedCoins(),
                    "a finished run keeps what it picked up, summed over the denominations");
            assertEquals(0, reward.bonusCoins(),
                    "losing pays no completion stipend - only a win does");
            assertEquals(60, reward.totalCoins());

            PlayerProfile stored = PlayerProfile.load(
                    NbtIo.readCompressed(gameDir.resolve("saves/" + WORLD + "/profile.dat")));
            assertEquals(60, stored.coins(), "the wallet is written on a loss too");
        }
    }

    @Test
    void aDyingZombieLeavesTheLevelsConfiguredCoinDrop() throws Exception {
        try (ServerHarness harness = ServerHarness.create(gameDir)) {
            // Certain drops, so the test does not gamble on the default 25% roll.
            writeLevel(harness.gameDir(), "drop_test", """
                    {
                      "id": "pvzce:drop_test",
                      "name": "Drop Test",
                      "width": 9,
                      "height": 5,
                      "scene": { "pvzce:grass": [ "0,0","1,0","2,0" ] },
                      "rules": { "pvzce:day_length": 0, "pvzce:night_length": -1 },
                      "waves": [],
                      "slots": [ "pvzce:pea_shooter", "pvzce:sun" ],
                      "initial_sun": 150,
                      "rewards": { "coin_drop_chance": 1.0, "coin_drop": "pvzce:coin_gold", "coin_drop_amount": 3 }
                    }
                    """);
            reloadAndAwaitLevelList(harness);
            harness.send(new PlayLevelC2S("pvzce:drop_test", WORLD, true, List.of()));
            harness.awaitPacket(LevelInitS2C.class, 5_000);

            harness.send(new CommandC2S("/spawn zombie pvzce:basic_zombie 5 2"));
            // Waited for as a packet, not by polling the level: the simulation is single-threaded
            // and `entities` is a plain ArrayList, so a test thread reading it can keep seeing the
            // old state - an intermittent five-second timeout that looks like the server never
            // spawning the zombie. The harness's packet list is drained on this thread, which makes
            // it the one channel that is safe to observe from here.
            harness.waitFor(p -> p instanceof EntitySpawnS2C spawn
                    && "zombie".equals(spawn.entityKind()), 5_000);

            // Kills it directly: this is about what a death spawns, not about
            // shooting one to death, which CombatSystemsTest already covers.
            harness.server().level().damageArea(ZombieEntity.damageType(PvzceIds.DAMAGE_ASH),
                    5.5F, 2.5F, 2F, 5_000, null);
            harness.waitFor(p -> p instanceof EntitySpawnS2C spawn
                    && "resource".equals(spawn.entityKind()), 5_000);
            assertEquals(150, coinDrops(harness),
                    "three gold coins, and a drop's amount is the denomination's worth");
        }
    }

    @Test
    void aLevelWhoseDropChanceIsZeroNeverSpawnsCoins() throws Exception {
        try (ServerHarness harness = ServerHarness.create(gameDir)) {
            writeLevel(harness.gameDir(), "nodrop_test", """
                    {
                      "id": "pvzce:nodrop_test",
                      "name": "No Drop Test",
                      "width": 9,
                      "height": 5,
                      "scene": { "pvzce:grass": [ "0,0","1,0","2,0" ] },
                      "rules": { "pvzce:day_length": 0, "pvzce:night_length": -1 },
                      "waves": [],
                      "slots": [ "pvzce:pea_shooter", "pvzce:sun" ],
                      "initial_sun": 150,
                      "rewards": { "coin_drop_chance": 0.0, "coin_drop": "pvzce:coin_silver" }
                    }
                    """);
            reloadAndAwaitLevelList(harness);
            harness.send(new PlayLevelC2S("pvzce:nodrop_test", WORLD, true, List.of()));
            harness.awaitPacket(LevelInitS2C.class, 5_000);

            harness.send(new CommandC2S("/spawn zombie pvzce:basic_zombie 5 2"));
            // Waited for as *a zombie*, not as "whatever EntitySpawnS2C came last": a level with a
            // sky drops sun on its own, and `awaitPacket` hands back the latest packet of a type -
            // so this used to assert against a sun whenever one happened to fall inside the window.
            harness.waitFor(packet -> packet instanceof EntitySpawnS2C spawn
                            && "zombie".equals(spawn.entityKind()), 5_000,
                    "the summoned zombie never reached the client");
            EntitySpawnS2C spawned = harness.packets().stream()
                    .filter(EntitySpawnS2C.class::isInstance)
                    .map(EntitySpawnS2C.class::cast)
                    .filter(spawn -> "zombie".equals(spawn.entityKind()))
                    .findFirst().orElseThrow();
            harness.server().level().damageArea(ZombieEntity.damageType(PvzceIds.DAMAGE_ASH),
                    5.5F, 2.5F, 2F, 5_000, null);
            // The death is observed, not the counter: a dying zombie keeps its corpse on the
            // field for seconds, and the level's entity list belongs to the server thread.
            harness.waitFor(p -> p instanceof EntityUpdateS2C update
                    && update.entityId() == spawned.entityId()
                    && update.animation().startsWith("death"), 5_000,
                    "the damaged zombie never played its death clip");
            assertEquals(0, coinDrops(harness), "a level that disables drops must stay coin-free");
        }
    }

    private static int coinDrops(ServerHarness harness) {
        if (harness.server().level() == null) {
            return 0;
        }
        int count = 0;
        for (var entity : harness.server().level().entities()) {
            if (entity instanceof ResourceDropEntity drop && !drop.isRemoved()
                    && PvzceIds.COIN_DENOMINATIONS.contains(drop.defId())) {
                count += drop.amount();
            }
        }
        return count;
    }

    private static Identifier id(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    // ------------------------------------------------------------------
    // Helpers the harness does not carry: level-specific ways to end a run
    // ------------------------------------------------------------------

    /** The plant team of the running level; {@code null} when no level is open. */
    private static Team team(ServerHarness harness) {
        return harness.server().level() == null ? null : harness.server().level().team(PvzceIds.PLANT_TEAM);
    }

    /**
     * Loses the running level.
     *
     * <p>A zombie at the left edge wins it for the zombie team after its countdown, which is
     * the same path a real defeat takes.
     */
    private static void endLevel(ServerHarness harness) {
        // A flier: 1-1 comes with a lawn mower, which would eat a walking zombie before it
        // could end the run. The mower is not supposed to touch what is in the air.
        harness.send(new CommandC2S("/spawn zombie pvzce:balloon_zombie 0 0"));
        // The zombie has to walk in and finish its countdown (~295 ticks). Sprinting runs
        // them back to back instead of billing the suite ~5s of wall clock; the simulated
        // outcome is identical.
        harness.send(new CommandC2S("/tick sprint 400"));
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
}

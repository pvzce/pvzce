package com.pvzce.server;

import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PacketListener;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.network.packet.ProfileS2C;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.StartLevelC2S;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final String FIRST_LEVEL = "pvzce:yard/adventure/1_1";
    private static final String WORLD = "backpackworld";

    // ------------------------------------------------------------------
    // The profile itself: defaults, round trip, gating rules
    // ------------------------------------------------------------------

    @Test
    void aFreshProfileOwnsTheStarterPlantAndToolAndNothingElse() {
        PlayerProfile profile = PlayerProfile.starter();
        assertTrue(profile.owns(PvzceIds.STARTER_PLANT), "the first level's only plant must be owned");
        assertTrue(profile.owns(PvzceIds.STARTER_TOOL), "the shovel exists so a mistake can be undone");
        assertFalse(profile.owns(id("sunflower")), "sunflower is earned by finishing 1-1");
        assertFalse(profile.owns(id("wall_nut")));
        assertEquals(0, profile.coins());
    }

    @Test
    void resourceCardsAreNeverGated() {
        PlayerProfile profile = PlayerProfile.starter();
        // A level without its sun card cannot be played at all, so the backpack has
        // no say over resources - and an unknown id is not "locked" either.
        assertTrue(profile.owns(PvzceIds.SUN));
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
        assertTrue(loaded.owns(id("sunflower")));
        assertTrue(loaded.owns(PvzceIds.STARTER_PLANT));
        assertFalse(loaded.owns(id("chomper")));
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
        assertTrue(profile.owns(id("some_future_plant")));
        assertTrue(profile.owns(id("wall_nut")));
        assertTrue(PlayerProfile.load(profile.save()).unlocksEverything());
    }

    @Test
    void coinsStopAtTheCap() {
        PlayerProfile profile = PlayerProfile.starter();
        assertEquals(40, profile.grantCoins(40));
        assertEquals(PvzceConstants.COIN_CAP - 40, profile.grantCoins(PvzceConstants.COIN_CAP));
        assertEquals(PvzceConstants.COIN_CAP, profile.coins());
        assertEquals(0, profile.grantCoins(500), "nothing may be added past the cap");
    }

    @Test
    void aMissingOrEmptyRecordFallsBackToTheStarterProfile() {
        assertTrue(PlayerProfile.load(new CompoundTag()).owns(PvzceIds.STARTER_PLANT),
                "a world that predates profiles must still be playable");
        assertTrue(PlayerProfile.load(null).owns(PvzceIds.STARTER_TOOL));
    }

    @Test
    void theRewardsCodecDefaultsMatchTheDocumentedNumbers() {
        // A level without a rewards block still pays the standard replay stipend; a
        // first clear pays nothing extra until the level declares an unlock.
        assertEquals(List.of(LevelRewards.Reward.coins(100)), LevelRewards.DEFAULT.repeat());
        assertTrue(LevelRewards.DEFAULT.firstClear().isEmpty());
        assertEquals(0.25F, LevelRewards.DEFAULT.coinDropChance(), 0.0001F);
        assertEquals(id("coin_silver"), LevelRewards.DEFAULT.coinDrop());
        assertEquals(1, LevelRewards.DEFAULT.coinDropAmount());
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

    // ------------------------------------------------------------------
    // What the backpack does to a level's cards
    // ------------------------------------------------------------------

    @Test
    void theSeedPoolHidesUnownedCardsButKeepsTheLevelsOwn() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-backpack-pool");
        try (Harness harness = new Harness(gameDir)) {
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
            harness.send(new StartLevelC2S(FIRST_LEVEL, WORLD, true, List.of()));
            LevelInitS2C init = harness.awaitPacket(LevelInitS2C.class, 5_000);
            List<String> running = init.payload().seedPool().stream().map(SeedOption::slotId).toList();
            assertEquals(pool, running, "the level list and the running level must describe one pool");
        }
    }

    @Test
    void theFirstLevelFixesPeashooterAndSunAndNothingElse() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-first-level-cards");
        try (Harness harness = new Harness(gameDir)) {
            // The client asks for cards it does not own; the level's own two win out.
            harness.send(new StartLevelC2S(FIRST_LEVEL, WORLD, true,
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
        Path gameDir = Files.createTempDirectory("pvzce-backpack-all");
        try (Harness harness = new Harness(gameDir)) {
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
        Path gameDir = Files.createTempDirectory("pvzce-rewards");
        try (Harness harness = new Harness(gameDir)) {
            // One zombie, no delay: the level can be won inside a test. Its rewards
            // block is 1-1's, which ContentFoundationTest pins separately.
            harness.writeLevel(gameDir, "reward_test", """
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
            harness.reloadAndAwaitLevelList();

            // First clear: the level's first_clear reward is the sunflower.
            harness.send(new StartLevelC2S("pvzce:reward_test", WORLD, true, List.of()));
            harness.awaitPacket(LevelInitS2C.class, 5_000);
            harness.winLevel();

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
            assertEquals(0, first.bonusCoins(), "a first clear pays the unlock, not the stipend");
            assertTrue(harness.awaitPacket(ProfileS2C.class, 5_000).unlocked().contains("pvzce:sunflower"));

            Path profileFile = gameDir.resolve("saves/" + WORLD + "/profile.dat");
            harness.waitForFile(profileFile, 5_000);
            assertTrue(PlayerProfile.load(NbtIo.readCompressed(profileFile)).owns(id("sunflower")),
                    "the unlock must be on disk, not only in memory");

            // Replay: the card is already owned, so the level pays its coin stipend.
            harness.send(new LeaveLevelC2S());
            harness.clear();
            harness.send(new StartLevelC2S("pvzce:reward_test", WORLD, true, List.of()));
            harness.awaitPacket(LevelInitS2C.class, 5_000);
            harness.clear();
            harness.winLevel();

            LevelRewardS2C repeat = harness.awaitPacket(LevelRewardS2C.class, 8_000);
            assertEquals("", repeat.unlockedCard(), "there is nothing left to unlock");
            assertEquals(100, repeat.bonusCoins(), "a replay pays the level's repeat stipend");
            assertEquals(100, repeat.totalCoins());
            assertEquals(100, PlayerProfile.load(NbtIo.readCompressed(profileFile)).coins());
        }
    }

    @Test
    void coinsCollectedInARunAreBankedEvenWhenTheRunIsLost() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-coin-bank");
        try (Harness harness = new Harness(gameDir)) {
            harness.send(new StartLevelC2S(FIRST_LEVEL, WORLD, true, List.of()));
            harness.awaitPacket(LevelInitS2C.class, 5_000);
            // Stands in for coins the run picked up: the wallet rule is what is under
            // test here, and the drop roll has its own test below.
            // A gold coin and a silver one: the wallet has to add what each is worth,
            // and the bank's total is a sum over the denominations.
            harness.send(new CommandC2S("/resource give pvzce:plant_team pvzce:coin_gold 50"));
            harness.waitFor(() -> harness.team().resourcesOf(PvzceIds.COIN_GOLD) == 50, 5_000);
            harness.send(new CommandC2S("/resource give pvzce:plant_team pvzce:coin_silver 10"));
            harness.waitFor(() -> harness.team().resourcesOf(PvzceIds.COIN_SILVER) == 10, 5_000);
            harness.clear();

            harness.endLevel();
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
        Path gameDir = Files.createTempDirectory("pvzce-coin-drop");
        try (Harness harness = new Harness(gameDir)) {
            // Certain drops, so the test does not gamble on the default 25% roll.
            harness.writeLevel(gameDir, "drop_test", """
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
            harness.reloadAndAwaitLevelList();
            harness.send(new StartLevelC2S("pvzce:drop_test", WORLD, true, List.of()));
            harness.awaitPacket(LevelInitS2C.class, 5_000);

            harness.send(new CommandC2S("/spawn zombie pvzce:basic_zombie 5 2"));
            harness.waitFor(() -> harness.server().level() != null
                    && harness.server().level().aliveZombieCount() == 1, 5_000);

            // Kills it directly: this is about what a death spawns, not about
            // shooting one to death, which CombatSystemsTest already covers.
            harness.server().level().damageArea(5.5F, 2.5F, 2F, 5_000, null);
            harness.waitFor(() -> coinDrops(harness) == 150, 5_000);
            assertEquals(150, coinDrops(harness),
                    "three gold coins, and a drop's amount is the denomination's worth");
        }
    }

    @Test
    void aLevelWhoseDropChanceIsZeroNeverSpawnsCoins() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-coin-off");
        try (Harness harness = new Harness(gameDir)) {
            harness.writeLevel(gameDir, "nodrop_test", """
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
            harness.reloadAndAwaitLevelList();
            harness.send(new StartLevelC2S("pvzce:nodrop_test", WORLD, true, List.of()));
            harness.awaitPacket(LevelInitS2C.class, 5_000);

            harness.send(new CommandC2S("/spawn zombie pvzce:basic_zombie 5 2"));
            harness.waitFor(() -> harness.server().level() != null
                    && harness.server().level().aliveZombieCount() == 1, 5_000);
            harness.server().level().damageArea(5.5F, 2.5F, 2F, 5_000, null);
            harness.waitFor(() -> harness.server().level().aliveZombieCount() == 0, 5_000);
            assertEquals(0, coinDrops(harness), "a level that disables drops must stay coin-free");
        }
    }

    private static int coinDrops(Harness harness) {
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
    // Harness
    // ------------------------------------------------------------------

    /**
     * A server on a memory connection plus the packet-level waits these tests need.
     *
     * <p>Deliberately not shared with {@code SaveSystemTest}'s harness: that one is
     * private to its class and built around waiting for packet predicates, while this
     * one waits for typed packets and drives the level end directly.
     */
    private static final class Harness implements AutoCloseable {
        private final Connection.Pair pair;
        private final PvzceServer server;
        private final List<PvzcePacket> packets = new ArrayList<>();

        Harness(Path gameDir) throws Exception {
            BuiltInRegistries.bootstrap();
            PvzcePackets.register();
            this.pair = Connection.createMemoryPair();
            this.server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
            PacketListener collector = packets::add;
            pair.client().setListener(collector);
            server.start();
            // Wait for the first data load before anything else is sent.
            send(new RequestLevelListC2S("__probe__"));
            waitFor(() -> packets.stream().anyMatch(LevelListS2C.class::isInstance), 5_000);
            packets.clear();
        }

        PvzceServer server() {
            return server;
        }

        /** The plant team of the running level; {@code null} when no level is open. */
        Team team() {
            return server.level() == null ? null : server.level().team(PvzceIds.PLANT_TEAM);
        }

        void send(PvzcePacket packet) {
            pair.client().send(packet);
        }

        void clear() {
            packets.clear();
        }

        /**
         * Loses the running level.
         *
         * <p>A zombie at the left edge wins it for the zombie team after its
         * countdown, which is the same path a real defeat takes.
         */
        void endLevel() {
            send(new CommandC2S("/spawn zombie pvzce:basic_zombie 0 0"));
            // The zombie has to walk in and finish its countdown (~295 ticks). Sprinting
            // runs them back to back instead of billing the suite ~5s of wall clock; the
            // simulated outcome is identical.
            send(new CommandC2S("/tick sprint 400"));
        }

        /**
         * Wins the running level by clearing every zombie and every wave.
         *
         * <p>Damage is applied directly rather than through a peashooter: what is
         * under test is the payout, and {@code CombatSystemsTest} already covers
         * shooting. Returns once the plant team has won.
         */
        void winLevel() throws Exception {
            waitFor(() -> {
                LevelServer level = server.level();
                if (level == null) {
                    return false;
                }
                if (level.aliveZombieCount() > 0) {
                    level.damageArea(0F, 0F, 500F, 100_000, level.team(PvzceIds.PLANT_TEAM));
                }
                return level.gameState().equals(com.pvzce.common.network.packet.GameStateS2C.WON)
                        && level.winner() != null && level.winner().equals(PvzceIds.PLANT_TEAM);
            }, 15_000);
        }

        <T extends PvzcePacket> T awaitPacket(Class<T> type, long timeoutMs) throws Exception {
            waitFor(() -> packets.stream().anyMatch(type::isInstance), timeoutMs);
            return packets.stream().filter(type::isInstance).map(type::cast).reduce((a, b) -> b).orElseThrow();
        }

        void waitFor(BooleanSupplier condition, long timeoutMs) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                pair.client().tick();
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(5);
            }
            throw new AssertionError("Timed out waiting for condition; level="
                    + (server.level() == null ? "null" : server.level().gameState()));
        }

        void waitForFile(Path file, long timeoutMs) throws Exception {
            waitFor(() -> Files.isRegularFile(file), timeoutMs);
        }

        /** Writes a data pack level; the server only sees it after a reload. */
        void writeLevel(Path gameDir, String name, String json) throws Exception {
            Path pack = gameDir.resolve("datapacks/" + name);
            Path levelFile = pack.resolve("data/pvzce/levels/" + name + ".json");
            Files.createDirectories(levelFile.getParent());
            Files.writeString(pack.resolve("pack.mcmeta"),
                    "{\"pack\":{\"pack_format\":1,\"description\":\"" + name + "\"}}");
            Files.writeString(levelFile, json);
        }

        /** Reloads content and waits for the pushed level list, so the new level exists. */
        void reloadAndAwaitLevelList() throws Exception {
            clear();
            send(new CommandC2S("/reload"));
            awaitPacket(LevelListS2C.class, 8_000);
            clear();
        }

        @Override
        public void close() throws Exception {
            server.stop();
            server.thread().join(3_000);
        }
    }
}

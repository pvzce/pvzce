package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.RakeData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.buff.BuiltInBuffs;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.RakeMechanic;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.shop.ShopItems;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.shop.ShopPurchases;
import com.pvzce.testutil.TestLevels;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shop: what it sells, what a purchase does, and the two things it unlocks.
 *
 * <p>Three claims worth pinning. The catalogue is priced in one place and the server re-reads it
 * (a modified client cannot buy cheaply). Ownership is derived from the profile rather than
 * counted, so an operator command and a purchase cannot disagree. And the rake really does flatten
 * the first zombie to walk in - the shop has to sell something that works.
 */
class ShopTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier BASIC = Identifier.withDefaultNamespace("basic_zombie");
    private static final String WORLD = "shopworld";

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class Bridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }

        List<String> messages() {
            List<String> lines = new ArrayList<>();
            for (PvzcePacket packet : packets) {
                if (packet instanceof ServerMessageS2C message) {
                    lines.add(message.message());
                }
            }
            return lines;
        }
    }

    /** Every item is priced, named, and buyable at least once. */
    @Test
    void theCatalogueIsWellFormed() {
        assertTrue(ShopItems.ITEMS.size() >= 3, "the shop has to sell something");
        for (var id : BuiltInRegistries.PLANTS.keySet()) {
            if (BuiltInRegistries.PLANTS.get(id).upgrade().isPresent()) {
                assertFalse(ShopPurchases.sells(id), id + " is earned in adventure, not bought");
            }
        }
        for (ShopItems.Item item : ShopItems.ITEMS) {
            assertTrue(item.price() > 0, item.id() + " has to cost something");
            assertTrue(item.maxOwned() >= 1, item.id() + " has to be buyable at least once");
            assertTrue(BuiltInRegistries.LEVEL_BUFFS.get(item.id()) != null
                            || com.pvzce.common.core.SlotResolver.resolve(item.id()).isPresent()
                            || ShopItems.STANDALONE_UNLOCKS.contains(item.id())
                            || item.kind() == ShopItems.Item.Kind.CARD_SLOTS,
                    item.id() + " names nothing the game knows about, so `apply` could not grant it");
        }
    }

    /**
     * The rake is a purchase, not a card.
     *
     * <p>It used to be sold as a tool, which put it in the player's backpack as a card whose effect
     * id nothing implemented - a card whose click did nothing at all. Nothing about the rake is a
     * card: it is laid on the lawn by the level for a player who owns one, and the whole of the
     * purchase is that flag. This pins the absence, because "the rake should not be a tool" is a
     * statement about the data and the data is what would grow it back.
     */
    @Test
    void theRakeIsNotACard() {
        assertTrue(ShopItems.byId(ShopItems.RAKE).isPresent(), "the shop still sells a rake");
        assertTrue(com.pvzce.common.core.SlotResolver.resolve(ShopItems.RAKE).isEmpty(),
                "and it is not a card, so the chooser can never offer it");
        assertFalse(BuiltInRegistries.TOOLS.keySet().contains(ShopItems.RAKE),
                "nor a tool, so there is no cursor art and no effect id to implement");
        assertTrue(ShopItems.STANDALONE_UNLOCKS.contains(ShopItems.RAKE),
                "the catalogue knows it is one of the unlocks that names no card");
    }

    /**
     * The card-slot item stops at the ceiling.
     *
     * <p>Bought four times from the default backpack, and refused on the fifth. The ceiling is
     * {@code PvzceConstants.MAX_SEED_SLOTS}, which the profile also enforces - so this is really
     * "the shop's own limit and the backpack's agree".
     */
    @Test
    void theCardSlotItemStopsAtTheBackpackCeiling() {
        PlayerProfile profile = PlayerProfile.starter();
        ShopItems.Item item = ShopItems.byId(ShopItems.CARD_SLOT).orElseThrow();
        int bought = 0;
        while (!ShopPurchases.maxedOut(profile, item)) {
            assertEquals("", ShopPurchases.apply(profile, item), "the purchase has to be accepted");
            bought++;
            assertTrue(bought <= com.pvzce.common.PvzceConstants.MAX_SEED_SLOTS,
                    "the loop has to terminate");
        }
        assertEquals(com.pvzce.common.PvzceConstants.MAX_SEED_SLOTS
                        - com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS, bought,
                "four slots from eight to twelve");
        assertEquals(com.pvzce.common.PvzceConstants.MAX_SEED_SLOTS, profile.seedSlots());
        assertFalse(ShopPurchases.apply(profile, item).isEmpty(),
                "and the fifth is refused with a reason rather than silently");
    }

    /** A one-off item cannot be bought twice. */
    @Test
    void aOneOffItemIsOwnedAfterTheFirstPurchase() {
        PlayerProfile profile = PlayerProfile.starter();
        ShopItems.Item rake = ShopItems.byId(ShopItems.RAKE).orElseThrow();
        assertEquals(1, rake.maxOwned());
        assertEquals(0, ShopPurchases.ownedCount(profile, rake));
        assertEquals("", ShopPurchases.apply(profile, rake));
        assertEquals(1, ShopPurchases.ownedCount(profile, rake));
        assertTrue(ShopPurchases.maxedOut(profile, rake));
        assertFalse(ShopPurchases.apply(profile, rake).isEmpty(), "and again is refused");
    }

    /**
     * Ownership is derived, so an operator command cannot make the shop disagree.
     *
     * <p>{@code /profile slots} widens the backpack without going through the shop at all. If the
     * shop counted its own purchases it would still offer four slots to a player who already has
     * twelve, and the purchase would silently do nothing.
     */
    @Test
    void anOperatorGrantCountsAsOwnership() {
        PlayerProfile profile = PlayerProfile.starter();
        ShopItems.Item item = ShopItems.byId(ShopItems.CARD_SLOT).orElseThrow();
        profile.addSeedSlots(4);
        assertEquals(4, ShopPurchases.ownedCount(profile, item));
        assertTrue(ShopPurchases.maxedOut(profile, item),
                "a backpack widened by a command is full, shop or no shop");

        profile.unlock(PvzceIds.RAKE);
        assertTrue(ShopPurchases.maxedOut(profile, ShopItems.byId(ShopItems.RAKE).orElseThrow()));
    }

    // ------------------------------------------------------------------
    // The purchase, from the wire down to the file
    // ------------------------------------------------------------------

    /**
     * A purchase goes through the real server, is charged once, and is on disk.
     *
     * <p>The unit tests above prove the arithmetic; this proves the wiring - that the packet is
     * registered, that the handler reaches the profile, that the wallet is charged the catalogue's
     * price rather than anything the client said, and that the result survives a reload. Buying is
     * exactly the kind of feature that works in a unit test and does nothing in game because the
     * packet was never dispatched.
     */
    @Test
    void aPurchaseChargesTheWalletAndSurvivesAReload(@org.junit.jupiter.api.io.TempDir Path gameDir)
            throws Exception {
        try (com.pvzce.testutil.ServerHarness harness =
                     com.pvzce.testutil.ServerHarness.createWithWorld(gameDir, WORLD, true)) {
            // The wallet starts empty, so fill it through the world store rather than by winning
            // levels: this test is about the shop, not about the payout, and a console command
            // would drag in the team resolution of a running level.
            PlayerProfile wallet = harness.server().worlds().profileFor(WORLD);
            wallet.setCoins(500);
            harness.server().worlds().saveProfile(WORLD, wallet);
            harness.clear();

            ShopItems.Item rake = ShopItems.byId(ShopItems.RAKE).orElseThrow();
            harness.send(new com.pvzce.common.network.packet.BuyShopItemC2S(
                    rake.id().toString(), WORLD));
            com.pvzce.common.network.packet.ProfileS2C after =
                    harness.awaitPacket(com.pvzce.common.network.packet.ProfileS2C.class, 5_000);
            assertTrue(after.unlocked().contains(rake.id().toString()),
                    "the rake has to be in the profile the client is handed back; got "
                            + after.unlocked());

            Path profileFile = gameDir.resolve("saves/" + WORLD + "/profile.dat");
            harness.waitForFile(profileFile, 5_000);
            PlayerProfile onDisk = PlayerProfile.load(
                    com.pvzce.common.nbt.NbtIo.readCompressed(profileFile));
            assertTrue(onDisk.unlocked().contains(rake.id()), "and on disk, not only in memory");
            assertEquals(500 - rake.price(), onDisk.coins(),
                    "charged exactly the catalogue's price, once");
        }
    }

    /** A delisted purple packet cannot be bought over the wire or erase an earlier purchase. */
    @Test
    void aDelistedUpgradeIsRefusedAndPreservesTheWalletAndExistingPlants(@org.junit.jupiter.api.io.TempDir Path gameDir)
            throws Exception {
        try (com.pvzce.testutil.ServerHarness harness =
                     com.pvzce.testutil.ServerHarness.createWithWorld(gameDir, WORLD, true)) {
            PlayerProfile wallet = harness.server().worlds().profileFor(WORLD);
            wallet.setCoins(50000);
            wallet.unlock(PvzceIds.id("gatling_pea"));
            harness.server().worlds().saveProfile(WORLD, wallet);
            harness.clear();

            harness.send(new com.pvzce.common.network.packet.BuyShopItemC2S(
                    "pvzce:gloom_shroom", WORLD));
            com.pvzce.common.network.packet.ServerMessageS2C message =
                    harness.awaitPacket(com.pvzce.common.network.packet.ServerMessageS2C.class,
                            5_000);
            assertTrue(message.message().contains("商店里没有"),
                    "it has to say what went wrong, got: " + message.message());

            Path profileFile = gameDir.resolve("saves/" + WORLD + "/profile.dat");
            PlayerProfile onDisk = PlayerProfile.load(
                    com.pvzce.common.nbt.NbtIo.readCompressed(profileFile));
            assertEquals(50000, onDisk.coins(), "and nothing was charged");
            assertTrue(onDisk.unlocked().contains(PvzceIds.id("gatling_pea")),
                    "earlier upgrade purchases remain owned");
            assertFalse(onDisk.unlocked().contains(PvzceIds.id("gloom_shroom")),
                    "a rejected purchase grants nothing");
        }
    }

    // ------------------------------------------------------------------
    // The rake
    // ------------------------------------------------------------------

    /** No profile, no rake: the mechanic is not installed at all. */
    @Test
    void aLevelWithoutTheRakeInTheProfileHasNoRake() {
        LevelServer level = plainLevel(false);
        assertFalse(hasMechanic(level, PvzceIds.MECHANIC_RAKE),
                "a level built with no backpack must not hand out a free rake");
        RakeMechanic.Rig rig = RakeMechanic.rig(level, RakeData.RANDOM);
        assertFalse(rig.armed(), "and nothing is lying on the lawn");
    }

    /** Owning it puts one on the lawn, in one lane. */
    @Test
    void owningTheRakePutsOneOnTheLawn() {
        LevelServer level = plainLevel(true);
        assertTrue(hasMechanic(level, PvzceIds.MECHANIC_RAKE), "the player's rake is installed");
        RakeMechanic.Rig rig = RakeMechanic.rig(level, RakeData.RANDOM);
        assertTrue(rig.armed(), "and it is lying on the lawn");
        assertTrue(rig.row() >= 0 && rig.row() < level.height(), "in a real lane: " + rig.row());
    }

    /** A level that says so gets none, even for a player who owns one. */
    @Test
    void aLevelThatDeclaresNoRakeGetsNone() {
        LevelDef base = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1"));
        List<TypedMechanic> mechanics = new ArrayList<>(base.mechanics());
        mechanics.add(TypedMechanic.of(PvzceIds.MECHANIC_RAKE, RakeData.NONE));
        LevelDef def = TestLevels.copy(base).mechanics(mechanics).waves(List.of()).build();
        LevelServer level = new LevelServer(def, List.of(),
                LevelServer.SeedContext.forProfile(def, profileWithRake()), List.of(), null);
        assertFalse(RakeMechanic.rig(level, RakeData.NONE).armed(),
                "an explicit empty row set means 'not here', even for a player who owns one");
    }

    /** It kills the first ground zombie to reach it, and is spent. */
    @Test
    void theRakeFlattensTheFirstZombieAndIsSpent() {
        LevelServer level = plainLevel(true);
        Bridge bridge = new Bridge();
        RakeMechanic.Rig rig = RakeMechanic.rig(level, RakeData.RANDOM);
        int row = rig.row();

        ZombieEntity victim = level.spawnZombie(BASIC, level.team(PvzceIds.ZOMBIE_TEAM), 1.2F, row);
        assertNotNull(victim);
        level.flushPending(bridge::send);
        assertTrue(victim.isAlive(), "it has to walk in before it is flattened");

        for (int i = 0; i < 900 && victim.isAlive(); i++) {
            level.tick(bridge::send);
        }
        assertFalse(victim.isAlive(), "the zombie that reached the rake is flattened");
        assertFalse(rig.armed(), "and the rake is spent");

        // A second zombie walks over the same spot untouched: one rake, one zombie.
        ZombieEntity later = level.spawnZombie(BASIC, level.team(PvzceIds.ZOMBIE_TEAM), 1.0F, row);
        assertNotNull(later);
        level.flushPending(bridge::send);
        for (int i = 0; i < 200; i++) {
            level.tick(bridge::send);
        }
        assertTrue(later.isAlive(), "a spent rake is not a second rake");
    }

    /** A flier is not flattened: the rake is on the ground like the mower. */
    @Test
    void theRakeIgnoresWhatFliesOverIt() {
        LevelServer level = plainLevel(true);
        Bridge bridge = new Bridge();
        RakeMechanic.Rig rig = RakeMechanic.rig(level, RakeData.RANDOM);
        ZombieEntity balloon = level.spawnZombie(
                Identifier.withDefaultNamespace("balloon_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), 1.0F, rig.row());
        assertNotNull(balloon);
        level.flushPending(bridge::send);
        for (int i = 0; i < 300; i++) {
            level.tick(bridge::send);
        }
        assertTrue(balloon.isAlive(), "a balloon zombie flies over the rake");
        assertTrue(rig.armed(), "and the rake is still waiting for something on foot");
    }

    // ------------------------------------------------------------------
    // The sun shovel
    // ------------------------------------------------------------------

    /**
     * Digging a plant up returns a fifth of its price, and only when the buff is on.
     *
     * <p>Both halves in one test because they are one rule: the refund is the buff's, and the
     * amount is what the buff says.
     */
    @Test
    void theSunShovelRefundsAFifthOfThePrice() {
        LevelServer dry = shovelLevel(false);
        assertEquals(0, shovelAndMeasureRefund(dry),
                "without the buff, digging a plant up returns nothing at all");

        LevelServer wet = shovelLevel(true);
        com.pvzce.api.content.PlantDef peashooter =
                BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("pea_shooter"));
        assertNotNull(peashooter);
        int price = peashooter.cost().amountOf(PvzceIds.SUN);
        assertEquals(Math.round(price * BuiltInBuffs.SUN_SHOVEL_REFUND), shovelAndMeasureRefund(wet),
                "with it, exactly a fifth of what the plant cost");
    }

    /**
     * A conveyor belt is exempt.
     *
     * <p>The user's own rule ("传送带无效"), and the reason is arithmetic rather than policy: a belt's
     * cards cost nothing, so a refund there would be sun conjured out of a plant nobody paid for.
     */
    @Test
    void theSunShovelRefundsNothingOnABeltLevel() {
        LevelDef base = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_5"));
        assertNotNull(base, "1-5 is the shipped belt level");
        LevelDef def = TestLevels.copy(base).waves(List.of()).build();
        LevelServer level = new LevelServer(def, List.of(),
                LevelServer.SeedContext.forProfile(def, PlayerProfile.starter()), List.of(), null);
        level.setActiveBuffs(List.of(BuiltInBuffs.SUN_SHOVEL));
        assertTrue(level.cardSource().dealsItsOwnCards(),
                "the fixture has to actually be a belt level");

        com.pvzce.api.content.PlantDef peashooter =
                BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("pea_shooter"));
        Bridge bridge = new Bridge();
        level.plantPlayer().replaceSlots(com.pvzce.server.PvzcePlayer.deckSlots(
                List.of(Identifier.withDefaultNamespace("pea_shooter"),
                        Identifier.withDefaultNamespace("sun"))));
        level.plantPlayer().replaceSlots(com.pvzce.server.PvzcePlayer.deckSlots(
                List.of(PvzceIds.id("pea_shooter"), PvzceIds.id("sun"), PvzceIds.id("shovel"))));
        level.spawnPlant(peashooter, level.team(PLANT_TEAM), 2, 0);
        level.flushPending(bridge::send);
        int before = level.plantPlayer().team().resourcesOf(PvzceIds.SUN);
        assertTrue(level.useTool(bridge, shovelSlot(level), 2, 0),
                "the fixture has to actually shovel something");
        assertEquals(before, level.plantPlayer().team().resourcesOf(PvzceIds.SUN),
                "a free card refunds nothing");
    }

    private static int shovelAndMeasureRefund(LevelServer level) {
        com.pvzce.api.content.PlantDef peashooter =
                BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("pea_shooter"));
        assertNotNull(peashooter);
        Bridge bridge = new Bridge();
        level.spawnPlant(peashooter, level.team(PLANT_TEAM), 2, 0);
        level.flushPending(bridge::send);
        int before = level.plantPlayer().team().resourcesOf(PvzceIds.SUN);
        assertTrue(level.useTool(bridge, shovelSlot(level), 2, 0),
                "the shovel has to accept a plant");
        return level.plantPlayer().team().resourcesOf(PvzceIds.SUN) - before;
    }

    /** The shovel's index on the bar; the card is pinned by the fixture. */
    private static int shovelSlot(LevelServer level) {
        return level.plantPlayer().slots().stream()
                .filter(slot -> slot.defId().equals(Identifier.withDefaultNamespace("shovel")))
                .map(com.pvzce.common.core.Slot::index)
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static LevelServer shovelLevel(boolean withBuff) {
        LevelDef base = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1"));
        LevelDef def = TestLevels.copy(base).waves(List.of())
                .slots(List.of(PvzceIds.id("pea_shooter"), PvzceIds.id("sun"), PvzceIds.id("shovel")))
                .build();
        LevelServer level = new LevelServer(def, List.of(),
                LevelServer.SeedContext.forProfile(def, PlayerProfile.starter()), List.of(), null);
        level.setActiveBuffs(withBuff ? List.of(BuiltInBuffs.SUN_SHOVEL) : List.of());
        return level;
    }

    /**
     * A lawn with the rake and nothing else.
     *
     * <p>No mowers, and that is not tidiness: the second half of the rake test walks a zombie down
     * the same lane after the rake is spent, and a mower that ran it over would read as "the rake
     * struck twice". The fixture has to have exactly one lawn fixture in it for the assertion to
     * mean what it says.
     */
    private static LevelServer plainLevel(boolean ownsRake) {
        LevelDef base = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1"));
        LevelDef def = TestLevels.copy(base).waves(List.of())
                .mechanics(List.of(com.pvzce.api.content.mechanic.TypedMechanic.of(
                        PvzceIds.MECHANIC_MOWER,
                        new com.pvzce.api.content.MowerData(java.util.Optional.of(List.of())))))
                .build();
        LevelServer.SeedContext context = LevelServer.SeedContext.forProfile(def,
                ownsRake ? profileWithRake() : PlayerProfile.starter());
        return new LevelServer(def, List.of(), context, List.of(), null);
    }

    private static PlayerProfile profileWithRake() {
        PlayerProfile profile = PlayerProfile.starter();
        profile.unlock(PvzceIds.RAKE);
        return profile;
    }

    private static boolean hasMechanic(LevelServer level, Identifier id) {
        for (var mechanic : level.mechanics()) {
            if (mechanic.type().equals(id)) {
                return true;
            }
        }
        return false;
    }
}

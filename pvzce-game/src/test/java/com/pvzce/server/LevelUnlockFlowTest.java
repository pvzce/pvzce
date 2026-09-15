package com.pvzce.server;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.common.network.packet.UnlockLevelC2S;
import com.pvzce.testutil.ServerHarness;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unlock conditions, end to end: the list the client is sent and what the server accepts.
 *
 * <p>The pure evaluation is covered by {@code LevelUnlocksTest}; what matters here is that
 * the server actually applies it - a verdict that is only computed for display would let a
 * client walk into any level, and a purchase that is only recorded on the client would be
 * forgotten on the next load.
 */
class LevelUnlockFlowTest {
    private static final String WORLD = "unlockworld";
    private static final String FIRST = "pvzce:yard/adventure/1_1";
    private static final String SECOND = "pvzce:yard/adventure/1_2";
    private static final String GATED_DEMO = "pvzce:yard/adventure/1_4";
    private static final String FREE = "pvzce:yard/adventure/demo_level";

    @Test
    void theShippedChainGatesOneTwoBehindOneOne() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-unlock-list");
        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, false)) {
            LevelListS2C list = harness.levelList(WORLD);

            LevelListS2C.LevelInfo first = find(list, FIRST);
            assertFalse(first.isLocked(), "the first level of the chain is open from the start");
            assertEquals("", first.unlock().reason());

            LevelListS2C.LevelInfo second = find(list, SECOND);
            assertTrue(second.isLocked(), "1-2 requires 1-1");
            assertTrue(second.unlock().reason().contains("1_1"),
                    "the reason names what is missing: " + second.unlock().reason());
            assertFalse(second.unlock().buyable(), "1-2 has no price, so it cannot be bought");
        }
    }

    /** The gate is the server's, not the menu's. */
    @Test
    void enteringALockedLevelIsRefused() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-unlock-refuse");
        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, false)) {
            harness.levelList(WORLD);
            harness.clear();
            harness.send(new PlayLevelC2S(SECOND, WORLD, true, List.of()));

            ServerMessageS2C message = harness.awaitPacket(ServerMessageS2C.class, 5_000);
            assertTrue(message.message().contains("尚未解锁"), message.message());
            // No level was built: nothing may report a running level. The refusal is already
            // in hand, so a couple of ticks are enough to prove no init followed it.
            assertNull(harness.awaitPacketOrNull(LevelInitS2C.class, 100),
                    "a refused level must not start");
        }
    }

    /**
     * A completion marker on the prerequisite opens the next level.
     *
     * <p>The marker file is what the rule reads, so it is written here in the same shape a
     * finished run writes it - playing 1-1 to completion in a unit test would mostly test
     * the simulation, which other tests already cover.
     */
    @Test
    void aCompletionMarkerOnThePrerequisiteOpensTheNextLevel() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-unlock-cleared");
        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, false)) {
            assertTrue(find(harness.levelList(WORLD), SECOND).isLocked(), "locked to begin with");

            writeCompletionMarker(gameDir, FIRST);
            harness.clear();
            harness.send(new RequestLevelListC2S(WORLD));

            LevelListS2C.LevelInfo second = find(harness.awaitPacket(LevelListS2C.class, 5_000), SECOND);
            assertFalse(second.isLocked(), "1-2 opens once 1-1 has a completion marker");
        }
    }

    /** The marker file a finished run writes, under the key the server derives from the id. */
    private static void writeCompletionMarker(Path gameDir, String levelId) throws Exception {
        Identifier id = Identifier.parse(levelId);
        String key = com.pvzce.server.level.LevelKey.of(id);
        Path dir = gameDir.resolve("saves").resolve(WORLD).resolve("level_status");
        Files.createDirectories(dir);
        CompoundTag status = new CompoundTag();
        status.putString("GameState", LevelListS2C.LevelInfo.COMPLETED);
        status.putString("LevelId", levelId);
        NbtIo.writeCompressed(status, dir.resolve(key + ".dat"));
    }

    /**
     * A priced level can be bought, is charged once, and stays bought.
     *
     * <p>Three server lifetimes on one directory, which is also how a wallet is seeded:
     * a profile edited on disk is read when the server starts, so the test does not need a
     * back door into the running one. (Writing the file under a live server does not work:
     * it caches the profile and writes its copy back on the next save.)
     */
    @Test
    void buyingAPricedLevelChargesOnceAndSticks() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-unlock-buy");

        // 1. Fresh world, no coins: the purchase is refused with a reason.
        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, false)) {
            LevelListS2C.LevelInfo gated = find(harness.levelList(WORLD), GATED_DEMO);
            assertTrue(gated.isLocked(), "1-4 requires the three levels before it");
            assertEquals(500, gated.unlock().cost());

            harness.clear();
            harness.send(new UnlockLevelC2S(GATED_DEMO, WORLD));
            ServerMessageS2C poor = harness.awaitPacket(ServerMessageS2C.class, 5_000);
            assertNotNull(poor, "the server must answer a refused purchase");
            assertTrue(poor.message().contains("金币不足"), poor.message());
        }

        // 2. A wallet with exactly the price: the purchase goes through and empties it.
        // The profile is written by the server itself, before it stops - a file written
        // underneath a running server is overwritten by its shutdown flush. Attaching to
        // the world (rather than creating it again) is what keeps that wallet: creating it
        // writes a starter profile over the top.
        try (ServerHarness harness = ServerHarness.create(gameDir)) {
            wallet(harness, 500);
            assertEquals(500, coins(harness), "the wallet write must be readable back");
        }
        try (ServerHarness harness = ServerHarness.create(gameDir)) {
            assertEquals(500, coins(harness), "the funded wallet is what this step starts from");
            harness.send(new UnlockLevelC2S(GATED_DEMO, WORLD));
            LevelListS2C afterBuy = harness.awaitPacket(LevelListS2C.class, 5_000);
            assertNotNull(afterBuy, "the purchase must refresh the level list");
            assertFalse(find(afterBuy, GATED_DEMO).isLocked(), "the purchase opens the level");
            assertEquals(0, coins(harness),
                    "the price was taken from the wallet");

            // Buying again is a no-op rather than a second charge.
            harness.clear();
            harness.send(new UnlockLevelC2S(GATED_DEMO, WORLD));
            harness.awaitPacket(LevelListS2C.class, 5_000);
            assertEquals(0, coins(harness),
                    "a level is only ever paid for once");
        }

        // 3. And it is on disk: a new server on the same directory still has it open, with
        // an empty wallet - so the level was bought, not merely unlocked by a restart.
        try (ServerHarness harness = ServerHarness.create(gameDir)) {
            assertFalse(find(harness.levelList(WORLD), GATED_DEMO).isLocked(),
                    "the purchase survived a restart");
            assertEquals(0, coins(harness));
        }
    }

    /** A sandbox world ignores every condition. */
    @Test
    void aSandboxWorldListsEverythingAsOpen() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-unlock-sandbox");
        try (ServerHarness harness = ServerHarness.createWithWorld(gameDir, WORLD, false)) {
            harness.send(new CreateWorldC2S(WORLD, true));
            harness.clear();
            LevelListS2C list = harness.levelList(WORLD);
            for (LevelListS2C.LevelInfo info : list.levels()) {
                assertFalse(info.isLocked(), info.id() + " must be open in a sandbox world");
            }
        }
    }

    /** Puts coins in this world's wallet through the server's own write path. */
    private static void wallet(ServerHarness harness, int coins) {
        PlayerProfile profile = harness.server().profileFor(WORLD);
        profile.setCoins(coins);
        harness.server().saveProfile(WORLD, profile);
    }

    /** This world's wallet, read from the profile the server holds for it. */
    private static int coins(ServerHarness harness) {
        return harness.server().profileFor(WORLD).coins();
    }

    private static LevelListS2C.LevelInfo find(LevelListS2C list, String id) {
        LevelListS2C.LevelInfo info = list.levels().stream()
                .filter(level -> level.id().equals(id))
                .findFirst().orElse(null);
        assertNotNull(info, id + " must be in the list; got "
                + list.levels().stream().map(LevelListS2C.LevelInfo::id).toList());
        return info;
    }

    /**
     * The same shape as {@code SaveSystemTest.TestServer}: a real server on a memory
     * connection, with the client side driven from the test thread.
     */
}

package com.pvzce.server;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.ProfileS2C;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.network.packet.StartLevelC2S;
import com.pvzce.common.network.packet.UnlockLevelC2S;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

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
        try (Harness harness = new Harness(gameDir)) {
            LevelListS2C list = harness.levelList();

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

    /** A level with no unlock block is playable, which is what keeps custom levels working. */
    @Test
    void aLevelWithNoConditionsIsListedUnlocked() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-unlock-open");
        try (Harness harness = new Harness(gameDir)) {
            LevelListS2C.LevelInfo free = find(harness.levelList(), FREE);
            assertFalse(free.isLocked());
            assertFalse(free.unlock().hidden());
        }
    }

    /** The gate is the server's, not the menu's. */
    @Test
    void enteringALockedLevelIsRefused() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-unlock-refuse");
        try (Harness harness = new Harness(gameDir)) {
            harness.levelList();
            harness.clear();
            harness.send(new StartLevelC2S(SECOND, WORLD, true, List.of()));

            ServerMessageS2C message = harness.awaitPacket(ServerMessageS2C.class, 5_000);
            assertTrue(message.message().contains("尚未解锁"), message.message());
            // No level was built: nothing may report a running level. The refusal is already
            // in hand, so a couple of ticks are enough to prove no init followed it.
            assertNull(harness.awaitPacket(LevelInitS2C.class, 100),
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
        try (Harness harness = new Harness(gameDir)) {
            assertTrue(find(harness.levelList(), SECOND).isLocked(), "locked to begin with");

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
        try (Harness harness = new Harness(gameDir)) {
            LevelListS2C.LevelInfo gated = find(harness.levelList(), GATED_DEMO);
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
        try (Harness harness = new Harness(gameDir, false)) {
            harness.wallet(500);
            assertEquals(500, harness.coins(), "the wallet write must be readable back");
        }
        try (Harness harness = new Harness(gameDir, false)) {
            assertEquals(500, harness.coins(), "the funded wallet is what this step starts from");
            harness.send(new UnlockLevelC2S(GATED_DEMO, WORLD));
            LevelListS2C afterBuy = harness.awaitPacket(LevelListS2C.class, 5_000);
            assertNotNull(afterBuy, "the purchase must refresh the level list");
            assertFalse(find(afterBuy, GATED_DEMO).isLocked(), "the purchase opens the level");
            assertEquals(0, harness.coins(),
                    "the price was taken from the wallet");

            // Buying again is a no-op rather than a second charge.
            harness.clear();
            harness.send(new UnlockLevelC2S(GATED_DEMO, WORLD));
            harness.awaitPacket(LevelListS2C.class, 5_000);
            assertEquals(0, harness.coins(),
                    "a level is only ever paid for once");
        }

        // 3. And it is on disk: a new server on the same directory still has it open, with
        // an empty wallet - so the level was bought, not merely unlocked by a restart.
        try (Harness harness = new Harness(gameDir, false)) {
            assertFalse(find(harness.levelList(), GATED_DEMO).isLocked(),
                    "the purchase survived a restart");
            assertEquals(0, harness.coins());
        }
    }

    /**
     * 1-4 pays out the glove.
     *
     * <p>The reward is checked through the same profile write the win uses, because the
     * way this broke was silent: the grant was gated on {@code profile.owns(card)}, which
     * is true for anything that needs no unlocking, so a valid unlock reward could end up
     * recorded as nothing and the award screen fell back to the money bag.
     */
    @Test
    void oneFourGrantsTheGloveOnFirstClear() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-reward-glove");
        Identifier glove = Identifier.parse("pvzce:glove");
        try (Harness harness = new Harness(gameDir)) {
            PlayerProfile before = harness.server().profileFor(WORLD);
            assertFalse(before.owns(glove), "the glove is not a starter card");

            // The payout half of a win, on the world this test made.
            harness.server().awardProfileForTest(
                    Identifier.parse(GATED_DEMO), WORLD, true);

            assertTrue(harness.server().profileFor(WORLD).owns(glove),
                    "1-4's first clear must unlock the glove");
        }
    }

    /** A sandbox world ignores every condition. */
    @Test
    void aSandboxWorldListsEverythingAsOpen() throws Exception {
        Path gameDir = Files.createTempDirectory("pvzce-unlock-sandbox");
        try (Harness harness = new Harness(gameDir)) {
            harness.send(new CreateWorldC2S(WORLD, true));
            harness.clear();
            LevelListS2C list = harness.levelList();
            for (LevelListS2C.LevelInfo info : list.levels()) {
                assertFalse(info.isLocked(), info.id() + " must be open in a sandbox world");
            }
        }
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
    private static final class Harness implements AutoCloseable {
        private final Path gameDir;
        private final PvzceServer server;
        private final Connection.Pair pair;
        private final List<PvzcePacket> packets = new ArrayList<>();

        /** A server on a world that does not exist yet. */
        Harness(Path gameDir) throws Exception {
            this(gameDir, true);
        }

        /**
         * @param createWorld false to attach to a world already on disk. Creating it again
         *                    would rewrite its profile with a starter one - the wallet a
         *                    test had just funded included.
         */
        Harness(Path gameDir, boolean createWorld) throws Exception {
            this.gameDir = gameDir;
            PvzcePackets.register();
            this.pair = Connection.createMemoryPair();
            this.server = new PvzceServer(pair.server(), gameDir,
                    Thread.currentThread().getContextClassLoader());
            pair.client().setListener(packets::add);
            server.start();
            if (createWorld) {
                // Creation is answered with a message and the profile, never with a level
                // list (PvzceServer.createWorld). Waiting for a list here used to burn the
                // full 5_000 ms timeout in every test of this class, silently: the result
                // was discarded, so the suite stayed green and only the clock paid.
                pair.client().send(new CreateWorldC2S(WORLD, false));
                assertNotNull(awaitPacket(ProfileS2C.class, 5_000),
                        "creating a world must be answered");
            }
            clear();
        }

        void send(PvzcePacket packet) {
            pair.client().send(packet);
        }

        LevelListS2C levelList() throws Exception {
            send(new RequestLevelListC2S(WORLD));
            return awaitPacket(LevelListS2C.class, 5_000);
        }

        @SuppressWarnings("unchecked")
        <T extends PvzcePacket> T awaitPacket(Class<T> type, long timeoutMs) throws Exception {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                pair.client().tick();
                synchronized (packets) {
                    PvzcePacket found = packets.stream().filter(type::isInstance).findFirst().orElse(null);
                    if (found != null) {
                        packets.remove(found);
                        return (T) found;
                    }
                }
                Thread.sleep(5);
            }
            return null;
        }

        void clear() {
            synchronized (packets) {
                packets.clear();
            }
        }

        PvzceServer server() {
            return server;
        }

        /** This world's wallet, read from the profile the server holds for it. */
        int coins() {
            return server.profileFor(WORLD).coins();
        }

        /**
         * Puts coins in this world's wallet through the server's own write path.
         *
         * <p>The game has no "give coins" command - coins are banked when a level ends -
         * so a test that needs a funded wallet has to ask the server to save one. Writing
         * {@code profile.dat} directly is not equivalent: the live server caches the
         * profile and writes its copy back on the way out.
         */
        void wallet(int coins) {
            PlayerProfile profile = server.profileFor(WORLD);
            profile.setCoins(coins);
            server.saveProfile(WORLD, profile);
        }

        Path gameDir() {
            return gameDir;
        }

        void waitFor(BooleanSupplier condition, long timeoutMs) throws Exception {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                pair.client().tick();
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(5);
            }
            throw new AssertionError("Timed out waiting for condition");
        }

        /**
         * Stops the server and waits for it to actually finish.
         *
         * <p>{@code stop()} only clears a flag, and the run loop writes the world's profile
         * on its way out. A test that starts the next server immediately can therefore read
         * {@code profile.dat} while the previous one is still writing it - which showed up
         * as a purchase that "did not survive a restart", intermittently and only when the
         * whole suite ran.
         */
        @Override
        public void close() throws Exception {
            try {
                pair.client().close();
            } catch (Exception ignored) {
            }
            server.stop();
            Thread thread = server.thread();
            if (thread != null) {
                thread.join(5_000);
            }
        }
    }
}

package com.pvzce.testutil;

import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.ContinueLevelC2S;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.ProfileS2C;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.RestartLevelC2S;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.PvzceServer;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * A real {@link PvzceServer} on the memory transport, plus a recorder for what it sends.
 *
 * <p>The one server harness in the suite. It replaces five copies of the same thing:
 * {@code SaveSystemTest.TestServer}, the {@code Harness} classes of {@code LevelUnlockFlowTest},
 * {@code BackpackAndCoinsTest} and {@code LevelRestartFlowTest}, and the inline construction in
 * {@code ServerMenuFlowTest}. Each copy had its own spelling of "start the server, wait for the
 * first data load, wait for a packet, stop the server"; the timeouts and the shutdown semantics
 * had already drifted apart.
 */
public final class ServerHarness implements AutoCloseable {
    private final Path gameDir;
    private final PvzceServer server;
    private final Connection.Pair pair;
    private final List<PvzcePacket> packets = new ArrayList<>();

    /**
     * Starts a server and waits until it has loaded its content.
     *
     * <p>The probe request is what makes "the server is ready" observable: content is loaded on
     * the server thread, so without it every test's first packet races the load.
     */
    public static ServerHarness create(Path gameDir) throws Exception {
        ServerHarness harness = new ServerHarness(gameDir);
        harness.awaitContentLoad();
        return harness;
    }

    /** Starts a server and creates {@code world} on it (unlocked/sandbox when asked). */
    public static ServerHarness createWithWorld(Path gameDir, String world, boolean unlockAll) throws Exception {
        ServerHarness harness = new ServerHarness(gameDir);
        harness.createWorld(world, unlockAll);
        return harness;
    }

    private ServerHarness(Path gameDir) throws Exception {
        this.gameDir = gameDir;
        PvzcePackets.register();
        this.pair = Connection.createMemoryPair();
        this.server = new PvzceServer(pair.server(), gameDir, Thread.currentThread().getContextClassLoader());
        pair.client().setListener(packets::add);
        server.start();
    }

    /** Waits for the first content load by asking for a level list the test does not care about. */
    public void awaitContentLoad() throws Exception {
        send(new RequestLevelListC2S("__probe__"));
        awaitPacket(LevelListS2C.class, 5_000);
        clear();
    }

    public PvzceServer server() {
        return server;
    }

    public Path gameDir() {
        return gameDir;
    }

    public Connection.Pair pair() {
        return pair;
    }

    /** Every packet the server sent since the last {@link #clear()}. */
    public List<PvzcePacket> packets() {
        return packets;
    }

    public void send(PvzcePacket packet) {
        pair.client().send(packet);
    }

    public void clear() {
        packets.clear();
    }

    /** This world's level list, as the menu would ask for it. */
    public LevelListS2C levelList(String world) throws Exception {
        send(new RequestLevelListC2S(world));
        return awaitPacket(LevelListS2C.class, 5_000);
    }

    /**
     * Sends the packet a menu sends to start a level.
     *
     * @param restart true = 丢掉存档从头开始, false = 继续保存的那一局
     */
    public void requestLevel(String levelId, String world, boolean restart) {
        send(restart
                ? new RestartLevelC2S(levelId, world, List.of())
                : new ContinueLevelC2S(levelId, world));
    }

    /**
     * Creates {@code world} and waits for the profile answer.
     *
     * <p>Creation is answered with a message and the profile, never with a level list. Waiting
     * for a list here burns the full timeout in every test of a class, silently: the result is
     * discarded, so the suite stays green and only the clock pays.
     */
    public void createWorld(String world, boolean unlockAll) throws Exception {
        send(new CreateWorldC2S(world, unlockAll));
        awaitPacket(ProfileS2C.class, 5_000);
        clear();
    }

    /** The latest packet of {@code type} received so far, or {@code null}. */
    public <T extends PvzcePacket> T packet(Class<T> type) {
        return packets.stream().filter(type::isInstance).map(type::cast).reduce((first, second) -> second)
                .orElse(null);
    }

    /**
     * Waits up to {@code timeoutMs} for a packet of {@code type}, returning the latest one or
     * {@code null}. Kept in the recorded list.
     *
     * <p>For the "this must NOT happen" half of a protocol: a refusal is asserted by waiting a
     * short while and finding no {@code LevelInitS2C} behind it.
     */
    public <T extends PvzcePacket> T awaitPacketOrNull(Class<T> type, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            pair.client().tick();
            T found = packet(type);
            if (found != null) {
                return found;
            }
            Thread.sleep(5);
        }
        return null;
    }

    /**
     * Waits for a packet of {@code type} and returns the latest one. Kept in the recorded list.
     *
     * @throws AssertionError if none arrives before {@code timeoutMs}
     */
    public <T extends PvzcePacket> T awaitPacket(Class<T> type, long timeoutMs) throws Exception {
        T found = awaitPacketOrNull(type, timeoutMs);
        if (found == null) {
            throw new AssertionError("Timed out after " + timeoutMs + "ms waiting for "
                    + type.getSimpleName() + state());
        }
        return found;
    }

    /** Waits for a packet of {@code type}, removes it from the recorded list, and returns it. */
    public <T extends PvzcePacket> T takePacket(Class<T> type, long timeoutMs) throws Exception {
        T found = awaitPacket(type, timeoutMs);
        packets.remove(found);
        return found;
    }

    public void waitFor(Predicate<PvzcePacket> condition, long timeoutMs) throws Exception {
        waitFor(condition, timeoutMs, "waiting for a packet");
    }

    /**
     * The same wait, with the caller's own words for what never arrived.
     *
     * <p>Use this rather than {@code waitForCondition(() -> server().level()...)}: the level's
     * collections belong to the server thread, and a condition that reads them can stay false for
     * a tick after the thing it waits for has happened.
     */
    public void waitFor(Predicate<PvzcePacket> condition, long timeoutMs, String what) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            pair.client().tick();
            if (packets.stream().anyMatch(condition)) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Timed out after " + timeoutMs + "ms " + what + state());
    }

    public void waitForCondition(BooleanSupplier condition, long timeoutMs) throws Exception {
        waitForCondition(condition, timeoutMs, "waiting for the server");
    }

    /**
     * The same wait, with the caller's own words for what never happened.
     *
     * <p>Worth passing when the condition is a precondition rather than the thing under test:
     * "the level's wave never produced a zombie" points at the fixture, while "timed out
     * waiting for the server" leaves the reader to guess which of five waits failed.
     */
    public void waitForCondition(BooleanSupplier condition, long timeoutMs, String what) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            pair.client().tick();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Timed out after " + timeoutMs + "ms " + what + state());
    }

    /** The state a timed-out wait wants to show: who is connected, what level is open, what came. */
    private String state() {
        return " | clientConnected=" + pair.client().isConnected()
                + " | serverConnected=" + pair.server().isConnected()
                + " | clientDisconnect=" + pair.client().disconnectReason()
                + " | serverDisconnect=" + pair.server().disconnectReason()
                + " | level=" + (server.level() == null ? "null" : server.level().gameState())
                + " | packets=" + packets.stream().map(packet -> packet.getClass().getSimpleName()).toList();
    }

    public void waitForFile(Path file, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for file " + file);
    }

    public void waitForDeleted(Path path, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (!Files.exists(path)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for deletion of " + path);
    }

    /**
     * Clears the field with ash damage until the plant team has won.
     *
     * <p>Two tests pay out on a kill and neither is about combat, so both nuked the board
     * instead of shooting - and both wrote the same loop, waiting on the level's own counters
     * while damaging from the test thread. That read is the one thing the server's memory model
     * does not offer: the entity list is the server thread's, and a count can stay stale for a
     * whole tick, which is exactly how "a dying zombie drops a coin" used to flake.
     *
     * <p>What this waits on instead is what a client sees: the level's own {@code GameStateS2C}
     * saying the plant team won. A win requires kills, so the payout the callers assert on is
     * about a real one - and if no zombie ever arrived, the timeout says so, because that is the
     * failure whose symptom ("the drop was NaN") points at nothing.
     *
     * <p>The damage call still happens from the test thread, which is a separate wart this helper
     * does not pretend to fix: the tests need the board cleared, and the server has no command
     * that clears it.
     */
    public void winByClearingTheField() throws Exception {
        long deadline = System.nanoTime() + 15_000L * 1_000_000L;
        while (System.nanoTime() < deadline) {
            LevelServer level = server.level();
            if (level != null && level.gameState().equals(GameStateS2C.RUNNING)) {
                level.damageArea(ZombieEntity.damageType(PvzceIds.DAMAGE_ASH), 0F, 0F, 500F, 100_000,
                        level.team(PvzceIds.PLANT_TEAM));
            }
            if (plantTeamWon()) {
                return;
            }
            pair.client().tick();
            Thread.sleep(5);
        }
        throw new AssertionError("Timed out after 15000ms "
                + (sawZombie() ? "waiting for the plant team to win" : "the level's wave never "
                        + "produced a zombie, so there is no kill to pay out") + state());
    }

    /** Whether the client has been told the plant team won. */
    private boolean plantTeamWon() {
        return packets().stream().anyMatch(p -> p instanceof GameStateS2C state
                && GameStateS2C.WON.equals(state.state())
                && PvzceIds.PLANT_TEAM.toString().equals(state.winTeamId()));
    }

    /** Whether any zombie was ever streamed to the client, wherever the run has got to. */
    private boolean sawZombie() {
        return packets().stream().anyMatch(p -> p instanceof EntitySpawnS2C spawn
                && "zombie".equals(spawn.entityKind()));
    }

    /**
     * Stops the server and waits for it to actually finish.
     *
     * <p>{@code stop()} only clears a flag, and the run loop writes the world's profile on its
     * way out. A test that starts the next server immediately can therefore read
     * {@code profile.dat} while the previous one is still writing it - which showed up as a
     * purchase that "did not survive a restart", intermittently and only when the whole suite
     * ran.
     */
    @Override
    public void close() throws Exception {
        try {
            pair.client().close();
        } catch (Exception ignored) {
            // The point of the close is to stop the server loop, not to keep the transport tidy.
        }
        server.stop();
        Thread thread = server.thread();
        if (thread != null) {
            thread.join(5_000);
        }
    }
}

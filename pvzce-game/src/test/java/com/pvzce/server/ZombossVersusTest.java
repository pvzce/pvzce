package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.zombie.BungeeCapability;
import com.pvzce.common.capability.zombie.ZombossCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.ConveyorMechanic;
import com.pvzce.common.level.mechanic.ZombossMechanic;
import com.pvzce.common.level.mechanic.ZombossMechanic.Pilot;
import com.pvzce.common.level.mechanic.ZombossMechanic.PilotMove;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static com.pvzce.common.PvzceConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Human versus on the boss map: both sides deal from their own belt, and the zombie side flies the machine. */
class ZombossVersusTest {
    private static final Identifier PLANT = PvzceIds.PLANT_TEAM, ZOMBIE = PvzceIds.ZOMBIE_TEAM;
    private static final String GUEST = "guest";
    private static final LevelServer.ServerBridge SILENT = packet -> {};
    private static LevelDef def;

    @BeforeAll static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/versus/zomboss"));
        assertNotNull(def);
    }

    /** The match as a versus room seats it: the host on one side, the guest's seat on the other. */
    private static LevelServer match(Identifier host) {
        LevelServer level = new LevelServer(def, List.of(), LevelServer.SeedContext.all(def), null, null, host, true);
        Identifier guest = host.equals(PLANT) ? ZOMBIE : PLANT;
        level.addParticipant(GUEST, guest, level.versusData().cardsFor(guest));
        level.flushPending(SILENT);
        return level;
    }

    /** One room tick: the level, then the guest's own clocks. */
    private static void tick(LevelServer level, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(SILENT);
            level.tickParticipant(GUEST, SILENT);
        }
    }

    private static List<SlotInfo> bar(LevelServer level, Identifier side) {
        if (side.equals(level.humanTeamId())) return level.slotInfos();
        List<SlotInfo> bar = new ArrayList<>();
        level.asParticipant(GUEST, SILENT, () -> bar.addAll(level.slotInfos()));
        return bar;
    }

    private static List<String> paths(List<SlotInfo> bar) {
        return bar.stream().map(slot -> Identifier.parse(slot.defId()).path()).toList();
    }

    private static ZombieEntity boss(LevelServer level) { return ZombossMechanic.boss(level); }
    private static ZombossCapability cap(LevelServer level) { return boss(level).capability(ZombossCapability.class); }

    /** Puts the machine in a phase with every clock of the pilot set, as a resumed save would. */
    private static void pose(LevelServer level, String phase, int recharge, int headClock) {
        CompoundTag tag = new CompoundTag();
        cap(level).save(tag);
        tag.putString("phase", phase); tag.putInt("timer", 1); tag.putInt("age", 100);
        tag.putInt("stompLeft", recharge); tag.putInt("rvLeft", recharge); tag.putInt("bungeeLeft", recharge);
        tag.putInt("headClock", headClock); tag.putInt("headHold", 0); tag.putInt("spat", 0);
        cap(level).load(tag);
        if (phase.startsWith("head_")) {
            boss(level).setCellX(ZOMBOSS_HEAD_X);
            boss(level).setCellY(ZOMBOSS_HEAD_Y);
        }
    }

    private static boolean command(LevelServer level, String move, int x, int y, List<PvzcePacket> replies) {
        return level.commandZomboss(replies::add, move, x, y);
    }

    private static List<String> refusals(List<PvzcePacket> replies) {
        return replies.stream().filter(p -> p instanceof ServerMessageS2C)
                .map(p -> ((ServerMessageS2C) p).message()).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pvzce:plant_team", "pvzce:zombie_team"})
    void eachSideDealsFromItsOwnBeltWhicheverSideHosts(String hostTeam) {
        LevelServer level = match(Identifier.parse(hostTeam));
        // The plant side opens with the boss fight's four cards and keeps its shovel beside the belt.
        assertEquals(List.of("cabbage_pult", "jalapeno", "cabbage_pult", "ice_shroom", "shovel"),
                paths(bar(level, PLANT)));
        List<SlotInfo> zombies = bar(level, ZOMBIE);
        assertEquals(level.versusData().zombieBelt().orElseThrow().initialCards(), zombies.size());
        assertTrue(zombies.stream().allMatch(slot -> slot.kind().equals("zombie")
                && slot.costSun() == SlotInfo.NO_PRICE), zombies.toString());

        // Each seat's belt streams to that seat: the host's on the level's tick, the guest's on its own.
        // Past the plant belt's opening delay, so that both belts have dealt a card.
        List<PvzcePacket> hostPackets = new ArrayList<>(), guestPackets = new ArrayList<>();
        for (int i = 0; i < ZOMBOSS_BELT_OPENING_TICKS; i++) {
            level.tick(hostPackets::add);
            level.tickParticipant(GUEST, guestPackets::add);
        }
        var zombieBelt = level.versusData().zombieBelt().orElseThrow();
        assertEquals(zombieBelt.initialCards() + ZOMBOSS_BELT_OPENING_TICKS / zombieBelt.intervalTicks(),
                bar(level, ZOMBIE).size(), "the zombie belt deals on its own clock");
        assertEquals(6, bar(level, PLANT).size(), "the plant belt deals once its opening delay is over");
        Identifier guest = Identifier.parse(hostTeam).equals(PLANT) ? ZOMBIE : PLANT;
        assertEquals(Set.of(hostTeam.equals("pvzce:plant_team") ? "plant" : "zombie"), barKinds(hostPackets));
        assertEquals(Set.of(guest.equals(PLANT) ? "plant" : "zombie"), barKinds(guestPackets));
    }

    /** The card kinds a set of belt syncs carried, tools left out. */
    private static Set<String> barKinds(List<PvzcePacket> packets) {
        Set<String> kinds = new HashSet<>();
        for (PvzcePacket packet : packets) {
            if (packet instanceof MechanicSyncS2C sync && sync.mechanic().equals(PvzceIds.MECHANIC_CONVEYOR)) {
                var buf = sync.payloadBuffer();
                try {
                    ConveyorMechanic.BarState.CODEC.decode(buf).cards().stream()
                            .filter(card -> !card.kind().equals("tool")).forEach(card -> kinds.add(card.kind()));
                } finally { buf.delegate().release(); }
            }
        }
        return kinds;
    }

    @Test void aZombieCardLeavesTheBeltForFreeAndOnlyInsideTheZombieZone() {
        LevelServer level = match(ZOMBIE);
        SlotInfo card = bar(level, ZOMBIE).getFirst();
        int sun = level.team(ZOMBIE).resourcesOf(PvzceIds.SUN);
        assertFalse(level.placeZombie(SILENT, card.index(), 3, 2), "the plants' columns are not the zombie zone");
        assertTrue(level.placeZombie(SILENT, card.index(), 6, 2));
        assertTrue(bar(level, ZOMBIE).stream().noneMatch(slot -> slot.index() == card.index()),
                "the card left the belt");
        assertFalse(level.placeZombie(SILENT, card.index(), 7, 2), "a card that left the belt cannot be placed twice");
        assertEquals(sun, level.team(ZOMBIE).resourcesOf(PvzceIds.SUN), "a belt card costs nothing");
        level.flushPending(SILENT);
        assertTrue(level.entities().stream().anyMatch(e -> e instanceof ZombieEntity z
                && z.defId().toString().equals(card.defId()) && z.cellX() == 6.5F && z.gridY() == 2));
    }

    @Test void theMachineWaitsForItsPilotUntilTheHeadsDeadlineLowersIt() {
        LevelServer level = match(PLANT);
        Set<String> phases = new HashSet<>();
        for (int i = 0; i < ZOMBOSS_PILOT_HEAD_FORCED_TICKS - 1; i++) {
            tick(level, 1);
            phases.add(cap(level).status(boss(level)).phase());
        }
        assertEquals(Set.of("enter", "idle"), phases, "no attack of its own");
        assertEquals(1, level.aliveZombieCount(), "and no summons: the zombie side's belt deals the zombies");
        tick(level, 1);
        assertEquals("head_enter", cap(level).status(boss(level)).phase(), "the head is forced down at its deadline");
        assertTrue(cap(level).exposed());
    }

    @Test void theHeadRechargesBeforeItLowersAndStaysDownBeforeItRises() {
        LevelServer level = match(ZOMBIE);
        List<PvzcePacket> replies = new ArrayList<>();
        tick(level, ZOMBOSS_PILOT_HEAD_COOLDOWN_TICKS - 1);
        assertFalse(command(level, "lower", 0, 0, replies));
        assertEquals(List.of("gui.pvzce.zomboss.refused.cooldown"), refusals(replies));
        tick(level, 1);
        assertTrue(command(level, "lower", 0, 0, replies), "the recharge is over");
        assertTrue(cap(level).exposed());

        replies.clear();
        tick(level, ZOMBOSS_HEAD_ENTER_TICKS);
        assertEquals("head_down", cap(level).status(boss(level)).phase());
        assertFalse(command(level, "raise", 0, 0, replies));
        assertEquals(List.of("gui.pvzce.zomboss.refused.hold"), refusals(replies));
        tick(level, ZOMBOSS_PILOT_HEAD_HOLD_TICKS - ZOMBOSS_HEAD_ENTER_TICKS - 1);
        assertFalse(command(level, "raise", 0, 0, replies), "one tick short of the minimum time down");
        tick(level, 1);
        assertTrue(command(level, "raise", 0, 0, replies));
        assertFalse(cap(level).exposed(), "a rising head takes no damage");

        tick(level, ZOMBOSS_HEAD_LEAVE_TICKS);
        assertEquals("idle", cap(level).status(boss(level)).phase());
        Pilot pilot = cap(level).pilot();
        assertEquals(ZOMBOSS_PILOT_HEAD_FORCED_TICKS, pilot.forced(), "the deadline restarts with the head up");
        assertEquals(ZOMBOSS_PILOT_HEAD_COOLDOWN_TICKS, pilot.lower(), "and so does the recharge");
    }

    @Test void aLoweringSpitsOneBallIntoTheRowThePilotChose() {
        LevelServer level = match(ZOMBIE);
        pose(level, "head_down", 0, 10_000);
        List<PvzcePacket> replies = new ArrayList<>();
        assertTrue(command(level, "ice", 0, 3, replies));
        assertEquals(2, cap(level).status(boss(level)).spitType());
        tick(level, ZOMBOSS_SPIT_TICKS);
        var status = cap(level).status(boss(level));
        assertEquals("head_down", status.phase(), "the head stays down after the spit");
        assertEquals(2, status.ballType());
        assertEquals(3, status.ballRow());
        assertFalse(command(level, "fire", 0, 1, replies));
        assertEquals(List.of("gui.pvzce.zomboss.refused.spat"), refusals(replies));
        assertFalse(command(level, "stomp", 0, 1, replies), "the head being down rules out the attacks");
    }

    @Test void attacksLandWhereTheyWereAimedAndThenRecharge() {
        LevelServer level = match(ZOMBIE);
        pose(level, "idle", 0, 10_000);
        PlantEntity inside = pot(level, 3, 1), corner = pot(level, 5, 2), right = pot(level, 6, 1);
        List<PvzcePacket> replies = new ArrayList<>();
        // The camper is centred on the clicked column and takes the clicked row and the one below.
        assertTrue(command(level, "rv", 4, 1, replies));
        tick(level, ZOMBOSS_RV_TICKS);
        assertTrue(inside.isRemoved() && corner.isRemoved(), "the 3 x 2 block around (4, 1) is crushed");
        assertFalse(right.isRemoved(), "the column past the footprint stands");
        assertFalse(level.plantsAt(2, 1).isEmpty(), "and so does the column before it");
        assertFalse(command(level, "rv", 4, 1, replies));
        assertEquals(List.of("gui.pvzce.zomboss.refused.cooldown"), refusals(replies));
        assertEquals(ZOMBOSS_PILOT_RV_COOLDOWN_TICKS - ZOMBOSS_RV_TICKS, cap(level).pilot().rv());

        // Three thieves for the clicked cell and its neighbours in that row, whatever was clicked last.
        assertTrue(command(level, "bungee", 0, 2, replies));
        tick(level, ZOMBOSS_BUNGEE_TICKS);
        List<ZombossMechanic.BungeeRope> ropes = level.entities().stream()
                .filter(e -> e instanceof ZombieEntity z && z.capability(BungeeCapability.class) != null)
                .map(e -> ((ZombieEntity) e).capability(BungeeCapability.class).bossRope((ZombieEntity) e)).toList();
        assertEquals(List.of(0, 1, 2), ropes.stream().map(ZombossMechanic.BungeeRope::column).toList());
        assertTrue(ropes.stream().allMatch(rope -> rope.row() == 2), ropes.toString());
    }

    @Test void onlyTheZombieSeatFliesTheMachine() {
        LevelServer level = match(PLANT);
        tick(level, ZOMBOSS_PILOT_HEAD_COOLDOWN_TICKS);
        List<PvzcePacket> replies = new ArrayList<>();
        assertFalse(command(level, "lower", 0, 0, replies));
        assertEquals(List.of("gui.pvzce.zomboss.refused.side"), refusals(replies));
        boolean[] lowered = new boolean[1];
        level.asParticipant(GUEST, replies::add, () -> lowered[0] = command(level, "lower", 0, 0, replies));
        assertTrue(lowered[0]);
        assertEquals("head_enter", cap(level).status(boss(level)).phase());
    }

    /**
     * Whole matches between two scripted sides, printed: how long one lasts, who wins, how often and
     * how long the head is down, and what each side put on the lawn. A measurement of the numbers,
     * not a balance assertion; run it with {@code -Ppvzce.smoke=pvzce.zombossSoak=true}.
     *
     * <p>The plant side pots the left five columns, puts pults on empty pots, holds the ice-shroom for
     * a fireball or a lowered head and the jalapeno for an iceball or a crowded row. The pilot never
     * lowers the head by choice, spits at the row with the most plants, raises the head as soon as it
     * may, attacks whenever a move is ready, and drops every zombie at the right edge of the quietest row.
     */
    @Test void aScriptedMatchIsMeasured() {
        assumeTrue(Boolean.getBoolean("pvzce.zombossSoak"), "measurement only");
        for (long seed = 1; seed <= 3; seed++) {
            LevelServer level = match(ZOMBIE);
            level.seedRandom(seed);
            Random pilotRandom = new Random(seed);
            Map<String, Integer> counts = new TreeMap<>();
            int ticks = 0, exposed = 0;
            String previous = "";
            while (ticks < 20 * 60 * TICKS_PER_SECOND && "running".equals(level.gameState())) {
                tick(level, 1);
                ticks++;
                if (boss(level) == null || !boss(level).isAlive()) continue;
                String phase = cap(level).status(boss(level)).phase();
                if (phase.equals("head_enter") && !previous.equals("head_enter")) {
                    counts.merge("lowerings", 1, Integer::sum);
                }
                previous = phase;
                if (cap(level).exposed()) exposed++;
                if (ticks % 30 == 0) {
                    playPlants(level, counts);
                    playZombies(level, pilotRandom, counts);
                }
            }
            ZombieEntity boss = boss(level);
            int seconds = ticks / TICKS_PER_SECOND;
            System.out.println("ZOMBOSS-VERSUS-SOAK seed=" + seed + " time=" + seconds / 60 + ":"
                    + String.format("%02d", seconds % 60) + " state=" + level.gameState()
                    + " winner=" + level.winner()
                    + " bossHealth=" + (boss == null ? 0 : Math.max(0, boss.health()))
                    + " exposed=" + Math.round(100F * exposed / ticks) + "% " + counts);
        }
    }

    private static void playPlants(LevelServer level, Map<String, Integer> counts) {
        level.asParticipant(GUEST, SILENT, () -> {
            var status = cap(level).status(boss(level));
            for (SlotInfo card : level.slotInfos()) {
                if (!card.kind().equals("plant")) continue;
                String id = Identifier.parse(card.defId()).path();
                int row = id.equals("jalapeno") ? (status.ballType() == 2 ? status.ballRow() : crowdedRow(level)) : -1;
                boolean wanted = switch (id) {
                    case "ice_shroom" -> status.ballType() == 1 || cap(level).exposed();
                    case "jalapeno" -> row >= 0;
                    default -> true;
                };
                if (!wanted) continue;
                int columns = id.equals("flower_pot") || id.endsWith("_pult") ? 5 : level.width();
                search:
                for (int x = 0; x < columns; x++) {
                    for (int y = 0; y < level.height(); y++) {
                        if (row >= 0 && y != row) continue;
                        if (level.placePlant(SILENT, card.index(), x, y)) {
                            counts.merge("planted." + id, 1, Integer::sum);
                            break search;
                        }
                    }
                }
            }
        });
    }

    /** The row with at least two living zombies and the most of them, or -1. */
    private static int crowdedRow(LevelServer level) {
        int[] zombies = new int[level.height()];
        for (var entity : level.entities()) {
            if (entity instanceof ZombieEntity z && z.isAlive() && z.capability(ZombossCapability.class) == null
                    && z.gridY() >= 0 && z.gridY() < zombies.length) zombies[z.gridY()]++;
        }
        int best = -1;
        for (int y = 0; y < zombies.length; y++) {
            if (zombies[y] >= 2 && (best < 0 || zombies[y] > zombies[best])) best = y;
        }
        return best;
    }

    private static void playZombies(LevelServer level, Random random, Map<String, Integer> counts) {
        int[] plants = new int[level.height()];
        int[] front = new int[level.height()];
        for (var entity : level.entities()) {
            if (entity instanceof PlantEntity p && !p.isRemoved() && !LevelServer.isCarrier(p)) {
                plants[p.gridY()]++;
                if (p.gridX() >= ZOMBOSS_STOMP_COLUMN) front[p.gridY()]++;
            }
        }
        int quietest = 0, busiest = 0;
        for (int y = 1; y < plants.length; y++) {
            if (plants[y] < plants[quietest]) quietest = y;
            if (plants[y] > plants[busiest]) busiest = y;
        }
        for (SlotInfo card : level.slotInfos()) {
            if (card.kind().equals("zombie") && level.placeZombie(SILENT, card.index(), level.width() - 1, quietest)) {
                counts.merge("zombies", 1, Integer::sum);
            }
        }
        var status = cap(level).status(boss(level)).with(List.of(), cap(level).pilot());
        boolean stompable = front[Math.min(busiest, level.height() - 2)] > 0;
        String move = status.refusal(PilotMove.FIRE) == null ? (random.nextBoolean() ? "fire" : "ice")
                : status.refusal(PilotMove.RAISE) == null ? "raise"
                : status.refusal(PilotMove.STOMP) == null && stompable ? "stomp"
                : status.refusal(PilotMove.RV) == null ? "rv"
                : status.refusal(PilotMove.BUNGEE) == null ? "bungee" : null;
        if (move != null && level.commandZomboss(SILENT, move, 2, Math.min(busiest, level.height() - 1))) {
            counts.merge("move." + move, 1, Integer::sum);
        }
    }

    private static PlantEntity pot(LevelServer level, int x, int y) {
        PlantEntity pot = level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("flower_pot")),
                level.team(PLANT), x, y);
        level.flushPending(SILENT);
        return pot;
    }
}

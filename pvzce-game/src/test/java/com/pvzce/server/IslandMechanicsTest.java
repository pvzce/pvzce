package com.pvzce.server;

import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PreparationData;
import com.pvzce.api.content.SceneSurfaceDef;
import com.pvzce.api.content.SurfaceProfile;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.OutpostPlan;
import com.pvzce.common.level.mechanic.OutpostsMechanic;
import com.pvzce.common.level.mechanic.StagePlan;
import com.pvzce.common.level.mechanic.StagesMechanic;
import com.pvzce.common.level.mechanic.SurfaceLinksData;
import com.pvzce.common.level.mechanic.SurfaceLinksMechanic;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.MusicEventS2C;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.LevelValidator;
import com.pvzce.common.tag.TestContent;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real movement, choices, occupation and saved queues exercise the composed engine contract. */
class IslandMechanicsTest {
    private static final String UPPER = "pvzce:test_bridge";
    private static final String GROUND = SceneBoard.DEFAULT_SURFACE;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef fixture(List<TypedMechanic> mechanics, List<WaveDef> waves) {
        LevelDef base = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_9"));
        SceneSurfaceDef upper = new SceneSurfaceDef(Identifier.parse(UPPER), "bridge", SurfaceProfile.flat(1.4F),
                0.2F, Map.of(PvzceIds.GRASS, IntStream.range(0, 45)
                        .mapToObj(i -> i % 9 + "," + i / 9).toList()));
        return TestLevels.copy(base).mechanics(mechanics).waves(waves).initialEntities(List.of())
                .slots(List.of(PvzceIds.SUN, PvzceIds.id("shovel"))).initialSun(10000)
                .surfaces(List.of(upper)).build();
    }

    private static WaveDef emptyWave() {
        return new WaveDef(WaveDef.WaveType.SMALL, 1, 0, List.of());
    }

    private static TypedMechanic outposts(int captureTicks, boolean goal) {
        return new TypedMechanic(PvzceIds.MECHANIC_OUTPOSTS, new OutpostPlan(List.of(
                new OutpostPlan.Point("supply", GROUND, 4, 1, captureTicks, 250, 0, 0, 1.5F, 0, 6),
                new OutpostPlan.Point("artillery", UPPER, 5, 3, captureTicks, 0, 1, 1800, 1.5F, 0, 7)),
                goal ? Optional.of(new OutpostPlan.Goal(GROUND, 8, 0)) : Optional.empty(), 3));
    }

    private static TypedMechanic stages() {
        return new TypedMechanic(PvzceIds.MECHANIC_STAGES, new StagePlan(List.of(
                new StagePlan.Phase("one", 1, 0, List.of(cue("watery_graves"))),
                new StagePlan.Phase("two", 2, 400, List.of(cue("ultimate_battle"))))));
    }

    private static LevelDef.MusicCue cue(String name) {
        return new LevelDef.MusicCue(LevelDef.MusicCue.Trigger.LEVEL_START, 0, "background",
                Optional.of(PvzceIds.id("music/" + name)), true, false, 0.75F, 3F, false);
    }

    private static PlantEntity plant(LevelServer level, String id, int x, int y, String surface) {
        PlantEntity plant = level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id(id)),
                level.team(PvzceIds.PLANT_TEAM), x, y, surface);
        level.flushPending(p -> { });
        return plant;
    }

    private static List<PvzcePacket> tick(LevelServer level, int count) {
        List<PvzcePacket> packets = new ArrayList<>();
        level.flushPending(packets::add);
        for (int i = 0; i < count; i++) {
            level.tick(packets::add);
        }
        return packets;
    }

    @Test
    void directedRampChangesSurfaceAndDoesNotImmediatelyBounceBack() {
        SurfaceLinksData links = new SurfaceLinksData(List.of(
                new SurfaceLinksData.Connection(UPPER, GROUND, 2, 2, -1),
                new SurfaceLinksData.Connection(GROUND, UPPER, 2, 2, 1)));
        LevelServer level = new LevelServer(fixture(List.of(new TypedMechanic(PvzceIds.MECHANIC_SURFACE_LINKS,
                links)), List.of()), 11L);
        ZombieEntity walker = level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(PvzceIds.ZOMBIE_TEAM),
                2.501F, 2, 1F, UPPER);
        tick(level, 10);
        assertEquals(GROUND, walker.surfaceId());
        assertEquals(level.surfaceHeight(GROUND, walker.cellX(), walker.cellY()), walker.height());
        assertTrue(walker.cellX() < 2.5F);
    }

    @Test
    void missingDestinationAndAirborneBodyCannotUseARamp() {
        SurfaceLinksData links = new SurfaceLinksData(List.of(
                new SurfaceLinksData.Connection(UPPER, "pvzce:missing", 2, 2, -1)));
        LevelDef def = fixture(List.of(new TypedMechanic(PvzceIds.MECHANIC_SURFACE_LINKS, links)), List.of());
        assertFalse(LevelMechanics.validate(def).isEmpty());
        LevelServer level = new LevelServer(def, 12L);
        ZombieEntity walker = level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(PvzceIds.ZOMBIE_TEAM),
                2.4F, 2, 1F, UPPER);
        SurfaceLinksMechanic.cross(level, walker, 2.6F, UPPER);
        assertEquals(UPPER, walker.surfaceId());
        LevelServer valid = new LevelServer(fixture(List.of(new TypedMechanic(PvzceIds.MECHANIC_SURFACE_LINKS,
                new SurfaceLinksData(List.of(new SurfaceLinksData.Connection(UPPER, GROUND, 2, 2, -1))))), List.of()), 12L);
        ZombieEntity airborne = valid.spawnZombie(PvzceIds.id("balloon_zombie"), valid.team(PvzceIds.ZOMBIE_TEAM),
                2.4F, 2, 1F, UPPER);
        airborne.setHeight(3F);
        SurfaceLinksMechanic.cross(valid, airborne, 2.6F, UPPER);
        assertEquals(UPPER, airborne.surfaceId());
    }

    @Test
    void upperWaveQueueKeepsItsSurfaceAfterSavingBeforeTheSecondSpawn() {
        WaveDef wave = WaveDef.declaringSpawnInterval(WaveDef.WaveType.FINAL, 1, 0, List.of(
                new WaveDef.Entry(PvzceIds.id("basic_zombie"), 2, List.of(2), 1F, UPPER)), 90);
        LevelDef def = fixture(List.of(), List.of(wave));
        LevelServer level = new LevelServer(def, 13L);
        tick(level, 5);
        assertEquals(1, level.hostileZombieCount());
        LevelServer resumed = new LevelServer(def, 13L);
        resumed.restore(level.save());
        tick(resumed, 95);
        assertEquals(2, resumed.hostileZombieCount());
        assertTrue(resumed.entities().stream().filter(e -> e instanceof ZombieEntity)
                .allMatch(e -> e.surfaceId().equals(UPPER)));
    }

    @Test
    void captureUnlocksOnlyItsSurfaceAndCannotBeFarmedByRemovingItsAnchor() {
        LevelServer level = new LevelServer(fixture(List.of(outposts(3, false)), List.of()), 14L);
        PlantEntity anchor = plant(level, "wall_nut", 4, 1, GROUND);
        int sun = level.team(PvzceIds.PLANT_TEAM).resources().getOrDefault(PvzceIds.SUN, 0);
        tick(level, 3);
        assertTrue(OutpostsMechanic.status(level).points().getFirst().owned());
        assertEquals(sun + 250, level.team(PvzceIds.PLANT_TEAM).resources().getOrDefault(PvzceIds.SUN, 0));
        assertTrue(level.canPlacePlant(anchor.def(), 6, 1, GROUND));
        assertFalse(level.canPlacePlant(anchor.def(), 6, 1, UPPER));
        anchor.remove();
        tick(level, 1);
        assertFalse(OutpostsMechanic.status(level).points().getFirst().owned());
        assertFalse(level.canPlacePlant(anchor.def(), 6, 1, GROUND));
        plant(level, "wall_nut", 4, 1, GROUND);
        tick(level, 3);
        assertEquals(sun + 250, level.team(PvzceIds.PLANT_TEAM).resources().getOrDefault(PvzceIds.SUN, 0));
    }

    @Test
    void enemyOnAnotherSurfaceDoesNotContestButASameSurfaceEnemyPausesCapture() {
        LevelServer level = new LevelServer(fixture(List.of(outposts(3, false)), List.of()), 15L);
        plant(level, "wall_nut", 4, 1, GROUND);
        level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(PvzceIds.ZOMBIE_TEAM), 4.8F, 1, 1F, UPPER);
        tick(level, 2);
        assertEquals(2, OutpostsMechanic.status(level).points().getFirst().progress());
        level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(PvzceIds.ZOMBIE_TEAM), 5F, 1, 1F, GROUND);
        tick(level, 2);
        assertEquals(2, OutpostsMechanic.status(level).points().getFirst().progress());
    }

    @Test
    void occupationProgressAndSpentArtillerySurviveResumeWithoutGivingRewardsAgain() {
        LevelDef def = fixture(List.of(outposts(3, false)), List.of());
        LevelServer level = new LevelServer(def, 16L);
        plant(level, "wall_nut", 5, 3, UPPER);
        tick(level, 2);
        LevelServer resumed = new LevelServer(def, 16L);
        resumed.restore(level.save());
        assertEquals(2, OutpostsMechanic.status(resumed).points().get(1).progress());
        tick(resumed, 1);
        assertTrue(resumed.fireOutpost(p -> { }, 1, 7, 3, UPPER));
        assertFalse(resumed.fireOutpost(p -> { }, 1, 7, 3, UPPER));
        LevelServer again = new LevelServer(def, 16L);
        again.restore(resumed.save());
        assertEquals(0, OutpostsMechanic.status(again).points().get(1).charges());
    }

    @Test
    void stageChoiceFreezesTheClockAndResumeRejectsAStalePhase() {
        LevelDef def = fixture(List.of(new TypedMechanic(PvzceIds.MECHANIC_PREPARATION, PreparationData.MANUAL),
                stages()), List.of(emptyWave(), emptyWave()));
        LevelServer level = new LevelServer(def, List.of(PvzceIds.id("cherry_bomb")), 17L);
        level.beginWaves();
        List<PvzcePacket> packets = tick(level, 5);
        assertTrue(StagesMechanic.isChoosing(level));
        int clock = level.tickCount();
        tick(level, 100);
        assertEquals(clock, level.tickCount());
        LevelServer resumed = new LevelServer(def, List.of(PvzceIds.id("cherry_bomb")), 17L);
        resumed.restore(level.save());
        assertTrue(StagesMechanic.isChoosing(resumed));
        assertFalse(StagesMechanic.resume(resumed, 3, List.of(), List.of(), packets::add));
        assertTrue(StagesMechanic.resume(resumed, 2, List.of(PvzceIds.id("wall_nut")),
                List.of(PvzceIds.BUFF_AUTO_COLLECT), packets::add));
        assertEquals(List.of(PvzceIds.BUFF_AUTO_COLLECT), resumed.activeBuffs().stream().map(b -> b.id()).toList());
        assertTrue(resumed.isPreparing());
        assertTrue(packets.stream().anyMatch(p -> p instanceof MusicEventS2C music
                && music.event().equals("pvzce:music/ultimate_battle")));
        assertFalse(StagesMechanic.resume(resumed, 2, List.of(), List.of(), packets::add));
    }

    @Test
    void finishingShotIsReservedAndRequiresAllOutpostsAndClearedFinalWaves() {
        LevelDef def = fixture(List.of(outposts(1, true)), List.of(emptyWave()));
        LevelServer level = new LevelServer(def, 18L);
        plant(level, "wall_nut", 4, 1, GROUND);
        plant(level, "wall_nut", 5, 3, UPPER);
        tick(level, 3);
        assertEquals(GameStateS2C.RUNNING, level.gameState());
        assertTrue(level.fireOutpost(p -> { }, 1, 7, 3, UPPER));
        assertEquals(0, OutpostsMechanic.status(level).points().get(1).charges());
        assertTrue(level.fireOutpost(p -> { }, 1, 8, 0, GROUND));
        assertEquals(GameStateS2C.WON, level.gameState());
        assertFalse(level.fireOutpost(p -> { }, 1, 8, 0, GROUND));
    }

    @Test
    void swappingAwayAndBackDoesNotRefreshAnExplosiveCard() {
        LevelServer level = new LevelServer(fixture(List.of(), List.of()),
                List.of(PvzceIds.id("cherry_bomb")), 19L);
        int slot = level.slotInfos().stream().filter(info -> info.defId().equals("pvzce:cherry_bomb"))
                .findFirst().orElseThrow().index();
        assertTrue(level.placePlant(p -> { }, slot, 1, 0));
        int remaining = level.slotInfos().get(slot).cooldownLeft();
        assertTrue(remaining > 0);
        Map<Identifier, Integer> memory = new java.util.HashMap<>();
        level.replaceStageSelection(List.of(PvzceIds.id("wall_nut")), List.of(), memory, p -> { });
        level.replaceStageSelection(List.of(PvzceIds.id("cherry_bomb")), List.of(), memory, p -> { });
        assertEquals(remaining, level.slotInfos().stream().filter(info -> info.defId().equals("pvzce:cherry_bomb"))
                .findFirst().orElseThrow().cooldownLeft());
    }

    /** Immediate removal measures authored pacing, independently of a player's damage output. */
    @Test
    void fullFortressSimulationMeasuresNormalSpeedScheduleAndSupply() {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/tidal_fortress"));
        LevelServer level = new LevelServer(def, 20L);
        plant(level, "wall_nut", 5, 1, GROUND);
        plant(level, "wall_nut", 6, 3, "pvzce:tidal_bridge");
        level.beginWaves();
        int spawned = 0;
        List<Integer> phaseSeconds = new ArrayList<>();
        for (int i = 0; i < 150000 && !StagesMechanic.isReady(level); i++) {
            level.tick(p -> { });
            for (var entity : level.entities()) {
                if (entity instanceof ZombieEntity && !entity.isRemoved()) {
                    spawned++;
                    entity.remove();
                }
                if (!(entity instanceof PlantEntity) && !(entity instanceof ZombieEntity)) {
                    entity.remove();
                }
            }
            if (StagesMechanic.isChoosing(level)) {
                var status = StagesMechanic.status(level);
                phaseSeconds.add(status.battleTicks() / 60);
                assertTrue(StagesMechanic.resume(level, status.phase() + 2, List.of(), List.of(), p -> { }));
                level.beginWaves();
            }
        }
        assertTrue(StagesMechanic.isReady(level));
        assertTrue(OutpostsMechanic.canFinish(level));
        System.out.println("Fortress normal-speed schedule: phase ends " + phaseSeconds
                + "s, final=" + StagesMechanic.status(level).battleTicks() / 60F
                + "s, spawned=" + spawned + ", scripted sun="
                + level.team(PvzceIds.PLANT_TEAM).resources().getOrDefault(PvzceIds.SUN, 0)
                + ", artillery=" + OutpostsMechanic.status(level).points().get(1).charges()
                + "; immediate removal excludes combat and preparation time");
    }

    @Test
    void shippedFortressHasValidComposableDataAndItsCodecKeepsUpperWaveEntries() {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/tidal_fortress"));
        assertNotNull(def);
        assertEquals(List.of(), LevelMechanics.validate(def));
        assertEquals(List.of(), LevelValidator.validateWaves(def));
        LevelDef decoded = LevelDef.CODEC.parse(JsonOps.INSTANCE,
                LevelDef.CODEC.encodeStart(JsonOps.INSTANCE, def).getOrThrow()).getOrThrow();
        assertEquals(def.mechanics(), decoded.mechanics());
        assertEquals(def.waves(), decoded.waves());
        assertTrue(def.waves().stream().flatMap(w -> w.entries().stream())
                .anyMatch(e -> e.surface().equals("pvzce:tidal_bridge")));
    }
}

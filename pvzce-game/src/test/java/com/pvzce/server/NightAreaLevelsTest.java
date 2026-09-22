package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.NocturnalCapability;
import com.pvzce.common.capability.plant.ProducerCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.DayNightCycle;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2-1, the first Night level: a fixed night, no sun from the sky, and the original's
 * gravestones.
 *
 * <p>A night that never ends is the one shape {@code DayNightCycle} could not express before
 * this level existed - {@code day_length: 0} used to mean "permanently day", because every
 * level that had ever been written said "never night" the same way. What is pinned here is
 * that the new reading did not change the old one (1-10's cycle still turns over) and that
 * the level itself is the level it was asked to be.
 */
class NightAreaLevelsTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef level(String name) {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/" + name));
        assertNotNull(def, name + " must be part of the built-in adventure");
        return def;
    }

    /** No day at all, so the clock cannot leave the night - from tick zero to any tick. */
    @Test
    void theFirstNightLevelIsFixedAtNight() {
        LevelDef def = level("2_1");
        assertEquals(0, def.rules().get(PvzceIds.RULE_DAY_LENGTH).getAsInt());
        assertTrue(def.rules().get(PvzceIds.RULE_NIGHT_LENGTH).getAsInt() > 0,
                "a night has to exist for the level to be night at all");

        LevelServer level = new LevelServer(def);
        assertTrue(level.isNight(), "night on the level's first tick");
        level.setDayTicks(60 * 60 * 24);
        assertTrue(level.isNight(), "and a day's worth of ticks later");
        assertEquals(1F, DayNightCycle.nightBlend(level.clock().dayTicks(),
                level.rules().getInt(PvzceIds.RULE_DAY_LENGTH),
                level.rules().getInt(PvzceIds.RULE_NIGHT_LENGTH)), 0.0001F,
                "the client's lighting is dark from the first frame, not after a dusk");
    }

    /** The other three shapes are untouched: this is the regression the change could cause. */
    @Test
    void theDayLevelsAndTheCycleStillReadTheSame() {
        assertTrue(DayNightCycle.alwaysNight(0, 360000), "no day + a night is night");
        assertFalse(DayNightCycle.alwaysNight(0, -1), "no day and no night is the old 'always day'");
        assertFalse(DayNightCycle.alwaysNight(3600, 360000), "a cycle is not fixed");

        LevelDef day = level("1_4");
        assertFalse(new LevelServer(day).isNight(), "an ordinary Day level is still day");

        LevelDef cycle = level("1_10");
        LevelServer clock = new LevelServer(cycle);
        assertFalse(clock.isNight(), "1-10 still opens in its one minute of daylight");
        clock.setDayTicks(3600);
        assertTrue(clock.isNight(), "and still turns over at 3600 ticks");
    }

    /** No sky sun: the level's own rule, so a pack can write a night level that drops some. */
    @Test
    void noSunFallsFromTheSky() {
        assertEquals(0F, level("2_1").rules().get(PvzceIds.RULE_SUN_SPAWN_CHANCE).getAsFloat());
    }

    /**
     * The original's four gravestones block planting, and open once - at the last wave.
     *
     * <p>Where they stand is the lawn's business rather than the file's: {@code grave_field}
     * scatters them when the level is built, from the level's own random source, so the four
     * cells are different from one run to the next (see {@code GraveFieldTest} for the
     * mechanic itself). What this pins is that 2-1 gets four of them, that the lawn was not
     * shortened to make room, and that they are still unplantable.
     *
     * <p>{@code graves_spawn_night} is left at its default (on) because the rule now means
     * "this level's graves give up their dead at the final wave" rather than "roll for a
     * zombie every tick", which is what four graves would have done four seconds apart. The
     * rise itself is covered by {@code GraveRiseTest}.
     */
    @Test
    void theGravestonesBlockPlantingAndOpenAtTheLastWave() {
        LevelDef def = level("2_1");
        assertEquals(4, com.pvzce.common.level.mechanic.LevelMechanics
                        .dataOf(def, PvzceIds.MECHANIC_GRAVE_FIELD,
                                com.pvzce.api.content.GraveFieldData.class)
                        .orElseThrow(() -> new AssertionError("2-1 scatters no graves")).count(),
                "2-1 ships the original's four gravestones");
        assertEquals(9 * 5, countGrass(def),
                "the whole lawn is painted: the graves are laid on top of it, not instead of it");

        LevelServer level = new LevelServer(def);
        List<String> graves = new java.util.ArrayList<>();
        for (var cell : level.graveCells()) {
            graves.add(cell.x() + "," + cell.y());
        }
        assertEquals(4, graves.size(), "four of them stand up when the level is built: " + graves);
        assertTrue(level.rules().getBoolean(PvzceIds.RULE_GRAVES_SPAWN_NIGHT),
                "2-1's graves open at the last wave");
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        for (String grave : graves) {
            String[] parts = grave.split(",");
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            assertFalse(level.canPlacePlant(pea, x, y), "a grave is not plantable: " + grave);
        }
        // Four graves, four different cells, and every one of them in the far half of the lawn
        // - which is where the original puts them and what `region` means.
        assertEquals(4, new java.util.HashSet<>(graves).size(), "no cell holds two graves");
        assertTrue(level.canPlacePlant(pea, 0, 0), "the rest of the lawn is plantable");
        for (String grave : graves) {
            assertTrue(Integer.parseInt(grave.split(",")[0]) >= 4,
                    "a grave outside the far half: " + grave);
        }
    }

    private static long countGrass(LevelDef def) {
        return def.scene().get(PvzceIds.GRASS).size();
    }

    /** The original introduces the newspaper zombie here, and the level list previews it. */
    @Test
    void theNewspaperZombieArrivesHere() {
        List<String> preview = level("2_1").previewZombieIds();
        assertTrue(preview.contains("pvzce:basic_zombie"), "got " + preview);
        assertTrue(preview.contains("pvzce:newspaper_zombie"), "got " + preview);
        assertTrue(preview.contains("pvzce:flag_zombie"), "got " + preview);
    }

    /** It opens after the Day area, and its first clear hands over the night's sun. */
    @Test
    void theNightAreaOpensAfterOneTen() {
        LevelDef def = level("2_1");
        assertEquals(List.of("pvzce:yard/adventure/1_10"),
                def.unlock().requires().stream()
                        .map(requirement -> requirement.id().orElseThrow().toString())
                        .toList());
        assertEquals("pvzce:sun_shroom", def.rewards().firstClear().stream()
                .filter(com.pvzce.api.content.LevelRewards.Reward::isUnlock)
                .map(reward -> reward.id().orElseThrow().toString())
                .findFirst().orElseThrow());
        assertNotNull(BuiltInRegistries.SLOT_TYPES.get(PvzceIds.id("sun_shroom")),
                "an unlock needs a card to land on");
    }

    /**
     * The sun-shroom: cheap, a mushroom, and awake in the level it is earned in.
     *
     * <p>15 sun per harvest is the original's ungrown form; the reanim also carries the grown
     * one, and this version does not have a producer that steps up, so the amount is flat.
     */
    @Test
    void theSunShroomIsTheNightsSun() {
        PlantDef shroom = BuiltInRegistries.PLANTS.get(PvzceIds.id("sun_shroom"));
        assertNotNull(shroom);
        assertEquals(25, shroom.cost().resources().getOrDefault(PvzceIds.SUN, 0));
        ProducerCapability producer = shroom.capability(ProducerCapability.class).orElseThrow();
        assertEquals(PvzceIds.SUN, producer.resource());
        assertEquals(15, producer.amount());
        assertEquals(1440, producer.everyTicks());
        assertNotNull(shroom.capability(NocturnalCapability.class), "it is a mushroom");

        // In its own level it is awake; the same plant in a Day level sleeps (that rule is
        // NocturnalPlantTest's, repeated here only for the pairing this level depends on).
        LevelServer night = new LevelServer(level("2_1"));
        assertFalse(night.spawnPlant(shroom, night.team(PvzceIds.PLANT_TEAM), 3, 2)
                .isAsleep(night), "a sun-shroom planted at night is awake");

        // And it is the level's sun supply: with no sky sun at all, this harvest is the only
        // sun a night level ever sees.
        CapturingBridge bridge = new CapturingBridge();
        for (int i = 0; i < 400; i++) {
            night.tick(bridge);
        }
        long suns = night.entities().stream()
                .filter(entity -> entity instanceof com.pvzce.server.entity.ResourceDropEntity drop
                        && drop.defId().equals(PvzceIds.SUN))
                .count();
        assertTrue(suns > 0, "a sun-shroom has to pay for itself before the first wave");
    }

    /** The bridge a level needs for a headless run: these tests read the board, not packets. */
    private static final class CapturingBridge implements LevelServer.ServerBridge {
        @Override
        public void send(com.pvzce.common.network.PvzcePacket packet) {
        }
    }
}

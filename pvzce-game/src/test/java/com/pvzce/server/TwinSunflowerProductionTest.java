package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Two heads must make two separately collectible suns, including across a mid-batch save. */
class TwinSunflowerProductionTest {
    private static LevelDef clearNight;
    private static final LevelServer.ServerBridge SILENT = packet -> {};

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        clearNight = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/6_3"));
        assertNotNull(clearNight);
    }

    private static LevelServer planted(String id) {
        LevelServer level = new LevelServer(clearNight, List.of(PvzceIds.SUN), LevelServer.SeedContext.all(clearNight),
                List.of(), owned -> true);
        level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id(id)),
                level.team(PvzceIds.PLANT_TEAM), 0, 2);
        level.flushPending(SILENT);
        return level;
    }

    private static List<ResourceDropEntity> suns(LevelServer level) {
        return level.entities().stream()
                .filter(e -> e instanceof ResourceDropEntity && !e.isRemoved())
                .map(e -> (ResourceDropEntity) e).filter(e -> e.defId().equals(PvzceIds.SUN)).toList();
    }

    private static void tick(LevelServer level, int count) {
        for (int i = 0; i < count; i++) {
            level.tick(SILENT);
            level.flushPending(SILENT);
        }
    }

    @Test
    void twoTwentyFiveValueSunsLandApartAndCanBothBeCollected() {
        LevelServer level = planted("twin_sunflower");
        // Clear-night action rate is 1.25: the existing 300-tick first delay takes 240 ticks.
        tick(level, 239);
        assertTrue(suns(level).isEmpty());
        tick(level, 1);
        assertEquals(List.of(25), suns(level).stream().map(ResourceDropEntity::amount).toList());
        tick(level, 11);
        assertEquals(1, suns(level).size(), "the second head pops shortly after the first");
        tick(level, 1);
        assertEquals(List.of(25, 25), suns(level).stream().map(ResourceDropEntity::amount).toList());
        tick(level, 90);
        var pair = suns(level);
        assertTrue(pair.stream().allMatch(ResourceDropEntity::landed));
        assertEquals(0.6F, Math.abs(pair.get(0).cellX() - pair.get(1).cellX()), 0.0001F,
                "zero default scatter must not hide one sun behind the other");
        int before = level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN);
        for (var sun : pair) assertTrue(level.collectResource(SILENT, sun.id()));
        assertEquals(before + 50, level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN));
        assertFalse(level.collectResource(SILENT, pair.getFirst().id()), "each sun is collectible once");
    }

    @Test
    void savingBetweenTheTwoDropsKeepsTheSecondAndTheNextProductionTime() {
        LevelServer level = planted("twin_sunflower");
        tick(level, 245);
        assertEquals(1, suns(level).size());
        assertTrue(level.collectResource(SILENT, suns(level).getFirst().id()));
        level.flushPending(SILENT);
        int bank = level.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN);
        LevelServer restored = new LevelServer(clearNight, List.of(PvzceIds.SUN), LevelServer.SeedContext.all(clearNight),
                List.of(), owned -> true);
        restored.restore(level.save());
        tick(restored, 6);
        assertEquals(0, suns(restored).size());
        tick(restored, 1);
        assertEquals(1, suns(restored).size(), "resume completes the pending batch exactly once");
        assertTrue(restored.collectResource(SILENT, suns(restored).getFirst().id()));
        restored.flushPending(SILENT);
        tick(restored, 851);
        assertEquals(0, suns(restored).size(), "the short pop delay must not change the 1080-tick cycle");
        tick(restored, 1);
        assertEquals(1, suns(restored).size());
        tick(restored, 12);
        assertEquals(2, suns(restored).size());
        assertEquals(bank + 25, restored.team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN));
        assertEquals(50, suns(restored).stream().mapToInt(ResourceDropEntity::amount).sum());
    }

    @Test
    void producersWithoutDropCountStillMakeOneSun() {
        LevelServer level = planted("sunflower");
        tick(level, 252);
        assertEquals(List.of(25), suns(level).stream().map(ResourceDropEntity::amount).toList());
    }
}

package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.WaveDef.WaveType;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameplaySimulationTest {
    private static LevelDef demoLevel;

    @BeforeAll
    static void loadDemo() throws Exception {
        // Content and convention tags together: the placement rules read tags,
        // so a data-only load would leave every cell unplantable.
        TestContent.loadBuiltInContentAndTags();
        demoLevel = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/demo_level"));
    }

    private static LevelServer newServer() {
        return new LevelServer(demoLevel);
    }

    @Test
    void placePlantConsumesSunAndRejectsWithoutSun() {
        LevelServer level = newServer();
        CapturingBridge bridge = new CapturingBridge();

        assertTrue(level.placePlant(bridge, 0, 0, 0));
        assertEquals(1, level.plantCount());
        assertEquals(50, level.plantPlayer().team().resourcesOf(Identifier.withDefaultNamespace("sun")));

        assertFalse(level.placePlant(bridge, 0, 1, 0));
        assertEquals(1, level.plantCount());
    }

    @Test
    void demoLevelWavesSpawnAndPlantsWin() {
        LevelServer level = newServer();
        CapturingBridge bridge = new CapturingBridge();
        level.plantPlayer().team().putResource(Identifier.withDefaultNamespace("sun"), 3000);

        int[][] cells = {
                {0, 0}, {1, 0}, {2, 0},
                {0, 1}, {1, 1}, {2, 1},
                {0, 2}, {1, 2}, {2, 2},
                {0, 3}, {1, 3}, {2, 3},
                {0, 4}, {1, 4}, {2, 4}
        };
        for (int[] cell : cells) {
            level.plantPlayer().slot(0).clearCooldown();
            assertTrue(level.placePlant(bridge, 0, cell[0], cell[1]));
        }
        assertEquals(15, level.plantCount());

        for (int i = 0; i < 30_000 && level.gameState().equals(GameStateS2C.RUNNING); i++) {
            level.tick(bridge);
        }

        assertEquals("won", level.gameState());
        assertEquals(Identifier.withDefaultNamespace("plant_team"), level.winner());
    }

    private static LevelDef singleZombieLevel() {
        return singleZombieLevel(1);
    }

    private static LevelDef singleZombieLevel(int waveTick) {
        return new LevelDef(
                Identifier.withDefaultNamespace("zombie_win_test"),
                "",
                "",
                9,
                5,
                Map.of(),
                List.of(
                        new TeamDef(Identifier.withDefaultNamespace("plant_team"), "植物方", "survive_waves"),
                        new TeamDef(Identifier.withDefaultNamespace("zombie_team"), "僵尸方", "plant_side_lost")
                ),
                Identifier.withDefaultNamespace("plant_team"),
                Map.of(),
                Map.of(),
                List.of(new WaveDef(WaveType.SMALL, waveTick, 600,
                        List.of(new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"), 1)))),
                1F,
                List.of(Identifier.withDefaultNamespace("pea_shooter"), Identifier.withDefaultNamespace("sun")),
                Map.of(),
                150,
                LevelDef.LevelMusicDef.DEFAULT,
                List.of(),
                6,
                com.pvzce.api.content.LevelRewards.NONE,
                com.pvzce.api.content.LevelUnlock.NONE,
                // No mowers: these tests are about the loss rule ("a zombie that reaches the
                // house ends the level"), which a mower would intercept. Wall-nut Bowling is
                // the shipped level with the same shape; the mower's own tests live in
                // MowerTest.
                List.of(com.pvzce.api.content.mechanic.TypedMechanic.of(
                        com.pvzce.common.PvzceIds.MECHANIC_MOWER,
                        new com.pvzce.api.content.MowerData(java.util.Optional.of(List.of())))),
                com.pvzce.api.content.LevelDialogue.EMPTY
        );
    }

    @Test
    void finishedLevelSaveDoesNotRestorePlants() {
        // A level whose one wave is due immediately: "all waves released" is half of the win
        // condition, so the wave has to be able to arrive before the level can be won.
        LevelServer level = new LevelServer(singleZombieLevel(1));
        CapturingBridge bridge = new CapturingBridge();
        level.plantPlayer().team().putResource(Identifier.withDefaultNamespace("sun"), 1000);
        for (int row = 1; row <= 4; row++) {
            assertTrue(level.placePlant(bridge, 0, 0, row));
            for (int t = 0; t < 300; t++) {
                level.tick(bridge);
            }
        }

        // The win is the wave list running out with nothing hostile standing; this zombie is
        // the "nothing hostile" half, and the kill below removes it so the level can finish.
        // (`singleZombieLevel` writes one empty wave: a level whose table is empty never
        // finishes its waves, so the win check could never fire on it.)
        ZombieDef basic = BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("basic_zombie"));
        ZombieEntity bystander = new ZombieEntity(basic,
                level.team(Identifier.withDefaultNamespace("zombie_team")),
                level.width() + 0.6F, 0);
        level.addEntity(bystander);

        for (int i = 0; i < 5_000 && level.gameState().equals(GameStateS2C.RUNNING); i++) {
            bystander.damageBody(10_000, level);
            level.tick(bridge);
        }
        assertEquals("won", level.gameState(),
                "a level whose waves are done and whose lawn is clear is won by the plants");
        assertTrue(level.wavesReleased(), "and it finished because its waves ran out");
        assertTrue(level.plantCount() > 0, "plants outside the losing row should survive; count="
                + level.plantCount() + " entities="
                + level.entities().stream().filter(e -> !e.isRemoved()).map(e -> e.entityKind() + "@" + e.gridY()).toList());

        LevelServer restarted = new LevelServer(singleZombieLevel());
        restarted.restore(level.save());
        assertEquals(0, restarted.plantCount(), "finished games must restart fresh");
        assertEquals(150, restarted.plantPlayer().team().resourcesOf(Identifier.withDefaultNamespace("sun")),
                "finished games must restart with the level's initial sun");
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }
}

package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pole vaulter: it runs with its pole, vaults over the first plant, and walks on.
 *
 * <p>All three were wrong in the same place. The vault was a single tick that teleported the
 * zombie and asked for the jump clip - which the walk loop overwrote before the client could
 * be told, so the zombie appeared on the far side of the plant with no jump at all - and the
 * only walking clip exported was the *post*-vault one, so it carried a pole it was drawn
 * without. These pin the sequence rather than the drawing.
 */
class PoleVaultTest {
    private static LevelDef board;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        // 1-4 is the five-row practice lawn; 1-1 is a single row, which is too narrow to
        // vault in and to plant beside.
        LevelDef source = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_4"));
        assertNotNull(source, "the shipped 1-4 must load");
        // No waves: this class is about one zombie and one plant, and an arriving wave would
        // be a second thing to explain.
        LevelDef quiet = TestLevels.withWaves(source, List.of());
        board = quiet;
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }

    private static void tick(LevelServer level, CapturingBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
    }

    private static ZombieEntity vaulter(LevelServer level, CapturingBridge bridge, float x, int row) {
        level.spawnZombie(PvzceIds.id("pole_vaulter_zombie"), level.team(PvzceIds.ZOMBIE_TEAM), x, row);
        level.flushPending(bridge);
        ZombieEntity zombie = level.zombiesInRow(row).stream().findFirst().orElse(null);
        assertNotNull(zombie, "the vaulter must exist");
        return zombie;
    }

    @Test
    void theVaultTakesTimeAndMovesTheZombieAcrossThePlant() {
        LevelServer level = new LevelServer(board);
        CapturingBridge bridge = new CapturingBridge();
        PlantDef wallNut = BuiltInRegistries.PLANTS.get(PvzceIds.id("wall_nut"));
        assertNotNull(wallNut);
        level.spawnPlant(wallNut, level.team(PvzceIds.PLANT_TEAM), 5, 2);
        level.flushPending(bridge);
        ZombieEntity zombie = vaulter(level, bridge, 6.4F, 2);

        // Walk up to the plant: still on the ground, still jogging with the pole.
        tick(level, bridge, 60);
        assertFalse(zombie.isDying());
        assertEquals(EntityAnimations.RUN, zombie.animation(),
                "before the vault it runs with the pole, not the empty-handed walk");

        // Tick until the vault starts.
        int guard = 0;
        while (zombie.animation().equals(EntityAnimations.RUN) && guard++ < 2_000) {
            tick(level, bridge, 1);
        }
        assertEquals(EntityAnimations.JUMP, zombie.animation(), "the vault is its own state");
        float startX = zombie.cellX();

        // It is a hop, not a teleport: one tick in, it has barely moved and is still vaulting.
        tick(level, bridge, 1);
        assertEquals(EntityAnimations.JUMP, zombie.animation(), "the walk loop must not steal it");
        assertTrue(startX - zombie.cellX() < 0.2F,
                "a 1.4-cell hop does not happen in two ticks (moved "
                        + (startX - zombie.cellX()) + " cells)");

        // Two thirds of the way through it is airborne and partway across.
        tick(level, bridge, 140);
        float midway = startX - zombie.cellX();
        assertTrue(midway > 0.2F && midway < 1.4F, "partway across, not yet landed: " + midway);

        // Tick to the landing itself: the position it lands at is the thing, and one more
        // second of walking would hide it.
        int landed = 0;
        while (zombie.animation().equals(EntityAnimations.JUMP) && landed++ < 1_000) {
            tick(level, bridge, 1);
        }
        assertEquals(EntityAnimations.WALK, zombie.animation(),
                "after landing it walks like any other zombie");
        assertEquals(startX - 1.4F, zombie.cellX(), 0.001F, "and it landed past the plant");
        assertTrue(zombie.cellX() < 5F, "which is the far side of the plant it vaulted");
        assertTrue(landed > 1, "the whole hop took more than a tick");
    }

    @Test
    void aZombieThatHasNotMetAPlantYetIsNeverAirborne() {
        LevelServer level = new LevelServer(board);
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity zombie = vaulter(level, bridge, 8.4F, 4);
        tick(level, bridge, 120);
        assertEquals(EntityAnimations.RUN, zombie.animation());
        com.pvzce.common.capability.zombie.VaultCapability vault =
                zombie.capability(com.pvzce.common.capability.zombie.VaultCapability.class);
        assertNotNull(vault);
        assertFalse(vault.isVaulting(), "nothing to vault over, so nothing to vault");
    }
}

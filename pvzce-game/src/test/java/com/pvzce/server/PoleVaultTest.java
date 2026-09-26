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
        // The hop the art draws, which is the vault capability's own default and the number the
        // curve is normalised against. It is not 1.4: the clip's feet only travel 1.077 cells, and
        // a server that moved the zombie further slid its landing past the frame it is drawn on.
        assertEquals(startX - com.pvzce.common.capability.zombie.VaultCapability.DEFAULT_JUMP_DISTANCE,
                zombie.cellX(), 0.001F, "and it landed past the plant, where the art lands");
        assertTrue(zombie.cellX() < 5F, "which is the far side of the plant it vaulted");
        assertTrue(landed > 1, "the whole hop took more than a tick");
        // And it came back down: a hop that left the entity's height raised would draw the rest of
        // the run with the zombie floating.
        assertEquals(0F, zombie.height(), 0.001F, "the landing puts it back on the ground");
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

    /**
     * The zombie is drawn where the simulation puts it, for the whole hop.
     *
     * <p>The reported "撑杆僵尸/海豚僵尸动画的掉落位置和实际位置不符合": the server eased the travel
     * across the art's airborne window while the art drew its own authored curve, and the two
     * disagree by half a cell at the landing. The server's curve is
     * {@code VaultCapability.travelAt}; the art's is the {@code jump} clip's own {@code body_1}
     * translation, which is what this reads. They are two halves of one fact and this is the only
     * place they meet, so a re-export that moves the art fails here rather than in a screenshot.
     */
    @Test
    void theArtAndTheSimulationAgreeOnWhereTheZombieIs() throws Exception {
        var file = (com.pvzce.client.animation.ControllerFile) parseClasspath("pole_vaulter_zombie");
        var clip = file.clips().get("jump");
        assertNotNull(clip, "the pole vaulter's jump clip must be exported");
        var model = file.model();
        float duration = clip.duration() / clip.rate();
        int ticks = com.pvzce.common.capability.zombie.VaultCapability.DEFAULT_JUMP_TICKS;

        // The art's own displacement, normalised exactly as the table is: first frame 0, last frame
        // 1. Read from the clip's sampled pose rather than from the raw keys, because the conversion
        // bakes the source's shear into the translation - the drawn centre is what moves, and the
        // drawn centre is what has to agree with the simulation.
        float[] artTravel = new float[ticks + 1];
        for (int tick = 0; tick <= ticks; tick++) {
            var pose = clip.samplePose(model, duration * tick / ticks).get("body_1");
            artTravel[tick] = pose.translation()[0];
        }
        float origin = artTravel[0];
        float span = artTravel[ticks] - origin;
        assertTrue(Math.abs(span) > 0.5F,
                "the jump clip only travels " + span + " cells; it is not the vault");

        float worst = 0F;
        int worstTick = -1;
        float worstDrawn = 0F;
        float worstSimulated = 0F;
        for (int tick = 0; tick <= ticks; tick++) {
            float drawn = (artTravel[tick] - origin) / span;
            float simulated = com.pvzce.common.capability.zombie.VaultCapability
                    .travelAt(tick / (float) ticks);
            if (Math.abs(drawn - simulated) > worst) {
                worst = Math.abs(drawn - simulated);
                worstTick = tick;
                worstDrawn = drawn;
                worstSimulated = simulated;
            }
        }
        // A fiftieth of the hop: the table is sampled every ten ticks and the art's keys every
        // five, so agreement between the two samples is interpolation error and nothing else.
        // Anything that moved the art a whole cell would come out near 1/1.4 = 0.71.
        assertTrue(worst < 0.02F,
                "at tick " + worstTick + " the art draws the zombie " + worstDrawn
                        + " of the hop across while the simulation says " + worstSimulated
                        + " (table " + worst + " apart)");
    }

    /** A shipped animation file, read exactly as the game reads it. */
    private static com.pvzce.client.animation.AnimationFile parseClasspath(String defId)
            throws Exception {
        com.pvzce.common.tag.TestContent.loadBuiltInContentAndTags();
        var fileId = com.pvzce.common.core.EntityArt.animationFile(
                com.pvzce.api.util.Identifier.withDefaultNamespace(defId));
        String resource = "/assets/" + fileId.namespace() + "/animations/" + fileId.path() + ".json";
        try (var stream = PoleVaultTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing test resource " + resource);
            }
            var reader = new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8);
            return com.pvzce.client.animation.AnimationResourceLoader.parse(
                    com.google.gson.JsonParser.parseReader(reader).getAsJsonObject(), fileId);
        }
    }
}

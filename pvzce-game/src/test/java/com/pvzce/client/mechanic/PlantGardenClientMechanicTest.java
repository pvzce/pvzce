package com.pvzce.client.mechanic;

import com.pvzce.client.ClientLevel;
import com.pvzce.client.gui.hud.cardbar.CardPainter;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.IZombieGarden;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlantGardenClientMechanicTest {
    @BeforeAll static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ClientMechanics.bootstrap();
    }

    private static ClientLevel mirror(String suffix) {
        var def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/puzzle/i_zombie_" + suffix));
        ClientLevel client = new ClientLevel();
        client.init(def.id().toString(), def.width(), def.height(), List.of(), List.of(),
                List.of(), def.maxSeedSlots(), List.of(), List.of(), "pvzce:zombie_team", "僵尸方",
                LevelMechanics.payloads(def), def.background().orElse(null),
                def.hiddenSceneElements(), def.disableShaders());
        return client;
    }

    @Test void clientReadsDeploymentBoundsAndBrainProgressThroughMechanicPayloads() {
        ClientLevel client = mirror("5");
        assertFalse(client.canPlaceZombie(PvzceIds.id("basic_zombie"), 3, 2));
        assertTrue(client.canPlaceZombie(PvzceIds.id("basic_zombie"), 4, 2));
        assertTrue(client.canPlaceZombie(PvzceIds.id("bungee_zombie"), 3, 2));
        assertFalse(client.canPlaceZombie(PvzceIds.id("bungee_zombie"), 4, 2));
        assertFalse(mirror("endless").canPlaceZombie(PvzceIds.id("imp"), 4, 2));
        assertTrue(mirror("endless").canPlaceZombie(PvzceIds.id("imp"), 5, 2));
        var state = new IZombieGarden.Progress(7, 0b10101);
        ClientMechanics.applySync(client, MechanicSyncS2C.of(PvzceIds.MECHANIC_PLANT_GARDEN,
                IZombieGarden.Progress.CODEC, state));
        assertEquals(state, client.mechanicStateOrNull(PvzceIds.MECHANIC_PLANT_GARDEN, IZombieGarden.Progress.class));
        assertNotNull(ClientMechanics.get(PvzceIds.MECHANIC_PLANT_GARDEN).createWorldOverlay(client));
    }

    @Test void everyPuzzleCardHasADecodableFace() throws Exception {
        for (String suffix : List.of("1", "2", "3", "4", "5", "endless")) {
            var def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/puzzle/i_zombie_" + suffix));
            for (var card : def.slots()) {
                // CardPainter receives content ids in the runtime bar, including the ladder alias.
                var resolved = com.pvzce.common.core.SlotResolver.resolve(card).orElseThrow();
                var icon = CardPainter.icon(resolved.content().toString());
                try (var stream = getClass().getResourceAsStream("/assets/" + icon.namespace() + "/" + icon.path() + ".png")) {
                    assertNotNull(stream, card + " points at missing art " + icon);
                    assertNotNull(javax.imageio.ImageIO.read(stream), card + " has invalid image data");
                }
            }
        }
    }
}

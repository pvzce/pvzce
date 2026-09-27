package com.pvzce.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "One card, one whole column": the level rule {@code plant_whole_column}.
 *
 * <p>What the rule is for is written where it is declared ({@code PvzceIds}). What is worth pinning
 * here is that it is a <em>placement shape</em> and not a discount or a special case: the click
 * fills every cell of its column that will take the plant, skips the ones that will not without
 * touching the rest, and is charged once - the card's own price - however many plants appeared. A
 * column with nowhere to put the plant costs nothing and says so, exactly as an occupied cell does
 * on an ordinary level.
 */
class ColumnPlantingTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<String> messages = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            if (packet instanceof com.pvzce.common.network.packet.ServerMessageS2C message) {
                messages.add(message.message());
            }
        }
    }

    /** The demo board, with the whole-column rule set the way the test wants it. */
    private static LevelServer lawn(boolean wholeColumn) {
        LevelDef demo = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        assertNotNull(demo, "demo_level must load");
        Map<Identifier, JsonElement> rules = new HashMap<>(demo.rules());
        rules.put(PvzceIds.RULE_PLANT_WHOLE_COLUMN, new JsonPrimitive(wholeColumn));
        return new LevelServer(com.pvzce.testutil.TestLevels.withRules(demo, rules));
    }

    /** The sun a card costs, and the bar slot that deals it. */
    private record Card(int slot, int price) {
    }

    private static Card card(LevelServer level, String plantId) {
        Identifier wanted = PvzceIds.id(plantId);
        com.pvzce.server.PvzcePlayer player = level.plantPlayer();
        assertNotNull(player, "the plant side has a player");
        for (com.pvzce.common.core.Slot slot : player.slots()) {
            if (wanted.equals(slot.defId())) {
                slot.clearCooldown();
                return new Card(slot.index(), slot.costSun());
            }
        }
        throw new AssertionError(plantId + " is not on this board's bar");
    }

    /** Plants of one kind standing on the board, in one column. */
    private static int plantsIn(LevelServer level, String plantId, int column) {
        int found = 0;
        for (var entity : level.entities()) {
            if (entity instanceof PlantEntity plant && plant.gridX() == column
                    && plant.def().id().equals(PvzceIds.id(plantId))) {
                found++;
            }
        }
        return found;
    }

    private static void settle(LevelServer level, CapturingBridge bridge) {
        level.flushPending(bridge);
    }

    /** One click, five plants, one price. */
    @Test
    void oneCardFillsItsWholeColumn() {
        LevelServer level = lawn(true);
        CapturingBridge bridge = new CapturingBridge();
        Card pea = card(level, "pea_shooter");
        level.team(PLANT_TEAM).addResource(PvzceIds.SUN, 1000);
        int before = level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN);

        assertTrue(level.placePlant(bridge, pea.slot(), 4, 2),
                "the click plants: " + bridge.messages);
        settle(level, bridge);

        assertEquals(5, plantsIn(level, "pea_shooter", 4),
                "every cell of the clicked column got one");
        assertEquals(0, plantsIn(level, "pea_shooter", 3), "and no other column did");
        assertEquals(pea.price(), before - level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "the column cost one card, not five");
    }

    /** A cell that cannot take the plant is skipped, and the rest of the column still gets one. */
    @Test
    void occupiedCellsAreSkippedAndTheRestStillPlant() {
        LevelServer level = lawn(true);
        CapturingBridge bridge = new CapturingBridge();
        PlantDef wallNut = BuiltInRegistries.PLANTS.get(PvzceIds.id("wall_nut"));
        assertNotNull(wallNut);
        level.spawnPlant(wallNut, level.team(PLANT_TEAM), 4, 2);
        settle(level, bridge);
        Card pea = card(level, "pea_shooter");
        level.team(PLANT_TEAM).addResource(PvzceIds.SUN, 1000);

        // Clicked straight on the wall-nut: the click is not refused, the cell is skipped.
        assertTrue(level.placePlant(bridge, pea.slot(), 4, 2),
                "a column with one plant in it is still plantable: " + bridge.messages);
        settle(level, bridge);

        assertEquals(1, plantsIn(level, "wall_nut", 4), "the wall-nut is untouched");
        assertEquals(4, plantsIn(level, "pea_shooter", 4), "and the other four cells got the pea");
    }

    /** Nowhere to put it: nothing is charged, and the player is told why. */
    @Test
    void aColumnWithNowhereToGoCostsNothing() {
        LevelServer level = lawn(true);
        CapturingBridge bridge = new CapturingBridge();
        PlantDef wallNut = BuiltInRegistries.PLANTS.get(PvzceIds.id("wall_nut"));
        assertNotNull(wallNut);
        for (int row = 0; row < level.height(); row++) {
            level.spawnPlant(wallNut, level.team(PLANT_TEAM), 4, row);
        }
        settle(level, bridge);
        Card pea = card(level, "pea_shooter");
        level.team(PLANT_TEAM).addResource(PvzceIds.SUN, 1000);
        int before = level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN);

        assertFalse(level.placePlant(bridge, pea.slot(), 4, 2), "a full column refuses the card");
        assertTrue(bridge.messages.contains("该格不能种植。"),
                "and says so: " + bridge.messages);
        assertEquals(before, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "nothing was paid for a placement that did not happen");
    }

    /** With the rule off, one click is one plant - and an occupied cell is refused, as before. */
    @Test
    void withoutTheRuleAPlacementIsStillOneCell() {
        LevelServer level = lawn(false);
        CapturingBridge bridge = new CapturingBridge();
        PlantDef wallNut = BuiltInRegistries.PLANTS.get(PvzceIds.id("wall_nut"));
        assertNotNull(wallNut);
        level.spawnPlant(wallNut, level.team(PLANT_TEAM), 4, 2);
        settle(level, bridge);
        Card pea = card(level, "pea_shooter");
        level.team(PLANT_TEAM).addResource(PvzceIds.SUN, 1000);

        assertFalse(level.placePlant(bridge, pea.slot(), 4, 2),
                "an occupied cell is still refused without the rule");
        assertTrue(level.placePlant(bridge, pea.slot(), 4, 3), "and a free one still plants");
        settle(level, bridge);
        assertEquals(1, plantsIn(level, "pea_shooter", 4), "one click, one plant");
    }

    /**
     * The client is told how far a card reaches, because its preview draws it.
     *
     * <p>The one rule that travels to the client, and the reason
     * {@code LevelPayload.plantsWholeColumn} exists; a preview that promised one cell and planted
     * five would be the worst possible version of this feature.
     */
    @Test
    void thePayloadCarriesTheRuleToTheClient() {
        LevelDef demo = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        assertNotNull(demo);
        Map<Identifier, JsonElement> rules = new HashMap<>(demo.rules());
        rules.put(PvzceIds.RULE_PLANT_WHOLE_COLUMN, new JsonPrimitive(true));
        LevelDef columns = com.pvzce.testutil.TestLevels.withRules(demo, rules);

        assertTrue(LevelServer.payloadFor(columns,
                        LevelServer.SeedContext.all(columns)).plantsWholeColumn(),
                "a level that plants in columns says so on the wire");
        assertFalse(LevelServer.payloadFor(demo,
                        LevelServer.SeedContext.all(demo)).plantsWholeColumn(),
                "and a level that does not, does not");
    }
}

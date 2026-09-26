package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.PlacementDef;
import com.pvzce.api.content.ResourceCost;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PlantPlacement;
import com.pvzce.common.core.Slot;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The purple packets: a plant that is planted <em>on</em> another plant, which it consumes.
 *
 * <p>Two halves, and both are asserted: the rule that decides whether an upgrade may go somewhere
 * ({@link PlantPlacement#canPlace}, which the client's own preview and the server's refusal both
 * read), and what happens when it does - the base is gone, the upgrade is in its cell, and the
 * player paid for one plant rather than two.
 */
class UpgradePlantTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier REPEATER = PvzceIds.id("repeater");
    private static final Identifier GATLING_PEA = PvzceIds.id("gatling_pea");
    private static final Identifier SUNFLOWER = PvzceIds.id("sunflower");
    private static final Identifier TWIN_SUNFLOWER = PvzceIds.id("twin_sunflower");
    private static final Identifier KERNEL_PULT = PvzceIds.id("kernel_pult");
    private static final Identifier LILY_PAD = PvzceIds.id("lily_pad");
    private static final Identifier CATTAIL = PvzceIds.id("cattail");
    private static final Identifier PEASHOOTER = PvzceIds.id("pea_shooter");

    private static LevelDef board;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        board = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_4"));
        assertNotNull(board, "the shipped 1-4 must load");
    }

    private static PlantDef plant(Identifier id) {
        PlantDef def = BuiltInRegistries.PLANTS.get(id);
        assertNotNull(def, id + " must be registered");
        return def;
    }

    /** A cell of the given terrain with the given plants already in it, bottom first. */
    private record CellContext(String terrain, List<PlantPlacement.PlantLayer> layers)
            implements PlantPlacement.Ctx {
        CellContext(String terrain, Identifier... plantsInCell) {
            this(terrain, layersOf(plantsInCell));
        }

        private static List<PlantPlacement.PlantLayer> layersOf(Identifier[] ids) {
            List<PlantPlacement.PlantLayer> layers = new ArrayList<>();
            int entityId = 1;
            for (Identifier id : ids) {
                layers.add(new PlantPlacement.PlantLayer(plant(id), entityId++));
            }
            return layers;
        }

        @Override
        public PlantPlacement.Terrain terrain(int x, int y) {
            var def = BuiltInRegistries.SCENE_ELEMENTS.get(PvzceIds.id(terrain));
            assertNotNull(def, "missing scene element " + terrain);
            return new PlantPlacement.Terrain(def, def.heightAt(x + 0.5F, 9));
        }

        @Override
        public List<PlantPlacement.PlantLayer> plants(int x, int y) {
            return layers.stream().sorted(PlantPlacement.bottomFirst()).toList();
        }
    }

    // ------------------------------------------------------------------
    // The rule
    // ------------------------------------------------------------------

    @Test
    void anUpgradeOnlyGoesOnItsOwnBase() {
        assertTrue(PlantPlacement.canPlace(plant(GATLING_PEA),
                        new CellContext("grass", REPEATER), 0, 0),
                "the repeater is what a gatling pea is planted on");
        assertFalse(PlantPlacement.canPlace(plant(GATLING_PEA),
                        new CellContext("grass", PEASHOOTER), 0, 0),
                "a peashooter is not a repeater: the card names one base plant, not a family");
        assertFalse(PlantPlacement.canPlace(plant(GATLING_PEA),
                        new CellContext("grass"), 0, 0),
                "and an empty cell is not a place to plant an upgrade at all");
        assertFalse(PlantPlacement.canPlace(plant(TWIN_SUNFLOWER),
                        new CellContext("grass", PEASHOOTER), 0, 0),
                "two upgrades, two different bases");
    }

    /**
     * A carrier is only upgradable while nothing is standing on it.
     *
     * <p>The original's own rule for the cattail ("if the Lily Pad is occupied, it can't be
     * upgraded") and the reason it generalises: the upgrade takes the carrier's place, so a plant
     * resting on the carrier would be left standing in the water.
     */
    @Test
    void aCarrierWithSomethingOnItCannotBeUpgraded() {
        assertTrue(PlantPlacement.canPlace(plant(CATTAIL),
                        new CellContext("water", LILY_PAD), 0, 0),
                "a bare lily pad is what a cattail is for");
        assertFalse(PlantPlacement.canPlace(plant(CATTAIL),
                        new CellContext("water", LILY_PAD, PEASHOOTER), 0, 0),
                "with a plant on the pad there is nothing to replace");
    }

    /**
     * The cob cannon's 2x1 footprint, asserted on the rule rather than on the plant.
     *
     * <p>The cannon itself is not in the game yet (it needs a targeting interaction - see
     * {@code docs/todo.md}), but the {@code adjacent} half of the rule is code that ships now, and
     * a synthetic definition is the only way to reach it: a rule nothing can call is a rule nothing
     * has tested.
     */
    @Test
    void aTwoByOneUpgradeNeedsItsNeighbour() {
        PlantDef cannon = new PlantDef(PvzceIds.id("test_cob_cannon"), ResourceCost.defaultPlantCost(),
                300, PlacementDef.PLANTABLE_DEF, List.<com.pvzce.api.content.capability.TypedCapability<PlantCapability>>of(),
                Optional.empty(), PlantDef.PlantSounds.EMPTY,
                com.pvzce.api.content.AnimationBindings.EMPTY, Optional.empty(),
                com.pvzce.api.content.ContentDefs.DEFAULT_RENDER_SCALE, 1000,
                Optional.of(new PlantDef.Upgrade(KERNEL_PULT, 1)));

        assertTrue(PlantPlacement.canPlace(cannon,
                        new KernelRow("grass", java.util.Set.of(0, 1)), 0, 0),
                "one kernel in this cell and one next to it is the original's 2x1 block");
        assertTrue(PlantPlacement.canPlace(cannon,
                        new KernelRow("grass", java.util.Set.of(-1, 0)), 0, 0),
                "and the neighbour may be on the left, which is the side the consumption prefers");
        assertFalse(PlantPlacement.canPlace(cannon,
                        new KernelRow("grass", java.util.Set.of(0)), 0, 0),
                "a lone kernel is not enough for the cannon");
        assertFalse(PlantPlacement.canPlace(cannon,
                        new KernelRow("grass", java.util.Set.of(0, 2)), 0, 0),
                "and a kernel two columns away is not adjacent");
    }

    /** A board whose named columns hold a kernel-pult each, and nothing else anywhere. */
    private record KernelRow(String terrain, java.util.Set<Integer> kernelColumns)
            implements PlantPlacement.Ctx {
        @Override
        public PlantPlacement.Terrain terrain(int x, int y) {
            var def = BuiltInRegistries.SCENE_ELEMENTS.get(PvzceIds.id(terrain));
            return new PlantPlacement.Terrain(def, def.heightAt(x + 0.5F, 9));
        }

        @Override
        public List<PlantPlacement.PlantLayer> plants(int x, int y) {
            return kernelColumns.contains(x)
                    ? List.of(new PlantPlacement.PlantLayer(plant(KERNEL_PULT), x + 1))
                    : List.of();
        }
    }

    // ------------------------------------------------------------------
    // What planting one does
    // ------------------------------------------------------------------

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }

    /** A level whose bar is exactly the two cards given, so a slot index means what it says. */
    private static LevelServer levelWith(List<Identifier> bar) {
        LevelServer level = new LevelServer(board, bar);
        level.team(PLANT_TEAM).putResource(PvzceIds.SUN, 1000);
        return level;
    }

    private static PlantEntity plantAt(LevelServer level, Identifier id, int x, int y) {
        return level.spawnPlant(plant(id), level.team(PLANT_TEAM), x, y);
    }

    @Test
    void plantingAnUpgradeConsumesThePlantItReplaces() {
        LevelServer level = levelWith(List.of(REPEATER, GATLING_PEA));
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity repeater = plantAt(level, REPEATER, 3, 2);
        level.flushPending(bridge);
        int before = level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN);

        assertTrue(level.placePlant(bridge, 1, 3, 2), "the gatling pea goes on the repeater");

        assertTrue(repeater.isRemoved(), "the repeater is consumed, quietly");
        assertEquals(1, livePlants(level, GATLING_PEA), "and the upgrade stands in its cell");
        assertEquals(0, livePlants(level, REPEATER), "with no leftover of the base");
        assertEquals(before - 250, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "one plant was paid for, not two");
    }

    @Test
    void anUpgradeOnTheWrongPlantIsRefusedWithAMessage() {
        LevelServer level = levelWith(List.of(REPEATER, GATLING_PEA));
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity peashooter = plantAt(level, PEASHOOTER, 3, 2);
        level.flushPending(bridge);
        int before = level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN);

        assertFalse(level.placePlant(bridge, 1, 3, 2), "a peashooter is not a repeater");
        assertFalse(peashooter.isRemoved(), "and nothing was taken off the lawn");
        assertEquals(before, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN), "nor charged");
        assertTrue(bridge.packets.stream().anyMatch(packet -> packet instanceof ServerMessageS2C),
                "the refusal is said out loud, like every other refused placement");
    }

    private static long livePlants(LevelServer level, Identifier id) {
        return level.entities().stream()
                .filter(entity -> entity instanceof PlantEntity plant && !plant.isRemoved()
                        && id.equals(plant.def().id()))
                .count();
    }

    /**
     * Every upgrade the game ships names a base that is a plant, and the card names the upgrade.
     *
     * <p>Data integrity rather than behaviour: a purple packet whose {@code upgrade.base} is a typo
     * is a card that can never be planted anywhere, and nothing else in the game would notice.
     */
    @Test
    void everyUpgradesBaseIsAPlantAndEveryCardResolves() {
        List<String> problems = new ArrayList<>();
        List<PlantDef> upgrades = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.PLANTS.keySet()) {
            PlantDef def = BuiltInRegistries.PLANTS.get(id);
            if (def == null || def.upgrade().isEmpty()) {
                continue;
            }
            upgrades.add(def);
            Identifier base = def.upgrade().get().base();
            if (BuiltInRegistries.PLANTS.get(base) == null) {
                problems.add(id + " upgrades " + base + ", which is not a plant");
            }
            if (SlotResolver.resolve(id).isEmpty()) {
                problems.add(id + " has no card, so the shop cannot sell it");
            }
            if (!SlotResolver.resolve(id).map(SlotResolver.ResolvedCard::kind)
                    .filter(kind -> kind == Slot.Kind.PLANT).isPresent()) {
                problems.add(id + "'s card is not a plant card");
            }
        }
        assertEquals(List.of(), problems);
        assertFalse(upgrades.isEmpty(), "the game ships upgrades, or this test proves nothing");
    }
}

package com.pvzce.server;

import com.pvzce.api.content.FusionData;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PlantRecipes;
import com.pvzce.common.level.FusionState;
import com.pvzce.common.level.FusionDrops;
import com.pvzce.common.level.mechanic.FusionMechanic;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.FusionActionC2S;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.CardDropEntity;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class FusionTest {
    private static final Identifier SHOOTER = PvzceIds.id("shooter"), PRODUCER = PvzceIds.id("producer");
    private static final class Bridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();
        @Override public void send(PvzcePacket packet) { packets.add(packet); }
    }
    @BeforeAll static void load() throws Exception { TestContent.loadBuiltInContentAndTags(); }
    private static LevelDef shipped(int number) {
        return BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/fusion_" + number));
    }
    private static LevelServer level(FusionData data, Predicate<Identifier> owns) {
        List<TypedMechanic> mechanics = new ArrayList<>(shipped(1).mechanics());
        mechanics.set(0, new TypedMechanic(PvzceIds.MECHANIC_FUSION, data));
        LevelDef def = TestLevels.copy(shipped(1)).mechanics(mechanics).build();
        LevelServer level = new LevelServer(def, List.of(), LevelServer.SeedContext.all(def), List.of(), owns);
        level.random().setSeed(17L); level.flushPending(new Bridge()); return level;
    }
    private static FusionState state(LevelServer level) { return FusionMechanic.snapshot(level, FusionMechanic.data(level)); }
    private static int amount(List<FusionState.Count> counts, Identifier ability) {
        return counts.stream().filter(c -> c.ability().equals(ability.toString())).mapToInt(FusionState.Count::amount).sum();
    }
    private static boolean action(LevelServer level, Bridge bridge, String action, Identifier ability) {
        return level.fusionAction(bridge, new FusionActionC2S(action, ability == null ? "" : ability.toString(), -1));
    }
    private static void dig(LevelServer level, Bridge bridge, int row) {
        assertTrue(level.useTool(bridge, level.plantPlayer().slots().getFirst().index(), 2, row));
        action(level, bridge, "collect", null);
    }
    private static List<CardDropEntity> cards(LevelServer level) {
        return level.entities().stream().filter(e -> e instanceof CardDropEntity && !e.isRemoved()).map(e -> (CardDropEntity) e).toList();
    }

    @Test void availableRecipesDeduplicatePlantVariantsAndRequireEveryMaterial() {
        var materials = java.util.Map.of(SHOOTER, 1, PRODUCER, 1,
                PvzceIds.id("defense"), 1, PvzceIds.id("carrier"), 1);
        List<String> owned = List.of("pea_shooter", "repeater", "gatling_pea", "sunflower", "twin_sunflower", "pumpkin");
        var recipes = PlantRecipes.availableRecipes(materials, id -> owned.contains(id.path()));
        assertEquals(3, recipes.size(), "three shooter variants still contribute only one combination");
        assertTrue(recipes.containsAll(List.of(List.of(SHOOTER), List.of(PRODUCER),
                List.of(PvzceIds.id("carrier"), PvzceIds.id("defense")))));
        assertTrue(PlantRecipes.availableRecipes(java.util.Map.of(PvzceIds.id("defense"), 9),
                PvzceIds.id("pumpkin")::equals).isEmpty(), "extra defence cannot stand in for missing carrying");
        assertTrue(PlantRecipes.availableRecipes(materials, id -> false).isEmpty());
        assertTrue(PlantRecipes.availableRecipes(java.util.Map.of(), id -> true).isEmpty());
    }

    @Test void randomFillReturnsOldTrayMaterialsAndOnlyLoadsACompleteOwnedRecipe() {
        LevelServer level = level(new FusionData(false, 0F, 0F, 25), PvzceIds.id("pumpkin")::equals);
        Bridge bridge = new Bridge();
        dig(level, bridge, 0); assertTrue(action(level, bridge, "add", SHOOTER));
        level.spawnCardDrop(PvzceIds.id("pumpkin"), 0, 0); level.flushPending(bridge);
        assertTrue(recycle(level, bridge, cards(level).getFirst().id()));
        assertTrue(action(level, bridge, "random", null));
        assertEquals(1, amount(state(level).inventory(), SHOOTER));
        assertEquals(0, amount(state(level).inventory(), PvzceIds.id("defense")));
        assertEquals(0, amount(state(level).inventory(), PvzceIds.id("carrier")));
        assertEquals(1, amount(state(level).tray(), PvzceIds.id("defense")));
        assertEquals(1, amount(state(level).tray(), PvzceIds.id("carrier")));
        assertEquals(2, state(level).tray().stream().mapToInt(FusionState.Count::amount).sum());
        assertTrue(cards(level).isEmpty(), "random filling is not automatic crafting");
        FusionState once = state(level);
        assertTrue(action(level, bridge, "random", null));
        assertEquals(once, state(level), "drawing again also reuses the current tray without duplicating materials");
        assertTrue(action(level, bridge, "fuse", null));
        assertEquals(PvzceIds.id("pumpkin"), cards(level).getFirst().card());
        assertEquals(0, level.plantPlayer().team().resourcesOf(PvzceIds.SUN));
    }

    @Test void failedRandomFillPreservesMaterialsAndPlaysOneErrorSound() {
        LevelServer level = level(new FusionData(false, 0F, 0F, 25), PvzceIds.id("pumpkin")::equals);
        Bridge bridge = new Bridge();
        dig(level, bridge, 0); assertTrue(action(level, bridge, "add", SHOOTER));
        level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("pumpkin")), level.plantPlayer().team(), 4, 0);
        level.flushPending(bridge);
        assertTrue(level.useTool(bridge, level.plantPlayer().slots().getFirst().index(), 4, 0));
        FusionState before = state(level); assertEquals(2, before.drops().size());
        bridge.packets.clear();
        assertFalse(action(level, bridge, "random", null));
        assertEquals(before.inventory(), state(level).inventory());
        assertEquals(before.tray(), state(level).tray());
        assertEquals(before.drops(), state(level).drops(), "uncollected ground materials are not usable inventory");
        assertEquals(before.tutorialStep(), state(level).tutorialStep());
        assertFalse(state(level).success());
        assertEquals(1, bridge.packets.stream().filter(p -> p instanceof EffectEventS2C e && e.sound().endsWith("buzzer")).count());
        assertTrue(cards(level).isEmpty());
        assertTrue(action(level, bridge, "collect", null));
        assertTrue(action(level, bridge, "random", null));

        LevelServer locked = level(new FusionData(false, 0F, 0F, 25), id -> false);
        dig(locked, bridge, 0);
        assertFalse(action(locked, bridge, "random", null), "materials alone cannot bypass the backpack");
        assertEquals(1, amount(state(locked).inventory(), SHOOTER));
    }

    @Test void randomFillingAdvancesTheLessonAndExcludesUnplantableTeachingRecipes() {
        LevelServer level = level(new FusionData(true, 0F, 0F, 25),
                id -> List.of("pea_shooter", "lily_pad").contains(id.path()));
        Bridge bridge = new Bridge();
        dig(level, bridge, 0);
        level.plantPlayer().team().addResource(PvzceIds.SUN, 25);
        assertTrue(action(level, bridge, "buy", PvzceIds.id("carrier")));
        assertTrue(action(level, bridge, "random", null));
        assertEquals(2, state(level).tutorialStep());
        assertEquals(List.of(new FusionState.Count(SHOOTER.toString(), 1)), state(level).tray());
        assertEquals(1, amount(state(level).inventory(), PvzceIds.id("carrier")));
        assertTrue(action(level, bridge, "fuse", null));
        assertTrue(level.pickUpCardDrop(bridge, cards(level).getFirst().id()));
        assertTrue(level.plantHeldCard(bridge, 2, 0));
        assertEquals(4, state(level).tutorialStep());
    }

    @Test void recipesIncludeMarkersAndIgnoreParametersButRetainDuplicates() {
        assertEquals(List.of(PvzceIds.id("defense")), PlantRecipes.recipe(BuiltInRegistries.PLANTS.get(PvzceIds.id("wall_nut"))));
        assertEquals(List.of(PvzceIds.id("carrier")), PlantRecipes.recipe(BuiltInRegistries.PLANTS.get(PvzceIds.id("lily_pad"))));
        var pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        var repeated = new com.pvzce.api.content.PlantDef(pea.id(), pea.cost(), pea.health(), pea.placement(),
                List.of(pea.capabilities().getFirst(), pea.capabilities().getFirst()), java.util.Optional.empty(),
                pea.sounds(), pea.animations(), pea.texture());
        assertEquals(List.of(SHOOTER, SHOOTER), PlantRecipes.recipe(repeated));
        List<Identifier> candidates = PlantRecipes.candidates(List.of(SHOOTER), id -> id.equals(PvzceIds.id("pea_shooter")) || id.equals(PvzceIds.id("repeater")));
        assertEquals(2, candidates.size());
        assertTrue(PlantRecipes.candidates(List.of(SHOOTER, SHOOTER), id -> true).isEmpty());
        assertTrue(PlantRecipes.candidates(List.of(), id -> true).isEmpty());
    }

    @Test void tutorialWaitsForActualPlantingAndFirstDigIsLossless() {
        LevelServer level = level(new FusionData(true, 1F, 0F, 25), id -> id.equals(PvzceIds.id("pea_shooter")));
        Bridge bridge = new Bridge(); assertTrue(level.isPreparing());
        level.beginWaves(); assertTrue(level.isPreparing());
        assertEquals(PvzceIds.id("shovel"), level.plantPlayer().slots().getFirst().defId());
        assertEquals(5, level.entities().stream().filter(e -> e instanceof PlantEntity).count());
        dig(level, bridge, 0); assertEquals(1, amount(state(level).inventory(), SHOOTER));
        assertEquals(1, state(level).tutorialStep());
        action(level, bridge, "add", SHOOTER); assertEquals(2, state(level).tutorialStep());
        action(level, bridge, "fuse", null); assertEquals(3, state(level).tutorialStep());
        assertTrue(level.isPreparing()); assertEquals(1, cards(level).size());
        assertTrue(level.pickUpCardDrop(bridge, cards(level).getFirst().id()));
        assertFalse(level.plantHeldCard(bridge, -1, 0)); assertTrue(level.isPreparing());
        assertTrue(level.plantHeldCard(bridge, 2, 0)); assertFalse(level.isPreparing());
        assertEquals(0, level.plantPlayer().team().resourcesOf(PvzceIds.SUN));
        dig(level, bridge, 1); assertEquals(0, amount(state(level).inventory(), SHOOTER), "a one-ability plant can lose its only ability");
    }

    @Test void compositePlantsCraftAndDecomposeThroughTheirSharedMaterials() {
        for (String name : List.of("pumpkin", "cob_cannon")) {
            Identifier plantId = PvzceIds.id(name);
            var def = BuiltInRegistries.PLANTS.get(plantId);
            List<Identifier> expected = name.equals("pumpkin")
                    ? List.of(PvzceIds.id("carrier"), PvzceIds.id("defense"))
                    : List.of(PvzceIds.id("explosive"), PvzceIds.id("thrower"));
            assertEquals(expected, PlantRecipes.recipe(def));
            assertEquals(List.of(plantId), PlantRecipes.candidates(expected.reversed(), id -> true));
            assertTrue(PlantRecipes.candidates(expected, id -> false).isEmpty());
            assertTrue(PlantRecipes.candidates(expected.subList(0, 1), plantId::equals).isEmpty());

            LevelServer level = level(new FusionData(false, 0F, 0F, 25), plantId::equals);
            Bridge bridge = new Bridge();
            level.spawnCardDrop(plantId, 0, 0); level.flushPending(bridge);
            assertTrue(recycle(level, bridge, cards(level).getFirst().id()));
            for (Identifier ability : expected) {
                assertEquals(1, amount(state(level).inventory(), ability));
                assertTrue(action(level, bridge, "add", ability));
            }
            assertTrue(action(level, bridge, "fuse", null));
            CardDropEntity packet = cards(level).getFirst();
            assertEquals(plantId, packet.card());
            assertTrue(level.pickUpCardDrop(bridge, packet.id()));
            assertTrue(level.plantHeldCard(bridge, 4, 0));
            PlantEntity plant = level.plantAt(4, 0);
            assertNotNull(plant); assertEquals(plantId, plant.defId());
            // Recipe expansion must not replace the behaviour implementing shell placement or cannon aiming.
            assertEquals(name.equals("pumpkin") ? PvzceIds.id("shell") : plantId,
                    plant.def().resolvedCapabilities().getFirst().type());
            assertTrue(level.useTool(bridge, level.plantPlayer().slots().getFirst().index(), 4, 0));
            assertTrue(plant.isRemoved());
            assertTrue(state(level).inventory().isEmpty());
            assertEquals(expected.stream().map(Identifier::toString).toList(),
                    state(level).drops().stream().map(FusionState.Drop::ability).toList());
            assertTrue(action(level, bridge, "collect", null));
            for (Identifier ability : expected) assertEquals(1, amount(state(level).inventory(), ability));
        }
    }

    @Test void compositeTokensAreExcludedFromPurchaseAndZombieDrops() {
        List<Identifier> pool = PlantRecipes.abilities();
        assertFalse(pool.contains(PvzceIds.id("shell")));
        assertFalse(pool.contains(PvzceIds.id("cob_cannon")));
        assertTrue(pool.containsAll(List.of(PvzceIds.id("carrier"), PvzceIds.id("defense"),
                PvzceIds.id("explosive"), PvzceIds.id("thrower"))));
        assertEquals(pool.size(), pool.stream().distinct().count());
        LevelServer level = level(new FusionData(false, 0F, 2F, 25), id -> true);
        Bridge bridge = new Bridge();
        level.spawnResource(PvzceIds.SUN, 100, 1F, 1F, level.plantPlayer().team()); level.flushPending(bridge);
        ResourceDropEntity sun = (ResourceDropEntity) level.entities().stream()
                .filter(e -> e instanceof ResourceDropEntity).findFirst().orElseThrow();
        assertTrue(level.collectResource(bridge, sun.id()));
        assertFalse(action(level, bridge, "buy", PvzceIds.id("shell")));
        assertFalse(action(level, bridge, "buy", PvzceIds.id("cob_cannon")));
        assertEquals(100, level.plantPlayer().team().resourcesOf(PvzceIds.SUN));
        assertTrue(action(level, bridge, "buy", PvzceIds.id("defense")));
        assertEquals(75, level.plantPlayer().team().resourcesOf(PvzceIds.SUN));
        ZombieEntity zombie = new ZombieEntity(BuiltInRegistries.ZOMBIES.get(PvzceIds.id("buckethead_zombie")),
                level.team(PvzceIds.ZOMBIE_TEAM), 5F, 0);
        for (int i = 0; i < 100; i++) FusionMechanic.defeated(level, zombie);
        assertFalse(state(level).drops().isEmpty());
        assertTrue(state(level).drops().stream().allMatch(drop -> pool.contains(Identifier.tryParse(drop.ability()))));
    }

    private static CompoundTag savedToken(String ability, int amount) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Ability", PvzceIds.id(ability).toString()); tag.putInt("Amount", amount);
        return tag;
    }

    @Test void legacyInventoryTrayAndDropsExpandWithoutDuplicatingOnTheNextRestore() {
        LevelServer level = level(new FusionData(false, 0F, 0F, 25), id -> true);
        CompoundTag root = new CompoundTag(), fusion = new CompoundTag();
        ListTag inventory = new ListTag(), tray = new ListTag(), drops = new ListTag();
        inventory.add(savedToken("shell", 2)); inventory.add(savedToken("defense", 3));
        inventory.add(savedToken("cob_cannon", 1));
        tray.add(savedToken("shell", 1)); tray.add(savedToken("cob_cannon", 1));
        List<String> oldAbilities = List.of("shell", "shooter", "cob_cannon");
        for (int i = 0; i < oldAbilities.size(); i++) {
            CompoundTag drop = savedToken(oldAbilities.get(i), 1);
            drop.putInt("Id", 7 + i); drop.putFloat("X", i == 2 ? level.width() - 0.5F : 0.5F);
            drop.putFloat("Y", 0.5F); drops.add(drop);
        }
        fusion.put("Inventory", inventory); fusion.put("Tray", tray); fusion.put("Drops", drops);
        fusion.putInt("Step", 4); fusion.putInt("NextDrop", 1); root.put("Fusion", fusion);
        new FusionMechanic().applySave(level, FusionMechanic.data(level), root);
        FusionState migrated = state(level);
        assertEquals(5, amount(migrated.inventory(), PvzceIds.id("defense")));
        assertEquals(2, amount(migrated.inventory(), PvzceIds.id("carrier")));
        assertEquals(1, amount(migrated.inventory(), PvzceIds.id("explosive")));
        assertEquals(1, amount(migrated.inventory(), PvzceIds.id("thrower")));
        assertEquals(4, migrated.tray().stream().mapToInt(FusionState.Count::amount).sum());
        assertEquals(5, migrated.drops().size());
        assertEquals(5, migrated.drops().stream().map(FusionState.Drop::id).distinct().count());
        assertTrue(migrated.drops().stream().map(FusionState.Drop::id).toList().containsAll(List.of(7, 8, 9)));
        assertTrue(migrated.drops().stream().allMatch(drop -> drop.x() >= 0.5F && drop.x() <= level.width() - 0.5F));
        assertTrue(migrated.drops().stream().allMatch(drop -> PlantRecipes.abilities().contains(Identifier.tryParse(drop.ability()))));
        LevelServer restored = level(new FusionData(false, 0F, 0F, 25), id -> true);
        restored.restore(level.save()); assertEquals(migrated, state(restored));
        Bridge bridge = new Bridge();
        assertTrue(restored.fusionAction(bridge, new FusionActionC2S("collect", "", 7)));
        assertFalse(restored.fusionAction(bridge, new FusionActionC2S("collect", "", 7)));
        assertTrue(action(restored, bridge, "collect", null));
        assertEquals(6, amount(state(restored).inventory(), PvzceIds.id("defense")));
        assertEquals(3, amount(state(restored).inventory(), PvzceIds.id("carrier")));
        assertEquals(2, amount(state(restored).inventory(), PvzceIds.id("explosive")));
        assertEquals(2, amount(state(restored).inventory(), PvzceIds.id("thrower")));
        assertEquals(1, amount(state(restored).inventory(), SHOOTER));
    }

    @Test void legacyTrayExpansionReturnsOverflowToInventoryWithoutLosingMaterials() {
        LevelServer level = level(new FusionData(false, 0F, 0F, 25), id -> true);
        CompoundTag root = new CompoundTag(), fusion = new CompoundTag(); ListTag tray = new ListTag();
        for (int i = 0; i < com.pvzce.common.PvzceConstants.FUSION_TRAY_CAPACITY; i++) tray.add(savedToken("cob_cannon", 1));
        fusion.put("Tray", tray); fusion.putInt("Step", 4); root.put("Fusion", fusion);
        new FusionMechanic().applySave(level, FusionMechanic.data(level), root);
        assertEquals(com.pvzce.common.PvzceConstants.FUSION_TRAY_CAPACITY,
                state(level).tray().stream().mapToInt(FusionState.Count::amount).sum());
        assertTrue(action(level, new Bridge(), "clear", null));
        assertEquals(com.pvzce.common.PvzceConstants.FUSION_TRAY_CAPACITY,
                amount(state(level).inventory(), PvzceIds.id("explosive")));
        assertEquals(com.pvzce.common.PvzceConstants.FUSION_TRAY_CAPACITY,
                amount(state(level).inventory(), PvzceIds.id("thrower")));
    }

    @Test void failedRecipeLosesExactlyOneAndReturnsTheRestWithoutUnlockBypass() {
        LevelServer level = level(new FusionData(false, 0F, 0F, 25), id -> false); Bridge bridge = new Bridge();
        dig(level, bridge, 0); dig(level, bridge, 1); dig(level, bridge, 2);
        action(level, bridge, "add", SHOOTER); action(level, bridge, "add", SHOOTER);
        assertTrue(action(level, bridge, "fuse", null));
        assertEquals(2, amount(state(level).inventory(), SHOOTER)); assertTrue(state(level).tray().isEmpty());
        assertTrue(cards(level).isEmpty());
        assertTrue(bridge.packets.stream().anyMatch(p -> p instanceof EffectEventS2C e && e.sound().endsWith("buzzer")));
        action(level, bridge, "add", SHOOTER); action(level, bridge, "fuse", null);
        assertTrue(cards(level).isEmpty(), "even a matching recipe cannot grant a locked plant");
        assertFalse(action(level, bridge, "add", PvzceIds.id("unknown")));
    }

    @Test void teachingCraftCanPlantACattailWithoutALilyPad() {
        Predicate<Identifier> owns = id -> id.equals(PvzceIds.id("cattail"));
        assertTrue(PlantRecipes.candidates(List.of(SHOOTER), owns).contains(PvzceIds.id("cattail")));
        LevelServer level = level(new FusionData(true, 0F, 0F, 25), owns); Bridge bridge = new Bridge();
        dig(level, bridge, 0); action(level, bridge, "add", SHOOTER); action(level, bridge, "fuse", null);
        CardDropEntity packet = cards(level).getFirst();
        assertEquals(PvzceIds.id("cattail"), packet.card());
        assertTrue(level.pickUpCardDrop(bridge, packet.id()));
        assertTrue(level.plantHeldCard(bridge, 0, 0));
        assertFalse(level.isPreparing());
    }

    @Test void buyingConsumesSunAndProducedSunCanBeCollectedWithoutAResourceCard() {
        LevelServer level = level(new FusionData(false, 0F, 0F, 25), id -> id.equals(PvzceIds.id("sunflower"))); Bridge bridge = new Bridge();
        assertFalse(action(level, bridge, "buy", PRODUCER));
        level.spawnResource(PvzceIds.SUN, 25, 1F, 1F, level.plantPlayer().team()); level.flushPending(bridge);
        ResourceDropEntity sun = (ResourceDropEntity) level.entities().stream().filter(e -> e instanceof ResourceDropEntity).findFirst().orElseThrow();
        assertTrue(level.collectResource(bridge, sun.id())); assertTrue(action(level, bridge, "buy", PRODUCER));
        assertEquals(0, level.plantPlayer().team().resourcesOf(PvzceIds.SUN));
        action(level, bridge, "add", PRODUCER); action(level, bridge, "fuse", null);
        assertEquals(PvzceIds.id("sunflower"), cards(level).getFirst().card());
        assertTrue(bridge.packets.stream().anyMatch(p -> p instanceof EffectEventS2C e && e.sound().endsWith("chime")));
    }

    @Test void inventoryTrayDropsAndTutorialSurviveSaveAndWireRoundTrip() {
        LevelServer level = level(new FusionData(true, 0F, 2F, 25), id -> true); Bridge bridge = new Bridge();
        dig(level, bridge, 0); dig(level, bridge, 1); action(level, bridge, "add", SHOOTER);
        ZombieEntity zombie = new ZombieEntity(BuiltInRegistries.ZOMBIES.get(PvzceIds.id("basic_zombie")), level.team(PvzceIds.ZOMBIE_TEAM), 5F, 0);
        FusionMechanic.defeated(level, zombie); assertFalse(state(level).drops().isEmpty());
        FusionState before = state(level);
        LevelServer restored = level(new FusionData(true, 0F, 2F, 25), id -> true); restored.restore(level.save());
        assertEquals(before, state(restored));
        MechanicSyncS2C wire = MechanicSyncS2C.of(PvzceIds.MECHANIC_FUSION, FusionState.CODEC, before);
        assertEquals(before, FusionState.CODEC.decode(wire.payloadBuffer()));
        action(restored, bridge, "collect", null); assertTrue(state(restored).drops().isEmpty());
        action(restored, bridge, "clear", null);
        int droppedShooters = (int) before.drops().stream().filter(d -> d.ability().equals(SHOOTER.toString())).count();
        assertEquals(2 + droppedShooters, amount(state(restored).inventory(), SHOOTER));
    }

    private static boolean recycle(LevelServer level, Bridge bridge, int id) {
        return level.fusionAction(bridge, new FusionActionC2S("recycle", "", id));
    }

    @Test void aGroundCardCanBeRecycledOnlyOnce() {
        LevelServer level = level(new FusionData(false, 0F, 0F, 25), id -> true); Bridge bridge = new Bridge();
        level.spawnCardDrop(PvzceIds.id("cattail"), 0, 0); level.flushPending(bridge);
        CardDropEntity packet = cards(level).getFirst();
        assertTrue(level.canPlacePlant(BuiltInRegistries.PLANTS.get(packet.card()), 0, 0));
        assertTrue(recycle(level, bridge, packet.id())); assertTrue(packet.isRemoved());
        assertEquals(1, amount(state(level).inventory(), SHOOTER));
        assertFalse(recycle(level, bridge, packet.id()));
        assertFalse(level.pickUpCardDrop(bridge, packet.id()));
        assertEquals(1, amount(state(level).inventory(), SHOOTER));
        assertTrue(bridge.packets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.EntityDespawnS2C d && d.entityId() == packet.id()));
    }

    @Test void heldCardRecyclingClearsTheHandAndStillPaysLossDuringTeaching() {
        LevelServer level = level(new FusionData(true, 1F, 0F, 25), id -> true); Bridge bridge = new Bridge();
        level.spawnCardDrop(PvzceIds.id("pea_shooter"), 0, 0); level.flushPending(bridge);
        CardDropEntity packet = cards(level).getFirst();
        assertTrue(level.pickUpCardDrop(bridge, packet.id())); assertNotNull(level.heldCard());
        assertTrue(recycle(level, bridge, packet.id())); assertNull(level.heldCard());
        assertTrue(state(level).inventory().isEmpty(), "packet recycling never takes the lossless first-dig exception");
        assertFalse(level.plantHeldCard(bridge, 0, 0));
        assertTrue(bridge.packets.contains(com.pvzce.common.network.packet.HeldCardS2C.NONE));
    }

    @Test void aMultiAbilityPacketLosesOneCopyAndTheTutorialCanBeRepeatedAfterRecycling() {
        LevelServer lossy = level(new FusionData(false, 1F, 0F, 25), id -> true); Bridge bridge = new Bridge();
        lossy.spawnCardDrop(PvzceIds.id("puff_shroom"), 0, 0); lossy.flushPending(bridge);
        int expected = PlantRecipes.recipe(BuiltInRegistries.PLANTS.get(PvzceIds.id("puff_shroom"))).size() - 1;
        assertTrue(expected > 0); assertTrue(recycle(lossy, bridge, cards(lossy).getFirst().id()));
        assertEquals(expected, state(lossy).inventory().stream().mapToInt(FusionState.Count::amount).sum());

        LevelServer teaching = level(new FusionData(true, 0F, 0F, 25), id -> id.equals(PvzceIds.id("pea_shooter")));
        dig(teaching, bridge, 0); action(teaching, bridge, "add", SHOOTER); action(teaching, bridge, "fuse", null);
        assertTrue(recycle(teaching, bridge, cards(teaching).getFirst().id()));
        assertEquals(1, state(teaching).tutorialStep());
        action(teaching, bridge, "add", SHOOTER); action(teaching, bridge, "fuse", null);
        assertTrue(teaching.pickUpCardDrop(bridge, cards(teaching).getFirst().id()));
        assertTrue(teaching.plantHeldCard(bridge, 2, 0)); assertFalse(teaching.isPreparing());
    }

    @Test void staleForeignNonCardAndFinishedRunRequestsCannotYieldAbilities() {
        LevelServer level = level(new FusionData(false, 0F, 0F, 25), id -> true); Bridge bridge = new Bridge();
        CardDropEntity foreign = new CardDropEntity(PvzceIds.id("pea_shooter"), level.team(PvzceIds.ZOMBIE_TEAM), 0, 0);
        level.addEntity(foreign); level.flushPending(bridge);
        assertFalse(recycle(level, bridge, foreign.id())); assertFalse(foreign.isRemoved());
        assertFalse(recycle(level, bridge, level.plantAt(2, 0).id())); assertFalse(recycle(level, bridge, -1));
        CardDropEntity expired = new CardDropEntity(PvzceIds.id("pea_shooter"), level.plantPlayer().team(), 0, 1, 1);
        level.addEntity(expired); level.flushPending(bridge); expired.tick(level);
        assertFalse(recycle(level, bridge, expired.id()));
        level.spawnCardDrop(PvzceIds.id("pea_shooter"), 0, 2); level.flushPending(bridge);
        var owned = cards(level).stream().filter(c -> c.team().equals(level.plantPlayer().team())).findFirst().orElseThrow();
        level.declareVictory(); assertFalse(recycle(level, bridge, owned.id()));
        assertTrue(state(level).inventory().isEmpty());
    }

    @Test void allShippedLevelsStartWithFivePeasAndOnlyTheOrdinaryShovelWithoutSkySun() {
        for (int i = 1; i <= 3; i++) {
            LevelDef def = shipped(i); assertNotNull(def);
            assertTrue(LevelMechanics.validate(def).isEmpty());
            LevelServer level = new LevelServer(def); level.flushPending(new Bridge());
            assertEquals(5, level.entities().stream().filter(e -> e instanceof PlantEntity p && p.defId().equals(PvzceIds.id("pea_shooter"))).count());
            assertEquals(1, level.plantPlayer().slots().size());
            assertEquals(com.pvzce.common.core.Slot.Kind.TOOL, level.plantPlayer().slots().getFirst().kind());
            assertEquals(0, def.initialSun());
            assertEquals(i == 1, FusionMechanic.data(level).tutorial());
        }
    }

    /** A real run, without injected sun: keep the five lanes and reinvest earned production. */
    @Test void simulateShippedEconomyAndPacing() {
        for (int number = 1; number <= 3; number++) {
            LevelDef def = shipped(number);
            LevelServer level = new LevelServer(def, List.of(), LevelServer.SeedContext.all(def), List.of(),
                    id -> List.of("pea_shooter", "repeater", "sunflower", "wall_nut").contains(id.path()));
            Bridge bridge = new Bridge(); level.random().setSeed(20261008L); level.flushPending(bridge);
            if (number == 1) {
                dig(level, bridge, 0); action(level, bridge, "add", SHOOTER); action(level, bridge, "fuse", null);
                level.pickUpCardDrop(bridge, cards(level).getFirst().id()); level.plantHeldCard(bridge, 2, 0);
            }
            int elapsed = 0, peak = 0, produced = 0, bought = 0, collectedAbilities = 0;
            while ("running".equals(level.gameState()) && elapsed < 60 * 1800) {
                level.tick(bridge); level.flushPending(bridge); elapsed++;
                peak = Math.max(peak, (int) level.aliveZombieCount());
                if (elapsed % 60 != 0) continue;
                collectedAbilities += state(level).drops().size();
                action(level, bridge, "collect", null);
                for (var entity : level.entities()) if (entity instanceof ResourceDropEntity drop && !drop.isRemoved()) {
                    if (level.collectResource(bridge, drop.id()) && drop.defId().equals(PvzceIds.SUN)) produced += drop.amount();
                }
                if (level.plantPlayer().team().resourcesOf(PvzceIds.SUN) >= 25) {
                    if (action(level, bridge, "buy", SHOOTER)) bought++;
                }
                Identifier ability = amount(state(level).inventory(), PRODUCER) > 0 ? PRODUCER :
                        amount(state(level).inventory(), SHOOTER) > 0 ? SHOOTER : null;
                if (ability != null) { action(level, bridge, "add", ability); action(level, bridge, "fuse", null); }
                for (CardDropEntity card : cards(level)) {
                    if (card.held()) continue;
                    boolean planted = false;
                    for (int x = 0; x < 7 && !planted; x++) for (int y = 0; y < 5 && !planted; y++) {
                        var plant = BuiltInRegistries.PLANTS.get(card.card());
                        if (!level.canPlacePlant(plant, x, y)) continue;
                        if (level.pickUpCardDrop(bridge, card.id())) planted = level.plantHeldCard(bridge, x, y);
                    }
                }
                bridge.packets.clear();
            }
            System.out.printf("[FUSION] level=%d multiplier=%.2f outcome=%s seconds=%.1f kills=%d peak=%d abilityDrops=%d sunCollected=%d purchased=%d inventory=%d%n",
                    number, FusionMechanic.data(level).dropMultiplier(), level.gameState(), elapsed / 60F, level.zombieKills(), peak, collectedAbilities, produced, bought,
                    state(level).inventory().stream().mapToInt(FusionState.Count::amount).sum());
            assertNotEquals("running", level.gameState(), "shipped waves must reach an outcome in a bounded run");
        }
    }

    @Test void aShovelledPlantLeavesPersistentClickableAbilitiesInsteadOfCreditingInventory() {
        LevelServer level = level(new FusionData(true, 1F, 0F, 25), id -> true); Bridge bridge = new Bridge();
        PlantEntity plant = level.plantAt(2, 0);
        float x = plant.cellX(), y = plant.cellY();
        int slot = level.plantPlayer().slots().getFirst().index();
        assertTrue(level.useTool(bridge, slot, 2, 0));
        assertTrue(plant.isRemoved()); assertTrue(state(level).inventory().isEmpty());
        FusionState.Drop drop = state(level).drops().getFirst();
        assertEquals(x, drop.x(), 0.001F); assertEquals(y, drop.y(), 0.001F);
        assertFalse(action(level, bridge, "add", SHOOTER));
        LevelServer restored = level(new FusionData(true, 1F, 0F, 25), id -> true); restored.restore(level.save());
        assertEquals(state(level), state(restored));
        assertTrue(restored.fusionAction(bridge, new FusionActionC2S("collect", "", drop.id())));
        assertEquals(1, amount(state(restored).inventory(), SHOOTER));
        assertFalse(restored.fusionAction(bridge, new FusionActionC2S("collect", "", drop.id())));
        assertTrue(restored.useTool(bridge, slot, 2, 1));
        assertTrue(state(restored).drops().isEmpty(), "later digs still pay the configured loss chance");
    }

    @Test void threatAndLevelMultiplierSelectAnExactDropQuantityWithNoIndependentExtraRolls() {
        var basic = BuiltInRegistries.ZOMBIES.get(PvzceIds.id("basic_zombie"));
        var cone = BuiltInRegistries.ZOMBIES.get(PvzceIds.id("conehead_zombie"));
        var bucket = BuiltInRegistries.ZOMBIES.get(PvzceIds.id("buckethead_zombie"));
        assertEquals(1, FusionDrops.count(basic, 1F, 0.39F));
        assertEquals(2, FusionDrops.count(basic, 1F, 0.41F));
        assertEquals(0, FusionDrops.count(basic, 1F, 0.51F));
        assertEquals(1, FusionDrops.count(cone, 1F, 0.49F));
        assertEquals(2, FusionDrops.count(cone, 1F, 0.51F));
        assertEquals(0, FusionDrops.count(cone, 1F, 0.71F));
        assertEquals(1, FusionDrops.count(bucket, 1F, 0.49F));
        assertEquals(2, FusionDrops.count(bucket, 1F, 0.51F));
        assertEquals(3, FusionDrops.count(bucket, 1F, 0.76F));
        assertEquals(0, FusionDrops.count(bucket, 1F, 0.86F));
        assertEquals(0, FusionDrops.count(basic, 0F, 0F));
        assertEquals(2, FusionDrops.count(basic, 0.5F, 0.24F));
        assertEquals(0, FusionDrops.count(basic, 0.5F, 0.26F));
        assertEquals(2, FusionDrops.count(basic, 4F, 0.99F));
        assertEquals(4, FusionDrops.count(BuiltInRegistries.ZOMBIES.get(PvzceIds.id("gargantuar")), 1F, 0.95F));
        assertEquals(4, FusionDrops.count(BuiltInRegistries.ZOMBIES.get(PvzceIds.id("football_zombie")), 1F, 0.95F));

        // A high multiplier must create distinct, in-bounds ground tokens, never credit the bag.
        LevelServer level = level(new FusionData(false, 0F, 4F, 25), id -> true);
        ZombieEntity zombie = new ZombieEntity(bucket, level.team(PvzceIds.ZOMBIE_TEAM), 20F, 0);
        level.random().setSeed(17L);
        FusionMechanic.defeated(level, zombie);
        assertTrue(state(level).inventory().isEmpty());
        assertTrue(state(level).drops().size() >= 2);
        assertEquals(state(level).drops().size(), state(level).drops().stream().map(FusionState.Drop::x).distinct().count());
        assertTrue(state(level).drops().stream().allMatch(d -> d.x() >= 0.5F && d.x() <= 8.5F));
    }

    @Test void everyPurplePacketPlantsDirectlyInAllFusionLevelsAndKeepsItsFootprint() {
        List<String> upgrades = List.of("gatling_pea", "twin_sunflower", "gloom_shroom", "cattail",
                "winter_melon", "gold_magnet", "spikerock", "cob_cannon");
        LevelServer ordinary = new LevelServer(BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_4")));
        for (String id : upgrades) {
            var plant = BuiltInRegistries.PLANTS.get(PvzceIds.id(id));
            assertTrue(plant.upgrade().isPresent(), "purple packet metadata must be preserved");
            assertFalse(ordinary.canPlacePlant(plant, 0, 0), "ordinary levels still require " + id + "'s base");
            for (int number = 1; number <= 3; number++) {
                LevelServer level = new LevelServer(shipped(number)); Bridge bridge = new Bridge(); level.flushPending(bridge);
                assertTrue(level.canPlacePlant(plant, 0, 0), id + " must plant directly in fusion " + number);
                level.spawnCardDrop(plant.id(), 0, 0); level.flushPending(bridge);
                CardDropEntity packet = cards(level).getFirst();
                assertTrue(level.pickUpCardDrop(bridge, packet.id()));
                assertTrue(level.plantHeldCard(bridge, 0, 0));
                assertEquals(plant.id(), level.plantsAt(0, 0).getFirst().defId());
                assertFalse(level.canPlacePlant(plant, 0, 0), "direct planting still respects occupancy");
                if (id.equals("cob_cannon")) {
                    assertEquals(level.plantsAt(0, 0).getFirst(), level.plantsAt(1, 0).getFirst());
                    assertFalse(level.canPlacePlant(plant, 8, 1), "a two-cell cannon cannot cross the lawn edge");
                    assertFalse(level.canPlacePlant(plant, 1, 2), "the occupied second footprint cell blocks planting");
                }
            }
        }
    }

    @Test void aDirectCannonDoesNotShiftOntoOrConsumeANeighbouringKernelPult() {
        LevelServer level = new LevelServer(shipped(2)); Bridge bridge = new Bridge(); level.flushPending(bridge);
        PlantEntity neighbour = level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("kernel_pult")),
                level.plantPlayer().team(), 3, 0);
        level.spawnCardDrop(PvzceIds.id("cob_cannon"), 0, 0); level.flushPending(bridge);
        assertTrue(level.pickUpCardDrop(bridge, cards(level).getFirst().id()));
        assertTrue(level.plantHeldCard(bridge, 4, 0));
        assertFalse(neighbour.isRemoved());
        assertEquals(PvzceIds.id("cob_cannon"), level.plantsAt(4, 0).getFirst().defId());
        assertEquals(4, level.plantsAt(4, 0).getFirst().gridX());
    }
}

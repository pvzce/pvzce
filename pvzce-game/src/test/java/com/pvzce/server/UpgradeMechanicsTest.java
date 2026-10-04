package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.CobCannonCapability;
import com.pvzce.common.capability.plant.GoldMagnetCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.PvzceEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last two purple packets: the cannon that is aimed by hand, and the magnet that collects money.
 *
 * <p>Both are plants whose behaviour is <em>not</em> "wait for a zombie and then act", which is why
 * they were the two that could not be written as data: one waits for the player, the other watches
 * the lawn rather than the lane. Each test below pins the one claim that makes its plant worth a
 * card slot.
 */
class UpgradeMechanicsTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();
        final List<String> messages = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
            if (packet instanceof com.pvzce.common.network.packet.ServerMessageS2C message) {
                messages.add(message.message());
            }
        }
    }

    /**
     * A flat nine-by-five lawn with no waves.
     *
     * <p>{@code demo_level} rather than {@code 1_1}: 1-1 is the tutorial's single lane (9x1), which
     * is the wrong shape for a test about a 2x1 upgrade and a 3x3 blast. No waves, because every
     * assertion here counts what is on the board.
     */
    private static LevelServer lawn() {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        assertNotNull(def, "demo_level must load");
        return new LevelServer(com.pvzce.testutil.TestLevels.copy(def).waves(List.of()).build());
    }

    private static void tick(LevelServer level, CapturingBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
    }

    private static PlantEntity spawn(LevelServer level, CapturingBridge bridge, String id, int x, int y) {
        PlantDef def = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(id));
        assertNotNull(def, id + " has to be registered");
        PlantEntity plant = level.spawnPlant(def, level.team(PLANT_TEAM), x, y);
        level.flushPending(bridge);
        assertNotNull(plant, id + " has to be plantable at " + x + "," + y);
        return plant;
    }

    private static CobCannonCapability cannon(PlantEntity plant) {
        CobCannonCapability capability = plant.capability(CobCannonCapability.class);
        assertNotNull(capability, "the cob cannon's own capability");
        return capability;
    }

    // ---------------------------------------------------------------- cob cannon

    /** The 2x1 rule, on the real shipped card: a cannon needs two kernel-pults, not one. */
    @Test
    void theCannonNeedsTwoKernelPultsAndEatsBoth() {
        LevelServer level = lawn();
        CapturingBridge bridge = new CapturingBridge();
        spawn(level, bridge, "kernel_pult", 3, 2);

        // One base is not enough for the shipped card, which is the whole of `adjacent: 1`.
        int slot = cannonSlot(level);
        assertFalse(level.placePlant(bridge, slot, 3, 2),
                "one kernel-pult is not a 2x1 block: " + bridge.messages);

        spawn(level, bridge, "kernel_pult", 4, 2);
        assertTrue(level.placePlant(bridge, slot, 3, 2),
                "two side-by-side kernel-pults are: " + bridge.messages);
        level.flushPending(bridge);

        assertEquals(0, countPlants(level, "pvzce:kernel_pult"),
                "and both of them are consumed: the cannon is what stands there now");
        assertEquals(1, countPlants(level, "pvzce:cob_cannon"), "and it is there");
    }

    @Test void clickingTheRightBaseStillCreatesOneCannonAcrossBothCells() {
        LevelServer level = lawn();
        CapturingBridge bridge = new CapturingBridge();
        spawn(level, bridge, "kernel_pult", 3, 2);
        spawn(level, bridge, "kernel_pult", 4, 2);
        assertTrue(level.placePlant(bridge, cannonSlot(level), 4, 2));
        level.flushPending(bridge);
        assertEquals("pvzce:cob_cannon", level.plantAt(3, 2).defId().toString());
        assertSame(level.plantAt(3, 2), level.plantAt(4, 2));
        assertEquals(3, level.plantAt(4, 2).gridX());
    }

    /**
     * The cannon is loaded, then waits for the player.
     *
     * <p>Three claims in one run, and they are the plant's whole behaviour: it does not fire by
     * itself, the click is what fires it, and the reload starts from the shot rather than from the
     * planting.
     */
    @Test
    void theCannonLoadsThenFiresOnlyWhenTold() {
        LevelServer level = lawn();
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity gun = spawn(level, bridge, "cob_cannon", 1, 2);
        CobCannonCapability capability = cannon(gun);

        tick(level, bridge, 60);
        assertFalse(capability.loaded(), "a cannon is not born loaded");
        assertEquals(0, countProjectiles(level),
                "and it has fired nothing on its own: " + countProjectiles(level)
                        + " charge=" + capability.chargeLeft());

        tick(level, bridge, CobCannonCapability.DEFAULT_INITIAL_TICKS);
        assertTrue(capability.loaded(), "five seconds later it is armed");

        assertTrue(level.fireAt(bridge, gun.id(), 5, 2), "the click is what fires it");
        level.flushPending(bridge);
        assertFalse(capability.loaded(), "and firing unloads it again");
    }

    /** An unloaded cannon refuses with a reason, and the reason is how long is left. */
    @Test
    void anUnloadedCannonSaysHowLongIsLeft() {
        LevelServer level = lawn();
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity gun = spawn(level, bridge, "cob_cannon", 1, 2);

        assertFalse(level.fireAt(bridge, gun.id(), 5, 2), "a cannon that is still loading refuses");
        assertTrue(bridge.messages.stream().anyMatch(line -> line.contains("装填")),
                "and the refusal names the wait: " + bridge.messages);
    }

    /** Off the board is off the board, whatever the cannon's state. */
    @Test
    void aShotOutsideTheBoardIsRefused() {
        LevelServer level = lawn();
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity gun = spawn(level, bridge, "cob_cannon", 1, 2);
        tick(level, bridge, CobCannonCapability.DEFAULT_INITIAL_TICKS);
        assertTrue(cannon(gun).loaded(), "it is armed");

        assertFalse(level.fireAt(bridge, gun.id(), level.width() + 3, 2), "off the right edge");
        assertFalse(level.fireAt(bridge, gun.id(), 2, -1), "and off the top");
        assertTrue(cannon(gun).loaded(), "a refused shot does not spend the cob");
    }

    /**
     * The cob lands where it was aimed and goes off in a 3x3.
     *
     * <p>Aimed <em>behind</em> the cannon on purpose: that is the shot the arc could not fly before
     * (it only ever moved right), and it is the one a player takes when a zombie has walked past.
     */
    @Test
    void theCobLandsOnTheAimedCellAndCoversThreeByThree() {
        LevelServer level = lawn();
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity gun = spawn(level, bridge, "cob_cannon", 6, 2);
        tick(level, bridge, CobCannonCapability.DEFAULT_INITIAL_TICKS);

        // Three rows: the target row, and the two the blast reaches.
        var left = level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), 1.5F, 1);
        var middle = level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), 1.5F, 2);
        var right = level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), 1.5F, 3);
        // And one four rows away, which the blast must not reach.
        var far = level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), 1.5F, 0);
        level.flushPending(bridge);
        assertNotNull(left);
        assertNotNull(middle);
        assertNotNull(right);
        assertNotNull(far);

        assertTrue(level.fireAt(bridge, gun.id(), 1, 2),
                "aimed two cells to the left of the cannon: " + bridge.messages);

        // Long enough for the arc to come down (the flight is the projectile's speed, not instant)
        // and short enough that nothing walks into a mower: 2.5 seconds is under a cell, and the
        // mower would take all four of them and turn this into a test of the mower.
        tick(level, bridge, 150);

        assertFalse(middle.isAlive(), "the cell that was aimed at is gone");
        assertFalse(left.isAlive(), "and so is the row above it");
        assertFalse(right.isAlive(), "and the row below");
        assertTrue(far.isAlive(), "four rows away is outside a 3x3: " + far.health());
    }

    // ---------------------------------------------------------------- gold magnet

    /**
     * The gold magnet collects coins in reach, and only coins.
     *
     * <p>Sun is deliberately left alone - the original's gold magnet is the money half of the
     * magnet-shroom - so a lawn with one of each on it must end with the coin in the wallet and the
     * sun still lying there for the player to click.
     */
    @Test
    void theGoldMagnetCollectsCoinsAndLeavesSunAlone() {
        LevelServer level = lawn();
        CapturingBridge bridge = new CapturingBridge();
        spawn(level, bridge, "gold_magnet", 4, 2);

        level.spawnResource(PvzceIds.id("coin_silver"), 10, 5, 2, level.team(PLANT_TEAM));
        level.spawnResource(PvzceIds.SUN, 25, 6, 2, level.team(PLANT_TEAM));
        level.flushPending(bridge);
        ResourceDropEntity coin = dropOf(level, PvzceIds.id("coin_silver"));
        ResourceDropEntity sun = dropOf(level, PvzceIds.SUN);
        assertNotNull(coin, "the coin is on the lawn");
        assertNotNull(sun, "and so is the sun");

        tick(level, bridge, GoldMagnetCapability.DEFAULT_INTERVAL_TICKS + 5);

        assertTrue(coin.collected(), "the coin was picked up without a click");
        assertEquals(10, level.team(PLANT_TEAM).resourcesOf(PvzceIds.id("coin_silver")),
                "and credited to the plant side");
        assertFalse(sun.collected(), "the sun is still there: sun is the player's click");
    }

    /** Out of reach is out of reach: the magnet is a radius, not the whole lawn. */
    @Test
    void aCoinAcrossTheLawnIsLeftWhereItLies() {
        LevelServer level = lawn();
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity magnet = spawn(level, bridge, "gold_magnet", 0, 0);
        level.spawnResource(PvzceIds.id("coin_silver"), 10,
                level.width() - 0.5F, level.height() - 1, level.team(PLANT_TEAM));
        level.flushPending(bridge);
        ResourceDropEntity far = dropOf(level, PvzceIds.id("coin_silver"));
        assertNotNull(far, "the coin is on the lawn");

        tick(level, bridge, GoldMagnetCapability.DEFAULT_INTERVAL_TICKS + 5);

        assertFalse(far.collected(), "a coin on the far side of the board is not in reach of a"
                + " magnet at 0,0 (range " + GoldMagnetCapability.DEFAULT_RANGE + ")");
        assertFalse(magnet.isRemoved(), "and the magnet is still standing there");
    }

    // ---------------------------------------------------------------- content

    /** Both cards remain usable plants even though upgrades are now adventure rewards. */
    @Test
    void bothCardsRemainRegisteredOutsideTheShop() {
        for (String id : List.of("cob_cannon", "gold_magnet")) {
            Identifier card = Identifier.withDefaultNamespace(id);
            var resolved = SlotResolver.resolve(card);
            assertTrue(resolved.isPresent(), id + " has to resolve to a card");
            assertEquals(com.pvzce.common.core.Slot.Kind.PLANT, resolved.get().kind(),
                    id + " is a plant card");
            assertNotNull(BuiltInRegistries.PLANTS.get(card), id + " has to be a registered plant");
        }
        assertFalse(com.pvzce.server.shop.ShopPurchases.sells(PvzceIds.id("cob_cannon")));
        assertFalse(com.pvzce.server.shop.ShopPurchases.sells(PvzceIds.id("gold_magnet")));
    }

    // ---------------------------------------------------------------- helpers

    private static int cannonSlot(LevelServer level) {
        // The shipped board's own bar: the cannon is not on it, so it is granted for the test the
        // same way a level would - through the level's card source.
        Identifier card = Identifier.withDefaultNamespace("cob_cannon");
        level.addCardToBar(card);
        com.pvzce.server.PvzcePlayer slots = level.plantPlayer();
        assertNotNull(slots, "the plant side has a player");
        for (com.pvzce.common.core.Slot slot : slots.slots()) {
            if (card.equals(slot.defId())) {
                // Free and ready: what is being tested is the placement rule, not the price.
                level.team(PLANT_TEAM).addResource(PvzceIds.SUN, 1000);
                slot.clearCooldown();
                return slot.index();
            }
        }
        throw new AssertionError("the granted cannon card is not on the bar");
    }

    /** The first drop of a resource still on the board. */
    private static ResourceDropEntity dropOf(LevelServer level, Identifier resourceId) {
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ResourceDropEntity drop && drop.defId().equals(resourceId)
                    && !drop.isRemoved()) {
                return drop;
            }
        }
        return null;
    }

    /**
     * Plants of one kind still standing.
     *
     * <p>{@code isRemoved()} is part of the question: an entity is flagged the moment it is eaten
     * or consumed and only drops out of the level's list on the next tick's sweep, so a plant the
     * upgrade has already eaten is still in {@code entities()} for a moment.
     */
    private static int countPlants(LevelServer level, String plantId) {
        int found = 0;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved()
                    && plant.def().id().toString().equals(plantId)) {
                found++;
            }
        }
        return found;
    }

    private static int countProjectiles(LevelServer level) {
        int found = 0;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof com.pvzce.server.entity.ProjectileEntity projectile
                    && !projectile.isRemoved()) {
                found++;
            }
        }
        return found;
    }
}

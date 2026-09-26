package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.VaseFieldData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.CardDropEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vase tool: one object, three clicks, and a vase field written in code.
 *
 * <p>4-4 used to be the level that stood vases up; it is an ordinary fog level now, because the
 * original has no vase field - its vase level is 4-5, and that one is Scary Potter
 * (`ScaryPotterTest`). The {@code pvzce:vase_field} mechanic stays, so the fixture here writes one
 * itself: a level, its vases and a bar to hold cards.
 *
 * <p>One object, three clicks, and all three are worth pinning because they are the same click
 * arriving at three different handlers: the tool places a vase in an empty cell, <em>smashes</em>
 * one that is already there, and a plant card clicked on a vase is <em>stored</em> - that last one
 * is a placement packet rather than a tool packet, because the server never learns which card the
 * client has selected (see {@code LevelServer.storeCardInVase}). A test that only exercised the
 * tool would miss the branch that players actually use most.
 *
 * <p>The claims here are the user's own words, one test each: "选择植物卡，然后点击花瓶后，可以把植物
 * 卡放进花瓶里，砸掉后掉落该植物卡", "在原地掉落一个植物卡片，可以捡起来种植", and "放下后可以用
 * 手套换位置".
 */
class VaseTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier PEA = Identifier.withDefaultNamespace("pea_shooter");
    private static final Identifier SUNFLOWER = Identifier.withDefaultNamespace("sunflower");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class Bridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }

        List<String> messages() {
            List<String> lines = new ArrayList<>();
            for (PvzcePacket packet : packets) {
                if (packet instanceof ServerMessageS2C message) {
                    lines.add(message.message());
                }
            }
            return lines;
        }
    }

    // ------------------------------------------------------------------
    // 4-4's opening board
    // ------------------------------------------------------------------

    /** The level declares its vases, and a live level has them standing where it said. */
    @Test
    void aVaseFieldStandsUpWhereTheLevelSaid() {
        LevelDef def = vaseFixture();
        VaseFieldData data = vaseField(def);
        assertEquals(8, data.vases().size(), "eight vases, one per card the level hands out");

        LevelServer level = new LevelServer(def);
        for (VaseFieldData.Vase vase : data.vases()) {
            assertEquals(PvzceIds.VASE_FULL, level.sceneIdAt(vase.x(), vase.y()),
                    "the vase at (" + vase.x() + "," + vase.y() + ") has to be standing");
            assertEquals(vase.card(), level.vaseContentAt(vase.x(), vase.y()),
                    "and it holds the card the level wrote for it");
            assertFalse(level.rowIsWater(vase.y()),
                    "vases stand on the lawn, not in the pool: (" + vase.x() + "," + vase.y() + ")");
        }
    }

    /**
     * A vase cell cannot be planted in, which is what makes putting a card <em>into</em> it mean
     * anything.
     */
    @Test
    void aVaseCellIsNotPlantable() {
        LevelServer level = fixture();
        VaseFieldData.Vase vase = vaseField(level.def()).vases().get(0);
        assertFalse(level.canPlacePlant(BuiltInRegistries.PLANTS.get(PEA), vase.x(), vase.y()),
                "a plant in a vase cell would be the plant and the vase in one cell");
    }

    /** Every card a vase holds is a card the bar could actually deal. */
    @Test
    void everyVaseHoldsARealCard() {
        for (VaseFieldData.Vase vase : vaseField(vaseFixture()).vases()) {
            assertTrue(SlotResolver.resolve(vase.card()).isPresent(),
                    vase.card() + " has to resolve to a card, or breaking that vase gives nothing");
        }
    }

    // ------------------------------------------------------------------
    // The three clicks
    // ------------------------------------------------------------------

    /** Click one: an empty cell gets a vase. */
    @Test
    void theToolPutsAVaseDown() {
        LevelServer level = fixture();
        Bridge bridge = new Bridge();
        int x = 6;
        int y = 0;
        assertEquals(PvzceIds.GRASS, level.sceneIdAt(x, y), "the fixture cell starts as lawn");
        assertTrue(useVaseTool(level, bridge, x, y), "an empty cell takes a vase");
        assertEquals(PvzceIds.VASE, level.sceneIdAt(x, y));
        assertNull(level.vaseContentAt(x, y), "and it is empty");
    }

    /** Click two: a plant card on that vase stores the card. */
    @Test
    void aPlantCardClickedOnAVaseGoesInsideIt() {
        LevelServer level = fixture();
        Bridge bridge = new Bridge();
        int x = 6;
        int y = 0;
        useVaseTool(level, bridge, x, y);
        int slot = giveCard(level, PEA);
        int sunBefore = level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN);

        assertTrue(level.placePlant(bridge::send, slot, x, y),
                "a plant card clicked on a vase is stored, not refused");
        assertEquals(PvzceIds.VASE_FULL, level.sceneIdAt(x, y), "the vase shows that it is full");
        assertEquals(PEA, level.vaseContentAt(x, y), "and holds the card that was clicked into it");
        assertEquals(0, level.plantCount(), "no plant appeared: the card went into the vase");
        assertEquals(sunBefore - 100, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "the card is paid for at the click, so the vase cannot bank a discount");
    }

    /** One card per vase, and the second one is refused with a message rather than swallowed. */
    @Test
    void aFullVaseRefusesASecondCard() {
        LevelServer level = fixture();
        Bridge bridge = new Bridge();
        int x = 6;
        int y = 0;
        useVaseTool(level, bridge, x, y);
        assertTrue(level.placePlant(bridge::send, giveCard(level, PEA), x, y));
        bridge.packets.clear();
        int sunBefore = level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN);

        assertFalse(level.placePlant(bridge::send, giveCard(level, SUNFLOWER), x, y));
        assertEquals(PEA, level.vaseContentAt(x, y), "the first card is still the one inside");
        assertEquals(sunBefore, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "a refused store costs nothing");
        assertTrue(bridge.messages().stream().anyMatch(line -> line.contains("已经有东西")),
                "and it says so: " + bridge.messages());
    }

    /**
     * Click three: an empty-handed click smashes it, and the card lands on the lawn as a packet.
     *
     * <p>It used to go straight onto the bar, and the difference is the point: a plant the player
     * already had then looked exactly like a vase that was empty. A packet is a thing the player
     * can see, pick up and plant.
     */
    @Test
    void smashingAVaseDropsItsCardAsASeedPacket() {
        LevelServer level = fixture();
        Bridge bridge = new Bridge();
        VaseFieldData.Vase vase = vaseField(level.def()).vases().get(0);
        assertFalse(hasCard(level, vase.card()), "the fixture has to start without that card");

        assertTrue(useVaseTool(level, bridge, vase.x(), vase.y()));
        assertEquals(PvzceIds.GRASS, level.sceneIdAt(vase.x(), vase.y()),
                "a smashed vase leaves ordinary lawn behind");
        assertNull(level.vaseContentAt(vase.x(), vase.y()), "and it is not holding anything");
        CardDropEntity packet = cardDropAt(level, vase.x(), vase.y());
        assertNotNull(packet, "the card inside is the player's, and it is lying where the vase was");
        assertEquals(vase.card(), packet.card(), "the packet holds the card the vase held");
        assertFalse(hasCard(level, vase.card()),
                "and it is not on the bar: the player picks it up and plants it themselves");

        assertTrue(level.pickUpCardDrop(bridge::send, packet.id()), "clicking it picks it up");
        assertEquals(vase.card(), level.heldCard(), "and the plant is in the player's hand");
        assertTrue(level.plantHeldCard(bridge::send, 6, 0), "so the next click plants it");
        assertEquals(1, level.plantCount());
        assertNull(level.heldCard(), "and the hand is empty again");
    }

    /** An empty vase smashes to nothing, and says so rather than pretending it dropped a card. */
    @Test
    void smashingAnEmptyVaseYieldsNothing() {
        LevelServer level = fixture();
        Bridge bridge = new Bridge();
        useVaseTool(level, bridge, 6, 0);
        bridge.packets.clear();
        assertTrue(useVaseTool(level, bridge, 6, 0));
        assertEquals(PvzceIds.GRASS, level.sceneIdAt(6, 0));
        assertTrue(bridge.messages().stream().noneMatch(line -> line.contains("掉出了一张卡")),
                "nothing came out, so nothing is announced: " + bridge.messages());
    }

    /**
     * The stored card comes back as a plant the player can plant, and it is free.
     *
     * <p>Storing it cost the card's sun and started its recharge - that is what keeps the vase
     * from banking a discount. What comes out is one plant and no second bill: the packet is what
     * the player paid for, and planting it charges nothing (see {@code LevelServer.plantHeldCard}).
     */
    @Test
    void theCardThatComesBackCanBePlantedForFree() {
        LevelServer level = fixture();
        Bridge bridge = new Bridge();
        int x = 6;
        int y = 0;
        useVaseTool(level, bridge, x, y);
        level.team(PLANT_TEAM).putResource(PvzceIds.SUN, 500);
        int slot = giveCard(level, PEA);
        assertTrue(level.placePlant(bridge::send, slot, x, y));
        int sunAfterStoring = level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN);
        assertTrue(useVaseTool(level, bridge, x, y), "smash it open again");

        CardDropEntity packet = cardDropAt(level, x, y);
        assertNotNull(packet, "the card is on the lawn, not on the bar");
        assertEquals(PEA, packet.card());
        assertTrue(level.pickUpCardDrop(bridge::send, packet.id()));
        // A different cell: the smashed one is the cell the plant would go back into, and it is
        // free again - but planting there would test nothing about the vase.
        assertTrue(level.plantHeldCard(bridge::send, 7, 0), "and it plants like any other plant");
        assertEquals(1, level.plantCount());
        assertEquals(sunAfterStoring, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "planting what came out of the vase costs nothing: the vase was the payment");
    }

    // ------------------------------------------------------------------
    // The two halves that live outside the click handlers
    // ------------------------------------------------------------------

    /** A vase and its card survive a save and come back together. */
    @Test
    void vaseContentsSurviveASave() {
        LevelServer level = fixture();
        Bridge bridge = new Bridge();
        VaseFieldData.Vase vase = vaseField(level.def()).vases().get(2);
        CompoundTag save = level.save();

        LevelServer restored = new LevelServer(level.def());
        restored.restore(save);
        assertEquals(PvzceIds.VASE_FULL, restored.sceneIdAt(vase.x(), vase.y()));
        assertEquals(vase.card(), restored.vaseContentAt(vase.x(), vase.y()),
                "a vase restored without its card is a vase that lies about what it holds");
        // And the smashed ones stay smashed: the scene block says so.
        assertTrue(useVaseTool(level, bridge, vase.x(), vase.y()));
        LevelServer afterSmash = new LevelServer(level.def());
        afterSmash.restore(level.save());
        assertEquals(PvzceIds.GRASS, afterSmash.sceneIdAt(vase.x(), vase.y()));
        assertNull(afterSmash.vaseContentAt(vase.x(), vase.y()));
    }

    /** The glove moves a vase and the card inside it together. */
    @Test
    void theGloveMovesAVaseAndItsCard() {
        LevelServer level = fixture();
        Bridge bridge = new Bridge();
        VaseFieldData.Vase vase = vaseField(level.def()).vases().get(0);
        int toX = 6;
        int toY = 0;

        assertTrue(useGlove(level, bridge, vase.x(), vase.y()), "the first click lifts it");
        assertTrue(useGlove(level, bridge, toX, toY), "the second puts it down");
        assertEquals(PvzceIds.VASE_FULL, level.sceneIdAt(toX, toY), "the vase stands in the new cell");
        assertEquals(vase.card(), level.vaseContentAt(toX, toY), "with its card still inside");
        assertEquals(PvzceIds.GRASS, level.sceneIdAt(vase.x(), vase.y()),
                "and the cell it came from is lawn again");
        assertNull(level.vaseContentAt(vase.x(), vase.y()),
                "a card left behind in the old cell would be a card duplicated");
    }

    /** Dropping a lifted vase on something else is refused, and the vase is still in hand. */
    @Test
    void aVaseCannotBeDroppedOnAnOccupiedCell() {
        LevelServer level = fixture();
        Bridge bridge = new Bridge();
        VaseFieldData.Vase vase = vaseField(level.def()).vases().get(0);
        VaseFieldData.Vase other = vaseField(level.def()).vases().get(1);

        assertTrue(useGlove(level, bridge, vase.x(), vase.y()));
        assertFalse(useGlove(level, bridge, other.x(), other.y()), "two vases in one cell");
        assertEquals(PvzceIds.VASE_FULL, level.sceneIdAt(other.x(), other.y()));
        assertEquals(other.card(), level.vaseContentAt(other.x(), other.y()));
        assertTrue(useGlove(level, bridge, 6, 0), "the vase is still in the glove's hand");
        assertEquals(PvzceIds.VASE_FULL, level.sceneIdAt(6, 0));
        assertEquals(vase.card(), level.vaseContentAt(6, 0));
    }

    /**
     * A vase dropped back where it came from is a no-op.
     *
     * <p>Worth a test rather than an assumption: the drop path clears the origin cell as part of
     * moving, and clearing the cell it is about to write would leave a vase that vanished.
     */
    @Test
    void aVaseDroppedBackWhereItCameFromStays() {
        LevelServer level = fixture();
        Bridge bridge = new Bridge();
        VaseFieldData.Vase vase = vaseField(level.def()).vases().get(0);
        assertTrue(useGlove(level, bridge, vase.x(), vase.y()));
        assertTrue(useGlove(level, bridge, vase.x(), vase.y()));
        assertEquals(PvzceIds.VASE_FULL, level.sceneIdAt(vase.x(), vase.y()));
        assertEquals(vase.card(), level.vaseContentAt(vase.x(), vase.y()));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A fog board with a vase field on it, no waves, the vase tool and a bar for cards. */
    private static LevelServer fixture() {
        LevelDef def = vaseFixture();
        LevelServer level = new LevelServer(def, List.of());
        // Deliberately not every card a vase holds: "smashing a vase puts its card on the bar" is
        // only a claim if the card was not there before. A test whose bar already had the card
        // would pass on a build where smashing did nothing at all.
        level.plantPlayer().replaceSlots(PvzcePlayer.deckSlots(
                List.of(PvzceIds.VASE, PEA, Identifier.withDefaultNamespace("glove"))));
        level.team(PLANT_TEAM).putResource(PvzceIds.SUN, 500);
        return level;
    }

    /**
     * The eight vases 4-4 used to stand up, written here instead.
     *
     * <p>Two columns of four on the lawn nearest the house, one card each. They are written rather
     * than rolled (the original's vases are a gift, not a dice roll), and the cells avoid the pool
     * rows - a vase in the water would be a vase the player cannot plant behind.
     */
    private static final List<VaseFieldData.Vase> VASES = List.of(
            new VaseFieldData.Vase(1, 0, Identifier.withDefaultNamespace("sunflower")),
            new VaseFieldData.Vase(2, 0, Identifier.withDefaultNamespace("pea_shooter")),
            new VaseFieldData.Vase(1, 1, Identifier.withDefaultNamespace("wall_nut")),
            new VaseFieldData.Vase(2, 1, Identifier.withDefaultNamespace("snow_pea")),
            new VaseFieldData.Vase(1, 4, Identifier.withDefaultNamespace("repeater")),
            new VaseFieldData.Vase(2, 4, Identifier.withDefaultNamespace("tall_nut")),
            new VaseFieldData.Vase(1, 5, Identifier.withDefaultNamespace("cherry_bomb")),
            new VaseFieldData.Vase(2, 5, Identifier.withDefaultNamespace("jalapeno")));

    /** 4-4 - an ordinary fog level now - with a vase field added in code. */
    private static LevelDef vaseFixture() {
        LevelDef base = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/4_4"));
        assertNotNull(base);
        List<TypedMechanic> mechanics = new ArrayList<>(base.mechanics());
        mechanics.add(TypedMechanic.of(PvzceIds.MECHANIC_VASE_FIELD, new VaseFieldData(VASES)));
        return TestLevels.copy(base).mechanics(mechanics).waves(List.of()).build();
    }

    private static VaseFieldData vaseField(LevelDef def) {
        for (TypedMechanic mechanic : def.mechanics()) {
            if (mechanic.is(PvzceIds.MECHANIC_VASE_FIELD)
                    && mechanic.value() instanceof VaseFieldData data) {
                return data;
            }
        }
        throw new AssertionError("4-4 has to declare its vases, or it is an ordinary pool level");
    }

    /**
     * Clicks the vase tool in a cell, through the same entry point a client uses.
     *
     * <p>The card's recharge is cleared first. It is ten seconds long - a fact worth pinning, and
     * pinned by the tool's own data test - and a vase test that had to spend ten seconds of
     * simulated lawn between two clicks would be testing the clock rather than the vase.
     */
    private static boolean useVaseTool(LevelServer level, Bridge bridge, int x, int y) {
        int slot = slotOf(level, PvzceIds.VASE);
        assertTrue(slot >= 0, "the fixture bar has to hold the vase tool");
        level.plantPlayer().slot(slot).clearCooldown();
        return level.useTool(bridge::send, slot, x, y);
    }

    /**
     * Clicks the glove in a cell, for the same reason: one move, two clicks, one recharge.
     *
     * <p>Clearing the clock is safe for both halves of a move - the second click is exempt from its
     * own card's recharge anyway, which is what makes a two-click move cost one charge.
     */
    private static boolean useGlove(LevelServer level, Bridge bridge, int x, int y) {
        int slot = slotOf(level, Identifier.withDefaultNamespace("glove"));
        assertTrue(slot >= 0, "the fixture bar has to hold the glove");
        level.plantPlayer().slot(slot).clearCooldown();
        return level.useTool(bridge::send, slot, x, y);
    }

    /** The seed packet lying in a cell, or {@code null} when there is none. */
    private static CardDropEntity cardDropAt(LevelServer level, int x, int y) {
        // The level queues what it spawns and flushes it on its own tick; a test that reads the
        // board right after a click has to ask for that flush itself, the same way the pot tests
        // do after a swing.
        level.flushPending();
        for (var entity : level.entities()) {
            if (entity instanceof CardDropEntity drop && !drop.isRemoved()
                    && drop.gridX() == x && drop.gridY() == y) {
                return drop;
            }
        }
        return null;
    }

    /** Puts one spare card of this kind on the bar and returns its slot index. */
    private static int giveCard(LevelServer level, Identifier cardId) {        int existing = slotOf(level, cardId);
        if (existing >= 0) {
            return existing;
        }
        List<com.pvzce.common.core.Slot> slots = new ArrayList<>(level.plantPlayer().slots());
        int index = 0;
        for (com.pvzce.common.core.Slot slot : slots) {
            index = Math.max(index, slot.index() + 1);
        }
        SlotResolver.ResolvedCard resolved = SlotResolver.resolve(cardId).orElseThrow();
        slots.add(new com.pvzce.common.core.Slot(index, resolved.kind(), resolved.content(),
                resolved.costSun(), 0, com.pvzce.common.core.Slot.UNLIMITED_USES,
                resolved.cooldownTicks()));
        level.plantPlayer().replaceSlots(slots);
        return index;
    }

    private static int slotOf(LevelServer level, Identifier defId) {
        for (com.pvzce.common.core.Slot slot : level.plantPlayer().slots()) {
            if (slot.defId().equals(defId)) {
                return slot.index();
            }
        }
        return -1;
    }

    private static boolean hasCard(LevelServer level, Identifier defId) {
        return slotOf(level, defId) >= 0;
    }
}

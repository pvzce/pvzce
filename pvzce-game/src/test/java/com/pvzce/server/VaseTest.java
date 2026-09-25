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
 * The vase tool, and the vases 4-4 stands on the lawn.
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
    void fourFourStartsWithItsVases() {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/4_4"));
        assertNotNull(def, "4-4 is the vase level");
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
        LevelServer level = fourFour();
        VaseFieldData.Vase vase = vaseField(level.def()).vases().get(0);
        assertFalse(level.canPlacePlant(BuiltInRegistries.PLANTS.get(PEA), vase.x(), vase.y()),
                "a plant in a vase cell would be the plant and the vase in one cell");
    }

    /** Every card a vase holds is a card the bar could actually deal. */
    @Test
    void everyVaseHoldsARealCard() {
        for (VaseFieldData.Vase vase : vaseField(level4_4Def()).vases()) {
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
        LevelServer level = fourFour();
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
        LevelServer level = fourFour();
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
        LevelServer level = fourFour();
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

    /** Click three: an empty-handed click smashes it, and the card lands on the bar. */
    @Test
    void smashingAVaseReturnsItsCardToTheBar() {
        LevelServer level = fourFour();
        Bridge bridge = new Bridge();
        VaseFieldData.Vase vase = vaseField(level.def()).vases().get(0);
        assertFalse(hasCard(level, vase.card()), "the fixture has to start without that card");

        assertTrue(useVaseTool(level, bridge, vase.x(), vase.y()));
        assertEquals(PvzceIds.GRASS, level.sceneIdAt(vase.x(), vase.y()),
                "a smashed vase leaves ordinary lawn behind");
        assertNull(level.vaseContentAt(vase.x(), vase.y()), "and it is not holding anything");
        assertTrue(hasCard(level, vase.card()),
                "the card inside is the player's - on the bar, ready to be planted");
    }

    /** An empty vase smashes to nothing, and says so rather than pretending it dropped a card. */
    @Test
    void smashingAnEmptyVaseYieldsNothing() {
        LevelServer level = fourFour();
        Bridge bridge = new Bridge();
        useVaseTool(level, bridge, 6, 0);
        bridge.packets.clear();
        assertTrue(useVaseTool(level, bridge, 6, 0));
        assertEquals(PvzceIds.GRASS, level.sceneIdAt(6, 0));
        assertTrue(bridge.messages().stream().noneMatch(line -> line.contains("掉出了一张卡")),
                "nothing came out, so nothing is announced: " + bridge.messages());
    }

    /**
     * The stored card comes back as a card the player can plant, and planting it is what charges
     * the sun - the vase moved <em>when</em> the plant appears, not how much it costs in total.
     */
    @Test
    void theCardThatComesBackCanBePlanted() {
        LevelServer level = fourFour();
        Bridge bridge = new Bridge();
        int x = 6;
        int y = 0;
        useVaseTool(level, bridge, x, y);
        assertTrue(level.placePlant(bridge::send, giveCard(level, PEA), x, y));
        assertTrue(useVaseTool(level, bridge, x, y), "smash it open again");

        int slot = slotOf(level, PEA);
        assertTrue(slot >= 0, "the card is on the bar");
        level.team(PLANT_TEAM).putResource(PvzceIds.SUN, 500);
        // The card's own recharge is still running - it started when the card was stored, and a
        // vase does not hand out a free recharge any more than it hands out free sun. That is not
        // what this test is about, so the clock is cleared rather than waited out.
        level.plantPlayer().slot(slot).clearCooldown();
        // A different cell: the smashed one is the cell the plant would go back into, and it is
        // free again - but planting there would test nothing about the vase.
        assertTrue(level.placePlant(bridge::send, slot, 7, 0), "and it plants like any other card");
        assertEquals(1, level.plantCount());
    }

    // ------------------------------------------------------------------
    // The two halves that live outside the click handlers
    // ------------------------------------------------------------------

    /** A vase and its card survive a save and come back together. */
    @Test
    void vaseContentsSurviveASave() {
        LevelServer level = fourFour();
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
        LevelServer level = fourFour();
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
        LevelServer level = fourFour();
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
        LevelServer level = fourFour();
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

    /** 4-4 with no waves: the board, the vase tool and a bar to hold cards, and nothing else. */
    private static LevelServer fourFour() {
        LevelDef def = level4_4Def();
        LevelServer level = new LevelServer(def, List.of());
        // Deliberately not every card a vase holds: "smashing a vase puts its card on the bar" is
        // only a claim if the card was not there before. A test whose bar already had the card
        // would pass on a build where smashing did nothing at all.
        level.plantPlayer().replaceSlots(PvzcePlayer.deckSlots(
                List.of(PvzceIds.VASE, PEA, Identifier.withDefaultNamespace("glove"))));
        level.team(PLANT_TEAM).putResource(PvzceIds.SUN, 500);
        return level;
    }

    private static LevelDef level4_4Def() {
        LevelDef base = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/4_4"));
        assertNotNull(base);
        return TestLevels.copy(base).waves(List.of()).build();
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

    /** Puts one spare card of this kind on the bar and returns its slot index. */
    private static int giveCard(LevelServer level, Identifier cardId) {
        int existing = slotOf(level, cardId);
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

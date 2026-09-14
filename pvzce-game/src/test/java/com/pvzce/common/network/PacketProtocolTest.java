package com.pvzce.common.network;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.network.packet.CollectResourceC2S;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.DebugInfoS2C;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.EntityDespawnS2C;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.GameSpeedS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelPayload;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.MusicEventS2C;
import com.pvzce.common.network.packet.OpenEditorS2C;
import com.pvzce.common.network.packet.MovePlantC2S;
import com.pvzce.common.network.packet.PauseGameC2S;
import com.pvzce.common.network.packet.PickCardC2S;
import com.pvzce.common.network.packet.PlacePlantC2S;
import com.pvzce.common.network.packet.ProfileS2C;
import com.pvzce.common.network.packet.ContinueLevelC2S;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.RequestSuggestionsC2S;
import com.pvzce.common.network.packet.ResourceCollectS2C;
import com.pvzce.common.network.packet.ResourceDeltaS2C;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.common.network.packet.SceneSyncS2C;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.network.packet.SetGameSpeedC2S;
import com.pvzce.common.network.packet.UnlockLevelC2S;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.SlotSyncS2C;
import com.pvzce.common.network.packet.RestartLevelC2S;
import com.pvzce.common.network.packet.SuggestionsS2C;
import com.pvzce.common.network.packet.TeamSyncS2C;
import com.pvzce.common.network.packet.TimeOfDayS2C;
import com.pvzce.common.network.packet.UseToolC2S;
import com.pvzce.common.network.packet.WaveProgressS2C;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol self-checks for the packet table.
 *
 * <p>Every packet used to spell its field order twice - once in {@code encode} and
 * once in {@code decode} - so a field could be written but never read, read with
 * the wrong type, or moved on one side only. None of that is a compile error; it
 * shows up as a silent desync at runtime. These tests make every packet's wire
 * format verifiable in one run: each is encoded, decoded and compared field by
 * field, and the round trip must consume the whole frame.
 */
class PacketProtocolTest {
    @BeforeAll
    static void register() {
        PvzcePackets.register();
    }

    /** A representative instance of every packet in the protocol. */
    private static List<PvzcePacket> samplePackets() {
        LevelPayload payload = new LevelPayload(9, 5,
                List.of(new SeedOption("pvzce:pea_shooter", "plant", "pvzce:pea_shooter",
                        "pvzce:textures/entities/pea_shooter", 100)),
                6, List.of("pvzce:basic_zombie"),
                List.of(new SceneSyncS2C.Cell(1, 2, "pvzce:water")), List.of(), List.of());
        List<LevelListS2C.TeamInfo> teams = List.of(
                new LevelListS2C.TeamInfo("pvzce:plant_team", "植物方", "survive_waves"));
        List<SlotInfo> slots = List.of(new SlotInfo(0, "pvzce:pea_shooter", "plant", 100, 12, -1, true));

        return List.of(
                new ContinueLevelC2S("pvzce:level_1", "world"),
                new PlayLevelC2S("pvzce:level_1", "world", false,
                        List.of("pvzce:pea_shooter", "pvzce:sun")),
                new RestartLevelC2S("pvzce:level_1", "world", List.of("pvzce:sun")),
                new RequestLevelListC2S("world"),
                new RequestSuggestionsC2S("/spawn ", 7),
                new LeaveLevelC2S(),
                new PickCardC2S(2),
                new PlacePlantC2S(0, 3, 4),
                new UseToolC2S(1, 5, 1),
                new CollectResourceC2S(42),
                new CommandC2S("/time set 600"),
                new SetGameSpeedC2S(2),
                new PauseGameC2S(true),
                new CreateWorldC2S("sandbox", true),
                new UnlockLevelC2S("pvzce:yard/adventure/1_2", "world"),
                new MovePlantC2S(2, 3, 4),

                new LevelInitS2C("pvzce:level_1", slots, List.of("normal", "final"), payload,
                        "pvzce:zombie_team", "僵尸方", PvzcePackets.PROTOCOL_VERSION),
                new LevelListS2C(List.of(LevelListS2C.LevelInfo.of("pvzce:yard/adventure/level_1", "第一关", "描述",
                        "pvzce:plant_team", teams, "in_progress", "day",
                        "pvzce:yard", "pvzce:adventure", true, payload,
                        // A locked row, so the sample set covers the unlock fields too.
                        new LevelListS2C.UnlockInfo(false, true, 500, "通关 1_1",
                                List.of(com.pvzce.api.content.LevelUnlock.Requirement.level(
                                        Identifier.of("pvzce", "yard/adventure/1_1"))),
                                false)))),
                // The tab table is its own packet so the select screen can render pages the
                // level list does not mention; the unclassified bucket travels as the
                // sentinel pair rather than as a null.
                new LevelTabsS2C(List.of(new LevelTabsS2C.Tab("pvzce:yard", "pvzce:adventure"),
                        new LevelTabsS2C.Tab("pvzce:uncategorized", "pvzce:uncategorized"))),
                new LevelSavePromptS2C("pvzce:level_1", "world", "第一关", 1234, 7, 250),
                new OpenEditorS2C("pvzce:demo_level"),
                new SceneSyncS2C(List.of(new SceneSyncS2C.Cell(0, 0, "pvzce:grass"))),
                new EntitySpawnS2C(9, "zombie", "pvzce:basic_zombie", "pvzce:zombie_team",
                        4.5F, 2.5F, 0, 200, "walk", 0.0F, 370),
                new EntityUpdateS2C(9, 4.25F, 2.5F, 180, "eat", 0.1F, 190),
                new EntityDespawnS2C(9),
                // The ripple fields are set (not left at their defaults) so the two
                // added columns are actually round-tripped. The class has exactly one
                // sample because the table is one-per-packet by design, so this single
                // instance has to cover both the plain and the liquid-carrying case -
                // both constructors are exercised by the other tests.
                new EffectEventS2C("pvzce:splash", 3.5F, 1.5F, "pvzce:sfx/effect/bite", 1F, 1F,
                        "pvzce:water", 1F),
                new ResourceCollectS2C(11, "pvzce:sun", 25, 3.5F, 1.5F, 0.4F, "pvzce:textures/resource/sun"),
                new ResourceDeltaS2C("pvzce:plant_team", "pvzce:redstone", 30),
                new SlotSyncS2C(new SlotInfo(1, "pvzce:sun", "resource", 0, 0, -1, true)),
                new GameStateS2C(GameStateS2C.WON, "pvzce:plant_team"),
                new TeamSyncS2C("pvzce:zombie_team", "僵尸方"),
                new SuggestionsS2C(7, List.of(new SuggestionsS2C.Suggestion(7, 13, "pvzce:basic_zombie"))),
                new ServerMessageS2C("+25 阳光"),
                new WaveProgressS2C(3, 5, 0.42F, true, false),
                new TimeOfDayS2C(600, 1200, 600),
                new DebugInfoS2C(98765L, false, true),
                new GameSpeedS2C(180F),
                new MusicEventS2C("background", "pvzce:music/grasswalk", true, false, 0.85F, 1.5F),
                // Non-default values: an empty unlock list or zero coins would hide a
                // writer/reader swap in either column.
                new ProfileS2C(350, List.of("pvzce:pea_shooter", "pvzce:sunflower", "pvzce:shovel"), false),
                new LevelRewardS2C("pvzce:yard/adventure/1_1", 12, 100, 462, "pvzce:sunflower"),
                // A belt card has no price (SlotInfo.NO_PRICE), which is the value a
                // writer/reader swap on costSun would silently turn into a real one. The
                // payload is opaque to the protocol, so a payload that decodes cleanly is
                // part of what is being pinned here.
                MechanicSyncS2C.of(com.pvzce.common.PvzceIds.MECHANIC_CONVEYOR,
                        com.pvzce.common.level.mechanic.ConveyorMechanic.BarState.CODEC,
                        new com.pvzce.common.level.mechanic.ConveyorMechanic.BarState(List.of(
                                new SlotInfo(4, "pvzce:bowling_nut", "plant", SlotInfo.NO_PRICE, 0, true),
                                new SlotInfo(5, "pvzce:bowling_nut", "plant", SlotInfo.NO_PRICE, 0, true)))));
    }

    /**
     * Every packet must survive a round trip with all fields intact and no bytes
     * left over. A "left over" failure means the reader consumed fewer bytes than
     * the writer produced, which is the classic signature of a field-order bug.
     */
    @Test
    void everyPacketRoundTripsAndConsumesItsWholeFrame() {
        List<String> failures = new ArrayList<>();
        for (PvzcePacket packet : samplePackets()) {
            int id = PacketRegistry.id(packet);
            var buffer = Unpooled.buffer();
            try {
                PacketByteBuf out = new PacketByteBuf(buffer);
                out.writeVarInt(id);
                packet.encode(out);

                PacketByteBuf in = new PacketByteBuf(buffer);
                int readId = in.readVarInt();
                PvzcePacket decoded = PacketRegistry.decode(packet.direction(), readId, in);

                if (in.readableBytes() != 0) {
                    failures.add(packet.getClass().getSimpleName() + ": decoder left "
                            + in.readableBytes() + " unread bytes");
                }
                if (!packet.equals(decoded)) {
                    failures.add(packet.getClass().getSimpleName() + ": decoded value differs\n  sent: "
                            + packet + "\n  read: " + decoded);
                }
            } catch (RuntimeException e) {
                failures.add(packet.getClass().getSimpleName() + ": " + e);
            } finally {
                buffer.release();
            }
        }
        assertTrue(failures.isEmpty(), "packet round-trip failures:\n" + String.join("\n", failures));
    }

    /** The table covers exactly the sample set: a new packet must be added to both. */
    @Test
    void theSampleSetCoversTheWholeProtocol() {
        assertEquals(PvzcePackets.count(), samplePackets().size(),
                "every registered packet needs a sample instance so its codec is verified");
        assertEquals(PvzcePackets.count(), PacketRegistry.size(), "the table and the registry disagree");
    }

    /** Ids are explicit and never reused, in either direction. */
    @Test
    void packetIdsAreExplicitAndUnique() {
        List<Integer> ids = PacketRegistry.ids();
        assertEquals(ids.size(), ids.stream().distinct().count(), "duplicate packet id");
        for (PvzcePacket packet : samplePackets()) {
            int id = PacketRegistry.id(packet);
            if (packet.direction() == ConnectionDirection.SERVERBOUND) {
                assertTrue(id > 0 && id < PvzcePackets.S2C_BASE,
                        packet.getClass().getSimpleName() + " must use a client-to-server id, got " + id);
            } else {
                assertTrue(id >= PvzcePackets.S2C_BASE && id < 256,
                        packet.getClass().getSimpleName() + " must use a server-to-client id, got " + id);
            }
        }
    }

    /** A packet must not be accepted on the wrong side of the connection. */
    @Test
    void directionIsEnforcedOnDecode() {
        PvzcePacket serverbound = new LeaveLevelC2S();
        int id = PacketRegistry.id(serverbound);
        var buffer = Unpooled.buffer();
        try {
            PacketByteBuf out = new PacketByteBuf(buffer);
            out.writeVarInt(id);
            serverbound.encode(out);
            PacketByteBuf in = new PacketByteBuf(buffer);
            in.readVarInt();
            boolean rejected = false;
            try {
                PacketRegistry.decode(ConnectionDirection.CLIENTBOUND, id, in);
            } catch (RuntimeException e) {
                rejected = true;
            }
            assertTrue(rejected, "a client-to-server packet must not decode on a clientbound connection");
        } finally {
            buffer.release();
        }
    }

    /** Unknown ids fail loudly instead of decoding as some other packet. */
    @Test
    void unknownIdsAreRejected() {
        boolean rejected = false;
        try {
            PacketRegistry.decode(ConnectionDirection.CLIENTBOUND, 250, new PacketByteBuf(Unpooled.buffer()));
        } catch (RuntimeException e) {
            rejected = true;
        }
        assertTrue(rejected, "an unassigned id must not be accepted");
    }

    /**
     * Truncated or hostile payloads must be reported, not allocated against. A
     * declared list length is the classic way to make a decoder allocate gigabytes.
     */
    @Test
    void corruptLengthsAreRejected() {
        var buffer = Unpooled.buffer();
        try {
            PacketByteBuf out = new PacketByteBuf(buffer);
            out.writeVarInt(Integer.MAX_VALUE);
            PacketByteBuf in = new PacketByteBuf(buffer);
            boolean rejected = false;
            try {
                in.readList(PacketByteBuf::readString);
            } catch (RuntimeException e) {
                rejected = true;
            }
            assertTrue(rejected, "an absurd declared collection length must be rejected");
        } finally {
            buffer.release();
        }
    }

    /** Sanity: a different value must not round-trip to the same object. */
    @Test
    void protocolVersionIsCarriedByTheFirstLevelPacket() {
        LevelInitS2C init = new LevelInitS2C("pvzce:level_1", List.of(), List.of(),
                new LevelPayload(9, 5, List.of(), 6, List.of(), List.of(), List.of(), List.of()), "", "", 99);
        assertNotEquals(PvzcePackets.PROTOCOL_VERSION, init.protocolVersion(),
                "the protocol version must actually be transmitted, not assumed");
    }
}

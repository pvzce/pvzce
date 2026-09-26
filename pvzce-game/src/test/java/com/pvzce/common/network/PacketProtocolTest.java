package com.pvzce.common.network;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.network.packet.CollectResourceC2S;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.DebugInfoS2C;
import com.pvzce.common.network.packet.CarrySyncS2C;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.EntityDespawnS2C;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.GameSpeedS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.HeldCardS2C;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelPayload;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.MusicEventS2C;
import com.pvzce.common.network.packet.OpenEditorS2C;
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
        // A card that is mid-cooldown, with the total it is counting down from: the two ints
        // sit next to each other on the wire, so a writer/reader swap between them would
        // leave the bar drawing a recharge that never matches the card.
        List<SlotInfo> slots = List.of(new SlotInfo(0, "pvzce:pea_shooter", "plant", 100, 12, 300, -1, true));

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
                new com.pvzce.common.network.packet.ReleaseMowerC2S(3),
                // A tool the level grants rather than a card the player holds: the id is all it
                // names, and the two coordinates follow it.
                new com.pvzce.common.network.packet.UseGrantedToolC2S(
                        Identifier.of("pvzce", "hammer"), 2, 4),
                // The save prompt's 重新开始: a run the player refused, named by level and world.
                new com.pvzce.common.network.packet.DiscardLevelSaveC2S(
                        "pvzce:yard/survival/mutation_normal", "world"),
                // A shop purchase: the item and the world, and never a price - the server re-reads
                // it, so there is no number here for a modified client to lower.
                new com.pvzce.common.network.packet.BuyShopItemC2S("pvzce:card_slot", "world"),
                // The endless round chooser's answer: the cards the next round is played with.
                new com.pvzce.common.network.packet.ReselectCardsC2S(
                        "pvzce:yard/survival/endless_pool", "world",
                        List.of("pvzce:pea_shooter", "pvzce:sunflower")),
                // A pack was switched off. The packet carries nothing because the list it is
                // about is a file both sides read; the empty body is still worth a sample.
                new com.pvzce.common.network.packet.ReloadPacksC2S(),
                // A bare click on a vase or a scary pot. Two coordinates and no tool id: the
                // mallet is the client's animation, so what travels is what was clicked.
                new com.pvzce.common.network.packet.SmashContainerC2S(6, 2),
                // A click on a seed packet lying on the lawn, named by entity - two packets can
                // be dropped in one cell and the client is the one that knows which it hit.
                new com.pvzce.common.network.packet.PickUpCardC2S(42),
                // .. and the packet in hand being planted, or put back where it fell. Neither
                // names a card: which card is in hand is the server's own state.
                new com.pvzce.common.network.packet.PlantHeldCardC2S(3, 1),
                new com.pvzce.common.network.packet.ReleaseHeldCardC2S(),

                new LevelInitS2C("pvzce:level_1", slots, List.of("normal", "final"), payload,
                        "pvzce:zombie_team", "僵尸方", PvzcePackets.PROTOCOL_VERSION),
                new LevelListS2C(List.of(LevelListS2C.LevelInfo.of("pvzce:yard/adventure/level_1", "第一关", "描述",
                        "pvzce:plant_team", teams, "in_progress", "day",
                        "pvzce:yard", "pvzce:adventure", true, true, payload,
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
                new CarrySyncS2C("pvzce:sunflower"),
                // The seed packet in the player's hand: its entity (so the board stops drawing it
                // where it fell) and the card it is. An empty card id is the same packet's "no
                // packet in hand", so one sample covers both shapes.
                new HeldCardS2C(12, "pvzce:squash"),
                new EntitySpawnS2C(9, "zombie", "pvzce:basic_zombie", "pvzce:zombie_team",
                        4.5F, 2.5F, 0, 200, "walk", 0.0F, 370, true,
                        EntitySpawnS2C.DEFAULT_SCALE),
                new EntityUpdateS2C(9, 4.25F, 2.5F, 180, "eat", 0.1F, 190, true),
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
                new SlotSyncS2C(new SlotInfo(1, "pvzce:sun", "resource", 0, 0, 0, -1, true)),
                // Non-default summary numbers: an endless run's whole score lives in these
                // three fields, and a sample of zeros would hide a writer/reader swap.
                new GameStateS2C(GameStateS2C.LOST, "pvzce:zombie_team", 37, 412, 88_800),
                // The mutation list with one of each state, a subject and a rolled multiplier,
                // plus a granted tool: every field of the record is non-default here.
                new com.pvzce.common.network.packet.MutationStateS2C(
                        "hell", 50, 1800, 421, 3F, "mutated", 12, 3,
                        List.of(new com.pvzce.common.network.packet.MutationStateS2C.ToolGrant(
                                        "pvzce:hammer", true, true, 0),
                                new com.pvzce.common.network.packet.MutationStateS2C.ToolGrant(
                                        "pvzce:shovel", false, false, 300)),
                        // The slots a mutation locked, which the bar draws padlocks from.
                        List.of(0, 3),
                        // The buffs in force, which a mutation can rewrite mid-level.
                        List.of("pvzce:auto_collect", "pvzce:mushroom_range"),
                        List.of(new com.pvzce.common.network.packet.MutationStateS2C.Entry(
                                        "pvzce:zombie_crisis", "active", 1.75F,
                                        "pvzce:buckethead_zombie"),
                                new com.pvzce.common.network.packet.MutationStateS2C.Entry(
                                        "pvzce:slot_replace", "suppressed", 1F, ""),
                                new com.pvzce.common.network.packet.MutationStateS2C.Entry(
                                        "pvzce:kelp_spread", "waiting", 0.75F, ""))),
                // The round numbers are non-default on purpose: on an endless run they are the
                // only score there is, and a sample of zeros would hide a writer/reader swap.
                new com.pvzce.common.network.packet.RoundSyncS2C(3, 13, 26, false, true,
                        List.of("small", "small", "huge")),
                new com.pvzce.common.network.packet.RoundClearS2C(3, 26, 214, 33_600),
                new TeamSyncS2C("pvzce:zombie_team", "僵尸方"),
                new SuggestionsS2C(7, List.of(new SuggestionsS2C.Suggestion(7, 13, "pvzce:basic_zombie"))),
                new ServerMessageS2C("+25 阳光"),
                new WaveProgressS2C(3, 5, 0.42F, true, false, 4),
                new TimeOfDayS2C(600, 1200, 600),
                new DebugInfoS2C(98765L, 4321, false, true),
                new GameSpeedS2C(180F),
                new MusicEventS2C("background", "pvzce:music/grasswalk", true, false, 0.85F, 1.5F),
                // Non-default values: an empty unlock list or zero coins would hide a
                // writer/reader swap in either column.
                new ProfileS2C(350, List.of("pvzce:pea_shooter", "pvzce:sunflower", "pvzce:shovel"), false),
                // A buff unlock rather than a card: the field is new, and a sample that left it
                // empty would not pin its position in the record.
                new LevelRewardS2C("pvzce:yard/adventure/1_9", 12, 100, 462, "",
                        "pvzce:mushroom_range", "pvzce:diamond", 1, 4.5F, 2.5F, 3, 150),
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
     * Every packet's bytes, so a change to the wire format has to be deliberate.
     *
     * <p>The round-trip test below proves a packet agrees with <em>itself</em>: encode and
     * decode are written by the same hand and will happily drift together, which would break
     * every existing client and server at once without failing anything here. This compares
     * the actual bytes against a recorded dump, so moving a field, changing a type or
     * reordering two ints shows up as a diff rather than as a mystery desync in a build that
     * passed.
     *
     * <p>Set {@code -Ppvzce.smoke=pvzce.printWire=true} to print the current dump (the build
     * forwards that property into the test JVM); paste it below when the change is intended,
     * and bump {@code PROTOCOL_VERSION}.
     */
    @Test
    void theWireFormatIsUnchanged() {
        String actual = wireDump();
        if (Boolean.getBoolean("pvzce.printWire")) {
            System.out.println("----- wire dump begin -----");
            System.out.println(actual);
            System.out.println("----- wire dump end -----");
            return;
        }
        assertEquals(EXPECTED_WIRE_FORMAT.trim(), actual.trim(),
                "a packet's bytes changed; if that is intended, print the dump and update"
                        + " PROTOCOL_VERSION");
    }

    /** {@code Name:hex} per packet, in sample order. */
    private static String wireDump() {
        StringBuilder dump = new StringBuilder();
        for (PvzcePacket packet : samplePackets()) {
            var buffer = Unpooled.buffer();
            try {
                PacketByteBuf out = new PacketByteBuf(buffer);
                out.writeVarInt(PacketRegistry.id(packet));
                packet.encode(out);
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.getBytes(buffer.readerIndex(), bytes);
                dump.append(packet.getClass().getSimpleName()).append(':');
                for (byte b : bytes) {
                    dump.append(String.format("%02x", b));
                }
                dump.append('\n');
            } finally {
                buffer.release();
            }
        }
        return dump.toString();
    }

    /**
     * The recorded dump; regenerate with {@code -Ppvzce.smoke=pvzce.printWire=true}.
     */
    private static final String EXPECTED_WIRE_FORMAT = """
ContinueLevelC2S:010d70767a63653a6c6576656c5f3105776f726c64
PlayLevelC2S:020d70767a63653a6c6576656c5f3105776f726c6400021170767a63653a7065615f73686f6f7465720970767a63653a73756e00
RestartLevelC2S:030d70767a63653a6c6576656c5f3105776f726c64010970767a63653a73756e
RequestLevelListC2S:0405776f726c64
RequestSuggestionsC2S:05072f737061776e2000000007
LeaveLevelC2S:06
PickCardC2S:0700000002
PlacePlantC2S:08000000000000000300000004
UseToolC2S:09000000010000000500000001
CollectResourceC2S:0a0000002a
CommandC2S:0b0d2f74696d652073657420363030
SetGameSpeedC2S:0c02
PauseGameC2S:0d01
CreateWorldC2S:0e0773616e64626f7801
UnlockLevelC2S:0f1870767a63653a796172642f616476656e747572652f315f3205776f726c64
ReleaseMowerC2S:1100000003
UseGrantedToolC2S:120c70767a63653a68616d6d65720000000200000004
DiscardLevelSaveC2S:132370767a63653a796172642f737572766976616c2f6d75746174696f6e5f6e6f726d616c05776f726c64
BuyShopItemC2S:150f70767a63653a636172645f736c6f7405776f726c64
ReselectCardsC2S:142070767a63653a796172642f737572766976616c2f656e646c6573735f706f6f6c05776f726c64021170767a63653a7065615f73686f6f7465720f70767a63653a73756e666c6f776572
ReloadPacksC2S:16
SmashContainerC2S:170000000600000002
PickUpCardC2S:180000002a
PlantHeldCardC2S:190000000300000001
ReleaseHeldCardC2S:1a
LevelInitS2C:41000000220d70767a63653a6c6576656c5f3101000000001170767a63653a7065615f73686f6f74657205706c616e74000000640000000c0000012cffffffff0102066e6f726d616c0566696e616c0000000900000005011170767a63653a7065615f73686f6f74657205706c616e741170767a63653a7065615f73686f6f7465722370767a63653a74657874757265732f656e7469746965732f7065615f73686f6f7465720000006400000006011270767a63653a62617369635f7a6f6d6269650100000001000000020b70767a63653a776174657200000000000000000000001170767a63653a7a6f6d6269655f7465616d09e583b5e5b0b8e696b9
LevelListS2C:42011c70767a63653a796172642f616476656e747572652f6c6576656c5f3109e7acace4b880e585b306e68f8fe8bfb01070767a63653a706c616e745f7465616d0b696e5f70726f6772657373036461790a70767a63653a796172640f70767a63653a616476656e747572650101011070767a63653a706c616e745f7465616d09e6a48de789a9e696b90d737572766976655f7761766573010000000900000005011170767a63653a7065615f73686f6f74657205706c616e741170767a63653a7065615f73686f6f7465722370767a63653a74657874757265732f656e7469746965732f7065615f73686f6f7465720000006400000006011270767a63653a62617369635f7a6f6d6269650100000001000000020b70767a63653a776174657200000000000000000000000001000001f40ae9809ae585b320315f3101056c6576656c1870767a63653a796172642f616476656e747572652f315f310000000100
LevelTabsS2C:56020a70767a63653a796172640f70767a63653a616476656e747572651370767a63653a756e63617465676f72697a65641370767a63653a756e63617465676f72697a6564
LevelSavePromptS2C:430d70767a63653a6c6576656c5f3105776f726c6409e7acace4b880e585b3000004d200000007000000fa
OpenEditorS2C:441070767a63653a64656d6f5f6c6576656c
SceneSyncS2C:450100000000000000000b70767a63653a6772617373
CarrySyncS2C:5a0f70767a63653a73756e666c6f776572
HeldCardS2C:5e0000000c0c70767a63653a737175617368
EntitySpawnS2C:4600000009067a6f6d6269651270767a63653a62617369635f7a6f6d6269651170767a63653a7a6f6d6269655f7465616d409000004020000000000000000000c80477616c6b0000000000000172013f800000
EntityUpdateS2C:47000000094088000040200000000000b4036561743dcccccd000000be01000000
EntityDespawnS2C:4800000009
EffectEventS2C:490c70767a63653a73706c617368406000003fc000001570767a63653a7366782f6566666563742f626974653f8000003f8000000b70767a63653a77617465723f800000
ResourceCollectS2C:4a0000000b0970767a63653a73756e00000019406000003fc000003ecccccd1b70767a63653a74657874757265732f7265736f757263652f73756e
ResourceDeltaS2C:4b1070767a63653a706c616e745f7465616d0e70767a63653a72656473746f6e650000001e
SlotSyncS2C:4c000000010970767a63653a73756e087265736f75726365000000000000000000000000ffffffff01
GameStateS2C:4d046c6f73741170767a63653a7a6f6d6269655f7465616d000000250000019c00015ae0
MutationStateS2C:5b0468656c6c0000003200000708000001a540400000076d7574617465640000000c00000003020c70767a63653a68616d6d65720101000000000c70767a63653a73686f76656c00000000012c020000000000000003021270767a63653a6175746f5f636f6c6c6563741470767a63653a6d757368726f6f6d5f72616e6765031370767a63653a7a6f6d6269655f637269736973066163746976653fe000001770767a63653a6275636b6574686561645f7a6f6d6269651270767a63653a736c6f745f7265706c6163650a737570707265737365643f800000001170767a63653a6b656c705f7370726561640777616974696e673f40000000
RoundSyncS2C:5c000000030000000d0000001a00010305736d616c6c05736d616c6c0468756765
RoundClearS2C:5d000000030000001a000000d600008340
TeamSyncS2C:4e1170767a63653a7a6f6d6269655f7465616d09e583b5e5b0b8e696b9
SuggestionsS2C:4f0000000701000000070000000d1270767a63653a62617369635f7a6f6d626965
ServerMessageS2C:500a2b323520e998b3e58589
WaveProgressS2C:5100000003000000053ed70a3d010000000004
TimeOfDayS2C:5200000258000004b000000258
DebugInfoS2C:5300000000000181cd000010e10001
GameSpeedS2C:5443340000
MusicEventS2C:550a6261636b67726f756e641570767a63653a6d757369632f677261737377616c6b01003f59999a3fc00000
ProfileS2C:570000015e031170767a63653a7065615f73686f6f7465720f70767a63653a73756e666c6f7765720c70767a63653a73686f76656c000000000008000000050000
LevelRewardS2C:581870767a63653a796172642f616476656e747572652f315f390000000c00000064000001ce001470767a63653a6d757368726f6f6d5f72616e67650d70767a63653a6469616d6f6e640000000140900000402000000000000300000096
MechanicSyncS2C:590e70767a63653a636f6e7665796f725b02000000041170767a63653a626f776c696e675f6e757405706c616e74ffffffff0000000000000000ffffffff01000000051170767a63653a626f776c696e675f6e757405706c616e74ffffffff0000000000000000ffffffff01
""";

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

    /**
     * The protocol version travels on the wire in the level-init packet.
     *
     * <p>It is not a constant both sides are assumed to share: a client has to be able to read
     * the server's version and notice that it is older. So the assertion is about what comes
     * back out of the decoder, not about the field holding what was put into it.
     */
    @Test
    void protocolVersionIsCarriedByTheFirstLevelPacket() {
        LevelInitS2C init = new LevelInitS2C("pvzce:level_1", List.of(), List.of(),
                new LevelPayload(9, 5, List.of(), 6, List.of(), List.of(), List.of(), List.of()), "", "",
                PvzcePackets.PROTOCOL_VERSION - 1);
        LevelInitS2C decoded = (LevelInitS2C) roundTrip(init);
        assertEquals(PvzcePackets.PROTOCOL_VERSION - 1, decoded.protocolVersion(),
                "the version that was encoded is the version that comes back");
    }

    /** Encodes a packet with its registry id and decodes it again, the way the transport does. */
    private static PvzcePacket roundTrip(PvzcePacket packet) {
        var buffer = Unpooled.buffer();
        try {
            PacketByteBuf out = new PacketByteBuf(buffer);
            out.writeVarInt(PacketRegistry.id(packet));
            packet.encode(out);

            PacketByteBuf in = new PacketByteBuf(buffer);
            int readId = in.readVarInt();
            PvzcePacket decoded = PacketRegistry.decode(packet.direction(), readId, in);
            assertEquals(0, in.readableBytes(), "the decoder must consume the whole frame");
            return decoded;
        } finally {
            buffer.release();
        }
    }
}
